// @ts-check
// Region IARU, obrys Polski, częstotliwości regionalne, tłumaczenie opisów bandplanu, dane słoneczne hamqsl.com
// i położenie Słońca – port data/geo/CountryResolver.kt, RegionalBandplans.kt (RegionalInfo), BandplanText.kt,
// SolarXmlParser.kt i GrayLineScreen.kt

/** @typedef {import("./geo.js").LatLon} LatLon */

// Ameryki, Grenlandia i Hawaje
const REGION2 = new Set(("US CA MX GL PM BM GT BZ SV HN NI CR PA CU JM HT DO PR VI VG AI KN AG MS GP DM MQ LC VC BB GD TT AW CW " +
  "BQ SX MF BL KY TC BS CO VE GY SR GF EC PE BO BR PY UY AR CL FK GS").split(" "));
// Azja Południowa, Wschodnia i Południowo-Wschodnia, Oceania (Iran i Afganistan też należą do R3)
const REGION3 = new Set(("AU NZ JP CN TW HK MO KR KP IN PK BD LK NP BT MV AF IR ID MY SG TH VN LA KH MM PH BN TL PG FJ SB VU NC " +
  "PF WS AS TO KI TV NR FM MH PW GU MP CK NU WF NF IO").split(" "));
const CARIBBEAN = new Set("CU JM HT DO PR VI VG AI KN AG MS GP DM MQ LC VC BB GD TT AW CW BQ SX MF BL KY TC BS".split(" "));

/** Region IARU (= region ITU) – decyduje o bandplanie i częstotliwościach APRS. */
export const IaruRegion = {
  /** @param {string | null | undefined} iso @returns {1|2|3|null} */
  forCountry(iso) {
    if (!iso) return null;
    if (REGION2.has(iso)) return 2;
    if (REGION3.has(iso)) return 3;
    return 1;
  },
  /** Gdy kraju nie znamy – zgrubnie z położenia. @param {LatLon} p @returns {1|2|3} */
  forPosition(p) {
    if (p.lon < -140 && p.lat < 0) return 3; // wyspy Pacyfiku na wschód od linii zmiany daty
    if (p.lon < -30) return 2;
    if (p.lat >= 50) return 1; // Rosja i Azja Środkowa należą do R1
    if (p.lat > 35 && p.lon < 75) return 1;
    if (p.lon > 60) return 3;
    return 1;
  },
};

