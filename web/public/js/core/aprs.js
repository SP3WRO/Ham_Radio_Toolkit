// @ts-check
// APRS (AprsParser.kt, AprsClient.kt): pakiety pozycji – zwykłe, skompresowane, Mic-E – z PHG, wysokością i pogodą,
// passcode APRS-IS i formatowanie własnej pozycji. Używane w przeglądarce i na serwerze (most do APRS-IS).

const KNOTS = 1.852, MPH = 1.60934, HUNDREDTHS_INCH_MM = 0.254, FEET = 0.3048;
/** @type {Record<string, number>} */
const WX_LEN = { c: 3, s: 3, g: 3, t: 3, r: 3, p: 3, P: 3, h: 2, b: 5, L: 3, l: 3, "#": 3 };

/**
 * @typedef {{callsign: string, latitude: number, longitude: number, comment: string, table: string, symbol: string, timestampMs: number,
 *   overlay: string | null, distanceKm?: number, speedKmh?: number, courseDegrees?: number, altitudeM?: number, powerW?: number,
 *   antennaHeightM?: number, antennaGainDb?: number, directivityDeg?: number, tempCelsius?: number, windSpeedKmh?: number,
 *   windGustKmh?: number, windDirDeg?: number, humidityPercent?: number, pressureHpa?: number, rain1hMm?: number, rain24hMm?: number,
 *   rainMidnightMm?: number}} AprsPacket
 */

/** Passcode APRS-IS (hash znaku bez SSID). @param {string} call */
/** Kod symbolu: tabela (/, \\ albo nakładka: cyfra lub wielka litera) i znak symbolu. @param {string} code */
export const isValidSymbolCode = (code) => code.length === 2 && /^[/\\0-9A-Z]$/.test(code[0]) && code.charCodeAt(1) >= 33 && code.charCodeAt(1) <= 126;

/** @param {string} call */
export function aprsPasscode(call) {
  const c = call.toUpperCase().split("-")[0];
  let hash = 0x73e2;
  for (let i = 0; i < c.length; i += 2) {
    hash ^= c.charCodeAt(i) << 8;
    if (i + 1 < c.length) hash ^= c.charCodeAt(i + 1);
  }
  return hash & 0x7fff;
}

/** @param {string} s */
const base91 = (s) => [...s].reduce((acc, ch) => acc * 91 + (ch.charCodeAt(0) - 33), 0);
/** @param {string} c */
const isDigit = (c) => c >= "0" && c <= "9";

/** @param {string} raw @param {number} degDigits @param {string} neg */
function coord(raw, degDigits, neg) {
  const d = raw.slice(0, degDigits), m = raw.slice(degDigits, degDigits + 5);
  if (!/^\d+$/.test(d) || !/^\d{2}\.\d{2}$/.test(m.replace(/ /g, "0"))) return null;
  const v = Number(d) + Number(m.replace(/ /g, "0")) / 60;
  return raw[raw.length - 1] === neg ? -v : v;
}

/** @returns {AprsPacket | null} */
function newPacket(callsign, lat, lon, tableChar, symbol, ts) {
  if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) return null;
  const overlay = /^[A-Z0-9]$/.test(tableChar) ? tableChar : null;
  return { callsign, latitude: lat, longitude: lon, comment: "", table: tableChar === "/" ? "/" : "\\", symbol, timestampMs: ts, overlay };
}

/** Parser linii z APRS-IS; null dla pakietów bez pozycji. @param {string} line @param {number} [ts] */
export function parseAprs(line, ts = Date.now()) {
  const gt = line.indexOf(">");
  if (gt <= 0) return null;
  const callsign = line.slice(0, gt);
  const colon = line.indexOf(":");
  if (colon < 0) return null;
  const info = line.slice(colon + 1);
  if (!info) return null;
  if (info[0] === "`" || info[0] === "'") {
    const dest = line.slice(gt + 1, colon).split(",")[0].split("-")[0];
    return parseMicE(callsign, dest, info, ts);
  }
  const posStart = info[0] === "=" || info[0] === "!" ? 1 : info[0] === "@" || info[0] === "/" ? 8 : -1;
  if (posStart < 0 || info.length <= posStart) return null;
  /** @type {AprsPacket | null} */
  let p;
  let comment;
  if (isDigit(info[posStart])) {
    if (info.length < posStart + 19) return null;
    const lat = coord(info.slice(posStart, posStart + 8), 2, "S");
    const lon = coord(info.slice(posStart + 9, posStart + 18), 3, "W");
    if (lat == null || lon == null) return null;
    p = newPacket(callsign, lat, lon, info[posStart + 8], info[posStart + 18], ts);
    if (!p) return null;
    comment = info.slice(posStart + 19);
    const ds = /^([0-9.]{3})\/([0-9.]{3})/.exec(comment);
    if (ds) {
      const dir = /^\d+$/.test(ds[1]) ? Number(ds[1]) : null, spd = /^\d+$/.test(ds[2]) ? Number(ds[2]) : null;
      if (p.symbol === "_") {
        if (dir != null) p.windDirDeg = dir;
        if (spd != null) p.windSpeedKmh = spd * MPH;
        comment = comment.slice(7);
      } else if (dir != null && spd != null) {
        p.courseDegrees = dir;
        p.speedKmh = spd * KNOTS;
        comment = comment.slice(7);
      }
    }
  } else {
    p = parseCompressed(callsign, info, posStart, ts);
    if (!p) return null;
    comment = info.slice(posStart + 13);
  }
  return finish(p, comment);
}

