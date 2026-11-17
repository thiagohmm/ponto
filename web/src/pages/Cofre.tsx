import { useEffect, useMemo, useState } from "react";
import { useCompany } from "../Company";
import { useConfirm } from "../ui/Confirm";
import { useVaultWorkspace, EMPTY_DRAFT, type VaultDraft } from "../vault/Vault";
import { DEFAULTS, MAX_LENGTH, MIN_LENGTH, bitsOf, generate, type GenOptions } from "../vault/generator";
import { BARS, strength } from "../vault/strength";
import { COPY_TTL_MS, copySecret, copiedMessage } from "../vault/clipboard";
import { duplicateCount, duplicateSummary, duplicates } from "../vault/duplicates";
import { APP_MAX, DESC_MAX, PASS_MAX, USER_MAX, blank, label, tooLong, type VaultOpen } from "../vault/model";
import {
  ITERATIONS, MAX_ITERATIONS, MIN_ITERATIONS,
  vaultCryptoBlockReason, type VaultCryptoBlock,
  INSECURE_CONTEXT_MSG, MISSING_WEBCRYPTO_MSG,
} from "../vault/crypto";
import { DEFAULT_IDLE_MINUTES, MAX_IDLE_MINUTES, MIN_IDLE_MINUTES, idleLabel } from "../vault/settings";

