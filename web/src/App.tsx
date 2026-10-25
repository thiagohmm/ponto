import { NavLink, Route, Routes } from "react-router-dom";
import { ConfigPage } from "./pages/Config";
import { ExportPage } from "./pages/Export";
import { FormPage } from "./pages/Form";
import { HomePage } from "./pages/Home";
import { SumPage } from "./pages/Sum";

export function App() {
  return (
    <div className="shell">
      <header className="top">
        <div className="brand">
          Ponto
          <small>cartão de horas · terminal e web no mesmo SQLite</small>
        </div>
        <nav className="nav">
          <NavLink to="/" end>Lista</NavLink>
          <NavLink to="/novo">Novo</NavLink>
          <NavLink to="/soma">Soma</NavLink>
          <NavLink to="/exportar">Exportar</NavLink>
          <NavLink to="/config">Config</NavLink>
        </nav>
      </header>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/novo" element={<FormPage />} />
        <Route path="/edit/:id" element={<FormPage />} />
        <Route path="/soma" element={<SumPage />} />
        <Route path="/exportar" element={<ExportPage />} />
        <Route path="/config" element={<ConfigPage />} />
      </Routes>
    </div>
  );
}