/** @param {string} callsign @param {string} info @param {number} at @param {number} ts */
function parseCompressed(callsign, info, at, ts) {
  if (info.length < at + 13) return null;
  const d = info.slice(at, at + 13);
  if ([...d.slice(1, 9)].some((c) => c.charCodeAt(0) < 33 || c.charCodeAt(0) > 123)) return null;
  let table = d[0];
  if (!"/\\".includes(table) && !/^[A-Z]$/.test(table) && !/^[a-j]$/.test(table)) return null;
  if (/^[a-j]$/.test(table)) table = String(table.charCodeAt(0) - 97);
  const p = newPacket(callsign, 90 - base91(d.slice(1, 5)) / 380926, -180 + base91(d.slice(5, 9)) / 190463, table, d[9], ts);
  if (!p) return null;
  const c = d.charCodeAt(10), s = d.charCodeAt(11), type = d.charCodeAt(12) - 33;
  if (d[10] !== " " && s >= 33) {
    if (type >= 0 && (type & 0x18) === 0x10) p.altitudeM = Math.pow(1.002, (c - 33) * 91 + (s - 33)) * FEET;
    else if (c - 33 >= 0 && c - 33 <= 89) {
      const course = (c - 33) * 4, knots = Math.pow(1.08, s - 33) - 1;
      if (p.symbol === "_") { p.windDirDeg = course; p.windSpeedKmh = knots * KNOTS; }
      else { if (course > 0) p.courseDegrees = course; p.speedKmh = knots * KNOTS; }
    }
  }
  return p;
}

