// @ts-check
// Przemienniki: parsery źródeł, scalanie i grupowanie – port data/model/Repeater.kt, RepeaterDataSourceFetcher.kt,
// InternationalRepeaters.kt i RepeatersViewModel.buildList. Moduł bez DOM – używa go też serwer.
import { parseCsvLine } from "./spots.js";

/**
 * @typedef {{callsign: string, qth: string, modes: string[], userRx: number, userTx: number, requiredCtcss: string,
 *   status: string, latitude: number, longitude: number, distanceKm: number, source: string, note: string, dmrColorCode: string}} Repeater
 */

export const RepeaterSources = {
  PRZEMIENNIKI_NET: "przemienniki_net",
  PRZEMIENNIKI_EU: "przemienniki_eu",
  /** HearHam + repeatermap.de scalone – domyślne poza Polską. */
  WORLD: "international",
  HEARHAM: "hearham",
  REPEATERMAP: "repeatermap",
  REPEATERBOOK: "repeaterbook_api",
  /** @param {string} s */
  isPolish: (s) => s === "przemienniki_net" || s === "przemienniki_eu",
  /** @param {string} s */
  label: (s) => ({ przemienniki_net: "przemienniki.net", przemienniki_eu: "przemienniki.eu", international: "HearHam + repeatermap.de",
    hearham: "HearHam", repeatermap: "repeatermap.de", repeaterbook_api: "RepeaterBook" })[s] ?? s,
};

/** @param {Partial<Repeater>} p @returns {Repeater} */
export function makeRepeater(p) {
  return {
    callsign: p.callsign ?? "", qth: p.qth ?? "", modes: p.modes ?? [], userRx: p.userRx ?? 0, userTx: p.userTx ?? 0,
    requiredCtcss: p.requiredCtcss ?? "-", status: p.status ?? "working", latitude: p.latitude ?? 0, longitude: p.longitude ?? 0,
    distanceKm: p.distanceKm ?? 0, source: p.source ?? "", note: p.note ?? "", dmrColorCode: p.dmrColorCode ?? "",
  };
}

/** Klucz wiersza listy. @param {Repeater} r */
export const listKey = (r) => `${r.callsign}_${r.userRx}_${r.userTx}_${r.latitude}_${r.longitude}`;

// ---------------------------------------------------------------- przemienniki.net / przemienniki.eu
/**
 * Emisje z polskich baz. Jeden wpis może zawierać kilka emisji naraz (np. "DSTAR/DV+C4FM/Fusion").
 * @param {string[]} rawModes
 */
export function normalizeModesPl(rawModes) {
  /** @type {Set<string>} */
  const result = new Set();
  for (const raw of rawModes) {
    const upper = String(raw ?? "").trim().toUpperCase();
    if (!upper) continue;
    const digital = [];
    if (upper.includes("DSTAR") || upper.includes("D-STAR")) digital.push("D-STAR");
    if (upper.includes("C4FM") || upper.includes("FUSION") || upper.includes("YSF")) digital.push("C4FM");
    if (upper.includes("DMR")) digital.push("DMR");
    digital.forEach((d) => result.add(d));
    // "FM" szukamy jako osobnego słowa – w "C4FM" też są litery FM
    const words = upper.split(/[^A-Z0-9]+/).filter(Boolean);
    if (words.includes("FM") || words.includes("NFM") || words.includes("ANALOG")) result.add("FM");
    // Samo "DV" w CHIRP to cyfrowa emisja bez nazwy systemu – zwykle Yaesu C4FM
    else if (!digital.length && upper === "DV") result.add("C4FM");
    else if (!digital.length && words.length && upper.length <= 8) result.add(upper);
  }
  return [...result];
}

/** CTCSS wejścia przemiennika z odpowiedzi przemienniki.eu (tekst, liczba albo false). */
function ctcssFromEu(raw) {
  if (raw == null || raw === false) return "-";
  return String(raw);
}

