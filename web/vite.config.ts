import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": "http://127.0.0.1:8090" },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    // navegador moderno: sem transpilação extra, sem polyfill
    target: "es2022",
    cssTarget: "safari16",
    cssCodeSplit: true,
    assetsInlineLimit: 2048,
    // as páginas entram por import() dinâmico (ver App.tsx): o Rollup separa
    // um arquivo por aba e o primeiro carregamento só traz a casca
    rollupOptions: { output: {} },
  },
});
