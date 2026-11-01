// @ts-check
// Licencja CEPT T/R 61-01 za granicą (data/intl/CeptLicence.kt): prefiks gościa i warunki dla kraju pobytu.

export const CEPT_SOURCE = "CEPT T/R 61-01 (Aneks 2: 02.2024, Aneks 4: 10.2024)";

/** @typedef {{prefixes: string[], cept: boolean, licences: string, note: string | null, suffix: boolean}} CeptCountry */
/** @typedef {{iso: string, adminArea?: string, position?: {lat: number, lon: number} | null}} CountryInfo */

/** @param {string[]} prefixes @param {string} licences @param {{cept?: boolean, note?: string, suffix?: boolean}} [o] @returns {CeptCountry} */
const c = (prefixes, licences, o = {}) => ({ prefixes, cept: o.cept ?? true, licences, note: o.note ?? null, suffix: o.suffix ?? false });
const HAREC = "HAREC, klasy 1 i 2";
const NON = { cept: false };

/** @type {Record<string, CeptCountry>} */
const COUNTRIES = {
  // Aneks 2 – kraje CEPT
  AL: c(["ZA"], "CEPT"), AT: c(["OE"], "1 (dawniej także 2)"), BE: c(["ON"], "A"), BA: c(["E7"], "CEPT 1"), BG: c(["LZ"], "Class 1"),
  HR: c(["9A"], "CEPT"), CY: c(["5B"], "Radioamateur Authorisation"), CZ: c(["OK"], "A"), DK: c(["OZ"], "A"), FO: c(["OY"], "A"),
  GL: c(["OX"], "A"), EE: c(["ES"], "A i B", { note: "cept_note_ee" }), FI: c(["OH"], "L, P, T, Y"), AX: c(["OH0"], "L, P, T, Y"),
  FR: c(["F"], HAREC), GP: c(["FG"], HAREC), GF: c(["FY"], HAREC), MQ: c(["FM"], HAREC), BL: c(["FJ"], HAREC), PM: c(["FP"], HAREC),
  MF: c(["FS"], HAREC), RE: c(["FR"], HAREC), YT: c(["FH"], HAREC), TF: c(["FT"], HAREC), PF: c(["FO"], HAREC), NC: c(["FK"], HAREC),
  WF: c(["FW"], HAREC), GE: c(["4L"], "A, Extra"), DE: c(["DL"], "1, 2 i A"), GR: c(["SV"], "1"),
  HU: c(["HA", "HG"], "CEPT; dawniej RB, RC, UB, UC"), IS: c(["TF"], "G"), IE: c(["EI", "EJ"], "1 i 2", { note: "cept_note_ie" }),
  IT: c(["I"], "A"), LV: c(["YL"], "A", { note: "cept_note_lv" }), LI: c(["HB0"], "CEPT"), LT: c(["LY"], "A"), LU: c(["LX"], "CEPT"),
  MK: c(["Z3"], "A i P"), MD: c(["ER"], "A i B"), MT: c(["9H"], "wszystkie"), MC: c(["3A"], "General"), ME: c(["4O"], "A i N"),
  NL: c(["PA"], "F"), NO: c(["LA"], "A"), SJ: c(["JW"], "A"), PL: c(["SP"], "1"), PT: c(["CT7"], "1, A i B"), RO: c(["YO"], "I i II"),
  RS: c(["YU"], "1"), SK: c(["OM"], "E (dawniej A, B, C)"), SI: c(["S5"], "A (dawniej 1, 2, 3)"), ES: c(["EA"], "A"),
  SE: c(["SM", "SA"], "wszystkie"), CH: c(["HB9"], "1, 2, CEPT"), TR: c(["TA"], "A"), UA: c(["UT"], "A (dawniej 1 i 2)"),
  GB: c(["M"], "FULL"), IM: c(["MD"], "FULL"), JE: c(["MJ"], "FULL"), GG: c(["MU"], "FULL"),
  // Aneks 4 – kraje spoza CEPT stosujące zalecenie
  AU: c(["VK"], "Advanced", { ...NON, note: "cept_note_au" }),
  CA: c(["VE3"], "Basic i Advanced; gość: uprawnienia Advanced", NON), KY: c(["ZF"], "A", NON),
  IL: c(["4X", "4Z"], "A, B, C; gość: B (General)", NON), AW: c(["P4"], "A, B, C; gość: A", NON), CW: c(["PJ2"], "A, B, C; gość: A", NON),
  BQ: c(["PJ4", "PJ5", "PJ6"], "A, B, C; gość: A", { ...NON, note: "cept_note_bq" }), SX: c(["PJ7"], "A, B, C; gość: A", NON),
  NZ: c(["ZL"], "General", { ...NON, note: "cept_note_nz" }), PE: c(["OA"], "", { ...NON, suffix: true, note: "cept_note_pe" }),
  ZA: c(["ZS"], "Restricted i Unrestricted; gość: Unrestricted", NON), US: c(["W"], "Amateur Extra i Advanced; gość: Amateur Extra", NON),
};

