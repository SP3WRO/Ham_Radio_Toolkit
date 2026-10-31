// @ts-check
// Model QSO, ADIF (eksport/import), statystyki logu i baza DXCC cty.csv – port QsoEntry.kt, utils/AdifGenerator.kt,
// utils/AdifParser.kt, utils/LogStatistics.kt i data/dxcc/CtyDatabase.kt
import { calculateBandFromFreq, parseNum, Callsigns } from "./radio.js";

/**
 * @typedef {{
 *  id: string, callsign: string, datetimeUtc: string, frequency: string, band: string, mode: string,
 *  rstSent: string, rstRcvd: string, comment: string, name: string, qth: string, gridSquare: string,
 *  dxcc: number | null, country: string, huntedRef: string, qrzUploaded: boolean, eqslUploaded: boolean
 * }} QsoEntry
 */

export const APP_VERSION = "0.1.0-web";

/** @returns {string} */
export function newId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** Wpis z domyślnymi wartościami pól. @param {Partial<QsoEntry>} p @returns {QsoEntry} */
export function makeQso(p) {
  return {
    id: p.id ?? newId(), callsign: p.callsign ?? "", datetimeUtc: p.datetimeUtc ?? "", frequency: p.frequency ?? "",
    band: p.band ?? "", mode: p.mode ?? "", rstSent: p.rstSent ?? "", rstRcvd: p.rstRcvd ?? "", comment: p.comment ?? "",
    name: p.name ?? "", qth: p.qth ?? "", gridSquare: p.gridSquare ?? "", dxcc: p.dxcc ?? null, country: p.country ?? "",
    huntedRef: p.huntedRef ?? "", qrzUploaded: p.qrzUploaded ?? false, eqslUploaded: p.eqslUploaded ?? false,
  };
}

const POTA_REF = /^[A-Z0-9]{1,4}-\d{4,5}$/;
const WWFF_REF = /^[A-Z0-9]{1,4}FF-\d{4}$/;

// ---------------------------------------------------------------- eksport ADIF
// Emisje, które w ADIF są podtrybami innej emisji
const SUBMODES = { FT4: "MFSK", JS8: "MFSK", Q65: "MFSK", FST4: "MFSK", PSK31: "PSK", PSK63: "PSK", PSK125: "PSK", USB: "SSB", LSB: "SSB" };

// Plik .adi dopuszcza tylko ASCII, dlatego polskie znaki zamieniamy na ich odpowiedniki bez ogonków
export function toAscii(value) {
  const withoutL = String(value).replace(/ł/g, "l").replace(/Ł/g, "L");
  return withoutL.normalize("NFD").replace(/\p{Mn}+/gu, "")
    .split("").map((ch) => { const c = ch.charCodeAt(0); return c >= 32 && c <= 126 ? ch : " "; }).join("");
}

// Długość pola liczona z wartości po konwersji, więc nie może się rozjechać z treścią
function field(name, value) {
  const ascii = toAscii(value);
  return `<${name}:${ascii.length}>${ascii} `;
}

