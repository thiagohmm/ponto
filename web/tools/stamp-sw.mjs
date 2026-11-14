import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
const root = new URL("../dist/", import.meta.url);
const html = await readFile(new URL("index.html", root), "utf8");
// Só o que a primeira pintura pede entra no precache: a casca e a tela inicial.
// As demais abas (import() dinâmico) são cacheadas na primeira visita, pelo
// próprio handler de fetch do sw.js.
const eager = [...html.matchAll(/\/assets\/[\w.-]+\.(?:js|css)/g)].map(m => m[0]);
const assets = [...new Set(eager)].sort();
const hash = createHash("sha256").update(html);
for (const name of assets) hash.update(await readFile(new URL("." + name, root)));
const template = await readFile(new URL("../public/sw.js", import.meta.url), "utf8");
hash.update(template);
await writeFile(new URL("sw.js", root), template.replace("__BUILD__", hash.digest("hex").slice(0,16))
  .replace("__ASSETS__", JSON.stringify(assets)));
