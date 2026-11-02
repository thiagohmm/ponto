import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { api } from "./api";
import type { Company, CompanyList } from "./types";

type Ctx = {
  active: Company | null;
  companies: Company[];
  reload: () => Promise<void>;
  select: (id: number) => Promise<void>;
};

const CompanyCtx = createContext<Ctx>({
  active: null,
  companies: [],
  reload: async () => {},
  select: async () => {},
});

export function useCompany() {
  return useContext(CompanyCtx);
}

export function CompanyProvider({ children }: { children: ReactNode }) {
  const [error, setError] = useState("");
  const [data, setData] = useState<CompanyList | null>(null);

  const reload = useCallback(async () => {
    const list = await api.companies();
    setData(list);
  }, []);

  useEffect(() => { void reload().catch(e => setError(e instanceof Error ? e.message : "Não foi possível carregar as empresas.")); }, [reload]);

  const select = useCallback(async (id: number) => {
    await api.activateCompany(id);
    await reload();
  }, [reload]);

  const active = data?.companies.find((c) => c.id === data.activeId) ?? null;

  return (
    <CompanyCtx.Provider value={{ active, companies: data?.companies ?? [], reload, select }}>
      {error && <p role="alert" className="err">{error} <button className="btn" onClick={() => { setError(""); void reload().catch(e => setError(String(e))); }}>Tentar novamente</button></p>}
      {children}
    </CompanyCtx.Provider>
  );
}

export function CompanyBar() {
  const { active, companies, select } = useCompany();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return <div className="company-bar">
    <label>Empresa ativa
      <select value={active?.id ?? ""} disabled={busy || !active} onChange={async e => {
        setBusy(true); setError("");
        try { await select(Number(e.target.value)); }
        catch (e) { setError(e instanceof Error ? e.message : "Não foi possível trocar de empresa."); }
        finally { setBusy(false); }
      }}>
        {!active && <option value="">Carregando empresas…</option>}
        {companies.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
    </label>
    {error && <p role="alert" className="err">{error}</p>}
  </div>;
}

export function CompanyStat() {
  const { active, companies, select } = useCompany();
  const [open, setOpen] = useState(false);
  const name = active?.name || "—";

  return (
    <div className={"stat pick" + (open ? " open" : "")}>
      <button type="button" className="stat-hit" onClick={() => setOpen((v) => !v)}>
        <b>{name}</b>
        <span>empresa</span>
      </button>
      {open && (
        <div className="picker">
          {companies.map((c) => (
            <button key={c.id} type="button" className={c.id === active?.id ? "on" : ""}
              onClick={() => { void select(c.id); setOpen(false); }}>
              {c.name}
            </button>
          ))}
          <Link to="/config" className="picker-cfg" onClick={() => setOpen(false)}>gerenciar</Link>
        </div>
      )}
    </div>
  );
}