/** @param {string} body JSON z przemienniki.eu/api/export/json @returns {Repeater[]} */
export function parsePrzemiennikiEu(body) {
  /** @type {any[]} */
  const list = JSON.parse(body);
  return list.flatMap((r) => (Array.isArray(r.coordinates) && r.coordinates.length >= 2 ? [makeRepeater({
    callsign: r.callsign ?? "", qth: r.qth ?? "", modes: normalizeModesPl(r.modes ?? []),
    userRx: Number(r.tx_frequency) || 0, userTx: Number(r.rx_frequency) || 0, requiredCtcss: ctcssFromEu(r.rx_ctcss),
    status: r.status ? String(r.status) : "working", latitude: Number(r.coordinates[0]), longitude: Number(r.coordinates[1]), source: "przemienniki.eu",
  })] : []));
}

/** Mapa emisji ze znaku (przemienniki.eu) – uzupełnia eksport CHIRP z przemienniki.net. @param {string} body */
export function modesFromEu(body) {
  /** @type {Map<string, string[]>} */
  const map = new Map();
  for (const r of JSON.parse(body)) {
    if (!String(r.callsign ?? "").trim()) continue;
    map.set(r.callsign, [...(map.get(r.callsign) ?? []), ...(r.modes ?? [])]);
  }
  return new Map([...map].map(([k, v]) => [k, normalizeModesPl(v)]));
}

/** Współrzędne z przemienniki.net/export/radar.xml. @param {string} xml */
export function parseRadarXml(xml) {
  /** @type {Map<string, [number, number]>} */
  const map = new Map();
  for (const m of xml.matchAll(/<repeater\s+[\s\S]*?>/g)) {
    /** @type {Record<string, string>} */
    const attrs = {};
    for (const a of m[0].matchAll(/(\w+)="([^"]*)"/g)) attrs[a[1]] = a[2].trim();
    const cs = attrs.callsign?.trim();
    const lat = Number(attrs.latitude) || 0, lon = Number(attrs.longitude) || 0;
    if (cs && lat !== 0 && lon !== 0) map.set(cs, [lat, lon]);
  }
  return map;
}

/**
 * Eksport CHIRP z przemienniki.net. Kolumny: Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,Mode,TStep,Comment
 * @param {string} csv @param {Map<string, [number, number]>} coords @param {Map<string, string[]>} modes @returns {Repeater[]}
 */
export function parseChirpCsv(csv, coords, modes) {
  const result = [];
  /** @type {Map<string, number> | null} */
  let cols = null;
  for (const raw of csv.split("\n")) {
    const line = raw.replace(/\r$/, "");
    if (!line.trim()) continue;
    const fields = parseCsvLine(line);
    if (!cols) { if (fields.includes("Frequency")) cols = new Map(fields.map((f, i) => [f, i])); continue; }
    const c = cols;
    const row = (n) => fields[c.get(n) ?? -1] ?? "";
    const callsign = row("Name");
    if (!callsign.trim()) continue;
    const userRx = Number(row("Frequency"));
    if (!row("Frequency") || !Number.isFinite(userRx) || userRx === 0) continue;
    const offset = Number(row("Offset")) || 0;
    const duplex = row("Duplex");
    const userTx = duplex === "-" ? userRx - offset : duplex === "+" ? userRx + offset : userRx;
    // CHIRP zawsze wypełnia rToneFreq/cToneFreq (88.5) – ton obowiązuje tylko, gdy kolumna Tone go włącza
    const tone = row("Tone");
    const ctcssRaw = tone === "Tone" ? row("rToneFreq") : tone === "TSQL" ? row("cToneFreq") : "";
    const ctcss = !ctcssRaw.trim() || Number(ctcssRaw) === 0 ? "-" : ctcssRaw;
    const qth = row("Comment");
    const pos = coords.get(callsign);
    if (!pos) continue; // bez współrzędnych nie policzymy odległości
    const raws = [...(modes.get(callsign) ?? []), row("Mode") || "FM"];
    const u = qth.toUpperCase();
    if (u.includes("C4FM") || u.includes("FUSION") || u.includes("WIRES-X")) raws.push("C4FM");
    if (u.includes("DMR")) raws.push("DMR");
    if (u.includes("DSTAR") || u.includes("D-STAR")) raws.push("D-STAR");
    const m = normalizeModesPl(raws);
    result.push(makeRepeater({ callsign, qth: qth.trim() ? qth : "Przemienniki.net", modes: m.length ? m : ["FM"], userRx, userTx,
      requiredCtcss: ctcss, status: "working", latitude: pos[0], longitude: pos[1], source: "przemienniki.net" }));
  }
  return result;
}

