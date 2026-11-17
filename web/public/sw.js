// The build stamps the version and complete asset list. No personal API data is cached.
const CACHE = "ponto-__BUILD__";
const CORE = ["/", "/theme.js", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-512.png", "/apple-touch-icon.png", ...__ASSETS__];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(CORE)).then(() => {
    if (!self.registration.active) return self.skipWaiting();
  }));
});
self.addEventListener("message", event => {
  if (event.data?.type === "ACTIVATE") self.skipWaiting();
});
self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key.startsWith("ponto-") && key !== CACHE) await caches.delete(key);
    await self.clients.claim();
  })());
});
// API traffic is never intercepted: a failed fetch here used to become JSON 503 and
// broke attachment list/download over flaky tunnels. The Connection UI already covers offline.
self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/api/")) return;
  if (url.pathname.startsWith("/assets/")) {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE);
      const hit = await cache.match(event.request);
      if (hit) return hit;
      const response = await fetch(event.request);
      if (response.ok) await cache.put(event.request, response.clone());
      return response;
    })());
  } else if (event.request.mode === "navigate") {
    event.respondWith((async () => {
      try { return await fetch(event.request); }
      catch { return (await (await caches.open(CACHE)).match("/")) || Response.error(); }
    })());
  }
});
