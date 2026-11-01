// @ts-check
// Pozycja GPS, dostęp do danych z sieci przez serwer pośredniczący i baza DXCC
import { CtyDatabase } from "./core/logbook.js";
import { saveLastKnownPosition, credentials } from "./store.js";
import { t } from "./i18n.js";
import { cbBaseCall, isCompleteCall, HAMDB_DXCC, combineCallbook } from "./core/callbook.js";
import { Maidenhead } from "./core/geo.js";
import { inPoland } from "./core/world.js";
import { logbook } from "./db.js";

// ---------------------------------------------------------------- GPS (działa, gdy strona jest na ekranie)
/** @typedef {{lat: number, lon: number, altitude: number | null, accuracy: number}} Fix */
/** @typedef {"idle" | "waiting" | "ok" | "denied" | "unavailable" | "insecure"} GpsStatus */

/** @type {{fix: Fix | null, status: GpsStatus}} */
const gps = { fix: null, status: "idle" };
/** @type {Set<() => void>} */
const gpsListeners = new Set();
/** @type {number | null} */
let watchId = null;

export const location = {
  get: () => gps,
  /** @param {() => void} l */
  subscribe(l) { gpsListeners.add(l); return () => gpsListeners.delete(l); },

  /** Start nasłuchu pozycji (na iOS pierwsze wywołanie pokazuje systemowe pytanie o zgodę). */
  start() {
    if (watchId != null) return;
    if (!window.isSecureContext) return set({ status: "insecure" });
    if (!("geolocation" in navigator)) return set({ status: "unavailable" });
    set({ status: gps.fix ? "ok" : "waiting" });
    watchId = navigator.geolocation.watchPosition(
      (p) => {
        const fix = { lat: p.coords.latitude, lon: p.coords.longitude, altitude: p.coords.altitude, accuracy: p.coords.accuracy };
        saveLastKnownPosition(fix);
        set({ fix, status: "ok" });
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          this.stop();
          set({ status: "denied" });
        } else if (!gps.fix) set({ status: "unavailable" });
      },
      { enableHighAccuracy: true, maximumAge: 15000, timeout: 30000 }
    );
  },

  stop() {
    if (watchId != null) navigator.geolocation.clearWatch(watchId);
    watchId = null;
  },

  /** Czy przeglądarka już dała zgodę (bez pytania użytkownika). */
  async alreadyGranted() {
    try {
      const p = await navigator.permissions?.query({ name: "geolocation" });
      return p?.state === "granted";
    } catch {
      return false;
    }
  },
};

/** @param {Partial<typeof gps>} patch */
function set(patch) {
  Object.assign(gps, patch);
  gpsListeners.forEach((l) => l());
}

// GPS tylko, gdy aplikacja jest widoczna – oszczędza baterię (jak ForegroundLocation w Androidzie)
if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) location.stop();
    else if (gps.status === "ok" || gps.status === "waiting") location.start();
  });
}

// ---------------------------------------------------------------- serwer pośredniczący
// Adres serwera: domyślnie ten sam, z którego wczytano aplikację (/api/...).
const API_BASE = (/** @type {any} */ (window).HRT_API_BASE ?? "") + "/api";

/**
 * Dane z zewnętrznej usługi przez serwer (CORS). [service] musi być na białej liście serwera.
 * @param {string} service np. "hamqsl" @param {string} path np. "solarxml.php" @param {RequestInit} [init]
 */
export async function proxied(service, path, init) {
  const res = await fetch(`${API_BASE}/proxy/${service}/${path}`, init);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res;
}

/** Adres endpointu serwera, np. apiUrl("/activations?lat=..") @param {string} path */
export const apiUrl = (path) => `${API_BASE}${path}`;

