import { lazy, Suspense, useEffect, useState } from "react";
import { NavLink, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { AuthProvider, LoginPage, RequireAuth, useAuth } from "./Auth";
import { CompanyBar, CompanyProvider, useCompany } from "./Company";
import { UnsavedProvider } from "./ui/Unsaved";
import { Connection } from "./ui/Connection";
import { BrandMark } from "./ui/BrandMark";
import { ConfirmProvider } from "./ui/Confirm";

// Uma aba = um arquivo. O carregamento inicial leva só a casca e a tela que o
// usuário abriu; notas, kanban, exportação e configurações vêm quando pedidas
// (e pré-carregam no hover/focus da barra lateral).
const LOADERS = {
  "/": () => import("./pages/Home"),
  "/novo": () => import("./pages/Form"),
  "/soma": () => import("./pages/Sum"),
  "/diario": () => import("./pages/Diario"),
  "/notas": () => import("./pages/Notes"),
  "/kanban": () => import("./pages/Kanban"),
  "/exportar": () => import("./pages/Export"),
  "/config": () => import("./pages/Config"),
  "/cofre": () => import("./pages/Cofre"),
  "/http": () => import("./pages/HttpClient"),
} as const;

function prefetchRoute(path: string) {
  const key = path === "/" ? "/"
    : path.startsWith("/edit") ? "/novo"
    : path.startsWith("/notas") ? "/notas"
    : (path as keyof typeof LOADERS);
  const loader = LOADERS[key as keyof typeof LOADERS];
  if (loader) void loader();
}

const HomePage = lazy(() => LOADERS["/"]().then((m) => ({ default: m.HomePage })));
const FormPage = lazy(() => LOADERS["/novo"]().then((m) => ({ default: m.FormPage })));
const SumPage = lazy(() => LOADERS["/soma"]().then((m) => ({ default: m.SumPage })));
const DiarioPage = lazy(() => LOADERS["/diario"]().then((m) => ({ default: m.DiarioPage })));
const NotesPage = lazy(() => LOADERS["/notas"]().then((m) => ({ default: m.NotesPage })));
const KanbanPage = lazy(() => LOADERS["/kanban"]().then((m) => ({ default: m.KanbanPage })));
const ExportPage = lazy(() => LOADERS["/exportar"]().then((m) => ({ default: m.ExportPage })));
const ConfigPage = lazy(() => LOADERS["/config"]().then((m) => ({ default: m.ConfigPage })));
const CofrePage = lazy(() => LOADERS["/cofre"]().then((m) => ({ default: m.CofrePage })));
const HttpClientPage = lazy(() => LOADERS["/http"]().then((m) => ({ default: m.HttpClientPage })));

function Ico({ path }: { path: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

const TABS = [
  { to: "/", end: true, label: "Registros", path: "M8 4h8l1 3H7zM5 8h14v12H5zM9 12h6" },
  { to: "/novo", label: "Novo registro", path: "M12 5v14M5 12h14" },
  { to: "/soma", label: "Resumo de horas", path: "M5 7h14M12 7v4M7 17h10M12 13v8" },
  { to: "/diario", label: "Diário", path: "M5 4h14v16H5zM8 8h8M8 12h8M8 16h5" },
  { to: "/notas", label: "Notas", path: "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9l-5-5zM14 3v5h5" },
  { to: "/kanban", label: "Kanban", path: "M4 5h4v14H4zM10 5h4v8h-4zM16 5h4v11h-4z" },
  { to: "/exportar", label: "Exportar", path: "M12 4v12M8 12l4 4 4-4M5 20h14" },
  { to: "/config", label: "Configurações", path: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 3v2M12 19v2M4.2 6.2l1.4 1.4M18.4 16.4l1.4 1.4M3 12h2M19 12h2M4.2 17.8l1.4-1.4M18.4 7.6l1.4-1.4" },
  { to: "/cofre", label: "Cofre", path: "M6 10h12v11H6zM9 10V7a3 3 0 0 1 6 0v3" },
  { to: "/http", label: "HTTP", path: "M4 8h16M4 16h16M8 4v16M16 4v16" },
] as const;

export function App() {
  const loc = useLocation();
  // Start downloading the active route chunk while /api/me is still in flight.
  useEffect(() => {
    if (loc.pathname !== "/login") prefetchRoute(loc.pathname);
  }, [loc.pathname]);
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<RequireAuth><Shell /></RequireAuth>} />
      </Routes>
    </AuthProvider>
  );
}

function Shell() {
  const loc = useLocation();
  const vault = loc.pathname.startsWith("/notas");
  const kanban = loc.pathname.startsWith("/kanban");
  const editing = loc.pathname.startsWith("/edit");
  const [menu, setMenu] = useState(false);
  const diario = loc.pathname.startsWith("/diario");
  const title = editing ? "Editar registro" : TABS.find(t => t.to === loc.pathname)?.label ?? "Notas";
  const blurb = vault
    ? "Suas ideias, tarefas e arquivos em um só lugar."
    : kanban
      ? "Arraste cartões entre colunas da empresa ativa."
      : diario
        ? "Tudo o que aconteceu no dia — ponto, notas, kanban e um resumo com IA."
        : loc.pathname === "/cofre"
          ? "Passwords guardados encrypted. One master password, no recovery."
          : loc.pathname === "/http"
            ? "Cliente HTTP local: a requisição sai da sua máquina, não do servidor."
            : "Organize sua rotina e acompanhe cada hora.";
  return (
    <ConfirmProvider><UnsavedProvider><Connection /><CompanyProvider>
      <div className={vault ? "workspace notes-workspace" : kanban ? "workspace kanban-workspace" : "workspace"}>
        <a className="skip-link" href="#conteudo">Ir para o conteúdo</a>
        <header className="mobile-header">
          <b className="mobile-brand"><BrandMark className="brand-mark brand-mark-sm" /><span>Ponto</span></b>
          <button className="btn" aria-expanded={menu} aria-controls="main-navigation" onClick={() => setMenu(!menu)}>Menu {menu ? "−" : "+"}</button>
        </header>
        <aside className={"sidebar" + (menu ? " expanded" : "")}>
          <div className="brand"><BrandMark /><div>Ponto<small>GESTÃO DE JORNADA</small></div></div>
          <p className="nav-caption">ESPAÇO DE TRABALHO</p>
          <nav id="main-navigation" className="side-nav" aria-label="Navegação principal">
            {TABS.map(t => <NavLink key={t.to} to={t.to} end={"end" in t ? t.end : false}
              onMouseEnter={() => prefetchRoute(t.to)} onFocus={() => prefetchRoute(t.to)}
              onClick={() => setMenu(false)} className={({ isActive }) => isActive || (t.to === "/novo" && editing) ? "active" : ""}>
              <Ico path={t.path} />{t.label}
            </NavLink>)}
          </nav>
          <Logout />
          <div className="sidebar-foot">Sua jornada, organizada.<small>Registros · Horas · Notas · Kanban</small></div>
        </aside>
        <div className="workspace-body">
          <header className="workspace-header"><span>Espaço de trabalho <span className="muted">/ {title}</span></span><CompanyBar /></header>
          <main id="conteudo" className="page-content" tabIndex={-1}>
            <div className="page-heading"><div><p className="eyebrow">GESTÃO DE JORNADA</p><h1>{title}</h1>
              <p className="muted">{blurb}</p></div></div>
            <Pages />
          </main>
        </div>
      </div>
    </CompanyProvider></UnsavedProvider></ConfirmProvider>
  );
}

function Logout() {
  const { user, logout } = useAuth();
  const nav = useNavigate();
  if (!user || user === "open" || user === "offline") return null;
  return (
    <div className="sidebar-auth">
      <span className="muted">{user}</span>
      <button type="button" className="btn" onClick={async () => { await logout(); nav("/login", { replace: true }); }}>Sair</button>
    </div>
  );
}

function Pages() {
  const { active } = useCompany();
  return (
    <Suspense fallback={<p className="muted" role="status">Carregando…</p>}>
      <Routes key={active?.id ?? 0}>
        <Route path="/" element={<HomePage />} />
        <Route path="/novo" element={<FormPage />} />
        <Route path="/edit/:id" element={<FormPage />} />
        <Route path="/soma" element={<SumPage />} />
        <Route path="/diario" element={<DiarioPage />} />
        <Route path="/notas" element={<NotesPage />} />
        <Route path="/notas/:id" element={<NotesPage />} />
        <Route path="/kanban" element={<KanbanPage />} />
        <Route path="/exportar" element={<ExportPage />} />
        <Route path="/config" element={<ConfigPage />} />
        <Route path="/cofre" element={<CofrePage />} />
        <Route path="/http" element={<HttpClientPage />} />
      </Routes>
    </Suspense>
  );
}
