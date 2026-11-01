// @ts-check
// Pozycja GPS, dostęp do danych z sieci przez serwer pośredniczący i baza DXCC
import { CtyDatabase } from "./core/logbook.js";
import { saveLastKnownPosition } from "./store.js";

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