export const Adif = {
  VERSION: "3.1.4",

  header() {
    return "Ham Radio Toolkit ADIF Export\n" + field("ADIF_VER", this.VERSION) + "\n" +
      field("PROGRAMID", "Ham Radio Toolkit") + "\n" + field("PROGRAMVERSION", APP_VERSION) + "\n<EOH>\n";
  },

  /** Jeden rekord zakończony <EOR>. @param {QsoEntry} qso @param {string} [stationCallsign] */
  record(qso, stationCallsign) {
    let s = "";
    const [datePart = "", timePart = ""] = qso.datetimeUtc.split(" ").map((x, i) => (i === 0 ? x.replace(/-/g, "") : x.replace(/:/g, "")));
    s += field("CALL", qso.callsign.toUpperCase());
    if (datePart.length === 8) s += field("QSO_DATE", datePart);
    if (timePart.length >= 4) s += field("TIME_ON", timePart.slice(0, 4));
    const khz = parseNum(qso.frequency);
    if (khz != null && khz > 0) s += field("FREQ", (khz / 1000).toFixed(4));
    s += field("BAND", qso.band.toLowerCase());
    const mode = qso.mode.toUpperCase();
    const parent = SUBMODES[mode];
    if (parent) s += field("MODE", parent) + field("SUBMODE", mode);
    else s += field("MODE", mode);
    if (qso.rstSent) s += field("RST_SENT", qso.rstSent);
    if (qso.rstRcvd) s += field("RST_RCVD", qso.rstRcvd);
    if (qso.name) s += field("NAME", qso.name);
    if (qso.qth) s += field("QTH", qso.qth);
    if (qso.gridSquare) s += field("GRIDSQUARE", qso.gridSquare.toUpperCase());
    if (qso.dxcc != null) s += field("DXCC", String(qso.dxcc));
    if (qso.country) s += field("COUNTRY", qso.country);
    const ref = qso.huntedRef.toUpperCase();
    if (WWFF_REF.test(ref)) s += field("WWFF_REF", ref) + field("SIG", "WWFF") + field("SIG_INFO", ref);
    else if (POTA_REF.test(ref)) s += field("POTA_REF", ref) + field("SIG", "POTA") + field("SIG_INFO", ref);
    if (qso.comment) s += field("COMMENT", qso.comment);
    if (stationCallsign && stationCallsign.trim() && stationCallsign !== "NOCALL") s += field("STATION_CALLSIGN", stationCallsign.toUpperCase());
    return s + "<EOR>";
  },

  /** @param {QsoEntry[]} entries @param {string} [stationCallsign] */
  generate(entries, stationCallsign) {
    return this.header() + "\n" + entries.map((q) => this.record(q, stationCallsign) + "\n").join("");
  },

  // ---------------------------------------------------------------- import ADIF (ADIF 2/3, długość pola w znakach)
  /** @param {string} text @returns {Record<string, string>[]} */
  parseRecords(text) {
    const eoh = /<eoh>/i.exec(text);
    let pos = !text.trimStart().startsWith("<") && eoh ? eoh.index + eoh[0].length : 0;
    const records = [];
    /** @type {Record<string, string>} */
    let current = {};
    for (;;) {
      const open = text.indexOf("<", pos);
      if (open === -1) break;
      const close = text.indexOf(">", open);
      if (close === -1) break;
      const parts = text.slice(open + 1, close).split(":");
      const name = parts[0].trim().toUpperCase();
      pos = close + 1;
      if (name === "EOR") {
        if (Object.keys(current).length) records.push(current);
        current = {};
      } else if (name === "EOH") {
        current = {};
      } else if (parts.length >= 2) {
        const len = parseInt(parts[1].trim(), 10);
        if (!Number.isFinite(len) || !/^\d+$/.test(parts[1].trim())) continue;
        const end = Math.min(pos + len, text.length);
        current[name] = text.slice(pos, end);
        pos = end;
      }
    }
    return records;
  },

  /** @param {string} text @returns {{entries: QsoEntry[], skipped: number}} */
  parse(text) {
    const entries = [];
    let skipped = 0;
    for (const f of this.parseRecords(text)) {
      const q = toQso(f);
      if (q) entries.push(q); else skipped++;
    }
    return { entries, skipped };
  },
};

function formatKhz(khz) {
  return khz.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
}

/** @param {Record<string, string>} f @returns {QsoEntry | null} */
function toQso(f) {
  const g = (k) => (f[k] ?? "").trim();
  const call = g("CALL").toUpperCase();
  const date = g("QSO_DATE"), time = g("TIME_ON");
  if (!call || date.length !== 8 || time.length < 4) return null;
  const datetime = `${date.slice(0, 4)}-${date.slice(4, 6)}-${date.slice(6, 8)} ${time.slice(0, 2)}:${time.slice(2, 4)}`;
  const mhz = parseNum(g("FREQ"));
  const freqKhz = mhz != null ? formatKhz(mhz * 1000) : "";
  let band = g("BAND").toLowerCase();
  if (!band) {
    if (!freqKhz) return null;
    band = calculateBandFromFreq(freqKhz);
  }
  // FT4 zapisane jako MODE=MFSK SUBMODE=FT4 pokazujemy jako FT4
  const mode = g("MODE").toUpperCase(), submode = g("SUBMODE").toUpperCase();
  const displayMode = mode === "SSB" ? "SSB" : submode || mode;
  if (!displayMode) return null;
  const sig = g("SIG").toUpperCase();
  const refRaw = f.POTA_REF ?? f.WWFF_REF ?? (sig === "POTA" || sig === "WWFF" ? f.SIG_INFO : undefined);
  const dxcc = parseInt(g("DXCC"), 10);
  return makeQso({
    callsign: call, datetimeUtc: datetime, frequency: freqKhz, band, mode: displayMode,
    rstSent: g("RST_SENT"), rstRcvd: g("RST_RCVD"), comment: (f.COMMENT ?? f.NOTES ?? "").trim(),
    name: g("NAME"), qth: g("QTH"), gridSquare: g("GRIDSQUARE").toUpperCase(),
    dxcc: Number.isFinite(dxcc) ? dxcc : null, country: g("COUNTRY"),
    huntedRef: (refRaw ?? "").trim().toUpperCase(),
    qrzUploaded: g("QRZCOM_QSO_UPLOAD_STATUS").toUpperCase() === "Y",
    eqslUploaded: g("EQSL_QSL_SENT").toUpperCase() === "Y",
  });
}

