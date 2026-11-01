// @ts-check
// Akcje dyplomowe radiodyplom.pl (data/awards/*.kt): kalendarz ICS, strona akcji i postęp z własnego logu.

const DAY_MS = 86_400_000;
export const RADIODYPLOM_ICS = "https://radiodyplom.pl/ics_download.php?days=365";
export const RADIODYPLOM_PAGE = "https://radiodyplom.pl/";
/** @param {string} id */
export const awardEventUrl = (id) => `https://radiodyplom.pl/ses/${id}`;

/** @typedef {{id: string, name: string, startMs: number, endMs: number, description: string, url: string}} AwardEvent */
/** @typedef {{callsign: string, points: number, type: string}} AwardStation */
/** @typedef {{title: string, minPoints: number | null, unit: string, bands: string[], modes: string[], allowedRegions: string[], excludedRegions: string[]}} AwardRule */
/** @typedef {{rank: number, points: number, qsos: number}} RankingEntry */
/**
 * @typedef {{eventId: string, name: string, startMs: number | null, endMs: number | null, organizers: string, description: string,
 *   repeatRule: string, repeatLimit: "BAND_MODE"|"BAND_MODE_DAILY"|"BAND"|"STATION", digiGroup: string[], hasTimeMultipliers: boolean,
 *   stations: AwardStation[], awards: AwardRule[], participants: number, rankingUpdated: string, rankingCallsign: string,
 *   myRanking: RankingEntry | null, fetchedAtMs: number}} AwardDetails
 */

/** @param {number} y @param {number} mo @param {number} d */
function validDate(y, mo, d) {
  return mo >= 1 && mo <= 12 && d >= 1 && d <= new Date(Date.UTC(y, mo, 0)).getUTCDate();
}

// ---------------------------------------------------------------- kalendarz ICS
/** Opis bez BBCode i niewidocznych znaków. @param {string} s */
export const cleanAwardText = (s) => s.replace(/\[\/?[a-zA-Z*]+(?:=[^\]]*)?]/g, " ").replace(/﻿/g, "")
  .replace(/[ \t]+/g, " ").replace(/ *\n */g, "\n").trim();

/** @param {string | undefined} v */
function icsDate(v) {
  const g = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2}))?/.exec(v?.trim() ?? "");
  if (!g) return null;
  const [y, mo, d] = [Number(g[1]), Number(g[2]), Number(g[3])];
  if (!validDate(y, mo, d)) return null;
  return Date.UTC(y, mo - 1, d) + (g[4] ? (Number(g[4]) * 60 + Number(g[5])) * 60_000 : 0);
}

/** @param {string} ics @returns {AwardEvent[]} */
export function parseRadiodyplomIcs(ics) {
  const unfolded = ics.replace(/\r\n/g, "\n").replace(/\n[ \t]/g, "");
  /** @type {Map<string, AwardEvent>} */
  const events = new Map();
  for (const ev of unfolded.matchAll(/BEGIN:VEVENT([\s\S]*?)END:VEVENT/g)) {
    /** @type {Record<string, string>} */
    const p = {};
    for (const line of ev[1].split("\n")) {
      const colon = line.indexOf(":");
      if (colon <= 0) continue;
      const key = line.slice(0, colon).split(";")[0].trim().toUpperCase();
      if (!(key in p)) p[key] = line.slice(colon + 1).replace(/\\[nN]/g, "\n").replace(/\\,/g, ",").replace(/\\;/g, ";").replace(/\\\\/g, "\\");
    }
    const name = p.SUMMARY?.trim();
    if (!name) continue;
    const url = p.URL?.trim() ?? "";
    const id = /\/ses\/(\d+)/.exec(url)?.[1] ?? /^ses-(\d+)-/.exec((p.UID ?? "").trim())?.[1];
    const start = icsDate(p.DTSTART);
    if (!id || start == null || events.has(id)) continue;
    let end = icsDate(p.DTEND) ?? start + DAY_MS;
    if (end <= start) end = start + DAY_MS;
    events.set(id, { id, name, startMs: start, endMs: end, description: cleanAwardText(p.DESCRIPTION ?? ""), url: url || awardEventUrl(id) });
  }
  return [...events.values()].sort((a, b) => a.startMs - b.startMs || a.name.localeCompare(b.name));
}

