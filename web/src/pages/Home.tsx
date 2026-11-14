import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, type EntrySave } from "../api";
import { useCompany } from "../Company";
import { JUST_CATEGORIES, matchesJustCategory, type JustCategoryCode } from "../justTaxonomy";
import { nowHm } from "../mask";
import { useConfirm } from "../ui/Confirm";
import { isEscuro, toggleTema } from "../theme";
import type { Bank, BankDay, DayInfo, Entry, Home, MonthSaldo } from "../types";
import { ApiError } from "../types";

/** "YYYY-MM-DD" do relógio local — dia da batida rápida. */
function todayIso(): string {
  const d = new Date();
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}

type PunchMode =
  | { kind: "in" }
  | { kind: "out"; open: Entry }
  | { kind: "ambiguous"; opens: Entry[]; note: string };

function minsOf(e: Entry): number {
  if (!e.closed || !e.hours) return 0;
  const n = Number(e.hours);
  return Number.isFinite(n) ? Math.round(n * 60) : 0;
}

function hm(mins: number): string {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h + ":" + (m < 10 ? "0" : "") + m;
}

function dec(mins: number): string {
  return (mins / 60).toFixed(2);
}

function Ico({ path }: { path: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

const MOON = "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z";
const SUN = "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42 1.42";

/** Overtime value with sign: "+5:02" / "-1:05". */
function otVal(mins: number): string {
  const a = Math.abs(mins);
  const hm = Math.floor(a / 60) + ":" + String(a % 60).padStart(2, "0");
  return (mins > 0 ? "+" : mins < 0 ? "-" : "") + hm;
}

/** Overtime caption: "5.03 h extras" / "1.08 h banco de horas". */
function otCap(mins: number): string {
  return (Math.abs(mins) / 60).toFixed(2) + " h " + (mins < 0 ? "banco de horas" : "extras");
}

/** "2026-09" para o seletor, a partir de mês ou dia (ISO ou BR). */
function mesFromDia(d: string): string {
  const t = d.trim();
  let y = "", m = "";
  const a = t.match(/^(\d{1,2})\/(\d{4})$/);                 // mm/yyyy
  const b = t.match(/^(\d{4})-(\d{1,2})$/);                   // yyyy-mm
  const c = t.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);       // dd/mm/yyyy
  const e = t.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);         // yyyy-mm-dd
  if (a) { m = a[1]; y = a[2]; }
  else if (b) { y = b[1]; m = b[2]; }
  else if (c) { m = c[2]; y = c[3]; }
  else if (e) { y = e[1]; m = e[2]; }
  else return "";
  return y + "-" + m.padStart(2, "0");
}

const CHART_MARGIN = { top: 8, right: 8, bottom: 4, left: 0 };
const CHART_Y_WIDTH = 40;

/** "2026-11" para o seletor de mês; o mês corrente é o padrão. */
function mesPickerAtual(): string {
  const d = new Date();
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0");
}

/** Último dia do mês "YYYY-MM" em ISO. */
function lastDiaIso(ym: string): string {
  const [y, m] = ym.split("-").map(Number);
  return ym + "-" + String(new Date(y, m, 0).getDate()).padStart(2, "0");
}

type DayGroup = {
  key: string;
  dia: string;
  d3: string;
  off: string;
  entries: Entry[];
  mins: number;
};

function groupByDay(rows: Entry[]): DayGroup[] {
  const groups: DayGroup[] = [];
  for (const e of rows) {
    const last = groups.at(-1);
    if (!last || last.key !== e.diaIso) {
      groups.push({ key: e.diaIso, dia: e.dia, d3: e.d3, off: e.off, entries: [e], mins: minsOf(e) });
    } else {
      last.entries.push(e);
      last.mins += minsOf(e);
    }
  }
  return groups;
}

/** Bar colour of one day of the bank chart: falta red, days off grey, workdays blue. */
function dayColor(d: BankDay): string {
  if (d.falta) return "#ff3b30";
  if (d.kind !== "work") return "#98989d";
  return "#007aff";
}

/** What the day is today, in one line: the kind plus the flags on it. */
function kindLabel(d: BankDay): string {
  const base = d.kind === "work" ? "dia útil"
             : d.kind === "weekend" ? "fim de semana"
             : d.kind === "holiday" ? "feriado"
             : d.kind === "optional" ? "opcional"
             : "seu dia off";
  return base + (d.falta ? " · falta" : "") + (d.normal ? " · normal" : "");
}