// ---------------------------------------------------------------- źródła zagraniczne
/** Emisje ze źródeł zagranicznych. @param {string[]} raw */
export function normalizeModesIntl(raw) {
  /** @type {Set<string>} */
  const result = new Set();
  for (const part of raw) {
    const u = String(part ?? "").trim().toUpperCase();
    if (!u) continue;
    if (u.includes("DMR") && u.includes("STAR")) { result.add("D-STAR"); result.add("DMR"); }
    else if (u.includes("DMR")) result.add("DMR");
    else if (u.includes("STAR")) result.add("D-STAR");
    else if (u === "YSF" || u.includes("C4FM") || u.includes("FUSION")) result.add("C4FM");
    else if (u.includes("P25")) result.add("P25");
    else if (u.includes("NXDN")) result.add("NXDN");
    else if (u === "FM" || u === "NFM" || u.includes("ANALOG")) result.add("FM");
    else if (u === "AX25") result.add("APRS");
    else if (u.length <= 8) result.add(u);
  }
  return result.size ? [...result] : ["FM"];
}

/** "082.5" → "82.5"; "0", "", "CC1" → "-". @param {string} raw */
export function ctcssOrDash(raw) {
  const t = String(raw ?? "").trim().replace(",", ".");
  const v = Number(t);
  if (!t || !Number.isFinite(v) || v < 60 || v > 260) return "-";
  return v.toFixed(1);
}

const str = (o, k) => (o[k] == null || typeof o[k] === "object" ? "" : String(o[k]));
const num = (o, k) => { const v = Number(o[k]); return o[k] == null || o[k] === "" || !Number.isFinite(v) ? null : v; };

/** hearham.com/api/repeaters/v1 @param {string} text @returns {Repeater[]} */
export function parseHearHam(text) {
  /** @type {any[]} */
  const list = JSON.parse(text);
  return list.flatMap((o) => {
    if (!o || typeof o !== "object") return [];
    const call = str(o, "callsign").trim().toUpperCase();
    const lat = num(o, "latitude"), lon = num(o, "longitude"), freqHz = num(o, "frequency");
    if (lat == null || lon == null || freqHz == null || !call || freqHz <= 0 || (lat === 0 && lon === 0)) return [];
    const offsetHz = num(o, "offset") ?? 0;
    const encode = str(o, "encode").trim();
    const cc = /^CC\s*(\d+)$/i.exec(encode)?.[1] ?? "";
    const group = str(o, "group").trim();
    const description = str(o, "description").split("\n").find((l) => l.trim())?.trim() ?? "";
    return [makeRepeater({
      callsign: call, qth: str(o, "city").trim(), modes: normalizeModesIntl(str(o, "mode").split(/[/ ]/)),
      userRx: freqHz / 1e6, userTx: (freqHz + offsetHz) / 1e6, requiredCtcss: ctcssOrDash(encode),
      status: (num(o, "operational") ?? 1) === 1 ? "working" : "off", latitude: lat, longitude: lon, source: "HearHam",
      note: [group && group !== "DMR" ? group : null, description || null].filter(Boolean).join(" • "), dmrColorCode: cc,
    })];
  });
}