/** The cofre: a list of sealed rows, opened in this tab and nowhere else. */
export function CofrePage() {
  const { active, companies } = useCompany();
  const confirm = useConfirm();
  const v = useVaultWorkspace(active?.id ?? 0);

  const [gate, setGate] = useState("");
  const [query, setQuery] = useState("");
  const [companyFilter, setCompanyFilter] = useState(0);
  const [draft, setDraft] = useState<VaultDraft>(EMPTY_DRAFT);
  const [editing, setEditing] = useState<{ id: number; revision: number } | null>(null);
  const [gen, setGen] = useState<GenOptions>(DEFAULTS);
  const [show, setShow] = useState<Record<number, number>>({});
  const [copied, setCopied] = useState<{ id: number; text: string } | null>(null);
  const [backupPass, setBackupPass] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [rekey, setRekey] = useState<{ from: string; to: string; iters: number } | null>(null);
  const [about, setAbout] = useState(false);

  const shown = useMemo(() => filterItems(v.items, query, companyFilter), [v.items, query, companyFilter]);
  const dups = useMemo(() => duplicates(v.items), [v.items]);
  const cryptoBlock = vaultCryptoBlockReason();

  // A revealed password hides itself again. The clipboard has its own, shorter, clock.
  useEffect(() => {
    if (!Object.keys(show).length) return;
    const t = setTimeout(() => setShow({}), Math.max(COPY_TTL_MS, 30_000));
    return () => clearTimeout(t);
  }, [show]);

  async function openVault(master: string) {
    if (v.phase === "unsealed") {
      if (await v.create(master)) setGate("");
      return;
    }
    if (await v.unlock(master)) setGate("");
  }

  async function wipeCompany() {
    if (!active) return;
    const mine = v.items.filter((i) => i.plain.companyId === active.id);
    const ok = await confirm({
      title: "Forget this company's credentials?",
      message: `${mine.length} record${mine.length === 1 ? "" : "s"} of ${active.name} will be deleted. The other companies keep theirs. There is no undo.`,
      ok: "Forget them",
      tone: "stamp",
    });
    if (ok) await v.wipeCompany(active.id);
  }

  async function changeMaster() {
    if (!rekey) return;
    if (await v.changeMaster(rekey.from, rekey.to, rekey.iters)) {
      setRekey(null);
      setBackupPass("");
    }
  }

  return (
    <div className="cofre">
      {v.err && <p role="alert" className="err">{v.err}</p>}
      {v.msg && <p role="status" className="ok">{v.msg}</p>}

      {v.phase === "loading" && <p role="status" className="muted">Checking the vault…</p>}

      {v.phase === "offline" && (
        <div className="card">
          <p>The server did not answer. Nothing was sent, and nothing was stored.</p>
          <div className="row"><button className="btn primary" onClick={() => void v.refresh()}>Try again</button></div>
        </div>
      )}

      {(v.phase === "locked" || v.phase === "unsealed") && (
        cryptoBlock
          ? <CryptoBlocked reason={cryptoBlock} />
          : <Gate phase={v.phase} value={gate} busy={v.busy}
              onChange={setGate} onSubmit={() => void openVault(gate)}
              onForget={() => setAbout(true)} />
      )}

      {v.unlocked && (
        <>
          <div className="cofre-bar">
            <strong>{v.items.length} credential{v.items.length === 1 ? "" : "s"}</strong>
            <input className="cofre-search" placeholder="search name, user, description" value={query}
              onChange={(e) => setQuery(e.target.value)} aria-label="Search the cofre" />
            <select value={companyFilter || ""} onChange={(e) => setCompanyFilter(Number(e.target.value))} aria-label="Company">
              <option value="">all companies</option>
              {companies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
            <button className="btn" onClick={() => setShow({})}>Hide all</button>
            <button className="btn" onClick={() => v.lock("closed")}>Lock</button>
          </div>

          {duplicateCount(dups) > 0 && (
            <div className="cofre-warn" role="status">
              <strong>{duplicateCount(dups)} issue{duplicateCount(dups) === 1 ? "" : "s"}</strong>
              <span>{duplicateSummary(dups)}</span>
              <span>reused passwords are the real risk — one leak unlocks the rest</span>
            </div>
          )}

          {!editing && <NewRecord draft={draft} companies={companies} active={active?.id ?? 0} busy={v.busy} gen={gen}
            onChange={setDraft} onGen={setGen}
            onSave={async () => { if (await v.save(draft, 0, 0)) { setDraft(EMPTY_DRAFT); } }} />}

          {shown.length === 0 && <p className="muted">{v.items.length ? "nothing matches that search" : "nothing yet — add the first credential above"}</p>}

          {shown.map((item) => (
            <Row key={item.id} item={item} companies={companies} revealed={Boolean(show[item.id])}
              copied={copied?.id === item.id ? copied.text : ""}
              onCopy={async () => {
                const r = await copySecret(item.plain.pass);
                setCopied({ id: item.id, text: copiedMessage(r.how) });
                // Restart the auto-hide clock only if the row was already open. A copy must
                // not reveal a password the user had left hidden — the clipboard already has it.
                setShow((s) => (item.id in s ? { ...s, [item.id]: Date.now() } : s));
              }}
              onReveal={(want) => setShow((s) => {
                const next = { ...s };
                if (want) next[item.id] = Date.now();
                else delete next[item.id];
                return next;
              })}
              onEdit={() => { setEditing({ id: item.id, revision: item.revision }); setDraft({ ...item.plain }); }}
              onRemove={async () => {
                if (await confirm({ title: "Delete this credential?", message: `${label(item.plain)} will be gone for good.`, ok: "Delete", tone: "stamp" })) {
                  await v.remove(item.id);
                }
              }} />
          ))}

          {editing && (
            <NewRecord draft={draft} companies={companies} active={active?.id ?? 0} busy={v.busy} gen={gen}
              editing={`Editing ${label(draft) || "this record"}`}
              onCancel={() => { setEditing(null); setDraft(EMPTY_DRAFT); }}
              onChange={setDraft} onGen={setGen}
              onSave={async () => {
                if (await v.save(draft, editing.id, editing.revision)) { setEditing(null); setDraft(EMPTY_DRAFT); }
              }} />
          )}

          <Tools v={v} companies={companies} backupPass={backupPass} file={file} rekey={rekey}
            setBackupPass={setBackupPass} setFile={setFile} setRekey={setRekey}
            onWipe={wipeCompany} onChangeMaster={changeMaster} onAbout={() => setAbout(true)} />
        </>
      )}

      {about && <About onClose={() => setAbout(false)} />}
    </div>
  );
}

// ---- the gate -----------------------------------------------------------

/** HTTP on a LAN IP (or an ancient browser): do not ask for a master password that cannot open anything. */
function CryptoBlocked({ reason }: { reason: VaultCryptoBlock }) {
  const insecure = reason === "insecure";
  return (
    <div className="card cofre-gate" role="alert">
      <h2>{insecure ? "Cofre precisa de HTTPS" : "WebCrypto indisponível"}</h2>
      <p>{insecure ? INSECURE_CONTEXT_MSG : MISSING_WEBCRYPTO_MSG}</p>
      {insecure && (
        <p className="muted">
          O Chrome (e os demais) só liberam a criptografia do cofre em HTTPS ou em localhost —
          não no endereço HTTP da rede local.
        </p>
      )}
    </div>
  );
}

function Gate({ phase, value, busy, onChange, onSubmit, onForget }: {
  phase: "locked" | "unsealed"; value: string; busy: boolean;
  onChange: (v: string) => void; onSubmit: () => void; onForget: () => void;
}) {
  const creating = phase === "unsealed";
  return (
    <div className="card cofre-gate">
      <h2>{creating ? "Create the cofre" : "Unlock the cofre"}</h2>
      <p className="muted">
        {creating
          ? "The master password never leaves this tab. It cannot be recovered, reset, or read by the server — if you lose it, the cofre is empty forever."
          : "Everything in here is sealed. The password becomes a key in this tab's memory, opens the list locally, and is forgotten when you leave."}
      </p>
      <label className="cofre-gate-field">{creating ? "Master password" : "Master password"}
        <input type="password" value={value} autoFocus autoComplete="off" placeholder="••••••••"
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && !busy) onSubmit(); }} />
      </label>
      {creating && value && <Strength pass={value} />}
      <div className="row">
        <button className="btn primary" disabled={!value || busy} onClick={onSubmit}>
          {busy ? "working…" : creating ? "Create and open" : "Open"}
        </button>
        {!creating && <button className="btn" disabled={busy} onClick={onForget}>I lost it</button>}
      </div>
      {creating && <p className="muted">~1 s to derive the key, on purpose.</p>}
    </div>
  );
}

