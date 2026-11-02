import { Fragment, useEffect, useMemo, useState } from "react";
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
import { api } from "../api";
import { useConfirm } from "../ui/Confirm";
import { isEscuro, toggleTema } from "../theme";
import type { Bank, BankDay, Entry, Home } from "../types";
import { ApiError } from "../types";

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

/** "2026-09" para o seletor, a partir de "09/2026" ou "2026-09" (dia textual). */
function mesFromDia(d: string): string {
  const t = d.trim();
  let y = "", m = "";
  const a = t.match(/^(\d{1,2})\/(\d{4})$/);
  const b = t.match(/^(\d{4})-(\d{1,2})$/);
  if (a) { m = a[1]; y = a[2]; }
  else if (b) { y = b[1]; m = b[2]; }
  else return "";
  return y + "-" + m.padStart(2, "0");
}

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
  const [msg, setMsg] = useState("");
  const [sel, setSel] = useState("");
  const [q, setQ] = useState("");
  const [dia, setDia] = useState("");            // texto (dia/mês/ano): sobrepõe a faixa
  const [de, setDe] = useState(mesPickerAtual);  // faixa "do mês" até
  const [ate, setAte] = useState(mesPickerAtual);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [escuro, setEscuro] = useState(() => isEscuro());

  async function load(nextQ = q, nextDia = dia, nextDe = de, nextAte = ate) {
    setErr("");
    // faixa ativa quando não há filtro de texto; pickers travam de <= até no estado
    const useRange = !nextDia && nextDe && nextAte;
    const deV = useRange && nextAte < nextDe ? nextAte : nextDe;
    const ateV = useRange && nextAte < nextDe ? nextDe : nextAte;
    try {
      const [h, list, bk] = await Promise.all([
        api.home(),
        useRange
          ? api.entriesRange(deV + "-01", lastDiaIso(ateV))
          : api.entries({ q: nextQ, dia: nextDia, n: 500 }),
        api.bank(useRange ? ateV : (mesFromDia(nextDia) || undefined)),
      ]);
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
    } catch (e) {
      setErr(e instanceof Error ? e.message : "erro");
    }
  }

  useEffect(() => { void load(); }, []);

  const groups = useMemo(() => groupByDay(rows), [rows]);
  const pageMins = useMemo(() => rows.reduce((s, e) => s + minsOf(e), 0), [rows]);
  const selDay = bank?.days.find((d) => d.diaIso === sel) ?? null;

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

  const filtered = Boolean(q.trim() || dia.trim() || de !== ate);

  return (
    <>
      {home && (
        <section className="stats">
          <div className="stat"><b>{home.who || "—"}</b><span>nome</span></div>
          <div className="stat"><b>{home.monthLabel}</b><span>este mês · {home.monthHours} h</span></div>
          <div className="stat"><b>{home.open}</b><span>abertos{home.openSince ? " desde " + home.openSince : ""}</span></div>
          <div className="stat"><b>{home.records}</b><span>{home.from} … {home.to}</span></div>
        </section>
      )}

      <div className="toolbar">
        <input placeholder="buscar justificativa (ex. atestado)" value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void load(); }} />
        <input placeholder="dia, mês ou ano (oct, 10/2026, hoje)" value={dia}
          onChange={(e) => setDia(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void load(); }} />
        <input type="month" className="mes" title="do mês — a lista abre no mês corrente e aceita um período"
          value={de} max={ate || undefined} disabled={Boolean(dia)}
          onChange={(e) => {
            const v = e.target.value;
            if (!v) return;
            const a = ate && v > ate ? v : ate;
            setDe(v); setAte(a); setDia(""); setSel("");
            void load(q, "", v, a);
          }} />
        <span className="faixa-sep">até</span>
        <input type="month" className="mes" title="até mês — com o outro selecionado, vale o período inteiro"
          value={ate} min={de || undefined} disabled={Boolean(dia)}
          onChange={(e) => {
            const v = e.target.value;
            if (!v) return;
            const d2 = de && v < de ? v : de;
            setAte(v); setDe(d2); setDia(""); setSel("");
            void load(q, "", d2, v);
          }} />
        <button className="btn tema" title="tema claro/escuro" aria-label="alternar tema"
          onClick={() => { toggleTema(); setEscuro(isEscuro()); }}>
          {escuro ? <Ico path={SUN} /> : <Ico path={MOON} />}
        </button>
        <button className="btn" onClick={() => void load()}>Filtrar</button>
        <Link className="btn primary push" to="/novo">Novo</Link>
        <Link className="btn" to="/exportar">PDF / XLS / CSV</Link>
      </div>

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
      {err && <p className="err">{err}</p>}
      <div className="card">
        <p className="muted">
          {rows.length} na página{rows.length !== total ? " de " + total : ""}
          {" · "}{hm(pageMins)} ({dec(pageMins)} h)
        </p>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>IN</th>
              <th>OUT</th>
              <th>DUR</th>
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
                      <div className="actions">
                        <Link className="btn" to={"/edit/" + e.id}>editar</Link>
                        <button className="btn danger" disabled={busy} onClick={() => void del(e.id)}>apagar</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </Fragment>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={7} className="muted">nada neste filtro</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {bank && (
        <div className="charts">
          <div className="card">
            <p className="muted">
              minutos por dia · {hm(bank.dailyMins)} por dia
              {bank.planLabel && <> · plano <b>{bank.planLabel}</b> (linha a {hm(bank.dailyMins + bank.planMins)})</>}
            </p>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={bank.days.map((d) => ({ ...d, dnum: Number(d.diaIso.slice(8, 10)) }))}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="dnum" tick={{ fontSize: 10 }} interval={1} tickLine={false} />
                <YAxis tick={{ fontSize: 10 }} width={34} tickLine={false} axisLine={false}
                  tickFormatter={(v: number) => Math.round(v / 60) + "h"} />
                <Tooltip formatter={(v) => hm(Number(v))}
                  labelFormatter={(l, p) => String(p?.[0]?.payload?.dia ?? l)} />
                <ReferenceLine y={bank.dailyMins + bank.planMins} stroke="#ff9f0a" strokeDasharray="4 4" />
                <Bar dataKey="mins" isAnimationActive={false}>
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
              <BarChart data={bank.months.map((m) => ({ ...m, mes: m.ym.slice(5) + "/" + m.ym.slice(2, 4) }))}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="mes" tick={{ fontSize: 10 }} interval={0} tickLine={false} />
                <YAxis tick={{ fontSize: 10 }} width={40} tickLine={false} axisLine={false}
                  tickFormatter={(v: number) => Math.round(v / 60) + "h"} />
                <Tooltip formatter={(v) => otVal(Number(v))} />
                <ReferenceLine y={0} stroke="rgba(127,127,127,0.6)" />
                <Bar dataKey="saldoMins" isAnimationActive={false}>
                  {bank.months.map((m) => (
                    <Cell key={m.ym} fill={m.saldoMins < 0 ? "#ff3b30" : "#34c759"} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}
    </>
  );
}
