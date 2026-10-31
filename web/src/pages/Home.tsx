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

/** "2026-10" for the month picker, when the dia filter is "10/2026" or "2026-10". */
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

type DayGroup = {
  key: string;
  dia: string;
  d3: string;
  entries: Entry[];
  mins: number;
};

function groupByDay(rows: Entry[]): DayGroup[] {
  const groups: DayGroup[] = [];
  for (const e of rows) {
    const last = groups.at(-1);
    if (!last || last.key !== e.diaIso) {
      groups.push({ key: e.diaIso, dia: e.dia, d3: e.d3, entries: [e], mins: minsOf(e) });
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
  if (d.kind !== "work") return "#c7c7cc";
  return "#007aff";
}

export function HomePage() {
  const [home, setHome] = useState<Home | null>(null);
  const [rows, setRows] = useState<Entry[]>([]);
  const [total, setTotal] = useState(0);
  const [filterDur, setFilterDur] = useState("0:00");
  const [filterHours, setFilterHours] = useState("0.00");
  const [filterOpen, setFilterOpen] = useState(0);
  const [filterOt, setFilterOt] = useState(0);
  const [faltas, setFaltas] = useState<Set<string>>(new Set());
  const [bank, setBank] = useState<Bank | null>(null);
  const [msg, setMsg] = useState("");
  const [q, setQ] = useState("");
  const [dia, setDia] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function load(nextQ = q, nextDia = dia) {
    setErr("");
    try {
      const [h, list, bk] = await Promise.all([
        api.home(),
        api.entries({ q: nextQ, dia: nextDia, n: 500 }),
        api.bank(),
      ]);
      setHome(h);
      setRows(list.entries);
      setTotal(list.total);
      setFilterDur(list.dur);
      setFilterHours(list.hours);
      setFilterOpen(list.open);
      setFilterOt(list.overtime);
      setFaltas(new Set(list.faltas));
      setBank(bk);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "erro");
    }
  }

  useEffect(() => { void load(); }, []);

  const groups = useMemo(() => groupByDay(rows), [rows]);
  const pageMins = useMemo(() => rows.reduce((s, e) => s + minsOf(e), 0), [rows]);

  async function del(id: number) {
    if (!confirm("apagar #" + id + "?")) return;
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

  async function toggleFalta(g: DayGroup) {
    const on = !faltas.has(g.key);
    if (!confirm((on ? "marcar" : "retirar") + " a falta de " + g.dia + "?")) return;
    setBusy(true);
    setMsg("");
    try {
      const r = await api.setFalta(g.key, on);
      setMsg(r.warn);
      await load();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "não marcou");
    } finally {
      setBusy(false);
    }
  }

  const filtered = Boolean(q.trim() || dia.trim());

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
          onKeyDown={(e) => { if (e.key === "Enter") void load(q, dia); }} />
        <input placeholder="dia, mês ou ano (oct, 10/2026, hoje)" value={dia}
          onChange={(e) => setDia(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void load(q, dia); }} />
        <input type="month" className="mes" title="selecionar mês (horas extras daquele mês)"
          value={mesFromDia(dia)}
          onChange={(e) => {
            const v = e.target.value; // "2026-10" or ""
            const nd = v ? v.replace(/^(\d{4})-(\d{2})$/, "$2/$1") : "";
            setDia(nd);
            void load(q, nd);
          }} />
        <button className="btn" onClick={() => void load(q, dia)}>Filtrar</button>
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
                        <span className="muted"> {g.d3} · {g.entries.length} linha(s)</span>
                      </span>
                      <span className="row">
                        <span className="hours">{hm(g.mins)} ({dec(g.mins)} h)</span>
                        <button
                          className={"btn" + (faltas.has(g.key) ? " falta-on" : "")}
                          disabled={busy}
                          title="falta: desconta a jornada do banco de horas"
                          onClick={() => void toggleFalta(g)}
                        >
                          {faltas.has(g.key) ? "falta ✓" : "falta"}
                        </button>
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
                <ReferenceLine y={0} stroke="rgba(0,0,0,0.3)" />
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
