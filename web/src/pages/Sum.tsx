import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import type { Home, Sum } from "../types";

export function SumPage() {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [sum, setSum] = useState<Sum | null>(null);
  const [err, setErr] = useState("");

  useEffect(() => {
    api.home().then((h: Home) => {
      setFrom(h.min || new Date().toISOString().slice(0, 10));
      setTo(h.max || new Date().toISOString().slice(0, 10));
    }).catch((e: unknown) => setErr(e instanceof Error ? e.message : "erro"));
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