// ---------------------------------------------------------------- statystyki
export const LogStatistics = {
  BAND_ORDER: ["160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm", "23cm"],
  MODE_GROUPS: ["CW", "PHONE", "DIGI"],

  // Grupy emisji jak w dyplomach DXCC: CW / fonia / emisje cyfrowe
  modeGroup(mode) {
    const m = String(mode).toUpperCase();
    if (m === "CW") return "CW";
    return ["SSB", "USB", "LSB", "AM", "FM", "DMR", "C4FM", "DSTAR", "D-STAR"].includes(m) ? "PHONE" : "DIGI";
  },

  /** @param {QsoEntry[]} logs */
  compute(logs) {
    const withDxcc = logs.filter((q) => q.dxcc != null);
    /** @type {Map<number, QsoEntry[]>} */
    const byDxcc = new Map();
    for (const q of withDxcc) byDxcc.set(/** @type {number} */ (q.dxcc), [...(byDxcc.get(/** @type {number} */ (q.dxcc)) ?? []), q]);
    const entities = [...byDxcc].map(([dxcc, qsos]) => ({
      dxcc,
      name: qsos.find((q) => q.country.trim())?.country ?? `DXCC ${dxcc}`,
      qsoCount: qsos.length,
      bands: new Set(qsos.map((q) => q.band.toLowerCase())),
      modes: new Set(qsos.map((q) => this.modeGroup(q.mode))),
    })).sort((x, y) => (x.name < y.name ? -1 : x.name > y.name ? 1 : 0));

    /** @type {Record<string, Record<string, number>>} */
    const bandModeCounts = {};
    for (const q of logs) {
      const b = q.band.toLowerCase(), g = this.modeGroup(q.mode);
      (bandModeCounts[b] ??= {})[g] = (bandModeCounts[b][g] ?? 0) + 1;
    }
    /** @type {Record<string, Set<number>>} */
    const perBand = {};
    for (const q of withDxcc) (perBand[q.band.toLowerCase()] ??= new Set()).add(/** @type {number} */ (q.dxcc));
    const dxccPerBand = Object.fromEntries(Object.entries(perBand).map(([b, s]) => [b, s.size]));

    const refs = logs.map((q) => q.huntedRef.toUpperCase()).filter((r) => r.trim());
    return {
      totalQso: logs.length,
      uniqueCalls: new Set(logs.map((q) => Callsigns.baseCall(q.callsign))).size,
      dxccCount: entities.length,
      entities,
      bandModeCounts,
      dxccPerBand,
      // Wzorzec POTA (XX-1234) pasuje też do WWFF (XXFF-1234), więc WWFF wykluczamy jawnie
      potaRefs: new Set(refs.filter((r) => POTA_REF.test(r) && !WWFF_REF.test(r))),
      wwffRefs: new Set(refs.filter((r) => WWFF_REF.test(r))),
      withoutDxcc: logs.length - withDxcc.length,
    };
  },
};

/** Wyszukiwanie w logu: każde słowo musi pasować do któregoś pola, np. "SP 40m CW". @param {QsoEntry[]} list @param {string} query */
export function filterLogs(list, query) {
  const terms = query.trim().split(/\s+/).filter(Boolean);
  if (!terms.length) return list;
  const has = (s, t) => s.toLowerCase().includes(t.toLowerCase());
  const eq = (s, t) => s.toLowerCase() === t.toLowerCase();
  return list.filter((q) => terms.every((t) =>
    has(q.callsign, t) || eq(q.band, t) || eq(q.mode, t) || has(q.country, t) ||
    q.gridSquare.toLowerCase().startsWith(t.toLowerCase()) || has(q.huntedRef, t) || has(q.name, t) ||
    has(q.qth, t) || has(q.comment, t) || q.datetimeUtc.startsWith(t)));
}

// ---------------------------------------------------------------- DXCC (cty.csv, AD1C)
/**
 * @typedef {{name: string, dxcc: number, primaryPrefix: string, continent: string, cqZone: number, ituZone: number,
 *   lat: number, lon: number, utcOffsetHours: number}} DxccEntity
 */