/** @type {Record<string, string>} */
const US_STATES = {
  Alabama: "W4", Alaska: "KL7", "American Samoa": "KH8", Arizona: "W7", Arkansas: "W5", California: "W6", Colorado: "W0",
  "Northern Mariana Islands": "KH0", "Puerto Rico": "KP4", Connecticut: "W1", Delaware: "W3", "District of Columbia": "W3", Florida: "W4",
  Georgia: "W4", Guam: "KH2", Hawaii: "KH6", Idaho: "W7", Illinois: "W9", Indiana: "W9", Iowa: "W0", Kansas: "W0", Kentucky: "W4",
  Louisiana: "W5", Maine: "W1", Maryland: "W3", Massachusetts: "W1", Michigan: "W8", Minnesota: "W0", Mississippi: "W5", Missouri: "W0",
  Montana: "W7", Nebraska: "W0", Nevada: "W7", "New Hampshire": "W1", "New Jersey": "W2", "New Mexico": "W5", "New York": "W2",
  "North Carolina": "W4", "North Dakota": "W0", Ohio: "W8", Oklahoma: "W5", Oregon: "W7", Pennsylvania: "W3", "Rhode Island": "W1",
  "South Carolina": "W4", "South Dakota": "W0", Tennessee: "W4", Texas: "W5", Utah: "W7", Vermont: "W1", "U.S. Virgin Islands": "KP2",
  Virginia: "W4", Washington: "W7", "West Virginia": "W8", Wisconsin: "W9", Wyoming: "W7",
};
/** @type {Record<string, string>} */
const US_TERRITORIES = { PR: "KP4", VI: "KP2", GU: "KH2", AS: "KH8", MP: "KH0" };
/** @type {Record<string, string>} */
const CANADA = {
  "Nova Scotia": "VE1", Quebec: "VE2", "Québec": "VE2", Ontario: "VE3", Manitoba: "VE4", Saskatchewan: "VE5", Alberta: "VE6",
  "British Columbia": "VE7", "Northwest Territories": "VE8", "New Brunswick": "VE9", "Newfoundland and Labrador": "VO1", Nunavut: "VY0",
  Yukon: "VY1", "Prince Edward Island": "VY2",
};
/** @type {Record<string, string>} */
const UK = { England: "M", Scotland: "MM", Wales: "MW", "Northern Ireland": "MI" };

/** @param {string} iso */
export const ceptFind = (iso) => COUNTRIES[iso] ?? (US_TERRITORIES[iso] ? COUNTRIES.US : null);

/** Prefiks gościa (z regionami i terytoriami) albo null. @param {CountryInfo} country */
export function ceptPrefixFor(country) {
  const { iso } = country;
  if (US_TERRITORIES[iso]) return US_TERRITORIES[iso];
  const entry = COUNTRIES[iso];
  if (!entry) return null;
  const area = country.adminArea ?? "";
  const pos = country.position;
  switch (iso) {
    case "US": return US_STATES[area] ?? "W";
    case "CA": return CANADA[area] ?? Object.entries(CANADA).find(([k]) => area.toLowerCase().includes(k.toLowerCase()))?.[1] ?? "VE";
    case "GB": return UK[area] ?? "M";
    case "PT": return pos && pos.lon < -24 ? "CT8" : pos && pos.lat < 34 && pos.lon < -15 ? "CT9" : "CT7";
    case "FR": return pos && pos.lat >= 41.3 && pos.lat <= 43.1 && pos.lon >= 8.5 && pos.lon <= 9.6 ? "TK" : "F";
    default: return entry.prefixes[0];
  }
}

/** @param {CountryInfo} country @param {string} ownCall */
export function ceptAdvice(country, ownCall) {
  const cept = ceptFind(country.iso);
  const prefix = ceptPrefixFor(country);
  const call = ownCall.trim().toUpperCase();
  const own = call && call !== "NOCALL" ? call : null;
  const callsign = !prefix || !own ? null : country.iso === "PL" ? own : cept?.suffix ? `${own}/${prefix}` : `${prefix}/${own}`;
  return { cept, prefix, callsign };
}

/** Kody krajów do ręcznego wyboru. */
export const ceptCountryCodes = () => [...Object.keys(COUNTRIES), ...Object.keys(US_TERRITORIES)];

/** Flaga z kodu ISO. @param {string} iso */
export const flagOf = (iso) => [...iso.toUpperCase()].map((ch) => String.fromCodePoint(0x1f1e6 + ch.charCodeAt(0) - 65)).join("");

/** Opis licencji (zapisany po polsku) w języku interfejsu. @param {string} raw @param {(k: string) => string} t */
export const localizedLicences = (raw, t) => raw.replace("dawniej także", t("cept_formerly_also")).replace("dawniej", t("cept_formerly"))
  .replace("gość", t("cept_guest")).replace("uprawnienia", t("cept_privileges")).replace("klasy", t("cept_classes")).replace(/ i /g, ` ${t("cept_and")} `);