/** Uproszczony obrys granic Polski (ok. 5–10 km dokładności) – do decyzji "w kraju / za granicą". */
const POLAND = [
  [53.92, 14.22], [54.18, 15.57], [54.76, 17.55], [54.83, 18.33], [54.6, 18.8], [54.36, 19.62], [54.42, 21.0], [54.36, 22.79],
  [54.0, 23.4], [53.9, 23.51], [53.1, 23.9], [52.7, 23.94], [52.08, 23.65], [51.6, 23.62], [50.85, 24.1], [50.4, 24.05],
  [49.8, 22.7], [49.0, 22.89], [49.42, 21.0], [49.22, 20.1], [49.4, 19.5], [49.52, 18.85], [49.95, 18.3], [50.3, 17.7],
  [50.2, 16.9], [50.62, 16.2], [50.87, 14.82], [51.4, 14.97], [51.9, 14.7], [52.6, 14.55], [53.0, 14.4], [53.4, 14.4],
];
/** @param {LatLon} p */
export function inPoland(p) {
  let inside = false;
  for (let i = 0, j = POLAND.length - 1; i < POLAND.length; j = i++) {
    const [yi, xi] = POLAND[i], [yj, xj] = POLAND[j];
    if (yi > p.lat !== yj > p.lat && p.lon < ((xj - xi) * (p.lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** Częstotliwości APRS, wywoławcze FM i kryzysowe dla regionu. @param {1|2|3} region @param {string | null} [iso] */
export function regionalInfo(region, iso) {
  if (region === 2) {
    return {
      aprs: "144.390 MHz" + (iso && CARIBBEAN.has(iso) ? " (Karaiby: chroniona też 145.010)" : ""),
      fmCalling: "146.520 MHz (2 m), 29.600 MHz (10 m)",
      emergency: "3.750, 3.985, 7.060, 7.240, 7.275, 14.300, 18.160, 21.360 MHz",
      source: "IARU Region 2 Band Plan (09.2020)",
    };
  }
  if (region === 3) {
    let aprs = "144.800 MHz (zalecana; część krajów używa innej – sprawdź krajowy bandplan)";
    if (iso && ["MY", "TH", "SG", "ID"].includes(iso)) aprs = "144.390 MHz";
    if (iso && ["CN", "TW", "HK"].includes(iso)) aprs = "144.640 MHz";
    return { aprs, fmCalling: "29.600 MHz (10 m); na UKF wg planów krajowych", emergency: "3.600, 7.110, 14.300, 18.160, 21.360 MHz", source: "IARU Region 3, R3-004 Rev. 2 (11.2024)" };
  }
  return { aprs: "144.800 MHz", fmCalling: "145.500 MHz (2 m), 433.500 MHz (70 cm)", emergency: "3.760, 7.110, 14.300, 18.160, 21.360 MHz", source: "IARU Region 1" };
}

/**
 * Tłumaczenie tekstów zapisanych po polsku z powtarzających się zwrotów: w innych językach podmieniamy zwroty
 * na tłumaczenia; częstotliwości, nazwy emisji i skróty zostają bez zmian.
 */
export class PhraseLocalizer {
  /** @param {[string, string][]} phrases zwrot → klucz tłumaczenia @param {(key: string) => string} translate @param {string} language */
  constructor(phrases, translate, language) {
    this.language = language;
    this.translate = translate;
    const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
    // Dłuższe zwroty przed krótszymi, które w nich występują
    this.patterns = [...phrases].sort((x, y) => y[0].length - x[0].length)
      // Bez lookbehind (Safari < 16.4): znak przed zwrotem w grupie 1, zwrot w grupie 2
      .map(([phrase, key]) => /** @type {[RegExp, string]} */ ([new RegExp(`(^|[^\\p{L}])(${esc(phrase)}|${esc(cap(phrase))})(?!\\p{L})`, "gu"), key]));
  }

  /** @param {string} text */
  localize(text) {
    if (this.language === "pl" || !text) return text;
    let result = text;
    for (const [re, key] of this.patterns) {
      re.lastIndex = 0;
      if (!re.test(result)) continue;
      const translated = this.translate(key);
      re.lastIndex = 0;
      result = result.replace(re, (_m, pre, m) => pre + (m.charAt(0) !== m.charAt(0).toLowerCase() ? translated.charAt(0).toUpperCase() + translated.slice(1) : translated));
    }
    return result;
  }
}

// ---------------------------------------------------------------- hamqsl.com solarxml.php
/**
 * @typedef {{updated: string, solarFlux: string, aIndex: string, kIndex: string, sunspots: string,
 *   hfConditions: {name: string, time: string, condition: string}[]}} SolarData
 */
/** @param {string} xml @returns {SolarData} */
export function parseSolarXml(xml) {
  const tag = (name) => {
    const m = new RegExp(`<${name}>\\s*([^<]*?)\\s*</${name}>`, "i").exec(xml);
    return m ? decode(m[1]) : "";
  };
  const hfConditions = [];
  for (const m of xml.matchAll(/<band\s+([^>]*)>\s*([^<]*?)\s*<\/band>/gi)) {
    const name = /name="([^"]*)"/.exec(m[1])?.[1] ?? "";
    const time = /time="([^"]*)"/.exec(m[1])?.[1] ?? "";
    if (name) hfConditions.push({ name, time, condition: decode(m[2]) });
  }
  return { updated: tag("updated"), solarFlux: tag("solarflux"), aIndex: tag("aindex"), kIndex: tag("kindex"), sunspots: tag("sunspots"), hfConditions };
}
function decode(s) {
  return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&");
}

// ---------------------------------------------------------------- Słońce i szarówka (jak GrayLineScreen.kt)
/** @param {Date} now @param {number} lat @param {number} lon */
export function sunInfo(now, lat, lon) {
  const start = Date.UTC(now.getUTCFullYear(), 0, 1);
  const dayOfYear = Math.floor((now.getTime() - start) / 86400000) + 1;
  const hour = now.getUTCHours(), minute = now.getUTCMinutes(), second = now.getUTCSeconds();
  const g = (2 * Math.PI * (dayOfYear - 1 + (hour - 12) / 24)) / 365.25;
  const decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) +
    0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
  const eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
  const utcHour = hour + minute / 60 + second / 3600;
  const solarLng = -((utcHour - 12) * 15 + eqTime / 4);
  const sunLon = ((((solarLng + 180) % 360) + 360) % 360) - 180;
  // Zmierzch cywilny: Słońce 6° pod horyzontem
  const zenith = ((90 + 6) * Math.PI) / 180;
  const latRad = (lat * Math.PI) / 180;
  const cosH = (Math.cos(zenith) - Math.sin(latRad) * Math.sin(decl)) / (Math.cos(latRad) * Math.cos(decl));
  const hasEvent = cosH >= -1 && cosH <= 1;
  const hAngle = hasEvent ? (Math.acos(cosH) * 180) / Math.PI : 0;
  const noon = 12 - lon / 15 - eqTime / 60;
  return { declinationRad: decl, declinationDeg: (decl * 180) / Math.PI, sunLon, hasEvent, sunriseUtc: noon - hAngle / 15, sunsetUtc: noon + hAngle / 15 };
}

/** "HH:MM UTC" z godziny dziesiętnej. @param {number} decimalHour */
export function formatDecimalHour(decimalHour) {
  const h = ((decimalHour % 24) + 24) % 24;
  const hh = Math.floor(h), mm = Math.floor((h - hh) * 60);
  return `${String(hh).padStart(2, "0")}:${String(Math.abs(mm)).padStart(2, "0")} UTC`;
}