export function HomePage() {
  const confirm = useConfirm();
  const { active } = useCompany();
  const request = useRef(0);
  const [loading, setLoading] = useState(true);
  const [home, setHome] = useState<Home | null>(null);
  const [rows, setRows] = useState<Entry[]>([]);
  const [total, setTotal] = useState(0);
  const [filterDur, setFilterDur] = useState("0:00");
  const [filterHours, setFilterHours] = useState("0.00");
  const [filterOpen, setFilterOpen] = useState(0);
  const [filterOt, setFilterOt] = useState(0);
  const [faltas, setFaltas] = useState<Set<string>>(new Set());
  const [normais, setNormais] = useState<Set<string>>(new Set());
  const [bank, setBank] = useState<Bank | null>(null);
  const [todayDay, setTodayDay] = useState<DayInfo | null>(null);
  const [clock, setClock] = useState(nowHm);
  const [msg, setMsg] = useState("");
  const [punchMsg, setPunchMsg] = useState("");
  const [sel, setSel] = useState("");
  const [q, setQ] = useState("");
  const [justCat, setJustCat] = useState<JustCategoryCode | "">("");
  const [dia, setDia] = useState("");            // texto (dia/mês/ano): sobrepõe a faixa
  const [de, setDe] = useState(mesPickerAtual);  // faixa "do mês" até
  const [ate, setAte] = useState(mesPickerAtual);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [escuro, setEscuro] = useState(() => isEscuro());

  async function load(nextQ = q, nextDia = dia, nextDe = de, nextAte = ate) {
    const current = ++request.current;
    setLoading(true);
    setErr("");
    // faixa ativa quando não há filtro de texto; pickers travam de <= até no estado
    const useRange = !nextDia && nextDe && nextAte;
    const deV = useRange && nextAte < nextDe ? nextAte : nextDe;
    const ateV = useRange && nextAte < nextDe ? nextDe : nextAte;
    const hoje = todayIso();
    try {
      const [h, list, bk, day] = await Promise.all([
        api.home(),
        useRange
          ? api.entriesRange(deV + "-01", lastDiaIso(ateV), 500, nextQ)
          : api.entries({ q: nextQ, dia: nextDia, n: 500 }),
        api.bank(useRange ? ateV : (mesFromDia(nextDia) || undefined)),
        api.day(hoje),
      ]);
      if (current !== request.current) return;
      setHome(h);
      setRows(list.entries);
      setTotal(list.total);
      setFilterDur(list.dur);
      setFilterHours(list.hours);
      setFilterOpen(list.open);
      setFilterOt(list.overtime);
      setFaltas(new Set(list.faltas));
      setNormais(new Set(list.normais));
      setBank(bk);
      setTodayDay(day);
      setClock(nowHm());
    } catch (e) {
      if (current === request.current) setErr(e instanceof Error ? e.message : "Não foi possível carregar os registros.");
    } finally { if (current === request.current) setLoading(false); }
  }

  useEffect(() => { void load(); }, [active?.id]);

  useEffect(() => {
    const tick = window.setInterval(() => setClock(nowHm()), 30_000);
    const onFocus = () => setClock(nowHm());
    window.addEventListener("focus", onFocus);
    return () => {
      window.clearInterval(tick);
      window.removeEventListener("focus", onFocus);
    };
  }, []);

  const visible = useMemo(() => {
    if (!justCat) return rows;
    return rows.filter((e) => matchesJustCategory(e.just, justCat));
  }, [rows, justCat]);
  const groups = useMemo(() => groupByDay(visible), [visible]);
  const pageMins = useMemo(() => visible.reduce((s, e) => s + minsOf(e), 0), [visible]);
  const selDay = bank?.days.find((d) => d.diaIso === sel) ?? null;

  const punchMode: PunchMode = useMemo(() => {
    const todayOpens = todayDay?.open ?? [];
    const openCount = home?.open ?? 0;
    if (todayOpens.length === 1) return { kind: "out", open: todayOpens[0] };
    if (todayOpens.length > 1) {
      return {
        kind: "ambiguous",
        opens: todayOpens,
        note: todayOpens.length + " abertos hoje — feche no formulário",
      };
    }
    if (openCount > 0) {
      const fromList = rows.filter((e) => e.open);
      return {
        kind: "ambiguous",
        opens: fromList,
        note: openCount === 1
          ? "Há 1 aberto de outro dia — edite no formulário"
          : "Há " + openCount + " abertos de outro dia — edite no formulário",
      };
    }
    return { kind: "in" };
  }, [todayDay, home, rows]);

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
    setErr("");
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
      setClock(nowHm());
      await load();
    } catch (e) {
      if (e instanceof ApiError && e.need) return; // cancelou a confirmação
      setErr(e instanceof Error ? e.message : "não bateu");
    } finally {
      setBusy(false);
    }
  }

  /** Clique numa barra do gráfico diário: filtra a lista por esse dia. */
  function onChartDayClick(d: BankDay) {
    setDia(d.diaIso);
    setJustCat("");
    setSel("");
    void load(q, d.diaIso, de, ate);
  }

  /** Resolve o dia a partir do evento do Recharts 3 (payload, campos espalhados ou índice). */
  function dayFromBarEvent(data: unknown, index: number): BankDay | null {
    if (!bank) return null;
    const row = data as { payload?: BankDay; diaIso?: string; originalDataIndex?: number } | null;
    const i = row?.originalDataIndex ?? index;
    if (Number.isInteger(i) && i >= 0 && i < bank.days.length) return bank.days[i];
    const iso = row?.payload?.diaIso ?? row?.diaIso;
    return iso ? bank.days.find((d) => d.diaIso === iso) ?? null : null;
  }

  /** Clique numa barra do gráfico de banco de horas (mês): filtra a lista por esse mês. */
  function onChartMonthClick(ym: string) {
    setDia("");
    setDe(ym);
    setAte(ym);
    setSel("");
    void load(q, "", ym, ym);
  }

  function monthFromBarEvent(data: unknown, index: number): string | null {
    if (!bank) return null;
    const row = data as { payload?: MonthSaldo; ym?: string; originalDataIndex?: number } | null;
    const i = row?.originalDataIndex ?? index;
    if (Number.isInteger(i) && i >= 0 && i < bank.months.length) return bank.months[i].ym;
    return row?.payload?.ym ?? row?.ym ?? null;
  }

  async function del(id: number) {
    if (!(await confirm({
      title: "Apagar #" + id,
      message: "O registro " + id + " sai do banco. Sem volta.",
      ok: "Apagar",
      tone: "stamp",
    }))) return;
    setBusy(true);
    try {
      await api.remove(id);
      await load();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "não apagou");
    } finally {
      setBusy(false);
    }
  }

  async function toggleFlag(diaIso: string, dia: string, what: "falta" | "normal") {
    const on = !(what === "falta" ? faltas : normais).has(diaIso);
    if (!(await confirm(what === "falta"
      ? {
          title: "Falta",
          message: on
            ? "Marcar a falta de " + dia + " — desconta a jornada do banco de horas."
            : "Retirar a falta de " + dia + " — o dia volta à regra normal.",
          ok: on ? "Marcar falta" : "Retirar falta",
          tone: on ? "stamp" : "accent",
        }
      : {
          title: "Dia normal",
          message: on
            ? "Marcar " + dia + " como dia normal — as batidas pagam a jornada (não somam banco)."
            : "Desmarcar o dia normal de " + dia + " — as batidas voltam a somar ao banco.",
          ok: on ? "Marcar normal" : "Desmarcar",
          tone: on ? "ok" : "accent",
        }))) return;
    setBusy(true);
    setMsg("");
    try {
      const r = what === "falta" ? await api.setFalta(diaIso, on) : await api.setNormal(diaIso, on);
      setMsg(r.warn);
      await load();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "não marcou");
    } finally {
      setBusy(false);
    }
  }

  const filtered = Boolean(q.trim() || justCat || dia.trim() || de !== ate);

  return (
    <>
      <section className="punch" aria-label="Batida rápida">
        <div className="punch-info">
          <b>{clock}</b>
          <span>
            {punchMode.kind === "in" && (todayDay?.lastOut
              ? "próxima entrada · última saída " + todayDay.lastOut
              : "sem aberto hoje")}
            {punchMode.kind === "out" && (
              <>aberto #{punchMode.open.id} desde {punchMode.open.tin}{punchMode.open.diaIso !== todayIso() ? " · " + punchMode.open.dia : ""}</>
            )}
            {punchMode.kind === "ambiguous" && punchMode.note}
          </span>
        </div>
        {punchMode.kind === "ambiguous" ? (
          <div className="punch-actions">
            {punchMode.opens[0] ? (
              <Link className="btn primary" to={"/edit/" + punchMode.opens[0].id}>
                editar #{punchMode.opens[0].id}
              </Link>
            ) : (
              <Link className="btn primary" to="/novo">Abrir formulário</Link>
            )}
            {punchMode.opens.length > 1 && (
              <span className="muted">+{punchMode.opens.length - 1} outro(s)</span>
            )}
          </div>
        ) : (
          <button
            type="button"
            className="btn primary"
            disabled={busy || loading}
            onClick={() => void punch()}
          >
            {busy ? "Batendo…" : punchMode.kind === "in" ? "Entrada agora" : "Saída agora"}
          </button>
        )}
      </section>
      {punchMsg && <p className="ok" role="status">{punchMsg}</p>}

      {home && (
        <section className="stats">
          <div className="stat"><b>{home.monthHours} h</b><span>Horas trabalhadas neste mês</span></div>
          <div className="stat"><b>{home.monthLabel}</b><span>este mês · {home.monthHours} h</span></div>
          <div className="stat"><b>{home.open}</b><span>abertos{home.openSince ? " desde " + home.openSince : ""}</span></div>
          <div className="stat"><b>{home.records}</b><span>{home.from} … {home.to}</span></div>
        </section>
      )}

      <div className="toolbar">
        <label>Buscar justificativa<input placeholder="Ex.: atestado" value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void load(); }} /></label>
        <div className="just-cats" role="group" aria-label="Categorias de justificativa">
          {JUST_CATEGORIES.filter((c) => c.code !== "outro").map((c) => (
            <button
              key={c.code}
              type="button"
              className={"chip" + (justCat === c.code ? " sel" : "")}
              title={"filtrar por " + c.label + " (sinônimos locais)"}
              onClick={() => {
                const next = justCat === c.code ? "" : c.code;
                setJustCat(next);
                if (next) setQ("");
                void load(next ? "" : q);
              }}
            >{c.label}</button>
          ))}
        </div>
        <details className="advanced-filter"><summary>Mais filtros</summary><label>Data específica<input placeholder="Dia, mês ou ano" value={dia}
          onChange={(e) => setDia(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void load(); }} /></label><small>Quando preenchida, esta data substitui o intervalo de meses.</small></details>
        <label>Do mês<input type="month" className="mes" title="do mês — a lista abre no mês corrente e aceita um período"
          value={de} max={ate || undefined} disabled={Boolean(dia)}
          onChange={(e) => {
            const v = e.target.value;
            if (!v) return;
            const a = ate && v > ate ? v : ate;
            setDe(v); setAte(a); setDia(""); setSel("");
            void load(q, "", v, a);
          }} /></label>
        <label>Até o mês<input type="month" className="mes" title="até mês — com o outro selecionado, vale o período inteiro"
          value={ate} min={de || undefined} disabled={Boolean(dia)}
          onChange={(e) => {
            const v = e.target.value;
            if (!v) return;
            const d2 = de && v < de ? v : de;
            setAte(v); setDe(d2); setDia(""); setSel("");
            void load(q, "", d2, v);
          }} /></label>
        <button className="btn tema" title="tema claro/escuro" aria-label="alternar tema"
          onClick={() => { toggleTema(); setEscuro(isEscuro()); }}>
          {escuro ? <Ico path={SUN} /> : <Ico path={MOON} />}
        </button>
        <button className="btn" disabled={loading} onClick={() => void load()}>Aplicar filtros</button>
        <button className="btn" onClick={() => { const m = mesPickerAtual(); setQ(""); setJustCat(""); setDia(""); setDe(m); setAte(m); setSel(""); void load("", "", m, m); }}>Limpar filtros</button>
        <Link className="btn primary push" to="/novo">+ Novo registro</Link>
        <Link className="btn" to="/exportar">PDF / XLS / CSV</Link>
      </div>

      {dia && <p className="warn">Data específica ativa: {dia}. O intervalo de meses está desativado.</p>}
      {loading && <p role="status" className="muted">Carregando registros…</p>}
      <h2 className="section-title">Resultado do filtro</h2>
      <div className="filter-sum" title="total do filtro · horas extras do período · banco do mês">
        <div className="fs-item">
          <b>{filterDur}</b>
          <span>{filterHours} h no total</span>
        </div>
        <div className="fs-item">
          <b>{total}</b>
          <span>{filtered ? "registro(s) no filtro" : "registro(s)"}</span>
        </div>
        <div className={"fs-item" + (filterOt > 0 ? " pos" : filterOt < 0 ? " neg" : "")}>
          <b>{otVal(filterOt)}</b>
          <span>{otCap(filterOt)}</span>
        </div>
        {home && (
          <div className={"fs-item" + (home.saldoMins > 0 ? " pos" : home.saldoMins < 0 ? " neg" : "")}>
            <b>{otVal(home.saldoMins)}</b>
            <span title={home.planLabel ? "plano para zerar o mês" : ""}>
              banco do mês{home.planLabel ? " · " + home.planLabel : ""}
            </span>
          </div>
        )}
      </div>

      {filterOpen > 0 && (
        <p className="warn">{filterOpen} linha(s) incompleta(s) fora da soma</p>
      )}
      {msg && <p className="warn">{msg}</p>}
      {err && <p role="alert" className="err">{err}</p>}
      <div className="card">
        <p className="muted">
          {visible.length} na página{visible.length !== total ? " de " + total : ""}
          {" · "}{hm(pageMins)} ({dec(pageMins)} h)
          {justCat ? " · categoria " + (JUST_CATEGORIES.find((c) => c.code === justCat)?.label ?? justCat) : ""}
        </p>
        <div className="table-scroll"><table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Entrada</th>
              <th>Saída</th>
              <th>Duração</th>
              <th>HORAS</th>
              <th>JUSTIFICATIVA</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => (
              <Fragment key={g.key}>
                <tr className="day-head">
                  <td colSpan={7}>
                    <div className="day-head-row">
                      <span>
                        <b>{g.dia}</b>
                        <span className="muted"> {g.d3} · {g.entries.length} linha(s){g.off && " · " + g.off}</span>
                      </span>
                      <span className="row">
                        <span className="hours">{hm(g.mins)} ({dec(g.mins)} h)</span>
                        <button
                          className={"btn" + (faltas.has(g.key) ? " falta-on" : "")}
                          disabled={busy}
                          title="falta: desconta a jornada do banco de horas"
                          onClick={() => void toggleFlag(g.key, g.dia, "falta")}
                        >
                          {faltas.has(g.key) ? "falta ✓" : "falta"}
                        </button>
                        {g.off !== "" && (
                          <button
                            className={"btn" + (normais.has(g.key) ? " normal-on" : "")}
                            disabled={busy}
                            title="dia normal: as batidas pagam a jornada (não somam banco de horas)"
                            onClick={() => void toggleFlag(g.key, g.dia, "normal")}
                          >
                            {normais.has(g.key) ? "normal ✓" : "normal"}
                          </button>
                        )}
                      </span>
                    </div>
                  </td>
                </tr>
                {g.entries.map((e) => (
                  <tr key={e.id} className={e.open ? "open" : e.note ? "note" : ""}>
                    <td>{e.id}</td>
                    <td>{e.tin || "—"}</td>
                    <td>{e.tout || "—"}</td>
                    <td>{e.dur}</td>
                    <td>{e.hours || "—"}</td>
                    <td>{e.just}</td>
                    <td>
                      <details className="record-actions"><summary>Ações</summary><div className="actions">
                        <Link className="btn" to={"/edit/" + e.id}>editar</Link>
                        <button className="btn danger" disabled={busy} onClick={() => void del(e.id)}>apagar</button>
                      </div></details>
                    </td>
                  </tr>
                ))}
              </Fragment>
            ))}
            {visible.length === 0 && (
              <tr><td colSpan={7} className="muted">Nenhum registro encontrado. Ajuste os filtros ou crie um novo registro.</td></tr>
            )}
          </tbody>
        </table></div>
      </div>

      {bank && (
        <>
          <p className="muted chart-hint">Clique numa barra para filtrar a lista por esse dia ou mês.</p>
          <div className="charts">
          <div className="card">
            <p className="muted">
              minutos por dia · {hm(bank.dailyMins)} por dia
              {bank.planLabel && <> · plano <b>{bank.planLabel}</b> (linha a {hm(bank.dailyMins + bank.planMins)})</>}
            </p>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart margin={CHART_MARGIN}
                data={bank.days.map((d) => ({ ...d, dnum: Number(d.diaIso.slice(8, 10)) }))}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="dnum" tick={{ fontSize: 10 }} interval={1} tickLine={false} />
                <YAxis tick={{ fontSize: 10 }} width={CHART_Y_WIDTH} tickLine={false} axisLine={false}
                  tickFormatter={(v: number) => Math.round(v / 60) + "h"} />
                <Tooltip contentStyle={{ background: "var(--card)", borderColor: "var(--line)", color: "var(--ink)" }} formatter={(v) => hm(Number(v))}
                  labelFormatter={(l, p) => String(p?.[0]?.payload?.dia ?? l)} />
                <ReferenceLine y={bank.dailyMins + bank.planMins} stroke="#ff9f0a" strokeDasharray="4 4" />
                {/* minPointSize: Recharts 3 descarta barras com altura 0 — sem isso dias vazios não recebem clique. */}
                <Bar dataKey="mins" isAnimationActive={false} cursor="pointer" minPointSize={3}
                  background={{ fill: "transparent" }}
                  onClick={(data, index) => {
                    const day = dayFromBarEvent(data, index);
                    if (day) onChartDayClick(day);
                  }}>
                  {bank.days.map((d) => <Cell key={d.diaIso} fill={dayColor(d)} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
            {bank.days.length > 0 && (
              <div className="day-chips" title="clique num dia para marcar falta ou dia normal (vale para dia vazio e futuro)">
                {bank.days.map((d) => (
                  <button key={d.diaIso}
                    className={"chip" + (d.falta ? " c-falta" : d.normal ? " c-normal" : d.kind === "work" ? " c-work" : " c-off")
                             + (sel === d.diaIso ? " sel" : "")}
                    title={d.dia + " · " + kindLabel(d)}
                    onClick={() => setSel(sel === d.diaIso ? "" : d.diaIso)}>
                    {d.diaIso.slice(8, 10)}
                  </button>
                ))}
              </div>
            )}
            {selDay && (
              <div className="chip-actions">
                <b>{selDay.dia}</b>
                <span className="muted">{kindLabel(selDay)}</span>
                <button className={"btn" + (faltas.has(selDay.diaIso) ? " falta-on" : "")} disabled={busy}
                  title="falta: desconta a jornada do banco de horas"
                  onClick={() => void toggleFlag(selDay.diaIso, selDay.dia, "falta")}>
                  {faltas.has(selDay.diaIso) ? "falta ✓" : "falta"}
                </button>
                <button className={"btn" + (normais.has(selDay.diaIso) ? " normal-on" : "")} disabled={busy}
                  title="dia normal: as batidas pagam a jornada (não somam banco de horas)"
                  onClick={() => void toggleFlag(selDay.diaIso, selDay.dia, "normal")}>
                  {normais.has(selDay.diaIso) ? "normal ✓" : "normal"}
                </button>
              </div>
            )}
          </div>
          <div className="card">
            <p className="muted">banco de horas · últimos 12 meses</p>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart margin={CHART_MARGIN}
                data={bank.months.map((m) => ({ ...m, mes: m.ym.slice(5) + "/" + m.ym.slice(2, 4) }))}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="mes" tick={{ fontSize: 10 }} interval={0} tickLine={false} />
                <YAxis tick={{ fontSize: 10 }} width={CHART_Y_WIDTH} tickLine={false} axisLine={false}
                  tickFormatter={(v: number) => Math.round(v / 60) + "h"} />
                <Tooltip contentStyle={{ background: "var(--card)", borderColor: "var(--line)", color: "var(--ink)" }} formatter={(v) => otVal(Number(v))} />
                <ReferenceLine y={0} stroke="rgba(127,127,127,0.6)" />
                <Bar dataKey="saldoMins" isAnimationActive={false} cursor="pointer" minPointSize={3}
                  background={{ fill: "transparent" }}
                  onClick={(data, index) => {
                    const ym = monthFromBarEvent(data, index);
                    if (ym) onChartMonthClick(ym);
                  }}>
                  {bank.months.map((m) => (
                    <Cell key={m.ym} fill={m.saldoMins < 0 ? "#ff3b30" : "#34c759"} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
          </div>
        </>
      )}
    </>
  );
}
