import { useEffect } from "react";
import { NavLink, Route, Routes, useLocation } from "react-router-dom";
import { ConfigPage } from "./pages/Config";
import { ExportPage } from "./pages/Export";
import { FormPage } from "./pages/Form";
import { HomePage } from "./pages/Home";
import { NotesPage } from "./pages/Notes";
import { SumPage } from "./pages/Sum";

function Ico({ path }: { path: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

const TABS = [
  { to: "/", end: true, label: "Lista", path: "M8 4h8l1 3H7zM5 8h14v12H5zM9 12h6" },
  { to: "/novo", label: "Novo", path: "M12 5v14M5 12h14" },
  { to: "/soma", label: "Soma", path: "M5 7h14M12 7v4M7 17h10M12 13v8" },
  { to: "/notas", label: "Notas", path: "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5" },
  { to: "/exportar", label: "Exportar", path: "M12 4v12M8 12l4 4 4-4M5 20h14" },
  { to: "/config", label: "Config", path: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 3v2M12 19v2M4.2 6.2l1.4 1.4M18.4 16.4l1.4 1.4M3 12h2M19 12h2M4.2 17.8l1.4-1.4M18.4 7.6l1.4-1.4" },
] as const;

export function App() {
  const loc = useLocation();
  const vault = loc.pathname.startsWith("/notas");
  const editing = loc.pathname.startsWith("/edit");
  useEffect(() => {
    document.documentElement.classList.add("mac");
    document.body.classList.add("mac");
  }, []);
  return (
    <div className={vault ? "shell wide" : "shell"}>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/novo" element={<FormPage />} />
        <Route path="/edit/:id" element={<FormPage />} />
        <Route path="/soma" element={<SumPage />} />
        <Route path="/notas" element={<NotesPage />} />
        <Route path="/notas/:id" element={<NotesPage />} />
        <Route path="/exportar" element={<ExportPage />} />
        <Route path="/config" element={<ConfigPage />} />
      </Routes>
      <nav className="tabs" aria-label="seções">
        {TABS.map((t) => (
          <NavLink
            key={t.to}
            to={t.to}
            end={"end" in t ? t.end : false}
            className={({ isActive }) =>
              isActive || (t.to === "/novo" && editing) ? "active" : ""
            }
          >
            <Ico path={t.path} />
            {t.label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
