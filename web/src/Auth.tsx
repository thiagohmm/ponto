import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { api } from "./api";
import { BrandMark } from "./ui/BrandMark";
import { ApiError } from "./types";

type AuthState = { user: string | null; ready: boolean; refresh: () => Promise<void>; logout: () => Promise<void> };

const Ctx = createContext<AuthState>({ user: null, ready: false, refresh: async () => {}, logout: async () => {} });

export function useAuth() { return useContext(Ctx); }

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  async function refresh() {
    try {
      const me = await api.me();
      setUser(me.user);
    } catch (e) {
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
  }

  useEffect(() => { refresh().catch(() => setReady(true)); }, []);

  return <Ctx.Provider value={{ user, ready, refresh, logout }}>{children}</Ctx.Provider>;
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
