// Serwer wersji WWW Ham Radio Toolkit: pliki aplikacji + pośrednik do serwisów krótkofalarskich (CORS).
// Bez zależności – wystarczy Node.js 18+.  Uruchomienie: node web/server/server.mjs
//
// Zmienne środowiskowe:
//   PORT (8080), HOST (127.0.0.1 – za nginx), STATIC_DIR (../public), CACHE_MAX_MB (32),
//   ALLOWED_ORIGINS – lista adresów stron rozdzielona przecinkami, które mogą korzystać z /api (domyślnie: tylko ten sam adres)
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { join, normalize, extname, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { nearby } from "./activations.mjs";
import { repeaters } from "./repeaters.mjs";
import { lookup as callbookLookup } from "./callbook.mjs";
import { contests } from "./contests.mjs";
import { awardEvents, awardDetails } from "./awards.mjs";
import { countryAt } from "./country.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT ?? 8080);
const HOST = process.env.HOST ?? "127.0.0.1";
const STATIC_DIR = normalize(process.env.STATIC_DIR ?? join(HERE, "..", "public"));
const CACHE_MAX_BYTES = Number(process.env.CACHE_MAX_MB ?? 32) * 1024 * 1024;
const ALLOWED_ORIGINS = new Set((process.env.ALLOWED_ORIGINS ?? "").split(",").map((s) => s.trim()).filter(Boolean));
const USER_AGENT = "HamRadioToolkit-Web/0.1 (+https://github.com/SP3WRO/Ham_Radio_Toolkit)";

/**
 * Biała lista usług: nazwa -> adres bazowy i czas przechowywania odpowiedzi.
 * Serwer nie jest otwartym proxy – pobiera tylko z tych adresów (i tylko GET).
 */
const SERVICES = {
  // Parametry słoneczne i warunki KF – hamqsl.com aktualizuje dane co ok. godzinę
  hamqsl: { base: "https://www.hamqsl.com/", ttl: 15 * 60, paths: /^solarxml\.php$/ },
  // Metadane map troposferycznych (obrazy przeglądarka pobiera bezpośrednio z tropocast.eu)
  tropocast: { base: "https://tropocast.eu/api/v1/tropo/", ttl: 20 * 60, paths: /^metadata\?[\w=&.-]+$/ },
  // Baza krajów DXCC (AD1C) – aplikacja odświeża ją raz w tygodniu
  ctyfiles: { base: "https://www.country-files.com/cty/", ttl: 24 * 3600, paths: /^cty\.csv$/ },
  // DX Cluster: spoty z DX Summit (odczyt) i wysyłanie własnego spotu (POST)
  dxsummit: { base: "https://www.dxsummit.fi/api/v1/", ttl: 30, paths: /^spots(\?limit=\d{1,3})?$/, post: /^spots$/ },
  // Aktywatorzy na żywo
  pota: { base: "https://api.pota.app/", ttl: 45, paths: /^spot\/activator$/ },
  wwff: { base: "https://spots.wwff.co/", ttl: 45, paths: /^static\/spots\.json$/ },
  // Szczegóły przemienników DMR (color code, sieć, grupy)
  radioid: { base: "https://radioid.net/api/dmr/", ttl: 24 * 3600, paths: /^repeater\/\?callsign=[A-Z0-9]{3,10}$/ },
};

const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8", ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png", ".svg": "image/svg+xml", ".ico": "image/x-icon", ".webp": "image/webp",
  ".txt": "text/plain; charset=utf-8", ".csv": "text/csv; charset=utf-8", ".tsv": "text/tab-separated-values; charset=utf-8",
};

const SECURITY_HEADERS = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "Permissions-Policy": "geolocation=(self), microphone=(self), camera=()",
  "Content-Security-Policy": "default-src 'self'; img-src 'self' data: blob: https://tropocast.eu; style-src 'self' 'unsafe-inline'; " +
    "script-src 'self' 'unsafe-inline'; connect-src 'self'; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'",
};

// ---------------------------------------------------------------- pamięć podręczna odpowiedzi
/** @type {Map<string, {body: Buffer, type: string, status: number, expires: number}>} */
const cache = new Map();
let cacheBytes = 0;
/** @type {Map<string, Promise<any>>} */
const inFlight = new Map();

function cachePut(key, entry) {
  const old = cache.get(key);
  if (old) cacheBytes -= old.body.length;
  cache.set(key, entry);
  cacheBytes += entry.body.length;
  // Najstarsze wpisy wylatują pierwsze
  for (const [k, v] of cache) {
    if (cacheBytes <= CACHE_MAX_BYTES) break;
    cache.delete(k);
    cacheBytes -= v.body.length;
  }
}

