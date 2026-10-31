import { useEffect, useState } from "react";
import { api } from "../api";
import type { Config } from "../types";

export function ConfigPage() {
  const [cfg, setCfg] = useState<Config | null>(null);
  const [who, setWho] = useState("");
  const [off, setOff] = useState("");
  const [daily, setDaily] = useState("");
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.config().then((c) => {
      setCfg(c);
      setWho(c.who);
      setOff(c.off);
      setDaily(c.daily);
    }).catch((e: unknown) => setErr(e instanceof Error ? e.message : "erro"));
  }, []);

  async function save(vacuum = false) {
    setBusy(true);
    setErr("");
    setMsg("");
    try {
      const c = await api.saveConfig({ who, off, daily, vacuum });
      setCfg(c);
      setMsg(vacuum ? "salvo e vacuum feito" : "salvo");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não salvou");
    } finally {
      setBusy(false);
    }
  }

  if (!cfg && !err) return <p className="muted">carregando…</p>;

  return (
    <div className="card">
      <h2>Config</h2>
      {err && <p className="err">{err}</p>}
      {msg && <p className="ok">{msg}</p>}
      {cfg && (
        <>
          <p className="muted">banco {cfg.db}</p>
          <p className="muted">{cfg.records} registros · {cfg.from} … {cfg.to} · java {cfg.java} · sqlite {cfg.sqlite}</p>
          <div className="form" style={{ marginTop: 12 }}>
            <label>Nome
              <input value={who} onChange={(e) => setWho(e.target.value)} />
            </label>
            <label className="span2">Folgas próprias (espaço, vírgula ou ;)
              <input value={off} onChange={(e) => setOff(e.target.value)} placeholder="27/10/2026 31/12" />
            </label>
            <label>Horas por dia
              <input type="number" min={0.5} max={24} step={0.25} value={daily} onChange={(e) => setDaily(e.target.value)} placeholder="8" />
            </label>
            <p className="muted" style={{ margin: 0, alignSelf: "start" }}>
jornada em uso: {cfg.dailyMins / 60} h/dia · vazio = 8 h</p>
          </div>
          {cfg.nextOff.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <p className="muted">próximos dias de folha</p>
              {cfg.nextOff.map((s) => <p key={s} className="warn">{s}</p>)}
            </div>
          )}
          <div className="row" style={{ marginTop: 16 }}>
            <button className="btn primary" disabled={busy} onClick={() => void save(false)}>Salvar</button>
            <button className="btn" disabled={busy} onClick={() => void save(true)}>Vacuum</button>
          </div>
        </>
      )}
    </div>
  );
}
