import { useEffect, useState } from "react";
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
    setErr("");
    try { setSum(await api.sum(nextFrom, nextTo)); }
    catch (e) { setErr(e instanceof Error ? e.message : "erro"); }
  }

  useEffect(() => { if (from && to) void run(from, to); }, [from, to]);

  return (
    <div className="card">
      <h2>Soma</h2>
      <div className="toolbar">
        <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        <button className="btn" onClick={() => void run()}>Calcular</button>
        <Link className="btn" to="/exportar">Exportar PDF / XLS / CSV</Link>
      </div>
      {err && <p className="err">{err}</p>}
      {sum && (
        <>
          <p className="muted">{sum.from} — {sum.to} · {sum.lines} linhas · {sum.total} ({sum.hours} h)</p>
          <p className="muted">
            saldo do período <b>{sum.saldo}</b>
            {sum.faltas > 0 && <> · faltas: {sum.faltas}</>}
            {sum.plano && <> · plano {sum.plano}</>}
          </p>
          {sum.open > 0 && <p className="warn">{sum.open} linha(s) incompleta(s) fora da soma</p>}
          <table>
            <thead>
              <tr><th>DATA</th><th>DIA</th><th>LINHAS</th><th>DURAÇÃO</th><th>HORAS</th></tr>
            </thead>
            <tbody>
              {sum.days.map((d) => (
                <tr key={d.diaIso}>
                  <td>{d.dia}</td><td>{d.d3}</td><td>{d.lines}</td><td>{d.dur}</td><td>{d.hours}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
