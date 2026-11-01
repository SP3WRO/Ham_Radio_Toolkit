// Akcje dyplomowe radiodyplom.pl dla wersji WWW (AwardSources.kt): kalendarz ICS i strony akcji pobierane najwyżej
// co 12 godzin. Strony akcji (z rankingiem nawet kilka MB) serwer czyta raz i trzyma tylko wynik: szczegóły + ranking.
import { parseRadiodyplomIcs, parseAwardPage, RADIODYPLOM_ICS, awardEventUrl } from "../public/js/core/awards.js";

const MAX_AGE = 12 * 3600 * 1000;
const FORCE_MIN = 10 * 60 * 1000;
const MAX_PAGES = 60;

async function get(url, ua) {
  const res = await fetch(url, { headers: { "User-Agent": ua }, signal: AbortSignal.timeout(60000) });
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
    const text = await get(RADIODYPLOM_ICS, ua);
    const events = parseRadiodyplomIcs(text);
    if (!events.length && !text.includes("BEGIN:VCALENDAR")) throw new Error("Nieprawidłowy kalendarz");
    list = { events, time: Date.now() };
  })().finally(() => { listLoading = null; });
  try {
    await listLoading;
    return { events: list?.events ?? [], fetchedAt: list?.time ?? null, fromCache: false, error: null };
  } catch (e) {
    const error = String(e?.message ?? e);
    return list ? { events: list.events, fetchedAt: list.time, fromCache: true, error } : { events: [], fetchedAt: null, fromCache: false, error };
  }
}

/** @type {Map<string, {details: any, ranking: Record<string, any>, time: number}>} */
const pages = new Map();
/** @type {Map<string, Promise<any>>} */
const pending = new Map();
/** @type {Map<string, number>} */
const forced = new Map();

/** Szczegóły akcji z wynikiem znaku [call] w rankingu. */
export async function awardDetails(id, call, ua, refresh = false) {
  if (!/^\d{1,7}$/.test(id)) throw Object.assign(new Error("Zły numer akcji"), { status: 400 });
  const sign = String(call ?? "").trim().toUpperCase();
  if (sign && !/^[A-Z0-9/]{3,15}$/.test(sign)) throw Object.assign(new Error("Zły znak"), { status: 400 });
  const force = refresh && Date.now() - (forced.get(id) ?? 0) > FORCE_MIN;
  if (force) forced.set(id, Date.now());
  let page = pages.get(id);
  if (!page || force || Date.now() - page.time > MAX_AGE) {
    if (!pending.has(id)) {
      pending.set(id, (async () => {
        const parsed = parseAwardPage(await get(awardEventUrl(id), ua), id);
        if (!parsed.details.stations.length && !parsed.details.awards.length) throw new Error("Nie udało się odczytać strony akcji");
        pages.delete(id);
        pages.set(id, { ...parsed, time: Date.now() });
        // Najstarsze kopie wypadają, żeby pamięć serwera nie rosła
        while (pages.size > MAX_PAGES) pages.delete(/** @type {string} */ (pages.keys().next().value));
      })().finally(() => pending.delete(id)));
    }
    try {
      await pending.get(id);
    } catch (e) {
      if (!page) throw Object.assign(new Error(String(e?.message ?? e)), { status: 502 });
    }
    page = pages.get(id) ?? page;
  }
  if (!page) throw Object.assign(new Error("Brak danych"), { status: 502 });
  return { ...page.details, fetchedAtMs: page.time, rankingCallsign: sign, myRanking: sign ? page.ranking[sign] ?? null : null };
}