const CTCSS_IN_TEXT = /(?:CTCSS|PL|Ton|Tone)\s*:?\s*(\d{2,3}[.,]\d)/i;
const COLOR_CODE_IN_TEXT = /\bCC\s*:?\s*(\d{1,2})\b/i;

/** repeatermap.de/api/v1/map.php @param {string} text @returns {Repeater[]} */
export function parseRepeatermap(text) {
  const root = JSON.parse(text);
  const result = [];
  for (const loc of Array.isArray(root?.locations) ? root.locations : []) {
    const pos = loc?.position;
    if (!pos || typeof pos !== "object") continue;
    const lat = num(pos, "lat"), lon = num(pos, "lon");
    if (lat == null || lon == null || !Array.isArray(loc.relais)) continue;
    for (const r of loc.relais) {
      if (!r || typeof r !== "object") continue;
      const call = str(r, "call").trim().toUpperCase();
      // tx – nadawanie przemiennika (słuchamy), rx – jego wejście (nadajemy)
      const output = num(r, "tx");
      if (output == null) continue;
      const input = num(r, "rx") ?? output;
      if (!call || output <= 0) continue;
      const remarks = str(r, "remarks").trim();
      // Pola szczegółowe emisji (gdy puste, API zwraca [] zamiast obiektu); ctcss_rx – ton wejścia przemiennika
      const details = r.mode_details && !Array.isArray(r.mode_details) && typeof r.mode_details === "object" ? r.mode_details : null;
      const ctcss = (details && str(details, "ctcss_rx").trim()) || (details && str(details, "ctcss_tx").trim()) || CTCSS_IN_TEXT.exec(remarks)?.[1] || null;
      const cc = (details && str(details, "cc").trim()) || COLOR_CODE_IN_TEXT.exec(remarks)?.[1] || "";
      result.push(makeRepeater({
        callsign: call, qth: (str(r, "qth").trim() ? str(r, "qth") : str(loc, "qth")).trim(), modes: normalizeModesIntl(str(r, "mode").split(/[/, ]/)),
        userRx: output, userTx: input, requiredCtcss: ctcss ? ctcssOrDash(ctcss.replace(/\s*hz$/i, "")) : "-", status: "working",
        latitude: lat, longitude: lon, source: "repeatermap.de", note: [remarks, str(r, "url").trim()].filter(Boolean).join(" • "), dmrColorCode: cc,
      }));
    }
  }
  return result;
}

/** Eksport RepeaterBook: {"count": n, "results": [...]} @param {string} text @returns {Repeater[]} */
export function parseRepeaterBook(text) {
  const root = JSON.parse(text);
  const results = Array.isArray(root?.results) ? root.results : [];
  const yes = (o, k) => str(o, k).toLowerCase() === "yes";
  return results.flatMap((o) => {
    const output = num(o, "Frequency"), lat = num(o, "Lat"), lon = num(o, "Long");
    if (output == null || lat == null || lon == null) return [];
    const modes = [];
    if (yes(o, "FM Analog")) modes.push("FM");
    if (yes(o, "DMR")) modes.push("DMR");
    if (yes(o, "D-Star")) modes.push("D-STAR");
    if (yes(o, "System Fusion")) modes.push("C4FM");
    if (yes(o, "NXDN")) modes.push("NXDN");
    if (yes(o, "APCO P-25")) modes.push("P25");
    if (yes(o, "Tetra")) modes.push("TETRA");
    return [makeRepeater({
      callsign: str(o, "Callsign").trim().toUpperCase(), qth: [str(o, "Nearest City"), str(o, "Landmark")].filter((s) => s.trim()).join(", "),
      modes: modes.length ? modes : ["FM"], userRx: output, userTx: num(o, "Input Freq") ?? output, requiredCtcss: ctcssOrDash(str(o, "PL")),
      status: /off/i.test(str(o, "Operational Status")) ? "off" : "working", latitude: lat, longitude: lon, source: "RepeaterBook",
      note: str(o, "Notes").trim(), dmrColorCode: str(o, "DMR Color Code").trim(),
    })];
  });
}