const IGNORED_SUFFIXES = new Set(["P", "M", "QRP", "QRPP", "A", "B", "LH", "R", "T", "J", "LGT"]);
const OVERRIDE = /\((\d+)\)|\[(\d+)\]|<([-\d.]+)\/([-\d.]+)>|\{(\w+)\}|~([-\d.]+)~/g;

export class CtyDatabase {
  /** @param {Map<string, DxccEntity>} byPrefix @param {Map<string, DxccEntity>} byExact @param {DxccEntity[]} entities */
  constructor(byPrefix, byExact, entities) {
    this.byPrefix = byPrefix;
    this.byExact = byExact;
    this.entities = entities;
    this.maxPrefixLength = Math.max(0, ...[...byPrefix.keys()].map((k) => k.length));
  }

  /** @param {string} callsign @returns {DxccEntity | null} */
  resolve(callsign) {
    const call = String(callsign).trim().toUpperCase();
    if (!call) return null;
    const exact = this.byExact.get(call);
    if (exact) return exact;
    const parts = call.split("/").filter(Boolean);
    const meaningful = parts.filter((p) => !IGNORED_SUFFIXES.has(p));
    if (parts.some((p) => p === "MM" || p === "AM")) return null; // morski / lotniczy mobilny – poza DXCC
    let lookup;
    if (meaningful.length === 0) return null;
    if (meaningful.length === 1) {
      const e = this.byExact.get(meaningful[0]);
      if (e) return e;
      lookup = meaningful[0];
    } else {
      const [x, y] = meaningful;
      const isDigit = (s) => s.length === 1 && s >= "0" && s <= "9";
      if (isDigit(y)) lookup = replaceAreaDigit(x, y);       // W1AW/4 -> W4AW
      else if (isDigit(x)) lookup = replaceAreaDigit(y, x);
      else lookup = y.length < x.length ? y : x;             // SP/DL1ABC, DL1ABC/SP – prefiksem jest krótsza część
    }
    for (let len = Math.min(lookup.length, this.maxPrefixLength); len >= 1; len--) {
      const e = this.byPrefix.get(lookup.slice(0, len));
      if (e) return e;
    }
    return null;
  }

  /** @param {string} csv */
  static parse(csv) {
    const byPrefix = new Map(), byExact = new Map(), entities = [];
    for (const raw of csv.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;
      const fields = splitLimit(line, ",", 10);
      if (fields.length < 10 || fields[0].startsWith("*")) continue; // "*" – obszary WAE, nie kraje DXCC
      const dxcc = parseInt(fields[2].trim(), 10);
      if (!Number.isFinite(dxcc)) continue;
      /** @type {DxccEntity} */
      const base = {
        name: fields[1].trim(), dxcc, primaryPrefix: fields[0].trim(), continent: fields[3].trim(),
        cqZone: parseInt(fields[4], 10) || 0, ituZone: parseInt(fields[5], 10) || 0,
        lat: Number(fields[6]) || 0, lon: -(Number(fields[7]) || 0),
        // W cty.csv strefa ma znak odwrotny (dodatnia na zachód), jak długość geograficzna
        utcOffsetHours: -(Number(fields[8]) || 0),
      };
      entities.push(base);
      for (const token of fields[9].replace(/;+$/, "").split(" ")) {
        if (!token.trim()) continue;
        const entity = applyOverrides(base, token);
        const key = token.replace(OVERRIDE, "").trim();
        if (key.startsWith("=")) byExact.set(key.slice(1), entity);
        else if (key) byPrefix.set(key, entity);
      }
    }
    return new CtyDatabase(byPrefix, byExact, entities);
  }
}

function splitLimit(s, sep, limit) {
  const out = [];
  let rest = s;
  while (out.length < limit - 1) {
    const i = rest.indexOf(sep);
    if (i < 0) break;
    out.push(rest.slice(0, i));
    rest = rest.slice(i + 1);
  }
  out.push(rest);
  return out;
}

function replaceAreaDigit(call, digit) {
  const i = call.search(/\d/);
  return i === -1 ? call + digit : call.slice(0, i) + digit + call.slice(i + 1);
}

/** @param {DxccEntity} base @param {string} token */
function applyOverrides(base, token) {
  let e = base;
  for (const m of token.matchAll(OVERRIDE)) {
    if (m[1]) e = { ...e, cqZone: Number(m[1]) };
    else if (m[2]) e = { ...e, ituZone: Number(m[2]) };
    else if (m[3]) e = { ...e, lat: Number(m[3]), lon: -Number(m[4]) };
    else if (m[5]) e = { ...e, continent: m[5] };
    else if (m[6]) e = { ...e, utcOffsetHours: -Number(m[6]) };
  }
  return e;
}
