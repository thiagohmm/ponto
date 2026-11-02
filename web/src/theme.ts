// tema claro/escuro: choice in localStorage, "sistema" follows the OS.
export type Tema = "sistema" | "claro" | "escuro";

const KEY = "ponto.tema";
export type Pref = "claro" | "escuro";

export function savedTema(): Tema {
  const v = localStorage.getItem(KEY);
  return v === "claro" || v === "escuro" ? v : "sistema";
}

function systemDark(): boolean {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function pref(t: Tema): Pref {
  if (t !== "sistema") return t;
  return systemDark() ? "escuro" : "claro";
}

/** data-tema on <html> + the PWA theme-color meta. */
export function applyTema(t: Tema) {
  const p = pref(t);
  document.documentElement.dataset.tema = p;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", p === "escuro" ? "#1c1c1e" : "#e8e8ed");
}

/** Saves and applies; "sistema" removes the stored choice. */
export function setTema(t: Tema) {
  if (t === "sistema") localStorage.removeItem(KEY);
  else localStorage.setItem(KEY, t);
  applyTema(t);
}

/** One-click switch on the toolbar: flips between claro and escuro. */
export function toggleTema() {
  setTema(document.documentElement.dataset.tema === "escuro" ? "claro" : "escuro");
}

export function isEscuro(): boolean {
  return pref(savedTema()) === "escuro";
}

// while on "sistema", follow the OS when it flips (day/night, etc.)
if (window.matchMedia) {
  window
    .matchMedia("(prefers-color-scheme: dark)")
    .addEventListener("change", () => {
      if (savedTema() === "sistema") applyTema("sistema");
    });
}