/** Szczegóły DMR z RadioID: [częstotliwość wyjścia, szczegóły][]. @param {string} text */
export function parseRadioId(text) {
  const root = JSON.parse(text);
  return (Array.isArray(root?.results) ? root.results : []).flatMap((o) => {
    const freq = num(o, "frequency");
    if (freq == null) return [];
    const talkgroups = (Array.isArray(o.talkgroups) ? o.talkgroups : []).flatMap((tg) => {
      const n = str(tg, "talkgroup");
      return n.trim() ? [`TS${str(tg, "timeslot")}: ${n}` + (str(tg, "description").trim() ? ` (${str(tg, "description")})` : "")] : [];
    });
    const trustee = (Array.isArray(o.trustee) ? o.trustee : []).filter((x) => typeof x === "string").join(", ");
    return [/** @type {[number, {colorCode: string, network: string, talkgroups: string[], trustee: string}]} */ (
      [freq, { colorCode: str(o, "color_code"), network: str(o, "ipsc_network"), talkgroups, trustee }])];
  });
}

/** Wybór pasującego przemiennika z odpowiedzi RadioID (ten sam znak bywa na kilku częstotliwościach). */
export function pickRadioId(list, outputMhz) {
  if (!list.length) return null;
  const best = list.reduce((a, b) => (Math.abs(b[0] - outputMhz) < Math.abs(a[0] - outputMhz) ? b : a));
  return Math.abs(best[0] - outputMhz) < 0.01 || list.length === 1 ? best[1] : null;
}

// ---------------------------------------------------------------- scalanie, obszar, grupy
const baseCall = (c) => c.toUpperCase().split("/").reduce((a, b) => (b.length > a.length ? b : a), "");

/**
 * Ten sam znak i ta sama częstotliwość wyjścia (±5 kHz) to jeden przemiennik; pozycja z pierwszego źródła.
 * @param {...Repeater[]} lists
 */
export function merge(...lists) {
  /** @type {Map<string, Repeater>} */
  const byKey = new Map();
  for (const list of lists) for (const r of list) {
    const key = `${baseCall(r.callsign)}@${Math.round(r.userRx * 200)}`;
    const e = byKey.get(key);
    byKey.set(key, !e ? r : {
      ...e,
      modes: [...new Set([...e.modes, ...r.modes])],
      requiredCtcss: e.requiredCtcss === "-" ? r.requiredCtcss : e.requiredCtcss,
      qth: e.qth.trim() ? e.qth : r.qth,
      note: [...new Set([e.note, r.note].filter((n) => n.trim()))].join(" • "),
      dmrColorCode: e.dmrColorCode.trim() ? e.dmrColorCode : r.dmrColorCode,
      status: e.status === "working" || r.status === "working" ? "working" : e.status,
      source: e.source.split(" + ").includes(r.source) ? e.source : `${e.source} + ${r.source}`,
    });
  }
  return [...byKey.values()];
}

export const FETCH_RADIUS_KM = 210;

/** Prostokąt współrzędnych wokół punktu. @param {number} lat @param {number} lon @param {number} radiusKm */
export function geoBox(lat, lon, radiusKm) {
  const dLat = radiusKm / 111;
  const dLon = radiusKm / (111 * Math.max(Math.cos((lat * Math.PI) / 180), 0.1));
  return { north: lat + dLat, south: lat - dLat, east: lon + dLon, west: lon - dLon };
}
export const boxContains = (b, lat, lon) => lat >= b.south && lat <= b.north && lon >= b.west && lon <= b.east;

