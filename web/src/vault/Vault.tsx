/**
 * The cofre's workspace: the state one unlocked vault needs, and nothing else.
 *
 * The master password and the derived key live here, in this hook's memory, for as long as
 * the Cofre page is mounted. That lifetime *is* the design: leaving the tab unmounts the
 * page, the key goes out of scope, and the vault is closed. There is no "did they forget to
 * lock?" case, because there is nothing left to forget.
 *
 * Four rules this file is written to keep:
 *
 * - **Plaintext is derived, never stored.** `records` (sealed rows from the server) is the
 *   state; `items` is those rows opened. Locking drops the key and both lists.
 * - **A failure locks.** Any error in the middle of reading the list closes the vault: a
 *   half-read vault is a vault you cannot trust, and the safe answer is to start over.
 * - **A 409 is never retried.** It means someone else wrote that record. Re-reading and
 *   writing again would pick a winner nobody chose, so the message stands.
 * - **The key never leaves.** Every function takes a master password, derives its own key,
 *   and lets it go. Nothing outside this hook holds one.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api";
import { ApiError } from "../types";
import type { VaultMeta } from "../types";
import { ITERATIONS, KDF, aadItem, deriveKey, open, openCheck, randomSalt, seal, sealCheck } from "./crypto";
import { blank, byLabel, parsePayload, payloadJson, tooLong, type VaultOpen, type VaultPlain } from "./model";
import { createIdleLock, type IdleLock } from "./session";
import { readSettings, writeSettings, type VaultSettings } from "./settings";
import { exportFile, fileName, readVaultFile } from "./file";

export type VaultPhase = "loading" | "unsealed" | "locked" | "unlocked" | "offline";
export type VaultDraft = Omit<VaultPlain, "companyId"> & { companyId: number };

export const EMPTY_DRAFT: VaultDraft = { app: "", user: "", pass: "", desc: "", companyId: 0 };

/** A row on its way to the server: an id, a company, a sealed blob, a revision. */
type RekeyRow = { id: number; companyId: number; blob: string; revision: number };

