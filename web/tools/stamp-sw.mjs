import { createHash } from "node:crypto";
import { readdir, readFile, writeFile } from "node:fs/promises";
const root = new URL("../dist/", import.meta.url);
const assets = (await readdir(new URL("assets/", root))).filter(name => /\.(js|css)$/.test(name)).sort();
const hash = createHash("sha256").update(await readFile(new URL("index.html", root)));
for (const name of assets) hash.update(await readFile(new URL("assets/" + name, root)));
const template = await readFile(new URL("../public/sw.js", import.meta.url), "utf8");
hash.update(template);
await writeFile(new URL("sw.js", root), template.replace("__BUILD__", hash.digest("hex").slice(0,16))
  .replace("__ASSETS__", JSON.stringify(assets.map(name => "/assets/" + name))));