/** Kafelki o boku [step]° (repeatermap.de zwraca szczegóły tylko dla małych obszarów). */
export function boxTiles(b, step) {
  const result = [];
  for (let s = b.south; s < b.north;) {
    const n = Math.min(s + step, b.north);
    for (let w = b.west; w < b.east;) {
      const e = Math.min(w + step, b.east);
      result.push({ north: n, south: s, east: e, west: w });
      w = e;
    }
    s = n;
  }
  return result;
}

/**
 * Jeden przemiennik ze wszystkimi częstotliwościami: grupa po znaku i położeniu (~1 km); kolejność jak pierwszych wpisów.
 * @param {Repeater[]} repeaters
 */
export function groupRepeaters(repeaters) {
  /** @type {Map<string, Repeater[]>} */
  const map = new Map();
  for (const r of repeaters) {
    const key = `${r.callsign.toUpperCase()}@${Math.round(r.latitude * 100)}/${Math.round(r.longitude * 100)}`;
    map.set(key, [...(map.get(key) ?? []), r]);
  }
  return [...map.values()].map((channels) => {
    const sorted = [...channels].sort((a, b) => a.userRx - b.userRx);
    return {
      channels: sorted, callsign: sorted[0].callsign, qth: sorted.map((c) => c.qth).find((q) => q.trim()) ?? "",
      distanceKm: Math.min(...sorted.map((c) => c.distanceKm)), isWorking: sorted.some((c) => c.status === "working"), listKey: listKey(sorted[0]),
    };
  });
}

/** @typedef {ReturnType<typeof groupRepeaters>[number]} RepeaterGroup */

const R = 6371;
function distanceKm(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180;
  const a = Math.sin(((lat2 - lat1) * r) / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(((lon2 - lon1) * r) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(Math.min(1, a)));
}

/**
 * Lista ekranu i najbliższy przemiennik dla strony głównej (RepeatersViewModel.buildList).
 * @param {Repeater[]} database @param {{lat: number, lon: number} | null} pos
 * @param {{radiusKm: number, band: string, mode: string, onlyWorking: boolean}} f
 */
export function buildRepeaterList(database, pos, f) {
  const withDistance = database.flatMap((rep) => {
    if (pos && (rep.latitude === 0 || rep.longitude === 0)) return [];
    return [{ ...rep, distanceKm: pos ? Math.round(distanceKm(pos.lat, pos.lon, rep.latitude, rep.longitude) * 10) / 10 : 0 }];
  });
  let nearest = null;
  if (pos && withDistance.length) {
    const closest = withDistance.reduce((a, b) => (b.distanceKm < a.distanceKm ? b : a));
    nearest = groupRepeaters(withDistance.filter((r) => r.callsign.toLowerCase() === closest.callsign.toLowerCase()))
      .find((g) => g.channels.some((c) => listKey(c) === listKey(closest))) ?? null;
  }
  const bandOk = (rx) => ({ "10M": rx >= 28 && rx <= 29.7, "6M": rx >= 50 && rx <= 54, "2M": rx >= 144 && rx <= 148,
    "70CM": rx >= 420 && rx <= 450, "23CM": rx >= 1240 && rx <= 1300 })[f.band] ?? true;
  const has = (rep, ...needles) => rep.modes.some((m) => needles.some((n) => m.toUpperCase().includes(n)));
  const modeOk = (rep) => ({ FM: has(rep, "FM", "ANALOG"), DMR: has(rep, "DMR"), C4FM: has(rep, "C4FM", "FUSION"), DSTAR: has(rep, "DSTAR", "D-STAR") })[f.mode] ?? true;
  const seen = new Set();
  const filtered = withDistance.filter((rep) => (!pos || rep.distanceKm <= f.radiusKm) && bandOk(rep.userRx) && modeOk(rep) &&
    (!f.onlyWorking || rep.status === "working"))
    .filter((r) => { const k = listKey(r); if (seen.has(k)) return false; seen.add(k); return true; })
    .sort((a, b) => a.distanceKm - b.distanceKm);
  return { groups: groupRepeaters(filtered), nearest };
}
