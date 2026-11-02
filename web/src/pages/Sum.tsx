import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import type { Sum } from "../types";

/** ISO date, local time. */
function iso(d: Date): string {
  return d.getFullYear() + "-" +
    String(d.getMonth() + 1).padStart(2, "0") + "-" +
    String(d.getDate()).padStart(2, "0");
}

export function SumPage() {
  const request = useRef(0);
  const [loading, setLoading] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [sum, setSum] = useState<Sum | null>(null);
  const [err, setErr] = useState("");

  useEffect(() => {
    // defaults to the current month: the 1st up to today
    const now = new Date();
    setFrom(iso(new Date(now.getFullYear(), now.getMonth(), 1)));
    setTo(iso(now));
  }, []);

  async function run(nextFrom = from, nextTo = to) {
    if (!nextFrom || !nextTo) return;
    const current = ++request.current;
    setErr(""); setLoading(true);
    try { const result = await api.sum(nextFrom, nextTo); if (current === request.current) setSum(result); }
    catch (e) { if (current === request.current) setErr(e instanceof Error ? e.message : "Não foi possível calcular as horas."); }
    finally { if (current === request.current) setLoading(false); }
  }

  useEffect(() => { if (from && to) void run(from, to); }, [from, to]);

  return (
    <div className="card">
      <h2>Horas por período</h2>
      <div className="toolbar">
        <label>Data inicial<input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} /></label>
        <label>Data final<input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} /></label>
        <button className="btn primary" disabled={loading || !from || !to || from > to} onClick={() => void run()}>{loading ? "Calculando…" : "Calcular horas"}</button>
        <Link className="btn" to="/exportar">Exportar PDF / XLS / CSV</Link>
      </div>
      {err && <p role="alert" className="err">{err}</p>}
      {sum && !loading && (
        <>
          <p className="muted">{sum.from} — {sum.to} · {sum.lines} linhas · {sum.total} ({sum.hours} h)</p>
          <p className="muted">
            saldo do período <b>{sum.saldo}</b>
            {sum.faltas > 0 && <> · faltas: {sum.faltas}</>}
            {sum.plano && <> · plano {sum.plano}</>}
          </p>
          {sum.open > 0 && <p className="warn">{sum.open} linha(s) incompleta(s) fora da soma</p>}
          <div className="table-scroll"><table>
            <thead>
              <tr><th>DATA</th><th>DIA</th><th>LINHAS</th><th>DURAÇÃO</th><th>HORAS</th></tr>
            </thead>
            <tbody>
              {sum.days.length === 0 && <tr><td colSpan={5}>Nenhum registro neste período.</td></tr>}
              {sum.days.map((d) => (
                <tr key={d.diaIso}>
                  <td>{d.dia}</td><td>{d.d3}</td><td>{d.lines}</td><td>{d.dur}</td><td>{d.hours}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        </>
      )}
    </div>
  );
}