/** Why the number is what it is — a link to the same page the module's comment describes. */
function About({ onClose }: { onClose: () => void }) {
  return (
    <div className="overlay" role="presentation" onClick={onClose}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="About the cofre" onClick={(e) => e.stopPropagation()}>
        <h3>There is no recovery</h3>
        <p>The master password is not stored anywhere: it is turned into a key here, in the browser, and
          only the encrypted result goes to the server. Nobody — not the administrator, not the database, not
          this app — can read it back or reset it. If it is lost, the cofre is empty and that is the design.</p>
        <p>The honest limit: a backup file is only as good as where you keep it, and the clipboard cannot be
          un-copied. We overwrite it after a few seconds and say so, because that is all a browser can do.</p>
        <div className="dialog-actions"><button className="btn primary" onClick={onClose}>Close</button></div>
      </div>
    </div>
  );
}

// ---- the list -----------------------------------------------------------

function Row({ item, companies, revealed, copied, onCopy, onReveal, onEdit, onRemove }: {
  item: VaultOpen; companies: { id: number; name: string }[]; revealed: boolean; copied: string;
  onCopy: () => void; onReveal: (want: boolean) => void; onEdit: () => void; onRemove: () => void;
}) {
  const company = companies.find((c) => c.id === item.plain.companyId);
  return (
    <div className="cofre-row">
      <div className="cofre-row-head">
        <strong>{item.plain.app || "(sin nome)"}</strong>
        {item.plain.user && <span className="muted">{item.plain.user}</span>}
        <span className={"chip cofre-company" + (company ? "" : " ghost")}>{company?.name ?? `company ${item.plain.companyId}`}</span>
      </div>
      <div className="cofre-secret">
        <input type={revealed ? "text" : "password"} value={item.plain.pass} readOnly tabIndex={-1} aria-hidden="true" />
        <button className="btn" onClick={() => onReveal(!revealed)}>{revealed ? "Hide" : "Show"}</button>
        <button className="btn" disabled={!item.plain.pass} onClick={onCopy}>Copy</button>
        <Strength pass={item.plain.pass} compact />
      </div>
      {copied && <p className="muted">{copied}</p>}
      {item.plain.desc && <p className="cofre-desc">{item.plain.desc}</p>}
      <div className="record-actions">
        <button className="btn" onClick={onEdit}>Edit</button>
        <button className="btn danger" onClick={onRemove}>Delete</button>
        <span className="stamp">rev {item.revision} · {item.updatedAt}</span>
      </div>
    </div>
  );
}

// ---- the form -----------------------------------------------------------

