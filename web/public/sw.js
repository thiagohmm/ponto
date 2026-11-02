// Ponto PWA: hand-written, no build plugin.
// cache-first for /assets/* (hashed names), network-first for navigation and
// /api/* — offline the page shows its own network error, as it does today.
const CACHE = "ponto-v2";
const CORE = ["/", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-512.png", "/apple-touch-icon.png"];

self.addEventListener("install", (e) => {
  e.waitUntil((async () => {
    const c = await caches.open(CACHE);
    for (const r of CORE) { try { await c.add(r); } catch (ignored) { } }
    self.skipWaiting();
  })());
});

self.addEventListener("activate", (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== CACHE) await caches.delete(k);
    self.clients.claim();
  })());
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== self.location.origin) return;

  if (url.pathname.startsWith("/api/")) {
    e.respondWith(fetch(e.request).catch(() =>
      new Response('{"error":"sem rede"}', { status: 504, headers: { "Content-Type": "application/json" } })));
    return;
  }

  if (url.pathname.startsWith("/assets/")) {
    e.respondWith((async () => {
      const hit = await caches.match(e.request);
      if (hit) return hit;
      const res = await fetch(e.request);
      const c = await caches.open(CACHE);
      c.put(e.request, res.clone());
      return res;
    })());
    return;
  }

  if (e.request.mode === "navigation") {
    e.respondWith((async () => {
      try {
        const res = await fetch(e.request);
        const c = await caches.open(CACHE);
        c.put("/", res.clone());
        return res;
      } catch (offline) {
        return (await caches.match(e.request)) || (await caches.match("/")) || Response.error();
      }
    })());
  }
});