export async function serverStatus() {
  try {
    const res = await fetch(`${API_BASE}/health`, { cache: "no-store" });
    return res.ok;
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------- baza DXCC
// Kopia dołączona do aplikacji, odświeżana z country-files.com co tydzień (przez serwer), jak CtyRepository
const CTY_KEY = "hrt.cty.v1";
const CTY_MAX_AGE = 7 * 24 * 3600 * 1000;
/** @type {Promise<CtyDatabase> | null} */
let ctyPromise = null;
let ctySource = "bundled";

export function cty() {
  ctyPromise ??= (async () => {
    let text = null;
    let stored = null;
    try { stored = JSON.parse(localStorage.getItem(CTY_KEY) ?? "null"); } catch { /* brak */ }
    if (stored?.text) { text = stored.text; ctySource = stored.date; }
    text ??= await fetch("data/cty.csv").then((r) => r.text());
    const db = CtyDatabase.parse(text);
    if (!stored || Date.now() - stored.time > CTY_MAX_AGE) refreshCty();
    return db;
  })();
  return ctyPromise;
}

export const ctyInfo = () => ctySource;

async function refreshCty() {
  try {
    const text = await (await proxied("ctyfiles", "cty.csv")).text();
    if (CtyDatabase.parse(text).entities.length < 300) return;
    const date = new Date().toISOString().slice(0, 10);
    localStorage.setItem(CTY_KEY, JSON.stringify({ text, time: Date.now(), date }));
    ctySource = date;
    ctyPromise = Promise.resolve(CtyDatabase.parse(text));
  } catch { /* bez sieci – zostaje ostatnia kopia */ }
}

// ---------------------------------------------------------------- wyszukiwarka znaków (Callbook.lookup)
const CALLBOOK_CACHE_MS = 10 * 60 * 1000;
/** @type {Map<string, [number, any]>} */
const callbookCache = new Map();

/**
 * Dane o stacji z kilku źródeł naraz. Nigdy nie rzuca wyjątku. [usePskReporter] – tylko przy jawnym wyszukiwaniu
 * (nie przy pisaniu znaku w oknie QSO), bo PSK Reporter pozwala na jedno zapytanie na 5 minut.
 * @param {string} callsign @param {boolean} [usePskReporter]
 */
export async function callbookLookup(callsign, usePskReporter = false) {
  const call = callsign.trim().toUpperCase();
  const hit = callbookCache.get(call);
  // Wynik bez lokatora odświeżamy, gdy teraz możemy dodatkowo zapytać PSK Reporter
  if (hit && Date.now() - hit[0] < CALLBOOK_CACHE_MS && !(usePskReporter && !hit[1].grid)) return hit[1];
  const db = await cty().catch(() => null);
  const entity = db?.resolve(call) ?? null;
  const base = cbBaseCall(call);
  const homeEntity = db?.resolve(base) ?? null;
  const online = isCompleteCall(call);
  const c = credentials.get();
  // Tylko dokładnie ten sam znak – lokator z pracy /P nie opisuje stacji domowej i odwrotnie
  const lastLogged = logbook.previousContacts(call).filter((q) => q.callsign.toUpperCase() === call && q.gridSquare.length >= 4 && Maidenhead.isValid(q.gridSquare))
    .sort((a, b) => (a.datetimeUtc < b.datetimeUtc ? 1 : -1))[0] ?? null;
  let data = { notes: [] };
  if (online || homeEntity?.dxcc === 269) {
    try {
      const res = await fetch(`${API_BASE}/callbook`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ call, base, online, uke: homeEntity?.dxcc === 269, hamDb: homeEntity != null && HAMDB_DXCC.has(homeEntity.dxcc),
          psk: usePskReporter, hamQthUser: c.hamQthUser, hamQthPassword: c.hamQthPassword, qrzcqUser: c.qrzcqUser, qrzcqPassword: c.qrzcqPassword }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`);
      data = body;
    } catch (e) {
      data = { notes: [t("cb_failed", /** @type {Error} */ (e).message)] };
    }
  }
  const result = combineCallbook({ call, entity, homeEntity, data, lastLogged, hamQthConfigured: credentials.hasHamQth() }, t);
  callbookCache.set(call, [Date.now(), result]);
  return result;
}

// ---------------------------------------------------------------- kraj z pozycji (CountryResolver)
const COUNTRY_KEY = "hrt.country.v1";
/** @typedef {{iso: string, adminArea: string, position: {lat: number, lon: number}}} ResolvedCountry */
/** @type {Promise<ResolvedCountry | null> | null} */
let countryPending = null;

/** Ostatnio ustalony kraj (bez czekania). @returns {ResolvedCountry | null} */
export function cachedCountry() {
  try { return JSON.parse(localStorage.getItem(COUNTRY_KEY) ?? "null"); } catch { return null; }
}

/**
 * Kraj dla pozycji: serwer (OpenStreetMap), a bez sieci – przybliżony obrys Polski. W promieniu ~5 km od
 * poprzedniego wyniku nie pytamy ponownie. @param {{lat: number, lon: number}} pos @returns {Promise<ResolvedCountry | null>}
 */
export function resolveCountry(pos) {
  const prev = cachedCountry();
  if (prev && Math.abs(prev.position.lat - pos.lat) < 0.05 && Math.abs(prev.position.lon - pos.lon) < 0.05) return Promise.resolve(prev);
  countryPending ??= (async () => {
    /** @type {ResolvedCountry | null} */
    let info = null;
    try {
      const res = await fetch(apiUrl(`/country?lat=${pos.lat.toFixed(4)}&lon=${pos.lon.toFixed(4)}`), { signal: AbortSignal.timeout(12000) });
      const data = await res.json();
      if (res.ok && data.iso) info = { iso: data.iso, adminArea: data.adminArea ?? "", position: pos };
    } catch { /* bez sieci */ }
    if (!info && inPoland(pos)) info = { iso: "PL", adminArea: "", position: pos };
    if (info) try { localStorage.setItem(COUNTRY_KEY, JSON.stringify(info)); } catch { /* pełna pamięć */ }
    return info;
  })().finally(() => { countryPending = null; });
  return countryPending;
}
