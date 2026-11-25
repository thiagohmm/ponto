import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { api, selectApiCompany } from "./api";
import { useAuth } from "./Auth";
import { useLeaveGuard } from "./ui/Unsaved";
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

function pickSelected(list: CompanyList): CompanyList {
  let preferred = 0;
  try { preferred = Number(sessionStorage.getItem("ponto-company")); } catch { /* private browser */ }
  const selected = list.companies.find(c => c.id === preferred)?.id ?? list.activeId;
  selectApiCompany(selected);
  return { ...list, activeId: selected };
}

export function CompanyProvider({ children }: { children: ReactNode }) {
  const { ready, initialCompanies } = useAuth();
  const canLeave = useLeaveGuard();
  const [error, setError] = useState("");
  const [data, setData] = useState<CompanyList | null>(() => (initialCompanies ? pickSelected(initialCompanies) : null));

  const reload = useCallback(async () => {
    const list = await api.companies();
    setData(pickSelected(list));
  }, []);

  useEffect(() => {
    if (data) return;
    if (initialCompanies) {
      setData(pickSelected(initialCompanies));
      return;
    }
    if (!ready) return;
    void reload().catch(e => setError(e instanceof Error ? e.message : "Não foi possível carregar as empresas."));
  }, [data, initialCompanies, ready, reload]);

  const select = useCallback(async (id: number) => {
    if (!(await canLeave())) return;
    if (data?.companies.some(c => c.id === id)) {
      selectApiCompany(id);
      try { sessionStorage.setItem("ponto-company", String(id)); } catch { /* private browser */ }
      setData(cur => (cur ? { ...cur, activeId: id } : cur));
      return;
    }
    selectApiCompany(id);
    try { sessionStorage.setItem("ponto-company", String(id)); } catch { /* private browser */ }
    await reload();
  }, [canLeave, data, reload]);

  const active = data?.companies.find((c) => c.id === data.activeId) ?? null;
  const ctxValue = useMemo(
    () => ({ active, companies: data?.companies ?? [], reload, select }),
    [active, data?.companies, reload, select],
  );

  return (
    <CompanyCtx.Provider value={ctxValue}>
      {error && <p role="alert" className="err">{error} <button className="btn" onClick={() => { setError(""); void reload().catch(e => setError(String(e))); }}>Tentar novamente</button></p>}
      {data ? children : !error && <p role="status">Carregando empresas…</p>}
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
              onClick={() => { void select(c.id).catch(e => window.alert(String(e))); setOpen(false); }}>
              {c.name}
            </button>
          ))}
          <Link to="/config" className="picker-cfg" onClick={() => setOpen(false)}>gerenciar</Link>
        </div>
      )}
    </div>
  );
}
