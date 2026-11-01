// @ts-check
// Spoty DX Cluster (DxClusterViewModel.kt, DxClusterScreen.kt) i aktywatorów POTA/WWFF (LiveSpotsClient.kt)

/** @typedef {{dx_call: string, de_call: string, frequency: number, time: string, info?: string | null, dx_country?: string | null}} DxSpot */
/**
 * @typedef {{program: string, activator: string, frequencyKhz: number, mode: string, reference: string, referenceName: string,
 *   spotter: string, comment: string, timeEpochSec: number, latitude: number | null, longitude: number | null}} LiveSpot
 */

export const DX_BANDS = ["ALL", "160M", "80M", "40M", "30M", "20M", "17M", "15M", "12M", "10M", "6M", "2M", "70CM"];
export const DX_MODES = ["ALL", "CW", "SSB", "FT8", "DIGI"];

/** DX Summit podaje kHz; na wszelki wypadek wartości poniżej 1000 traktujemy jak MHz. @param {number} f */
export const spotKhz = (f) => (f < 1000 ? f * 1000 : f);

const BAND_RANGES = {
  "160M": [1800, 2000], "80M": [3500, 3800], "40M": [7000, 7300], "30M": [10100, 10150], "20M": [14000, 14350],
  "17M": [18068, 18168], "15M": [21000, 21450], "12M": [24890, 24990], "10M": [28000, 29700], "6M": [50000, 54000],
  "2M": [144000, 146000], "70CM": [430000, 440000],
};

/** @param {number} frequency @param {string} band */
export function matchesBand(frequency, band) {
  if (band === "ALL") return true;
  const r = BAND_RANGES[band];
  if (!r) return true;
  const khz = spotKhz(frequency);
  return khz >= r[0] && khz <= r[1];
}

/** Segmenty CW na dole pasm. @param {number} khz */
export function isCwSegment(khz) {
  const r = [[1810, 1838], [3500, 3570], [7000, 7040], [10100, 10130], [14000, 14070], [18068, 18095], [21000, 21070],
    [24890, 24915], [28000, 28070], [50000, 50100], [144000, 144150]];
  return r.some(([a, b]) => khz >= a && khz <= b);
}

/** @param {number} frequency @param {string} info @param {string} mode */
export function matchesMode(frequency, info, mode) {
  if (mode === "ALL") return true;
  const u = (info ?? "").toUpperCase();
  const khz = spotKhz(frequency);
  const isFt8 = u.includes("FT8") || u.includes("FT4");
  const isDigi = isFt8 || ["RTTY", "PSK", "JT65", "DIGI", "DATA", "JS8"].some((x) => u.includes(x));
  const isSsbText = u.includes("SSB") || u.includes("USB") || u.includes("LSB");
  const isCw = u.includes("CW") || (!isDigi && !isSsbText && isCwSegment(khz));
  const isSsb = isSsbText || u.includes("PHONE") || (!isDigi && !isCw);
  return mode === "FT8" ? isFt8 : mode === "DIGI" ? isDigi : mode === "CW" ? isCw : mode === "SSB" ? isSsb : true;
}

/** Emisja ze spotu: z komentarza, a bez niego z segmentu bandplanu. @param {number} khz @param {string} info */
export function guessModeFromSpot(khz, info) {
  const t = (info ?? "").toUpperCase();
  if (t.includes("FT8")) return "FT8";
  if (t.includes("FT4")) return "FT4";
  if (t.includes("CW")) return "CW";
  if (t.includes("RTTY")) return "RTTY";
  if (t.includes("SSB") || t.includes("USB") || t.includes("LSB")) return "SSB";
  if (t.includes("FM")) return "FM";
  return isCwSegment(khz) ? "CW" : "SSB";
}

/**
 * Filtr pasma, emisji i tekstu (jak spots w DxClusterViewModel).
 * @param {DxSpot[]} spots @param {{band: string, mode: string, query: string}} f
 */
export function filterDxSpots(spots, f) {
  const q = f.query.trim().toLowerCase();
  return spots.filter((s) => matchesBand(s.frequency, f.band) && matchesMode(s.frequency, s.info ?? "", f.mode) &&
    (!q || s.dx_call.toLowerCase().includes(q) || s.de_call.toLowerCase().includes(q) || (s.info ?? "").toLowerCase().includes(q)));
}

/** Częstotliwość do pola QSO: "14025.5", bez ".0". @param {number} khz */
export const qsoFrequency = (khz) => khz.toFixed(1).replace(/\.0$/, "");

// ---------------------------------------------------------------- POTA / WWFF
/** @param {string} body @returns {LiveSpot[]} */
export function parsePota(body) {
  /** @type {any[]} */
  const list = JSON.parse(body);
  return list.flatMap((s) => {
    const khz = Number(String(s.frequency ?? "").trim());
    if (!String(s.frequency ?? "").trim() || !Number.isFinite(khz)) return [];
    // spotTime POTA jest w UTC, bez strefy: "2026-10-20T20:22:17"
    const t = Date.parse(`${s.spotTime ?? ""}Z`);
    return [{
      program: "POTA", activator: String(s.activator ?? "").toUpperCase(), frequencyKhz: khz, mode: String(s.mode ?? "").toUpperCase(),
      reference: String(s.reference ?? "").toUpperCase(), referenceName: s.name ?? "", spotter: s.spotter ?? "", comment: s.comments ?? "",
      timeEpochSec: Number.isFinite(t) ? Math.floor(t / 1000) : 0, latitude: s.latitude ?? null, longitude: s.longitude ?? null,
    }];
  });
}

/** @param {string} body @returns {LiveSpot[]} */
export function parseWwff(body) {
  /** @type {any[]} */
  const list = JSON.parse(body);
  return list.filter((s) => Number(s.frequency_khz) > 0).map((s) => ({
    program: "WWFF", activator: String(s.activator ?? "").toUpperCase(), frequencyKhz: Number(s.frequency_khz), mode: String(s.mode ?? "").toUpperCase(),
    reference: String(s.reference ?? "").toUpperCase(), referenceName: s.reference_name ?? "", spotter: s.spotter ?? "", comment: s.remarks ?? "",
    timeEpochSec: Number(s.spot_time) || 0, latitude: s.latitude ?? null, longitude: s.longitude ?? null,
  }));
}

/** Ciąg CSV zgodny z RFC 4180 – jedna linia (CsvParser.parseLine). @param {string} line */
export function parseCsvLine(line) {
  const fields = [];
  let sb = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inQuotes && c === '"' && line[i + 1] === '"') { sb += '"'; i++; }
    else if (c === '"') inQuotes = !inQuotes;
    else if (c === "," && !inQuotes) { fields.push(sb.trim()); sb = ""; }
    else sb += c;
  }
  fields.push(sb.trim());
  return fields;
}