/** Pobranie z usługi (jedno zapytanie naraz dla tego samego adresu, limit czasu i rozmiaru). */
async function fetchUpstream(url) {
  const res = await fetch(url, {
    headers: { "User-Agent": USER_AGENT, Accept: "*/*" },
    redirect: "follow",
    signal: AbortSignal.timeout(15000),
  });
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length > 5 * 1024 * 1024) throw new Error("Odpowiedź za duża");
  return { body: buf, type: res.headers.get("content-type") ?? "application/octet-stream", status: res.status };
}

/** Treść JSON zapytania POST (do 4 KB). */
async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > 4096) throw Object.assign(new Error("Za duże zapytanie"), { status: 413 });
    chunks.push(c);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch { throw Object.assign(new Error("Niepoprawny JSON"), { status: 400 }); }
}

/** Wysłanie danych do usługi (tylko adresy z [post] na białej liście, JSON do 4 KB, bez pamięci podręcznej). */
async function proxyPost(req, res, service, path) {
  const svc = SERVICES[service];
  if (!svc?.post || !svc.post.test(path)) return send(res, 404, { error: "Nieznana usługa" });
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > 4096) return send(res, 413, { error: "Za duże zapytanie" });
    chunks.push(c);
  }
  const body = Buffer.concat(chunks).toString("utf8");
  try { JSON.parse(body); } catch { return send(res, 400, { error: "Niepoprawny JSON" }); }
  try {
    const up = await fetch(svc.base + path, {
      method: "POST", body, headers: { "User-Agent": USER_AGENT, "Content-Type": "application/json" }, signal: AbortSignal.timeout(15000),
    });
    res.writeHead(up.status, { "Content-Type": up.headers.get("content-type") ?? "text/plain", ...SECURITY_HEADERS });
    res.end(Buffer.from(await up.arrayBuffer()));
    // Nowy spot ma być widoczny od razu
    for (const k of cache.keys()) if (k.startsWith(svc.base)) cache.delete(k);
  } catch (e) {
    send(res, 502, { error: "Usługa niedostępna", detail: String(e?.message ?? e) });
  }
}

async function proxy(req, res, service, path) {
  const svc = SERVICES[service];
  if (!svc || !svc.paths.test(path)) return send(res, 404, { error: "Nieznana usługa" });
  const url = svc.base + path;
  const now = Date.now();
  const hit = cache.get(url);
  if (hit && hit.expires > now) return sendCached(res, hit, "HIT");
  try {
    let pending = inFlight.get(url);
    if (!pending) {
      pending = fetchUpstream(url).finally(() => inFlight.delete(url));
      inFlight.set(url, pending);
    }
    const entry = await pending;
    if (entry.status >= 200 && entry.status < 300) {
      const stored = { ...entry, expires: now + svc.ttl * 1000 };
      cachePut(url, stored);
      return sendCached(res, stored, "MISS");
    }
    // Błąd usługi – lepiej oddać starszą kopię niż nic
    if (hit) return sendCached(res, hit, "STALE");
    return send(res, 502, { error: `Usługa odpowiedziała ${entry.status}` });
  } catch (e) {
    if (hit) return sendCached(res, hit, "STALE");
    return send(res, 502, { error: "Usługa niedostępna", detail: String(e?.message ?? e) });
  }
}

function sendCached(res, entry, state) {
  res.writeHead(200, { "Content-Type": entry.type, "Cache-Control": "no-cache", "X-Cache": state, ...SECURITY_HEADERS });
  res.end(entry.body);
}

// ---------------------------------------------------------------- prosty limit zapytań (na adres IP)
const hits = new Map();
function rateLimited(ip) {
  const minute = Math.floor(Date.now() / 60000);
  const key = `${ip}|${minute}`;
  const n = (hits.get(key) ?? 0) + 1;
  hits.set(key, n);
  if (hits.size > 10000) for (const k of hits.keys()) if (!k.endsWith(`|${minute}`)) hits.delete(k);
  return n > 120;
}

// ---------------------------------------------------------------- pliki aplikacji
async function serveStatic(req, res, pathname) {
  let rel = decodeURIComponent(pathname).replace(/^\/+/, "");
  if (rel === "" || rel.endsWith("/")) rel += "index.html";
  const file = normalize(join(STATIC_DIR, rel));
  if (!file.startsWith(STATIC_DIR)) return send(res, 403, "Forbidden");
  try {
    const info = await stat(file);
    if (!info.isFile()) throw new Error("nie plik");
    const etag = `"${info.size.toString(16)}-${Math.floor(info.mtimeMs).toString(16)}"`;
    // Service worker i lista plików zawsze świeże – od nich zależy aktualizacja aplikacji na iPhonie
    const noCache = rel === "sw.js" || rel === "sw-assets.js" || rel === "index.html" || rel.endsWith(".webmanifest");
    const headers = {
      "Content-Type": MIME[extname(file).toLowerCase()] ?? "application/octet-stream",
      "Cache-Control": noCache ? "no-cache" : "public, max-age=3600",
      ETag: etag,
      ...SECURITY_HEADERS,
    };
    if (req.headers["if-none-match"] === etag) {
      res.writeHead(304, headers);
      return res.end();
    }
    res.writeHead(200, headers);
    res.end(req.method === "HEAD" ? undefined : await readFile(file));
  } catch {
    send(res, 404, "Not found");
  }
}

