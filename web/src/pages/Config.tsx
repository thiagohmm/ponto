import { useEffect, useState } from "react";
import { api } from "../api";
import { useCompany } from "../Company";
import { savedTema, setTema, type Tema } from "../theme";
import type { Company, Config } from "../types";
import { useConfirm } from "../ui/Confirm";

const TEMAS: { id: Tema; label: string }[] = [
  { id: "sistema", label: "Sistema" },
  { id: "claro", label: "Claro" },
  { id: "escuro", label: "Escuro" },
];

type Draft = { name: string; off: string; daily: string };

function draftOf(c: Company): Draft {
  return { name: c.name, off: c.off, daily: c.daily };
}

export function ConfigPage() {
  const confirm = useConfirm();
  const { active, companies, reload, select } = useCompany();
  const [cfg, setCfg] = useState<Config | null>(null);
  const [who, setWho] = useState("");
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [fresh, setFresh] = useState<Draft>({ name: "", off: "", daily: "" });
  const [tema, setTemaState] = useState<Tema>(() => savedTema());
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.config().then((c) => {
      setCfg(c);
      setWho(c.who);
    }).catch((e: unknown) => setErr(e instanceof Error ? e.message : "erro"));
  }, []);

  useEffect(() => {
    setDrafts((cur) => {
      const next: Record<number, Draft> = {};
      for (const c of companies) next[c.id] = cur[c.id] ?? draftOf(c);
      return next;
    });
  }, [companies]);

  function setDraft(id: number, patch: Partial<Draft>) {
    setDrafts((cur) => ({ ...cur, [id]: { ...cur[id], ...patch } }));
  }

  async function saveWho(vacuum = false) {
    setBusy(true);
    setErr("");
    setMsg("");
    try {
      const c = await api.saveConfig({ who, vacuum });
      setCfg(c);
      setMsg(vacuum ? "salvo e vacuum feito" : "salvo");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não salvou");
    } finally {
      setBusy(false);
    }
  }

  async function saveCompany(id: number) {
    const d = drafts[id];
    if (!d) return;
    setBusy(true);
    setErr("");
    setMsg("");
    try {
      await api.updateCompany(id, d);
      await reload();
      const c = await api.config();
      setCfg(c);
      setMsg("empresa salva");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não salvou");
    } finally {
      setBusy(false);
    }
  }

  async function addCompany() {
    const name = fresh.name.trim();
    if (!name) { setErr("nome da empresa"); return; }
    setBusy(true);
    setErr("");
    setMsg("");
    try {
      const c = await api.createCompany(fresh);
      setFresh({ name: "", off: "", daily: "" });
      await reload();
      await select(c.id);
      setMsg("empresa criada");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não criou");
    } finally {
      setBusy(false);
    }
  }

  async function dropCompany(c: Company) {
    if (!(await confirm({
      title: "Apagar " + c.name,
      message: "Só some se não tiver ponto nem notas. A última empresa não sai.",
      ok: "Apagar",
      tone: "stamp",
    }))) return;
    setBusy(true);
    setErr("");
    setMsg("");
    try {
      await api.removeCompany(c.id);
      await reload();
      setMsg("empresa apagada");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou");
    } finally {
      setBusy(false);
    }
  }

  if (!cfg && !err) return <p className="muted">carregando…</p>;

  return (
    <div className="card">
      <h2>Preferências do workspace</h2>
      {err && <p role="alert" className="err">{err}</p>}
      {msg && <p role="status" className="ok">{msg}</p>}
      {cfg && (
        <>
          <h3>Perfil</h3>
          <div className="form" style={{ marginTop: 12 }}>
            <label>Nome
              <input value={who} onChange={(e) => setWho(e.target.value)} />
            </label>
          </div>
          <div className="row" style={{ marginTop: 16 }}>
            <button className="btn primary" disabled={busy} onClick={() => void saveWho(false)}>Salvar</button>

          </div>

          <h3 style={{ marginTop: 28 }}>Empresas</h3>
          <p className="muted">jornada, folgas e banco de horas são de cada empresa</p>
          {companies.map((c) => {
            const d = drafts[c.id] ?? draftOf(c);
            const on = active?.id === c.id;
            return (
              <div key={c.id} className={"company-card" + (on ? " on" : "")}>
                <div className="form">
                  <label>Nome
                    <input value={d.name} onChange={(e) => setDraft(c.id, { name: e.target.value })} />
                  </label>
                  <label>Horas por dia
                    <input type="number" min={0.5} max={24} step={0.25} value={d.daily}
                      onChange={(e) => setDraft(c.id, { daily: e.target.value })} placeholder="8" />
                  </label>
                  <label className="span2">Folgas próprias (espaço, vírgula ou ;)
                    <input value={d.off} onChange={(e) => setDraft(c.id, { off: e.target.value })}
                      placeholder="27/10/2026 31/12" />
                  </label>
                </div>
                <div className="row" style={{ marginTop: 12 }}>
                  {!on && <button className="btn" disabled={busy} onClick={() => void select(c.id)}>Usar</button>}
                  {on && <span className="ok">em uso</span>}
                  <button className="btn primary" disabled={busy} onClick={() => void saveCompany(c.id)}>Salvar</button>
                  <button className="btn danger" disabled={busy} onClick={() => void dropCompany(c)}>Apagar</button>
                </div>
              </div>
            );
          })}
          <div className="company-card">
            <p className="muted" style={{ marginTop: 0 }}>Nova empresa</p>
            <div className="form">
              <label>Nome
                <input value={fresh.name} onChange={(e) => setFresh({ ...fresh, name: e.target.value })} />
              </label>
              <label>Horas por dia
                <input type="number" min={0.5} max={24} step={0.25} value={fresh.daily}
                  onChange={(e) => setFresh({ ...fresh, daily: e.target.value })} placeholder="8" />
              </label>
              <label className="span2">Folgas próprias
                <input value={fresh.off} onChange={(e) => setFresh({ ...fresh, off: e.target.value })}
                  placeholder="27/10/2026 31/12" />
              </label>
            </div>
            <div className="row" style={{ marginTop: 12 }}>
              <button className="btn primary" disabled={busy} onClick={() => void addCompany()}>Cadastrar</button>
            </div>
          </div>

          {cfg.nextOff.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <p className="muted">próximos dias de folha (empresa em uso)</p>
              {cfg.nextOff.map((s) => <p key={s} className="warn">{s}</p>)}
            </div>
          )}
          <div className="row" style={{ marginTop: 16 }}>
            <h3>Aparência</h3>
            <div className="seg">
              {TEMAS.map((t) => (
                <button key={t.id}
                  className={tema === t.id ? "on" : ""}
                  onClick={() => { setTemaState(t.id); setTema(t.id); }}>
                  {t.label}
                </button>
              ))}
            </div>
          </div>
          <details className="maintenance"><summary>Manutenção</summary><p className="muted">Otimize o espaço do banco de dados sem remover registros.</p>          <p className="muted">banco {cfg.db}</p>
          <p className="muted">{cfg.records} registros · {cfg.from} … {cfg.to} · java {cfg.java} · sqlite {cfg.sqlite}</p>
          <p className={cfg.storage ? "ok" : "warn"}>
            arquivos das notas: {cfg.storage ? "armazenamento S3 respondendo" : "armazenamento S3 fora do ar — PONTO_S3_ENDPOINT"}
          </p>
<button className="btn" disabled={busy} onClick={() => void saveWho(true)}>Otimizar banco de dados</button></details>
        </>
      )}
    </div>
  );
}