// ---------------------------------------------------------------- strona akcji
/** @param {string} s */
const decode = (s) => s.replace(/&nbsp;/g, " ").replace(/&quot;/g, "\"").replace(/&#0?39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
  .replace(/&bdquo;/g, "„").replace(/&rdquo;/g, "”").replace(/&ndash;/g, "–").replace(/&mdash;/g, "—").replace(/&amp;/g, "&");

/** Tekst jak w przeglądarce: wiersze dzielą <br> i </p>. @param {string} html */
export function htmlToText(html) {
  return decode(html.replace(/\s+/g, " ").replace(/<br\s*\/?>/gi, "\n").replace(/<\/p>/gi, "\n").replace(/<[^>]+>/g, ""))
    .replace(/ /g, " ").replace(/﻿/g, "").replace(/[ \t]+/g, " ").replace(/ *\n */g, "\n").replace(/\n{3,}/g, "\n\n").trim();
}

/** @param {string} s */
function utc(s) {
  const g = /(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/.exec(s);
  if (!g) return null;
  const [y, mo, d] = [Number(g[1]), Number(g[2]), Number(g[3])];
  return validDate(y, mo, d) ? Date.UTC(y, mo - 1, d) + (Number(g[4]) * 60 + Number(g[5])) * 60_000 : null;
}

/** @param {string} rule @returns {AwardDetails["repeatLimit"]} */
export function repeatLimit(rule) {
  const r = rule.toLowerCase();
  if (r.includes("pasmo + emisja") || r.includes("pasmo i emisja")) return r.includes("dziennie") ? "BAND_MODE_DAILY" : "BAND_MODE";
  if (r.includes("pasm")) return "BAND";
  if (r.trim() && (r.includes("jedn") || r.includes("raz"))) return "STATION";
  return "BAND_MODE";
}

/** @param {string} html @returns {AwardStation[]} */
function stations(html) {
  const start = html.indexOf("<!-- Lista stacji -->");
  if (start < 0) return [];
  const endAt = html.indexOf("class=\"ses-public-rules", start);
  const seen = new Set();
  /** @type {AwardStation[]} */
  const out = [];
  for (const card of html.slice(start, endAt > 0 ? endAt : html.length).split("<!-- Górny róg").slice(1)) {
    const call = /ses_callsign=([A-Z0-9/]+)"/.exec(card)?.[1];
    const points = /<strong class="ses-station-points">(\d+)<\/strong>/.exec(card)?.[1];
    if (!call || points == null || seen.has(call)) continue;
    seen.add(call);
    out.push({ callsign: call, points: Number(points), type: />badge<\/span>\s*([^<]+?)\s*<\/span>/.exec(card)?.[1]?.trim() ?? "" });
  }
  return out;
}

/** @param {string} html @returns {AwardRule[]} */
function awardRules(html) {
  /** @type {AwardRule[]} */
  const out = [];
  for (const raw of html.split("<div class=\"award-card\"").slice(1)) {
    // Ostatnia karta ciągnie się do końca strony – ucinamy ją przed rankingiem
    const card = raw.split("Ranking uczestników całej akcji")[0].slice(0, 30_000);
    const titleM = /<h3 class="award-title">[\s\S]*?<\/span>\s*([\s\S]*?)\s*<\/h3>/.exec(card);
    const title = titleM ? htmlToText(titleM[1]) : "";
    if (!title) continue;
    const min = /Należy zdobyć minimum:<\/p>[\s\S]*?<strong[^>]*>(\d+)<\/strong>\s*([^\s<]+)/.exec(card);
    const allowedAt = card.indexOf("Dozwolone regiony geograficzne");
    const allowed = allowedAt < 0 ? "" : card.slice(allowedAt).split("Niedozwolone regiony")[0];
    const all = (/** @type {RegExp} */ re, /** @type {string} */ s) => [...s.matchAll(re)].map((m) => decode(m[1].trim()));
    out.push({
      title, minPoints: min ? Number(min[1]) : null, unit: min?.[2]?.trim() || "punktów",
      bands: [...new Set(all(/<span class="band-tag">([^<]+)<\/span>/g, card))],
      modes: [...new Set(all(/<span class="mode-tag">([^<]+)<\/span>/g, card).map((m) => m.toUpperCase()))],
      allowedRegions: [...all(/<span class="tag is-success is-light mr-1 mb-1">([^<]+)<\/span>/g, allowed), ...all(/<span class="tag is-info is-light mr-1 mb-1">([^<]+)<\/span>/g, allowed)],
      excludedRegions: all(/<span class="tag is-danger is-light mr-1 mb-1">([^<]+)<\/span>/g, card),
    });
  }
  return out;
}

/** Cały ranking: znak -> pozycja (serwer czyta stronę raz dla wszystkich znaków). @param {string} html @returns {Record<string, RankingEntry>} */
export function parseRanking(html) {
  /** @type {Record<string, RankingEntry>} */
  const out = {};
  const re = /<td class="has-text-centered">(\d+)\.<\/td>\s*<td[^>]*>\s*<a href="[^"]*sesParticipant\.php\?ses_id=\d+&(?:amp;)?callsign=([^"]+)">[^<]*<\/a>\s*<\/td>\s*<td[^>]*>(\d+)<\/td>\s*<td[^>]*>(\d+)<\/td>/g;
  for (const m of html.matchAll(re)) {
    const call = decodeURIComponent(m[2]).toUpperCase();
    if (!(call in out)) out[call] = { rank: Number(m[1]), points: Number(m[3]), qsos: Number(m[4]) };
  }
  return out;
}

/**
 * Szczegóły bez wyniku znaku + cały ranking. Każda część opcjonalna – zmiana wyglądu strony daje puste listy, nie błąd.
 * @param {string} html @param {string} eventId @param {number} [nowMs]
 * @returns {{details: AwardDetails, ranking: Record<string, RankingEntry>}}
 */
export function parseAwardPage(html, eventId, nowMs = Date.now()) {
  const duration = /Czas trwania akcji<\/h3>\s*<div class="ses-rule-dates">\s*<div>\s*Od\s*<time>([^<]+)<\/time>[\s\S]*?Do\s*<time>([^<]+)<\/time>/.exec(html);
  const repeatM = /Kiedy kolejna łączność daje punkty\?<\/h3>\s*<p>([\s\S]*?)<\/p>/.exec(html);
  const repeatRule = repeatM ? htmlToText(repeatM[1]) : "";
  const end = duration ? utc(duration[2]) : null;
  const orgM = /Organizatorzy:<\/span>\s*<span class="ses-detail-value">([\s\S]*?)<\/span>/.exec(html);
  const descM = /<div class="ses-description">([\s\S]*?)<\/div>/.exec(html);
  const groupM = /<div class="ses-mode-group">([\s\S]*?)<\/section>/.exec(html);
  return {
    details: {
      eventId,
      name: decode(/<meta property="og:description" content="([^"]*)"/.exec(html)?.[1] ?? "").trim(),
      startMs: duration ? utc(duration[1]) : null,
      endMs: end != null ? end + 60_000 : null,
      organizers: orgM ? htmlToText(orgM[1]) : "",
      description: descM ? htmlToText(descM[1]).slice(0, 6000) : "",
      repeatRule, repeatLimit: repeatLimit(repeatRule),
      digiGroup: groupM ? [...groupM[1].matchAll(/<span class="mode-tag">([^<]+)<\/span>/g)].map((m) => m[1].trim().toUpperCase()) : [],
      hasTimeMultipliers: html.includes("Harmonogram mnożników czasowych"),
      stations: stations(html), awards: awardRules(html),
      participants: (html.match(/<tr class="ranking-row/g) ?? []).length,
      rankingUpdated: /Ostatnia aktualizacja rankingu \(cache\):\s*<\/span>\s*<span[^>]*>([^<]+)<\/span>/.exec(html)?.[1]?.trim() ?? "",
      rankingCallsign: "", myRanking: null, fetchedAtMs: nowMs,
    },
    ranking: parseRanking(html),
  };
}

// ---------------------------------------------------------------- postęp z własnego logu
/** @param {string} mode */
export function normalizeAwardMode(mode) {
  const m = mode.trim().toUpperCase();
  return m === "USB" || m === "LSB" ? "SSB" : m === "D-STAR" ? "DSTAR" : m;
}

/** @param {string} call */
function baseCall(call) {
  const parts = call.split("/").filter(Boolean);
  return parts.length ? parts.reduce((a, b) => (b.length > a.length ? b : a)) : call;
}

/**
 * Szacunek punktów: czas akcji, pasma i emisje dyplomu, limit powtórzeń, grupa DIGI. Bez mnożników i regionów.
 * @param {AwardDetails} d @param {{callsign: string, datetimeUtc: string, band: string, mode: string}[]} logs
 * @param {number} [fallbackStart] @param {number} [fallbackEnd]
 */
export function computeAwardProgress(d, logs, fallbackStart, fallbackEnd) {
  /** @type {Map<string, AwardStation>} */
  const stationsByCall = new Map(d.stations.map((s) => [s.callsign.toUpperCase(), s]));
  const start = d.startMs ?? fallbackStart ?? -Infinity;
  const end = d.endMs ?? fallbackEnd ?? Infinity;
  const digi = new Set(d.digiGroup);
  const hits = logs.flatMap((q) => {
    const call = q.callsign.trim().toUpperCase();
    const station = stationsByCall.get(call) ?? stationsByCall.get(baseCall(call));
    const time = utc(q.datetimeUtc.trim());
    if (!station || time == null || time < start || time >= end) return [];
    const raw = normalizeAwardMode(q.mode);
    return [{ station, time, band: q.band.trim().toLowerCase(), mode: digi.has(raw) ? "DIGI" : raw, raw }];
  }).sort((a, b) => a.time - b.time);
  const rules = d.awards.map((rule) => {
    const bands = new Set(rule.bands.map((b) => b.toLowerCase()));
    const modes = new Set(rule.modes);
    const seen = new Set();
    let points = 0, counted = 0;
    for (const x of hits) {
      if (bands.size && !bands.has(x.band)) continue;
      if (modes.size && !modes.has(x.raw) && !modes.has(x.mode)) continue;
      const c = x.station.callsign;
      const key = d.repeatLimit === "BAND_MODE_DAILY" ? `${c}|${x.band}|${x.mode}|${Math.floor(x.time / DAY_MS)}`
        : d.repeatLimit === "BAND" ? `${c}|${x.band}` : d.repeatLimit === "STATION" ? c : `${c}|${x.band}|${x.mode}`;
      if (seen.has(key)) continue;
      seen.add(key);
      points += x.station.points;
      counted++;
    }
    return { rule, points, countedQsos: counted, achieved: rule.minPoints != null && points >= rule.minPoints };
  });
  /** @type {Record<string, number>} */
  const worked = {};
  for (const x of hits) worked[x.station.callsign] = (worked[x.station.callsign] ?? 0) + 1;
  return { rules, workedStations: worked, matchingQsos: hits.length };
}
