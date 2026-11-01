// Dane satelitów dla wersji WWW (SatelliteScreen.kt): TLE z AMSAT (zapasowo CelesTrak), nadajniki z SatNOGS
// i częstotliwości z bazy AMSAT – pobierane raz na dobę i oddawane telefonowi w jednej, skróconej odpowiedzi.
// Przeloty liczy już przeglądarka (SGP4), więc działa to także bez sieci, na zapisanych TLE.
import { parseTles } from "../public/js/core/sgp4.js";

const DAY = 24 * 3600 * 1000;
const FORCE_MIN = 10 * 60 * 1000;

async function get(url, ua, timeout = 60000) {
  const res = await fetch(url, { headers: { "User-Agent": ua }, signal: AbortSignal.timeout(timeout) });
  if (!res.ok) throw new Error(`HTTP ${res.status} z ${new URL(url).host}`);
  return res.text();
}

/** @type {{value: any, time: number} | null} */
let copy = null;
/** @type {Promise<any> | null} */
let loading = null;
let forced = 0;

async function tles(ua) {
  for (const url of ["https://www.amsat.org/tle/current/nasabare.txt", "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle"]) {
    try {
      const list = parseTles(await get(url, ua));
      if (list.length >= 10) return list;
    } catch (e) {
      console.warn(`TLE ${url}: ${e.message}`);
    }
  }
  throw new Error("TLE niedostępne (AMSAT i CelesTrak)");
}

/** Nadajniki SatNOGS skrócone do pól potrzebnych na ekranie; aktywne = choć jeden działający nadajnik. */
async function satnogs(ua) {
  const list = JSON.parse(await get("https://db.satnogs.org/api/transmitters/", ua, 90000));
  /** @type {Record<string, any[]>} */
  const byNorad = {};
  const active = new Set();
  for (const t of Array.isArray(list) ? list : []) {
    const id = t?.norad_cat_id;
    if (id == null) continue;
    if (t.alive && String(t.status ?? "").toLowerCase() === "active") active.add(String(id));
    (byNorad[id] ??= []).push({ mode: t.mode ?? null, up: t.uplink_low ?? null, down: t.downlink_low ?? null, desc: t.description ?? "" });
  }
  return { byNorad, active: [...active] };
}

async function amsat(ua) {
  const list = JSON.parse(await get("https://raw.githubusercontent.com/palewire/amateur-satellite-database/main/data/amsat-active-frequencies.json", ua));
  return (Array.isArray(list) ? list : []).map((f) => ({
    name: String(f.name ?? ""), norad: f.norad_id != null ? String(f.norad_id) : null, uplink: f.uplink ?? null, downlink: f.downlink ?? null,
    beacon: f.beacon ?? null, mode: f.mode ?? null, callsign: f.callsign ?? null,
  }));
}

export async function satellites(ua, refresh = false) {
  const force = refresh && Date.now() - forced > FORCE_MIN;
  if (force) forced = Date.now();
  if (copy && !force && Date.now() - copy.time < DAY) return { ...copy.value, fetchedAt: copy.time };
  loading ??= (async () => {
    // Częstotliwości są tylko uzupełnieniem – ich brak nie blokuje listy satelitów
    const [list, sn, am] = await Promise.all([tles(ua), satnogs(ua).catch(() => copy?.value.satnogs ?? null), amsat(ua).catch(() => copy?.value.amsat ?? null)]);
    copy = { value: { tles: list, satnogs: sn, amsat: am }, time: Date.now() };
  })().finally(() => { loading = null; });
  try {
    await loading;
  } catch (e) {
    if (!copy) throw e;
  }
  return { ...copy.value, fetchedAt: copy.time };
}
