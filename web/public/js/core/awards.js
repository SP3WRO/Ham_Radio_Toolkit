// @ts-check
// Akcje dyplomowe radiodyplom.pl (RadiodyplomFeed.kt). Serwis zezwala na korzystanie wyłącznie z dwóch publicznych plików:
// public_events.json (lista akcji) i public_bandmap.json (stacje z ostatnich 30 minut). Stron akcji (/ses/) aplikacja nie czyta.
// Czasy w plikach są w UTC.
import { utcMillis } from "./contests.js";

export const RADIODYPLOM_EVENTS = "https://radiodyplom.pl/public_events.json";
export const RADIODYPLOM_BANDMAP = "https://radiodyplom.pl/public_bandmap.json";
export const RADIODYPLOM_PAGE = "https://radiodyplom.pl/";
const BANDMAP_WINDOW_MINUTES = 30;
const MINUTE_MS = 60_000;

/**
 * Akcja; [endMs] – koniec (wyłącznie). Wersje "global" (po angielsku) podaje tylko część akcji.
 * @typedef {{id: string, name: string, globalName: string | null, startMs: number, endMs: number, organizers: string, globalOrganizers: string | null, url: string}} AwardEvent
 * @typedef {{id: string, title: string, pointsInfo: string}} BandmapEvent
 * @typedef {{callsign: string, band: string, mode: string, frequencyKhz: number | null, timeUtc: string, minutesAgo: number, events: BandmapEvent[]}} BandmapSpot
 */

/** @param {unknown} v */
const str = (v) => (typeof v === "string" ? v.trim() : "");

/** @param {string} text @returns {AwardEvent[]} */
export function parseAwardEvents(text) {
  const root = JSON.parse(text);
  const list = Array.isArray(root?.events) ? root.events : [];
  /** @type {Map<string, AwardEvent>} */
  const byId = new Map();
  for (const e of list) {
    if (e?.status === "ended" || e?.id == null) continue;
    const id = String(e.id), name = str(e.title);
    const start = utcMillis(e.start_date), end = utcMillis(e.end_date);
    if (!name || start == null || end == null || byId.has(id)) continue;
    const url = str(e.url);
    byId.set(id, {
      id, name, globalName: str(e.title_global) || null, startMs: start,
      // Koniec w pliku to ostatnia minuta akcji
      endMs: end + MINUTE_MS,
      organizers: str(e.organizers), globalOrganizers: str(e.organizers_global) || null,
      url: url.startsWith("http") ? url : RADIODYPLOM_PAGE,
    });
  }
  return [...byId.values()].sort((a, b) => a.startMs - b.startMs || a.name.localeCompare(b.name));
}

/** @param {AwardEvent} e @param {boolean} polish */
export const awardTitle = (e, polish) => (polish ? e.name : e.globalName ?? e.name);
/** @param {AwardEvent} e @param {boolean} polish */
export const awardOrganizers = (e, polish) => (polish ? e.organizers : e.globalOrganizers ?? e.organizers);

/** @param {string} text @returns {BandmapSpot[]} */
export function parseBandmap(text) {
  const root = JSON.parse(text);
  const list = Array.isArray(root?.spots) ? root.spots : [];
  /** @type {BandmapSpot[]} */
  const out = [];
  for (const s of list) {
    const call = str(s?.callsign).toUpperCase();
    if (!call) continue;
    out.push({
      callsign: call, band: str(s.band), mode: str(s.mode),
      frequencyKhz: typeof s.frequency_khz === "number" && Number.isFinite(s.frequency_khz) ? s.frequency_khz : null,
      timeUtc: str(s.time_on_display), minutesAgo: Number.isFinite(s.minutes_ago) ? s.minutes_ago : 0,
      events: (Array.isArray(s.ses_list) ? s.ses_list : []).filter((e) => e?.id != null)
        .map((e) => ({ id: String(e.id), title: str(e.title), pointsInfo: str(e.points_info) })),
    });
  }
  return out;
}

/** Stacje z kopii sprzed [copyAgeMs], które nadal mieszczą się w 30-minutowym oknie. @param {BandmapSpot[]} spots @param {number} copyAgeMs */
export const activeSpots = (spots, copyAgeMs) => spots.filter((s) => s.minutesAgo + Math.floor(copyAgeMs / MINUTE_MS) <= BANDMAP_WINDOW_MINUTES);

/** Stacje obserwowanych akcji: znak → teksty "akcja (+1 p.)". @param {BandmapSpot[]} spots @param {Set<string>} followed */
export function followedTags(spots, followed) {
  /** @type {Map<string, string[]>} */
  const out = new Map();
  for (const spot of spots) {
    for (const e of spot.events) {
      if (!followed.has(e.id)) continue;
      const text = `${e.title || `#${e.id}`} ${e.pointsInfo}`.trim();
      const tags = out.get(spot.callsign) ?? [];
      if (!tags.includes(text)) tags.push(text);
      out.set(spot.callsign, tags);
    }
  }
  return out;
}
