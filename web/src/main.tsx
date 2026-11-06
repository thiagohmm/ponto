import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { App } from "./App";
import { applyTema, savedTema } from "./theme";
import "./styles.css";

// the inline script in index.html already set the theme pre-paint; this also
// refreshes the theme-color meta to match
applyTema(savedTema());

if (location.hash.startsWith("#/")) {
  location.replace(location.hash.slice(1) + location.search);
}

// the hand-written PWA service worker (web/public/sw.js) — production only,
// so `vite dev` never fights the cache
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").then(registration => {
      const notify = () => {
        if (registration.waiting && navigator.serviceWorker.controller)
          window.dispatchEvent(new CustomEvent("ponto-update", { detail: registration.waiting }));
      };
      notify();
      registration.addEventListener("updatefound", () => registration.installing?.addEventListener("statechange", notify));
    }).catch(() => { });
  });
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RouterProvider router={createBrowserRouter([{ path: "*", element: <App /> }])} />
  </StrictMode>,
);