function NewRecord({ draft, companies, active, busy, gen, onChange, onGen, onSave, editing, onCancel }: {
  draft: VaultDraft; companies: { id: number; name: string }[]; active: number; busy: boolean;
  gen: GenOptions; onChange: (d: VaultDraft) => void; onGen: (g: GenOptions) => void; onSave: () => void;
  editing?: string; onCancel?: () => void;
}) {
  const why = tooLong(draft);
  const s = strength(draft.pass);
  return (
    <div className="cofre-form">
      {editing && <p className="cofre-editing">{editing}</p>}
      <div className="form">
        <label>Application
          <input value={draft.app} maxLength={APP_MAX} placeholder="Gmail, VPN, router…"
            onChange={(e) => onChange({ ...draft, app: e.target.value })} />
        </label>
        <label>User
          <input value={draft.user} maxLength={USER_MAX} placeholder="ana, 08012345678…"
            onChange={(e) => onChange({ ...draft, user: e.target.value })} />
        </label>
        <label className="span2">Password
          <input value={draft.pass} maxLength={PASS_MAX} placeholder="type it, or generate one"
            onChange={(e) => onChange({ ...draft, pass: e.target.value })} />
        </label>
        <label>Company
          <select value={draft.companyId || active || ""} onChange={(e) => onChange({ ...draft, companyId: Number(e.target.value) })}>
            <option value="">this company{active ? ` (${active})` : ""}</option>
            {companies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label className="span2">Description
          <textarea value={draft.desc} maxLength={DESC_MAX} rows={2} placeholder="recovery phone, seat, notes…"
            onChange={(e) => onChange({ ...draft, desc: e.target.value })} />
        </label>
      </div>
      <Generator gen={gen} onGen={onGen}
        onUse={(pass) => onChange({ ...draft, pass })} disabled={busy} />
      {!blank(draft) && s.score < 2 && <p className="muted cofre-hint">{s.bits} bits · {s.label} — {s.hint}</p>}
      {why && <p className="field-error">{why}</p>}
      <div className="row">
        <button className="btn primary" disabled={blank(draft) || Boolean(why) || busy} onClick={onSave}>
          {busy ? "sealing…" : editing ? "Save changes" : "Save"}
        </button>
        {onCancel && <button className="btn" disabled={busy} onClick={onCancel}>Cancel</button>}
      </div>
    </div>
  );
}

/** The generator, and the options that change what it can produce. */
function Generator({ gen, onGen, onUse, disabled }: {
  gen: GenOptions; onGen: (g: GenOptions) => void; onUse: (pass: string) => void; disabled: boolean;
}) {
  const [error, setError] = useState("");
  const [last, setLast] = useState("");
  const bits = bitsOf(gen);
  return (
    <div className="cofre-gen">
      <label>Length
        <input type="range" min={MIN_LENGTH} max={MAX_LENGTH} value={gen.length}
          onChange={(e) => onGen({ ...gen, length: Number(e.target.value) })} />
      </label>
      <div className="cofre-gen-sets">
        {([["upper", "A-Z"], ["lower", "a-z"], ["digits", "0-9"], ["symbols", "!@#"], ["avoidSimilar", "no il1LoO0"]] as const).map(([key, text]) => (
          <label key={key} className="cofre-check">
            <input type="checkbox" checked={Boolean(gen[key])}
              onChange={(e) => onGen({ ...gen, [key]: e.target.checked })} />
            {text}
          </label>
        ))}
      </div>
      <div className="row">
        <button className="btn" disabled={disabled} onClick={() => {
          try { const pass = generate(gen); setLast(pass); onUse(pass); setError(""); }
          catch (e) { setError(e instanceof Error ? e.message : "cannot generate"); }
        }}>Generate</button>
        <span className="muted">{Math.floor(bits)} bits{last ? " · last one kept here only" : ""}</span>
      </div>
      {error && <p className="field-error">{error}</p>}
    </div>
  );
}

// ---- strength -----------------------------------------------------------

function Strength({ pass, compact }: { pass: string; compact?: boolean }) {
  const s = strength(pass);
  return (
    <div className={"cofre-strength" + (compact ? " compact" : "")}>
      <div className="cofre-bars" aria-hidden="true">
        {Array.from({ length: BARS }, (_, i) => <i key={i} className={i < s.score ? "on" : ""} />)}
      </div>
      <span className="cofre-score">{s.label}</span>
      {!compact && <span className="muted">{s.bits} bits · {s.hint}</span>}
    </div>
  );
}

// ---- the tools ----------------------------------------------------------

function Tools({ v, companies, backupPass, file, rekey, setBackupPass, setFile, setRekey, onWipe, onChangeMaster, onAbout }: {
  v: ReturnType<typeof useVaultWorkspace>; companies: { id: number; name: string }[];
  backupPass: string; file: File | null; rekey: { from: string; to: string; iters: number } | null;
  setBackupPass: (s: string) => void; setFile: (f: File | null) => void;
  setRekey: (r: { from: string; to: string; iters: number } | null) => void;
  onWipe: () => void; onChangeMaster: () => void; onAbout: () => void;
}) {
  return (
    <div className="cofre-tools">
      <section className="cofre-tool">
        <h3>Backup</h3>
        <p className="muted">One sealed file, one master password. The whole list is a single blob, so an edited file will not open.</p>
        <label>Master password
          <input type="password" value={backupPass} autoComplete="off" onChange={(e) => setBackupPass(e.target.value)} />
        </label>
        <div className="row">
          <button className="btn" disabled={!backupPass || v.busy} onClick={() => void v.backup(backupPass)}>Save sealed file</button>
          <label className="cofre-import">Import a file
            <input type="file" accept="application/json,.json" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </label>
          <button className="btn" disabled={!file || !backupPass || v.busy} onClick={() => { if (file) void v.restore(file, backupPass); }}>Import</button>
          {file && <span className="chip">{file.name}</span>}
        </div>
      </section>

      <section className="cofre-tool">
        <h3>Master password</h3>
        <p className="muted">Changing it re-seals every record in one request. The salt is new, so the old backups still open with the old password.</p>
        {rekey ? (
          <>
            <label>Current
              <input type="password" value={rekey.from} autoComplete="off" onChange={(e) => setRekey({ ...rekey, from: e.target.value, to: rekey.to, iters: rekey.iters })} />
            </label>
            <label>New
              <input type="password" value={rekey.to} autoComplete="off" onChange={(e) => setRekey({ ...rekey, from: rekey.from, to: e.target.value, iters: rekey.iters })} />
            </label>
            <label>Iterations <span className="muted">{Math.round(rekey.iters / 1000)}k</span>
              <input type="range" min={MIN_ITERATIONS} max={MAX_ITERATIONS} step={10_000} value={rekey.iters}
                onChange={(e) => setRekey({ ...rekey, from: rekey.from, to: rekey.to, iters: Number(e.target.value) })} />
            </label>
            <div className="row">
              <button className="btn primary" disabled={!rekey.from || !rekey.to || v.busy} onClick={onChangeMaster}>Re-key {v.items.length} records</button>
              <button className="btn" onClick={() => setRekey(null)}>Cancel</button>
            </div>
          </>
        ) : (
          <div className="row"><button className="btn" onClick={() => setRekey({ from: "", to: "", iters: ITERATIONS })}>Change master password</button></div>
        )}
      </section>

      <section className="cofre-tool">
        <h3>Auto-lock</h3>
        <p className="muted">The cofre closes itself when nothing happens for {idleLabel(v.settings.idleMinutes)}. Leaving the tab locks it too.</p>
        <label>Minutes
          <input type="number" min={MIN_IDLE_MINUTES} max={MAX_IDLE_MINUTES} step={1}
            defaultValue={v.settings.idleMinutes || DEFAULT_IDLE_MINUTES}
            onBlur={(e) => v.setIdleMinutes(Number(e.target.value))} />
        </label>
        <div className="row">
          <button className="btn" onClick={onWipe}>Forget this company</button>
          <button className="btn" onClick={onAbout}>How this works</button>
        </div>
        <p className="muted">{companies.length} companies · {v.items.length} records here · the server only holds ciphertext</p>
      </section>
    </div>
  );
}

// ---- helpers -------------------------------------------------------------

function filterItems(items: VaultOpen[], query: string, company: number): VaultOpen[] {
  const q = query.trim().toLowerCase();
  return items.filter((i) => {
    if (company && i.plain.companyId !== company) return false;
    if (!q) return true;
    return [i.plain.app, i.plain.user, i.plain.desc].some((f) => f.toLowerCase().includes(q));
  });
}
