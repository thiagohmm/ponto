import { createContext, useContext, useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { api } from "./api";
import { BrandMark } from "./ui/BrandMark";
import { ApiError, type CompanyList } from "./types";

type AuthState = {
  user: string | null;
  ready: boolean;
  initialCompanies: CompanyList | null;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
};

const Ctx = createContext<AuthState>({
  user: null,
  ready: true,
  initialCompanies: null,
  refresh: async () => {},
  logout: async () => {},
});

export function useAuth() { return useContext(Ctx); }

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [initialCompanies, setInitialCompanies] = useState<CompanyList | null>(null);

  async function refresh() {
    try {
      const me = await api.me();
      setUser(me.user);
      if (typeof me.activeId === "number" && Array.isArray(me.companies)) {
        setInitialCompanies({ activeId: me.activeId, companies: me.companies });
      } else {
        setInitialCompanies(null);
      }
    } catch (e) {
      setInitialCompanies(null);
      if (e instanceof ApiError && e.status === 401) setUser(null);
      else if (!navigator.onLine || (e instanceof ApiError && (e.status === 503 || e.status === 0))) {
        // Keep the offline PWA shell mounted; Connection shows the banner.
        setUser((cur) => cur ?? "offline");
      } else {
        setUser(null);
        throw e;
      }
    } finally {
      setReady(true);
    }
  }

  async function logout() {
    await api.logout();
    setUser(null);
    setInitialCompanies(null);
  }

  useEffect(() => { refresh().catch(() => setReady(true)); }, []);

  const value = useMemo(
    () => ({ user, ready, initialCompanies, refresh, logout }),
    [user, ready, initialCompanies],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const loc = useLocation();
  if (!ready) return <div className="login-shell"><p className="muted">Carregando…</p></div>;
  if (!user) {
    const next = loc.pathname + loc.search;
    return <Navigate to={"/login" + (next && next !== "/" ? "?next=" + encodeURIComponent(next) : "")} replace />;
  }
  return children;
}

export function LoginPage() {
  const { user, ready, refresh } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const params = new URLSearchParams(loc.search);
  const next = params.get("next") || "/";

  useEffect(() => {
    if (ready && user) nav(next.startsWith("/") ? next : "/", { replace: true });
  }, [ready, user, nav, next]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr("");
    try {
      await api.login(name, password);
      await refresh();
      nav(next.startsWith("/") ? next : "/", { replace: true });
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : "Não foi possível entrar.");
    } finally {
      setBusy(false);
    }
  }

  if (!ready || user) return <div className="login-shell"><p className="muted">Carregando…</p></div>;

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={submit}>
        <div className="brand"><BrandMark /><div>Ponto<small>ACESSO</small></div></div>
        <p className="muted">Entre com seu usuário e senha para continuar.</p>
        <label>Usuário
          <input autoComplete="username" autoFocus value={name} onChange={e => setName(e.target.value)} required />
        </label>
        <label>Senha
          <input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required />
        </label>
        {err ? <p className="err" role="alert">{err}</p> : null}
        <button className="btn primary" type="submit" disabled={busy}>{busy ? "Entrando…" : "Entrar"}</button>
      </form>
    </div>
  );
}
