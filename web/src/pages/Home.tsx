import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import type { Entry, Home } from "../types";
import { ApiError } from "../types";

export function HomePage() {
  const [home, setHome] = useState<Home | null>(null);
  const [rows, setRows] = useState<Entry[]>([]);
  const [total, setTotal] = useState(0);
  const [q, setQ] = useState("");
  const [dia, setDia] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function load(nextQ = q, nextDia = dia) {
    setErr("");
    try {
      const [h, list] = await Promise.all([
        api.home(),
        api.entries({ q: nextQ, dia: nextDia, n: 80 }),
      ]);
      setHome(h);
      setRows(list.entries);
      setTotal(list.total);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "erro");
    }
  }

  useEffect(() => { void load(); }, []);

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
        <button className="btn" onClick={() => void load(q, dia)}>Filtrar</button>
        <Link className="btn primary" to="/novo">Novo</Link>
        <Link className="btn" to="/exportar">PDF / XLS / CSV</Link>
      </div>

      {err && <p className="err">{err}</p>}
      <div className="card">
        <p className="muted">{total} registro(s)</p>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>DATA</th>
              <th className="hide-sm">DIA</th>
              <th>IN</th>
              <th>OUT</th>
              <th>DUR</th>
              <th>JUSTIFICATIVA</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id} className={e.open ? "open" : e.note ? "note" : ""}>
                <td>{e.id}</td>
                <td>{e.dia}</td>
                <td className="hide-sm">{e.d3}</td>
                <td>{e.tin || "—"}</td>
                <td>{e.tout || "—"}</td>
                <td>{e.dur}</td>
                <td>{e.just}</td>
                <td>
                  <div className="actions">
                    <Link className="btn" to={"/edit/" + e.id}>editar</Link>
                    <button className="btn danger" disabled={busy} onClick={() => void del(e.id)}>apagar</button>
                  </div>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={8} className="muted">nada neste filtro</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