/** @param {string} callsign @param {string} dest @param {string} info @param {number} ts */
function parseMicE(callsign, dest, info, ts) {
  if (dest.length < 6 || info.length < 9) return null;
  const digits = [];
  for (let i = 0; i < 6; i++) {
    const ch = dest[i];
    if (isDigit(ch)) digits.push(ch);
    else if (ch >= "A" && ch <= "J") digits.push(String(ch.charCodeAt(0) - 65));
    else if (ch >= "P" && ch <= "Y") digits.push(String(ch.charCodeAt(0) - 80));
    else if ("KLZ".includes(ch)) digits.push("0");
    else return null;
  }
  const flag = (/** @type {number} */ i) => dest[i] >= "P" && dest[i] <= "Z";
  const latDeg = Number(digits.slice(0, 2).join("")), latMin = Number(digits.slice(2, 4).join("")) + Number(digits.slice(4, 6).join("")) / 100;
  if (latDeg > 90 || latMin >= 60) return null;
  const lat = (latDeg + latMin / 60) * (flag(3) ? 1 : -1);
  let lonDeg = info.charCodeAt(1) - 28;
  if (flag(4)) lonDeg += 100;
  if (lonDeg >= 180 && lonDeg <= 189) lonDeg -= 80; else if (lonDeg >= 190 && lonDeg <= 199) lonDeg -= 190;
  let lonMin = info.charCodeAt(2) - 28;
  if (lonMin >= 60) lonMin -= 60;
  const lonH = info.charCodeAt(3) - 28;
  if (lonDeg < 0 || lonDeg > 180 || lonMin < 0 || lonMin > 59 || lonH < 0 || lonH > 99) return null;
  const lon = (lonDeg + (lonMin + lonH / 100) / 60) * (flag(5) ? -1 : 1);
  const p = newPacket(callsign, lat, lon, info[8], info[7], ts);
  if (!p) return null;
  const sp = info.charCodeAt(4) - 28, dc = info.charCodeAt(5) - 28, se = info.charCodeAt(6) - 28;
  let knots = sp * 10 + Math.floor(dc / 10), course = (dc % 10) * 100 + se;
  if (knots >= 800) knots -= 800;
  if (course >= 400) course -= 400;
  if (knots >= 1 && knots <= 799) p.speedKmh = knots * KNOTS;
  if (course >= 1 && course <= 360) p.courseDegrees = course % 360;
  let comment = info.slice(9);
  const alt = /^[>\]`']?([!-{]{3})\}/.exec(comment);
  if (alt) { p.altitudeM = base91(alt[1]) - 10000; comment = comment.slice(alt[0].length); }
  else if (comment && ">]`'".includes(comment[0])) comment = comment.slice(1);
  comment = comment.replace(/(_[ "#$%&()*+,\-./0-9:;<=>?@A-Z[\]^_`{|}~]|[=^])$/, "");
  return finish(p, comment);
}

/** PHG, wysokość "/A=", pogoda. @param {AprsPacket} p @param {string} comment */
function finish(p, comment) {
  if (comment.startsWith("PHG") && comment.length >= 7 && /^\d{4}$/.test(comment.slice(3, 7))) {
    const [pw, h, g, d] = [...comment.slice(3, 7)].map(Number);
    p.powerW = pw * pw;
    p.antennaHeightM = 10 * Math.pow(2, h) * FEET;
    p.antennaGainDb = g;
    p.directivityDeg = d * 45;
    comment = comment.slice(7);
  }
  const alt = /\/A=(-?\d{6})/.exec(comment);
  if (alt) { p.altitudeM = Number(alt[1]) * FEET; comment = comment.slice(0, alt.index) + comment.slice(alt.index + alt[0].length); }
  if (p.symbol === "_") comment = weather(comment, p);
  p.comment = comment.trim();
  return p;
}

/** @param {string} comment @param {AprsPacket} p */
function weather(comment, p) {
  let i = 0;
  while (i < comment.length) {
    const key = comment[i], len = WX_LEN[key];
    if (!len || i + 1 + len > comment.length) break;
    const raw = comment.slice(i + 1, i + 1 + len);
    if (!/^[\d. -]+$/.test(raw)) break;
    const t = raw.trim();
    if (/^-?\d+$/.test(t)) {
      const v = Number(t);
      switch (key) {
        case "c": p.windDirDeg = v; break;
        case "s": p.windSpeedKmh = v * MPH; break;
        case "g": p.windGustKmh = v * MPH; break;
        case "t": p.tempCelsius = ((v - 32) * 5) / 9; break;
        case "r": p.rain1hMm = v * HUNDREDTHS_INCH_MM; break;
        case "p": p.rain24hMm = v * HUNDREDTHS_INCH_MM; break;
        case "P": p.rainMidnightMm = v * HUNDREDTHS_INCH_MM; break;
        case "h": p.humidityPercent = v === 0 ? 100 : v; break;
        case "b": p.pressureHpa = v / 10; break;
      }
    }
    i += 1 + len;
  }
  return comment.slice(i);
}

/** Stopnie → stopnie i setne minuty (zaokrąglone przed podziałem – 59,996' nie daje "60.00"). @param {number} v */
function degMin(v) {
  const h = Math.round(Math.abs(v) * 6000);
  return [Math.floor(h / 6000), (h % 6000) / 100];
}
/** @param {number} lat */
export function aprsLat(lat) {
  const [d, m] = degMin(lat);
  return `${String(d).padStart(2, "0")}${m.toFixed(2).padStart(5, "0")}${lat >= 0 ? "N" : "S"}`;
}
/** @param {number} lon */
export function aprsLon(lon) {
  const [d, m] = degMin(lon);
  return `${String(d).padStart(3, "0")}${m.toFixed(2).padStart(5, "0")}${lon >= 0 ? "E" : "W"}`;
}

/** Znak z SSID (np. "-7" albo "7"). @param {string} base @param {string} ssid */
export function aprsCallWithSsid(base, ssid) {
  const s = ssid.trim();
  if (!s || s === "0" || s === "-0") return base;
  return s.startsWith("-") ? `${base}${s}` : `${base}-${s}`;
}

/** Ramka pozycji do APRS-IS. @param {{call: string, ssid: string, icon: string, comment: string, lat: number, lon: number}} o */
export function aprsBeacon(o) {
  const table = o.icon[0] ?? "/", symbol = o.icon[1] ?? ">";
  const comment = o.comment.replace(/[\r\n]/g, " ").slice(0, 43);
  return `${aprsCallWithSsid(o.call, o.ssid)}>APRS,TCPIP*:=${aprsLat(o.lat)}${table}${aprsLon(o.lon)}${symbol}${comment}`;
}

/**
 * Pozycje stacji usłyszanych w APRS-IS (ostatnie 2 h, najwyżej 2000) – wyszukiwarka znaków bierze z nich lokator
 * (AprsHeardStations.kt).
 */
export const aprsHeard = {
  /** @type {Map<string, {callsign: string, lat: number, lon: number, timeMs: number}>} */
  stations: new Map(),
  /** @param {string} callsign @param {number} lat @param {number} lon @param {number} [timeMs] */
  record(callsign, lat, lon, timeMs = Date.now()) {
    if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) || (lat === 0 && lon === 0)) return;
    const call = callsign.trim().toUpperCase();
    if (!call) return;
    this.stations.delete(call);
    this.stations.set(call, { callsign: call, lat, lon, timeMs });
    // Map pamięta kolejność dodania – najstarsze wypadają pierwsze
    while (this.stations.size > 2000) this.stations.delete(/** @type {string} */ (this.stations.keys().next().value));
  },
  /** Najświeższa pozycja znaku (dowolne SSID) z ostatnich 2 h. @param {string} callsign @param {number} [now] */
  find(callsign, now = Date.now()) {
    const call = callsign.trim().toUpperCase();
    if (!call) return null;
    let best = null;
    for (const s of this.stations.values()) {
      if ((s.callsign === call || s.callsign.startsWith(`${call}-`)) && now - s.timeMs < 2 * 3_600_000 && (!best || s.timeMs > best.timeMs)) best = s;
    }
    return best;
  },
};
