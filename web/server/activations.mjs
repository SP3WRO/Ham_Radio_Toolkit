// Bazy obiektów POTA, SOTA i WWFF (ActivationsViewModel.syncDatabases w aplikacji).
// Pliki z całego świata mają dziesiątki MB – serwer pobiera je raz na dobę, trzyma w pamięci w zwartej postaci
// i odpowiada telefonom tylko obiektami z okolicy (/api/activations?lat=..&lon=..&radius=500).
import { parseCsvLine } from "../public/js/core/spots.js";

const SOURCES = [
  // POTA – kolumny: reference,name,active,entityId,locationDesc,latitude,longitude,grid
  { program: "POTA", url: "https://pota.app/all_parks_ext.csv", header: "reference", ref: "reference", name: "name", lat: "latitude", lon: "longitude",
    keep: (row) => row("active") !== "0" },
  // SOTA – pierwsza linia to tytuł, nagłówek jest w drugiej
  { program: "SOTA", url: "https://www.sotadata.org.uk/summitslist.csv", header: "SummitCode", ref: "SummitCode", name: "SummitName", lat: "Latitude", lon: "Longitude",
    keep: () => true },
  // WWFF – wszystkie programy krajowe (SPFF, DLFF, ONFF…)
  { program: "WWFF", url: "https://wwff.co/wwff-data/wwff_directory.csv", header: "reference", ref: "reference", name: "name", lat: "latitude", lon: "longitude",
    keep: (row) => row("status") === "active" },
];
const MAX_AGE_MS = 24 * 3600 * 1000;
const RETRY_MS = 30 * 60 * 1000;

/** @type {Map<string, {refs: string[], names: string[], lat: Float32Array, lon: Float32Array, loaded: number}>} */
const db = new Map();
/** @type {Map<string, number>} */
const lastAttempt = new Map();
/** @type {Map<string, Promise<void>>} */
const loading = new Map();

async function load(src, userAgent) {
  const res = await fetch(src.url, { headers: { "User-Agent": userAgent }, signal: AbortSignal.timeout(180000) });
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
  const refs = [], names = [], lats = [], lons = [];
  /** @type {Map<string, number> | null} */
  let columns = null;
  let rest = "";
  const decoder = new TextDecoder();
  const handle = (raw) => {
    const line = raw.replace(/\r$/, "");
    if (!line.trim()) return;
    const fields = parseCsvLine(line);
    if (!columns) {
      if (fields.includes(src.header)) columns = new Map(fields.map((f, i) => [f, i]));
      return;
    }
    const cols = columns;
    const row = (name) => fields[cols.get(name) ?? -1] ?? "";
    if (!src.keep(row)) return;
    const ref = row(src.ref), lat = Number(row(src.lat)), lon = Number(row(src.lon));
    if (!ref || !row(src.lat) || !row(src.lon) || !Number.isFinite(lat) || !Number.isFinite(lon)) return;
    refs.push(ref); names.push(row(src.name)); lats.push(lat); lons.push(lon);
  };
  for await (const chunk of res.body) {
    rest += decoder.decode(chunk, { stream: true });
    const lines = rest.split("\n");
    rest = lines.pop() ?? "";
    lines.forEach(handle);
  }
  if (rest) handle(rest);
  if (refs.length < 100) throw new Error(`Za mało obiektów (${refs.length})`);
  db.set(src.program, { refs, names, lat: Float32Array.from(lats), lon: Float32Array.from(lons), loaded: Date.now() });
  console.log(`Aktywacje: ${src.program} – ${refs.length} obiektów`);
}

/** Pobiera (w tle) bazy starsze niż doba; nie czeka, jeśli jest już jakakolwiek kopia. */
function ensure(userAgent) {
  const waits = [];
  for (const src of SOURCES) {
    const have = db.get(src.program);
    const stale = !have || Date.now() - have.loaded > MAX_AGE_MS;
    const tried = Date.now() - (lastAttempt.get(src.program) ?? 0) < RETRY_MS;
    if (stale && !tried && !loading.has(src.program)) {
      lastAttempt.set(src.program, Date.now());
      const p = load(src, userAgent).catch((e) => console.warn(`Aktywacje: ${src.program} – ${e.message}`)).finally(() => loading.delete(src.program));
      loading.set(src.program, p);
    }
    if (!have && loading.has(src.program)) waits.push(loading.get(src.program));
  }
  return Promise.all(waits);
}

const R = 6371;
function distanceKm(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180;
  const a = Math.sin(((lat2 - lat1) * r) / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(((lon2 - lon1) * r) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(Math.min(1, a)));
}

/** Obiekty w promieniu [radiusKm]. @returns {Promise<{spots: any[], programs: Record<string, number>}>} */
export async function nearby(lat, lon, radiusKm, userAgent) {
  await ensure(userAgent);
  const spots = [];
  /** @type {Record<string, number>} */
  const programs = {};
  const latWindow = radiusKm / 111 + 0.5;
  for (const [program, d] of db) {
    programs[program] = d.refs.length;
    for (let i = 0; i < d.refs.length; i++) {
      // Szybkie odrzucenie po szerokości (~111 km/°), dokładnie – po ortodromie
      if (Math.abs(d.lat[i] - lat) > latWindow) continue;
      const km = distanceKm(lat, lon, d.lat[i], d.lon[i]);
      if (km > radiusKm) continue;
      spots.push({ reference: d.refs[i], name: d.names[i], program, latitude: Math.round(d.lat[i] * 1e5) / 1e5, longitude: Math.round(d.lon[i] * 1e5) / 1e5 });
    }
  }
  return { spots, programs };
}
