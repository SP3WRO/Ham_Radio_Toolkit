// Service worker: aplikacja działa bez internetu; dane z sieci – najpierw sieć, bez niej ostatnia kopia.
/* global self, caches */
importScripts("sw-assets.js"); // self.HRT_VERSION, self.HRT_ASSETS – generowane przez tools/build-data.mjs

const SHELL = `hrt-shell-${self.HRT_VERSION}`;
const DATA = "hrt-data-v1";

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(self.HRT_ASSETS)));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key.startsWith("hrt-shell-") && key !== SHELL) await caches.delete(key);
    await self.clients.claim();
  })());
});

// Nowa wersja czeka na zgodę strony ("Odśwież"), żeby nie podmieniać plików w trakcie pracy
self.addEventListener("message", (event) => {
  if (event.data === "skipWaiting") self.skipWaiting();
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);

  if (url.origin === self.location.origin && url.pathname.includes("/api/")) {
    if (url.pathname.endsWith("/health")) return;
    event.respondWith(networkFirst(req));
    return;
  }
  // Mapy Tropocast – ostatnio oglądane klatki zostają w pamięci podręcznej
  if (url.hostname === "tropocast.eu") {
    event.respondWith(networkFirst(req));
    return;
  }
  if (url.origin !== self.location.origin) return;

  event.respondWith((async () => {
    const shell = await caches.open(SHELL);
    // Nawigacja (dowolny #/ekran) – zawsze index.html z pamięci
    if (req.mode === "navigate") return (await shell.match("index.html")) ?? fetch(req);
    const hit = await shell.match(req, { ignoreSearch: true });
    return hit ?? fetch(req);
  })());
});

async function networkFirst(req) {
  const cache = await caches.open(DATA);
  try {
    const res = await fetch(req);
    if (res.ok || res.type === "opaque") cache.put(req, res.clone());
    return res;
  } catch (e) {
    const hit = await cache.match(req);
    if (hit) return hit;
    throw e;
  }
}
