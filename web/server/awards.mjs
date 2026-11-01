// Akcje dyplomowe radiodyplom.pl dla wersji WWW (AwardSources.kt) – wyłącznie public_events.json (najwyżej co 12 godzin)
// i public_bandmap.json (najwyżej co 5 minut, tylko gdy ktoś ma otwarty ekran, który z niej korzysta).
import { parseAwardEvents, parseBandmap, RADIODYPLOM_EVENTS, RADIODYPLOM_BANDMAP } from "../public/js/core/awards.js";

const MAX_AGE = 12 * 3600 * 1000;
const BANDMAP_MAX_AGE = 5 * 60 * 1000;
const FORCE_MIN = 10 * 60 * 1000;

async function get(url, ua) {
  const res = await fetch(url, { headers: { "User-Agent": ua }, signal: AbortSignal.timeout(30000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}

/** @type {{events: any[], time: number} | null} */
let list = null;
/** @type {Promise<any> | null} */
let listLoading = null;
let listForced = 0;

export async function awardEvents(ua, refresh = false) {
  const force = refresh && Date.now() - listForced > FORCE_MIN;
  if (force) listForced = Date.now();
  if (list && !force && Date.now() - list.time < MAX_AGE) return { events: list.events, fetchedAt: list.time, fromCache: false, error: null };
  listLoading ??= (async () => {
    list = { events: parseAwardEvents(await get(RADIODYPLOM_EVENTS, ua)), time: Date.now() };
  })().finally(() => { listLoading = null; });
  try {
    await listLoading;
    return { events: list?.events ?? [], fetchedAt: list?.time ?? null, fromCache: false, error: null };
  } catch (e) {
    const error = String(e?.message ?? e);
    return list ? { events: list.events, fetchedAt: list.time, fromCache: true, error } : { events: [], fetchedAt: null, fromCache: false, error };
  }
}

/** @type {{spots: any[], time: number} | null} */
let bandmap = null;
/** @type {Promise<any> | null} */
let bandmapLoading = null;

/** Mapa pasm: { spots, fetchedAt } – spots już bez wpisów starszych niż 30 minut; null, gdy nie ma sieci ani kopii. */
export async function awardBandmap(ua) {
  if (!bandmap || Date.now() - bandmap.time >= BANDMAP_MAX_AGE) {
    bandmapLoading ??= (async () => {
      bandmap = { spots: parseBandmap(await get(RADIODYPLOM_BANDMAP, ua)), time: Date.now() };
    })().finally(() => { bandmapLoading = null; });
    try { await bandmapLoading; } catch { /* zostaje starsza kopia */ }
  }
  return bandmap ? { spots: bandmap.spots, fetchedAt: bandmap.time } : null;
}
