import { useEffect } from "react";
import { NavLink, Route, Routes, useLocation } from "react-router-dom";
import { ConfigPage } from "./pages/Config";
import { ExportPage } from "./pages/Export";
import { FormPage } from "./pages/Form";
import { HomePage } from "./pages/Home";
import { NotesPage } from "./pages/Notes";
import { SumPage } from "./pages/Sum";

export function App() {
  const loc = useLocation();
  const vault = loc.pathname.startsWith("/notas");
  useEffect(() => {
    document.documentElement.classList.add("mac");
    document.body.classList.add("mac");
  }, []);
  return (
    <div className={vault ? "shell wide" : "shell"}>
      <header className="top">
        <div className="brand">
          Ponto
          <small>cartão de horas</small>
        </div>
        <nav className="nav">
          <NavLink to="/" end>Lista</NavLink>
          <NavLink to="/novo">Novo</NavLink>
          <NavLink to="/soma">Soma</NavLink>
          <NavLink to="/notas">Notas</NavLink>
          <NavLink to="/exportar">Exportar</NavLink>
          <NavLink to="/config">Config</NavLink>
        </nav>
      </header>
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
    </div>
  );
}