export function useVaultWorkspace(activeCompany: number) {
  const [phase, setPhase] = useState<VaultPhase>("loading");
  const [meta, setMeta] = useState<VaultMeta | null>(null);
  const [items, setItems] = useState<VaultOpen[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");
  const [settings, setSettings] = useState<VaultSettings>(() => readSettings());

  const keyRef = useRef<CryptoKey | null>(null);
  const idleRef = useRef<IdleLock | null>(null);
  const itemsRef = useRef<VaultOpen[]>([]);
  itemsRef.current = items;

  const lock = useCallback((why = "locked") => {
    idleRef.current?.stop();
    keyRef.current = null;
    setItems([]);
    setPhase((p) => (p === "unsealed" ? "unsealed" : "locked"));
    setMsg(why);
  }, []);

  // The timer only exists while the vault is open; locking stops it. An armed timer left
  // behind would fire "closed after inactivity" on a vault that is already closed.
  useEffect(() => {
    if (phase !== "unlocked") return;
    const idle = createIdleLock(() => lock("closed after inactivity"), settings.idleMinutes * 60_000);
    idleRef.current = idle;
    idle.start();
    return () => {
      idle.stop();
      idleRef.current = null;
    };
  }, [phase, settings.idleMinutes, lock]);

  useEffect(() => () => { keyRef.current = null; }, []);

  const refresh = useCallback(async () => {
    setErr("");
    try {
      const next = await api.vault();
      setMeta(next);
      if (!next.sealed) {
        setPhase("unsealed");
        return;
      }
      setPhase((p) => (p === "unlocked" ? p : "locked"));
    } catch (e) {
      setErr(text(e));
      setPhase(e instanceof ApiError && e.status === 0 ? "offline" : "locked");
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  /** Read every row and open it. One row that will not open closes the whole vault. */
  async function load(key: CryptoKey): Promise<void> {
    const r = await api.vaultItems();
    const opened: VaultOpen[] = [];
    for (const record of r.items) {
      const text = await open(key, record.blob, aadItem(record.companyId));
      opened.push({ ...record, plain: parsePayload(text) });
    }
    opened.sort(byLabel);
    setItems(opened);
    setPhase("unlocked");
  }

  /** Unlock: verify the local verifier, then read the list. The password never travels. */
  const unlock = useCallback(async (master: string): Promise<boolean> => {
    setBusy(true);
    setErr("");
    try {
      const current = await api.vault();
      setMeta(current);
      if (!current.sealed) {
        setPhase("unsealed");
        setErr("the cofre does not exist yet — pick a master password to create it");
        return false;
      }
      const key = await deriveKey(master, current.salt, current.iterations);
      if (!(await openCheck(key, current.check))) {
        setErr("wrong master password");
        return false;
      }
      keyRef.current = key;
      await load(key);
      setMsg("open");
      return true;
    } catch (e) {
      setErr(text(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  /** First use: generate a salt, seal the verifier, and the vault exists from this call on. */
  const create = useCallback(async (master: string): Promise<boolean> => {
    setBusy(true);
    setErr("");
    try {
      if (master.length < 8) throw new Error("a master password of at least 8 characters, please");
      const salt = randomSalt();
      const key = await deriveKey(master, salt, ITERATIONS);
      const created = await api.sealVault({ kdf: KDF, salt, iterations: ITERATIONS, check: await sealCheck(key) });
      setMeta(created);
      keyRef.current = key;
      await load(key);
      setMsg("cofre created — write the master password down somewhere safe: it cannot be recovered");
      return true;
    } catch (e) {
      setErr(text(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  /** Create or edit. A new row carries a company; an edit carries the revision it read. */
  const save = useCallback(async (draft: VaultDraft, id: number, revision: number): Promise<VaultOpen | null> => {
    const key = keyRef.current;
    if (!key) {
      setErr("locked — unlock the cofre first");
      return null;
    }
    const companyId = draft.companyId > 0 ? draft.companyId : activeCompany;
    if (companyId < 1) {
      setErr("pick the company this credential belongs to");
      return null;
    }
    const plain: VaultPlain = { app: draft.app, user: draft.user, pass: draft.pass, desc: draft.desc, companyId };
    const why = tooLong(plain) || (blank(plain) ? "a record needs a name, a user, or a password" : "");
    if (why) {
      setErr(why);
      return null;
    }
    setBusy(true);
    setErr("");
    try {
      const blob = await seal(key, payloadJson(plain), aadItem(companyId));
      const saved = id > 0
        ? await api.updateVaultItem(id, { blob, revision })
        : await api.createVaultItem({ companyId, blob });
      const opened: VaultOpen = { ...saved, plain };
      setItems((all) => dedupe([...all.filter((i) => i.id !== saved.id), opened]).sort(byLabel));
      setMsg("saved");
      return opened;
    } catch (e) {
      setErr(text(e));
      return null;
    } finally {
      setBusy(false);
    }
  }, [activeCompany]);

  const remove = useCallback(async (id: number): Promise<boolean> => {
    setBusy(true);
    setErr("");
    try {
      await api.removeVaultItem(id);
      setItems((all) => all.filter((i) => i.id !== id));
      setMsg("deleted");
      return true;
    } catch (e) {
      setErr(text(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  /** Forget this company's credentials. Other companies keep theirs — the server decides that. */
  const wipeCompany = useCallback(async (companyId: number): Promise<boolean> => {
    setBusy(true);
    setErr("");
    try {
      const r = await api.wipeVaultCompany();
      setItems((all) => all.filter((i) => i.plain.companyId !== companyId));
      setMsg(`forgotten ${r.deleted} credential${r.deleted === 1 ? "" : "s"} of this company`);
      return true;
    } catch (e) {
      setErr(text(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  /**
   * A new master password, over the whole vault in one request. Every row is opened with the
   * old key and sealed with the new one; the server swaps salt, verifier and rows in the same
   * transaction. A partial re-key would leave rows no single password can open.
   */
  const changeMaster = useCallback(async (oldMaster: string, newMaster: string, iterations: number = ITERATIONS): Promise<boolean> => {
    setBusy(true);
    setErr("");
    try {
      const current = await api.vault();
      const from = await deriveKey(oldMaster, current.salt, current.iterations);
      if (!(await openCheck(from, current.check))) throw new Error("wrong master password");
      if (newMaster.length < 8) throw new Error("a new master password of at least 8 characters, please");
      if (newMaster === oldMaster) throw new Error("the new master password has to be a different one");

      const salt = randomSalt();
      const to = await deriveKey(newMaster, salt, iterations);
      const source: RekeyRow[] = itemsRef.current.map(({ id, companyId, blob, revision }) => ({ id, companyId, blob, revision }));
      const resealed: { id: number; blob: string; revision: number }[] = [];
      for (const record of source) {
        const text = await open(from, record.blob, aadItem(record.companyId));
        resealed.push({ id: record.id, blob: await seal(to, text, aadItem(record.companyId)), revision: record.revision });
      }
      await api.vaultRekey({ kdf: KDF, salt, iterations, check: await sealCheck(to), items: resealed });
      keyRef.current = to;
      await load(to);
      setMsg("master password changed, and every record re-sealed");
      return true;
    } catch (e) {
      setErr(text(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  /** A backup of everything open, sealed as one blob. Needs the master password again. */
  const backup = useCallback(async (master: string): Promise<boolean> => {
    setBusy(true);
    setErr("");
    try {
      const current = await api.vault();
      const key = await deriveKey(master, current.salt, current.iterations);
      if (!(await openCheck(key, current.check))) throw new Error("wrong master password");
      const fresh = itemsRef.current;
      if (!fresh.length) throw new Error("nothing to back up yet");
      const blob = await exportFile(fresh, master, current.salt, current.iterations);
      saveAs(blob, fileName());
      setMsg("backup saved — it is sealed, and only this master password opens it");
      return true;
    } catch (e) {
      setErr(text(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  /**
   * Import. The file's own salt is used to derive its key, so a backup taken before a re-key
   * still opens. Then every record is re-sealed for the *live* key, so the vault stays sealed
   * with one password. One bad record stops the whole file: the server does the same.
   */
  const restore = useCallback(async (file: File, master: string): Promise<boolean> => {
    setBusy(true);
    setErr("");
    try {
      const current = await api.vault();
      const live = await deriveKey(master, current.salt, current.iterations);
      if (!(await openCheck(live, current.check))) throw new Error("wrong master password");
      const plains = await readVaultFile(await file.text(), master);
      const blobs = await Promise.all(plains.map(async (p) => {
        const companyId = p.companyId;
        return { companyId, blob: await seal(live, payloadJson({ ...p, companyId }), aadItem(companyId)) };
      }));
      const r = await api.importVaultItems(blobs);
      setMsg(`imported ${r.imported} record${r.imported === 1 ? "" : "s"}`);
      await load(live);
      return true;
    } catch (e) {
      setErr(text(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  const setIdleMinutes = useCallback((minutes: number) => {
    setSettings(writeSettings({ idleMinutes: minutes }));
  }, []);

  return {
    phase, meta, items, busy, err, msg, settings,
    unlocked: phase === "unlocked",
    refresh, unlock, create, save, remove, wipeCompany, changeMaster, backup, restore, lock, setIdleMinutes,
  };
}

// ---- helpers -------------------------------------------------------------

/** One message for every failure, including the ones with no body (the server was unreachable). */
function text(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 409) return "someone saved that record first — read it again before writing";
    if (e.status === 423 && e.need === "create") return "the cofre does not exist yet — pick a master password";
    if (e.status === 0) return "the server did not answer: nothing was sent";
    return e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

/** The same id twice in the local list is a bug in the list, not in the server. */
function dedupe(items: VaultOpen[]): VaultOpen[] {
  const seen = new Set<number>();
  const out: VaultOpen[] = [];
  for (const item of items) {
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    out.push(item);
  }
  return out;
}

/** The browser's download dance, in one place. */
function saveAs(blob: Blob, name: string): void {
  if (typeof document === "undefined") return;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