function send(res, status, body) {
  const json = typeof body !== "string";
  res.writeHead(status, { "Content-Type": json ? "application/json; charset=utf-8" : "text/plain; charset=utf-8", ...SECURITY_HEADERS });
  res.end(json ? JSON.stringify(body) : body);
}

// ---------------------------------------------------------------- serwer
const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  const ip = String(req.headers["x-forwarded-for"] ?? req.socket.remoteAddress ?? "").split(",")[0].trim();

  if (url.pathname.startsWith("/api/")) {
    const origin = req.headers.origin;
    if (origin && ALLOWED_ORIGINS.has(origin)) {
      res.setHeader("Access-Control-Allow-Origin", origin);
      res.setHeader("Vary", "Origin");
    }
    if (req.method === "OPTIONS") {
      res.writeHead(204, { "Access-Control-Allow-Methods": "GET, POST", "Access-Control-Allow-Headers": "Content-Type" });
      return res.end();
    }
    if (url.pathname === "/api/health") return send(res, 200, { ok: true, services: Object.keys(SERVICES), cacheEntries: cache.size });
    if (rateLimited(ip)) return send(res, 429, { error: "Za dużo zapytań" });
    const m = /^\/api\/proxy\/([a-z0-9]+)\/(.+)$/.exec(url.pathname);
    if (m && req.method === "POST") return proxyPost(req, res, m[1], m[2]);
    if (url.pathname === "/api/callbook" && req.method === "POST") {
      try {
        return send(res, 200, await callbookLookup(await readJson(req), USER_AGENT));
      } catch (e) {
        return send(res, e?.status ?? 400, { error: String(e?.message ?? e) });
      }
    }
    if (req.method !== "GET") return send(res, 405, { error: "Tylko GET i POST" });
    if (m) return proxy(req, res, m[1], m[2] + url.search);
    if (url.pathname === "/api/repeaters") {
      const source = url.searchParams.get("source") ?? "przemienniki_net";
      const lat = url.searchParams.has("lat") ? Number(url.searchParams.get("lat")) : null;
      const lon = url.searchParams.has("lon") ? Number(url.searchParams.get("lon")) : null;
      if ((lat != null && !Number.isFinite(lat)) || (lon != null && !Number.isFinite(lon))) return send(res, 400, { error: "Złe współrzędne" });
      try {
        return send(res, 200, { repeaters: await repeaters(source, lat, lon, USER_AGENT) });
      } catch (e) {
        return send(res, 502, { error: String(e?.message ?? e) });
      }
    }
    if (url.pathname === "/api/contests") {
      try {
        return send(res, 200, await contests(USER_AGENT, url.searchParams.get("refresh") === "1"));
      } catch (e) {
        return send(res, 502, { error: String(e?.message ?? e) });
      }
    }
    if (url.pathname === "/api/country") {
      const lat = Number(url.searchParams.get("lat")), lon = Number(url.searchParams.get("lon"));
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return send(res, 400, { error: "Podaj lat i lon" });
      try {
        return send(res, 200, await countryAt(lat, lon, USER_AGENT));
      } catch (e) {
        return send(res, 502, { error: String(e?.message ?? e) });
      }
    }
    if (url.pathname === "/api/awards") {
      return send(res, 200, await awardEvents(USER_AGENT, url.searchParams.get("refresh") === "1"));
    }
    const award = /^\/api\/awards\/([^/]+)$/.exec(url.pathname);
    if (award) {
      try {
        return send(res, 200, await awardDetails(award[1], url.searchParams.get("call"), USER_AGENT, url.searchParams.get("refresh") === "1"));
      } catch (e) {
        return send(res, e?.status ?? 502, { error: String(e?.message ?? e) });
      }
    }
    if (url.pathname === "/api/activations") {
      const lat = Number(url.searchParams.get("lat")), lon = Number(url.searchParams.get("lon"));
      const radius = Math.min(Number(url.searchParams.get("radius") ?? 500), 500);
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return send(res, 400, { error: "Podaj lat i lon" });
      try {
        return send(res, 200, await nearby(lat, lon, radius, USER_AGENT));
      } catch (e) {
        return send(res, 502, { error: String(e?.message ?? e) });
      }
    }
    return send(res, 404, { error: "Nie ma takiego adresu" });
  }
  if (req.method !== "GET" && req.method !== "HEAD") return send(res, 405, "Method not allowed");
  return serveStatic(req, res, url.pathname);
});

server.listen(PORT, HOST, () => {
  console.log(`Ham Radio Toolkit (WWW) – http://${HOST}:${PORT}  [pliki: ${STATIC_DIR}]`);
});

for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => server.close(() => process.exit(0)));
