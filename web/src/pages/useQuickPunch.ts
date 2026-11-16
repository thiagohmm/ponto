import { useMemo, useState } from "react";
import { api, type EntrySave } from "../api";
import { nowHm } from "../mask";
import { useConfirm } from "../ui/Confirm";
import type { DayInfo, Entry, Home } from "../types";
import { ApiError } from "../types";

export type PunchMode =
  | { kind: "in" }
  | { kind: "out"; open: Entry }
  | { kind: "ambiguous"; opens: Entry[]; note: string };

function todayIso(): string {
  const d = new Date();
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}

export function useQuickPunch(opts: {
  home: Home | null;
  todayDay: DayInfo | null;
  rows: Entry[];
  setClock: (v: string) => void;
  setErr: (v: string) => void;
  reload: () => Promise<void>;
}) {
  const confirm = useConfirm();
  const [busy, setBusy] = useState(false);
  const [punchMsg, setPunchMsg] = useState("");
  const [msg, setMsg] = useState("");

  const punchMode: PunchMode = useMemo(() => {
    const todayOpens = opts.todayDay?.open ?? [];
    const openCount = opts.home?.open ?? 0;
    if (todayOpens.length === 1) return { kind: "out", open: todayOpens[0] };
    if (todayOpens.length > 1) {
      return {
        kind: "ambiguous",
        opens: todayOpens,
        note: todayOpens.length + " abertos hoje — feche no formulário",
      };
    }
    if (openCount > 0) {
      const fromList = opts.rows.filter((e) => e.open);
      return {
        kind: "ambiguous",
        opens: fromList,
        note: openCount === 1
          ? "Há 1 aberto de outro dia — edite no formulário"
          : "Há " + openCount + " abertos de outro dia — edite no formulário",
      };
    }
    return { kind: "in" };
  }, [opts.todayDay, opts.home, opts.rows]);

  async function createPunch(body: EntrySave, confirmMidnight = false, confirmOverlap = false): Promise<Entry> {
    try {
      return await api.create({ ...body, confirmMidnight, confirmOverlap });
    } catch (e) {
      if (e instanceof ApiError && e.need === "midnight") {
        if (!(await confirm({
          title: "Virada de meia-noite",
          message: e.message + " — confirmar mesmo assim?",
          ok: "Confirmar",
          tone: "accent",
        }))) throw e;
        return createPunch(body, true, confirmOverlap);
      }
      if (e instanceof ApiError && e.need === "overlap") {
        if (!(await confirm({
          title: "Sobreposição",
          message: e.message + " — confirmar mesmo assim?",
          ok: "Confirmar",
          tone: "accent",
        }))) throw e;
        return createPunch(body, confirmMidnight, true);
      }
      throw e;
    }
  }

  async function punch() {
    if (busy || punchMode.kind === "ambiguous") return;
    const hoje = todayIso();
    const hm = nowHm();
    setBusy(true);
    opts.setErr("");
    setPunchMsg("");
    setMsg("");
    try {
      if (punchMode.kind === "in") {
        const e = await createPunch({ dia: hoje, tin: hm, tout: "", just: "", closeOpen: false });
        setPunchMsg("Entrada " + e.tin + " registrada (#" + e.id + ")");
      } else {
        const e = await createPunch({ dia: hoje, tin: "", tout: hm, just: "", closeOpen: true });
        setPunchMsg("Saída " + e.tout + " — fechou #" + e.id);
      }
      opts.setClock(nowHm());
      await opts.reload();
    } catch (e) {
      if (e instanceof ApiError && e.need) return;
      opts.setErr(e instanceof Error ? e.message : "não bateu");
    } finally {
      setBusy(false);
    }
  }

  return { punchMode, punch, busy, punchMsg, msg, setMsg };
}
