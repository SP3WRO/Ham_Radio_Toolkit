// @ts-check
// Kalendarz zawodów (data/contests/*.kt): parsery SP9CXN, PK-UKF i QRZ.RU, łączenie źródeł, zawody cykliczne
// oraz zapasowy kalendarz wbudowany. Parsery działają na serwerze, kalendarz wbudowany – w przeglądarce.

export const HOUR_MS = 3_600_000;
export const DAY_MS = 86_400_000;

/**
 * @typedef {{name: string, startMs: number, endMs: number, source: string, polish: boolean, vhf: boolean, modes: string[],
 *   bands: string, originalName: string | null, rulesUrl: string | null, rulesPlUrl: string | null, homepage: string | null,
 *   organizer: string, exchange: string, logDeadline: string, notes: string[], sources: string[]}} ContestEntry
 */

export const CONTEST_SOURCES = {
  SP9CXN: { label: "SP9CXN", pageUrl: "https://sp9cxn.pzk.pl/kalendarz.html" },
  PK_UKF: { label: "PK-UKF", pageUrl: "https://pk-ukf.pl/" },
  QRZ_RU: { label: "QRZ.RU", pageUrl: "https://www.qrz.ru/contest/" },
  BUILTIN: { label: "", pageUrl: "" },
};

/** @param {Partial<ContestEntry> & {name: string, startMs: number, endMs: number, source: string}} p @returns {ContestEntry} */
export function makeContest(p) {
  return {
    polish: false, vhf: false, modes: [], bands: "", originalName: null, rulesUrl: null, rulesPlUrl: null, homepage: null,
    organizer: "", exchange: "", logDeadline: "", notes: [], ...p, sources: p.sources ?? [p.source],
  };
}

/** @param {ContestEntry} c */
export const contestKey = (c) => `${c.source}_${c.startMs}_${c.name}`;

// ---------------------------------------------------------------- daty (UTC)
/** @param {number} year @param {number} month 1–12 */
export function lengthOfMonth(year, month) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}
/** @param {number} y @param {number} m 1–12 @param {number} d */
export const dayMs = (y, m, d) => Date.UTC(y, m - 1, d);

// ---------------------------------------------------------------- wspólne narzędzia tekstu
const NAMED = { "&nbsp;": " ", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&apos;": "'", "&ndash;": "–", "&mdash;": "—", "&laquo;": "«", "&raquo;": "»", "&oacute;": "ó", "&Oacute;": "Ó" };

/** @param {string} s */
export function decodeEntities(s) {
  let out = s.replace(/&#(x?)([0-9a-fA-F]+);/g, (m, hex, num) => {
    const code = parseInt(num, hex ? 16 : 10);
    return Number.isFinite(code) && code <= 0x10ffff ? String.fromCodePoint(code) : m;
  });
  for (const [e, v] of Object.entries(NAMED)) out = out.split(e).join(v);
  return out.split("&amp;").join("&");
}

/** Tekst komórki HTML. @param {string} html */
export const htmlText = (html) => decodeEntities(html.replace(/<br\s*\/?>/gi, " ").replace(/<[^>]+>/g, "")).replace(/\s+/g, " ").trim();
/** @param {string} s */
export const dashes = (s) => s.replace(/[–—−]/g, "-");

const CW_RE = /\bCW\b|telegraf|Telegraphy|телеграф/i;
const SSB_RE = /\bSSB\b|\bFM\b|\bAM\b|fonia|\bphone\b|телефон/i;
const RTTY_RE = /RTTY/i;
const DIGI_RE = /DIGI|\bFT[48]\b|FT8|FT4|\bPSK|\bMFSK|\bJT\d|Hell|\bOLIVIA\b/i;
/** @param {string} text @returns {string[]} */
export function modesIn(text) {
  const out = [];
  if (CW_RE.test(text)) out.push("CW");
  if (SSB_RE.test(text)) out.push("SSB");
  if (RTTY_RE.test(text)) out.push("RTTY");
  if (DIGI_RE.test(text)) out.push("DIGI");
  return out;
}
const POLISH_RE = /\bSP ?DX\b|\bSP\d\b|\bSP[ -]?CW\b|\bSPAC\b|Poland|Polish|\bPZK\b|PK[ -]?UKF/i;
/** @param {string} s */
export const looksPolish = (s) => POLISH_RE.test(s);
const VHF_RE = /\b(VHF|UHF|SHF|UKF|EME)\b|\bSPAC\b|\d\s*(MHz|GHz)/i;
/** @param {string} s */
export const looksVhf = (s) => VHF_RE.test(s);
/** Koniec 16:59 / 23:59 = pełna godzina. @param {number} endMs */
export const roundEnd = (endMs) => (Math.floor(endMs / 60_000) % 60 === 59 ? endMs + 60_000 : endMs);
/** @param {string[]} a @param {string[]} b */
const union = (a, b) => [...new Set([...a, ...b])];

const ROW = /<tr\b[^>]*>([\s\S]*?)<\/tr>/gi;
const CELL = /<td\b[^>]*>([\s\S]*?)<\/td>/gi;
/** @param {string} row */
const cellsOf = (row) => [...row.matchAll(CELL)].map((m) => m[1]);

// ---------------------------------------------------------------- SP9CXN
const SP_BASE = "https://sp9cxn.pzk.pl/";
/** @param {string} href */
function spAbsolute(href) {
  const url = decodeEntities(href.trim());
  if (!url || url.startsWith("#") || url.startsWith("mailto:")) return null;
  return /^https?:\/\//.test(url) ? url : SP_BASE + url.replace(/^\.\//, "").replace(/^\//, "");
}
/** @param {string} dateCell @param {string} timeCell */
function spDateTime(dateCell, timeCell) {
  const d = /^(\d{1,2})\.(\d{1,2})\.(\d{4})$/.exec(htmlText(dateCell));
  const t = /^(\d{1,2})[:.](\d{2})$/.exec(htmlText(timeCell));
  if (!d || !t) return null;
  const [day, month, year] = [Number(d[1]), Number(d[2]), Number(d[3])];
  if (month < 1 || month > 12 || day < 1 || day > lengthOfMonth(year, month)) return null;
  return dayMs(year, month, day) + (Number(t[1]) * 60 + Number(t[2])) * 60_000;
}

/** Notatki to klucze tłumaczeń (ct_note_*). @param {string} html @returns {ContestEntry[]} */
export function parseSp9cxn(html) {
  const headings = [...html.matchAll(/KALENDARZ\s+ZAWOD\S*\s+(KRAJOWYCH|MI\S*DZYNARODOWYCH)/gi)].map((m) => ({ at: m.index ?? 0, domestic: m[1].toUpperCase().startsWith("K") }));
  /** @type {ContestEntry[]} */
  const result = [];
  for (const table of html.matchAll(/<table\b[\s\S]*?<\/table>/gi)) {
    const at = table.index ?? 0;
    const heading = headings.filter((x) => x.at < at).pop();
    if (!heading) continue;
    for (const row of table[0].matchAll(ROW)) {
      const e = spRow(cellsOf(row[1]), heading.domestic);
      if (e) result.push(e);
    }
  }
  return result.sort((a, b) => a.startMs - b.startMs);
}

/** @param {string[]} cells @param {boolean} domestic */
function spRow(cells, domestic) {
  if (cells.length < 5) return null;
  const start = spDateTime(cells[0], cells[1]);
  let end = spDateTime(cells[cells.length - 2], cells[cells.length - 1]);
  if (start == null || end == null) return null;
  // Literówki w dacie końca – godzina końca z dnia startu
  if (end <= start || end - start > 14 * DAY_MS) {
    const mod = (/** @type {number} */ x) => ((x % DAY_MS) + DAY_MS) % DAY_MS;
    end = start - mod(start) + mod(end);
    if (end <= start) end += DAY_MS;
  }
  const nameCell = cells[2];
  const links = [...nameCell.matchAll(/<a\b[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi)];
  const cellText = htmlText(nameCell);
  let name = (links[0] && htmlText(links[0][2])) || cellText;
  const notes = [];
  if (cellText.includes("??")) notes.push("ct_note_unconfirmed");
  if (cellText.trimEnd().endsWith("*") || /\s\*(\s|$)/.test(cellText)) notes.push("ct_note_old_rules");
  if (/UA\s*i\s*EU/.test(cellText)) notes.push("ct_note_ru_by");
  name = name.split("??").join("").replace(/UA\s*i\s*EU/g, "").replace(/[*\s]+$/, "").replace(/\s+/g, " ").trim();
  if (!name) return null;
  const plLink = cells.length >= 6 ? /<a\b[^>]*href="([^"]*)"/i.exec(cells[3]) : null;
  const vhf = looksVhf(name);
  return makeContest({
    name, startMs: start, endMs: roundEnd(end), source: "SP9CXN", polish: domestic || looksPolish(name), vhf,
    modes: [...modesIn(`${name} ${cellText}`), ...(vhf ? ["VHF"] : [])],
    rulesUrl: links[0] ? spAbsolute(links[0][1]) : null, rulesPlUrl: plLink ? spAbsolute(plLink[1]) : null, notes,
  });
}

// ---------------------------------------------------------------- PK-UKF
export const pkUkfUrl = (/** @type {number} */ year) => `https://pk-ukf.pl/kalendarz-zawodow-${year}/`;
const PL_MONTHS = ["sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru"];
/** @param {string} name */
const plMonth = (name) => { const i = PL_MONTHS.indexOf(name.toLowerCase().slice(0, 3)); return i < 0 ? null : i + 1; };
/** @param {string} cell */
function pkLink(cell) {
  const m = /<a\b[^>]*href="([^"]*)"/i.exec(cell);
  const url = m ? decodeEntities(m[1]).trim() : "";
  return url.startsWith("http") ? url : null;
}

/** @param {string} html @returns {ContestEntry[]} */
export function parsePkUkf(html) {
  /** @type {ContestEntry[]} */
  const out = [];
  for (const row of html.matchAll(ROW)) {
    const e = pkRow(cellsOf(row[1]));
    if (e) out.push(e);
  }
  return out.sort((a, b) => a.startMs - b.startMs);
}

/** @param {string[]} cells */
function pkRow(cells) {
  if (cells.length < 9) return null;
  const d = /^(\d{1,2})(?:\s*-\s*(\d{1,2}))?\s+(\p{L}+)(?:\s*-\s*(\d{1,2})\s+(\p{L}+))?\s+(\d{4})$/u.exec(dashes(htmlText(cells[1])));
  const t = /^(\d{1,2})[:.](\d{1,2})\s*-\s*(\d{1,2})[:.](\d{1,2})$/.exec(dashes(htmlText(cells[2])));
  if (!d || !t) return null;
  const startMonth = plMonth(d[3]);
  const endMonth = d[5] ? plMonth(d[5]) : startMonth;
  if (startMonth == null || endMonth == null) return null;
  const year = Number(d[6]);
  const startYear = endMonth < startMonth ? year - 1 : year;
  const startDay = Number(d[1]);
  const endDay = Number(d[4] || d[2] || d[1]);
  if (startDay > lengthOfMonth(startYear, startMonth) || endDay > lengthOfMonth(year, endMonth)) return null;
  const start = dayMs(startYear, startMonth, startDay) + (Number(t[1]) * 60 + Number(t[2])) * 60_000;
  let end = dayMs(year, endMonth, endDay) + (Number(t[3]) * 60 + Number(t[4])) * 60_000;
  if (end <= start) end += DAY_MS;
  const name = htmlText(cells[3]);
  if (!name) return null;
  const bands = htmlText(cells[4]);
  const org = htmlText(cells[5]);
  const organizer = org === "-" || org === "–" ? "" : org;
  const deadline = htmlText(cells[8]);
  return makeContest({
    name, startMs: start, endMs: roundEnd(end), source: "PK_UKF",
    polish: /PK[ -]?UKF|PZK|\bSP\d?\b/i.test(organizer) || looksPolish(name), vhf: true,
    modes: union(modesIn(`${name} ${bands}`), ["VHF"]), bands,
    rulesUrl: pkLink(cells[7]) ?? pkLink(cells[6]), rulesPlUrl: pkLink(cells[6]), organizer,
    logDeadline: deadline === "-" || deadline === "–" ? "" : deadline,
  });
}

// ---------------------------------------------------------------- QRZ.RU (ICS)
export const QRZ_RU_ICS = "https://www.qrz.ru/contest/qrz-contest.ics";
const PERIOD = /^(.*?),?\s*проводится с (\d{2})\.(\d{2})\.(\d{4}) (\d{1,2}):(\d{2}) (UTC|MSK) по (\d{2})\.(\d{2})\.(\d{4}) (\d{1,2}):(\d{2}) (UTC|MSK)/;

/** @param {string} y @param {string} mo @param {string} d @param {string} h @param {string} mi @param {string} zone */
function ruTime(y, mo, d, h, mi, zone) {
  const [Y, M, D] = [Number(y), Number(mo), Number(d)];
  if (M < 1 || M > 12 || D < 1 || D > lengthOfMonth(Y, M)) return null;
  const ms = dayMs(Y, M, D) + (Number(h) * 60 + Number(mi)) * 60_000;
  return zone === "MSK" ? ms - 3 * HOUR_MS : ms;
}

/** @param {string} s */
export const bandsToLatin = (s) => s.split("ГГц").join("GHz").split("МГц").join("MHz").split("кГц").join("kHz")
  .replace(/(\d)\s*см(?!\p{L})/gu, "$1 cm").replace(/(\d)\s*мм(?!\p{L})/gu, "$1 mm").replace(/(\d)\s*м(?!\p{L})/gu, "$1 m").trim();

/** @param {string} ics @param {Record<string, string>} [known] znane tłumaczenia nazw rosyjskich @returns {ContestEntry[]} */
export function parseQrzRu(ics, known = {}) {
  const unfolded = ics.replace(/\r\n/g, "\n").replace(/\n[ \t]/g, "");
  /** @type {ContestEntry[]} */
  const out = [];
  for (const ev of unfolded.matchAll(/BEGIN:VEVENT([\s\S]*?)END:VEVENT/g)) {
    /** @type {Record<string, string>} */
    const p = {};
    for (const line of ev[1].split("\n")) {
      const colon = line.indexOf(":");
      if (colon <= 0) continue;
      const key = line.slice(0, colon).split(";")[0].trim().toUpperCase();
      if (!(key in p)) p[key] = line.slice(colon + 1).replace(/\\[nN]/g, "\n").replace(/\\,/g, ",").replace(/\\;/g, ";").replace(/\\\\/g, "\\");
    }
    const e = qrzEvent(p, known);
    if (e) out.push(e);
  }
  return out.sort((a, b) => a.startMs - b.startMs);
}

/** @param {Record<string, string>} p @param {Record<string, string>} known */
function qrzEvent(p, known) {
  const summary = p.SUMMARY?.trim();
  if (!summary) return null;
  const g = PERIOD.exec(summary);
  let original, start, end;
  if (g) {
    original = g[1].trim().replace(/,$/, "").trim();
    start = ruTime(g[4], g[3], g[2], g[5], g[6], g[7]);
    end = ruTime(g[10], g[9], g[8], g[11], g[12], g[13]);
  } else {
    original = summary;
    const dt = (/** @type {string | undefined} */ v) => {
      const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})/.exec(v?.trim() ?? "");
      return m ? ruTime(m[1], m[2], m[3], m[4], m[5], "UTC") : null;
    };
    start = dt(p.DTSTART);
    end = dt(p.DTEND);
  }
  if (!original || start == null || end == null) return null;
  if (end <= start) end = start + HOUR_MS;
  const loc = /Диапазон\(ы\):\s*(.*?)\.\s*Модуляция:\s*(.*?)\.?\s*$/.exec((p.LOCATION ?? "").trim());
  const bands = loc ? bandsToLatin(loc[1]) : "";
  const modulation = loc?.[2] ?? "";
  const description = p.DESCRIPTION ?? "";
  const vhf = /MHz|GHz/.test(bands) || /УКВ|VHF|UHF/i.test(original);
  const id = /^(\d+)@qrz\.ru$/.exec((p.UID ?? "").trim())?.[1];
  const russian = isCyrillic(original);
  const field = (/** @type {string} */ label) => description.split("\n").map((l) => l.trim()).find((l) => l.startsWith(`${label}:`))
    ?.slice(label.length + 1).trim() || null;
  const rules = field("Положение");
  const homepage = field("Homepage");
  return makeContest({
    name: russian ? translateRussian(original, known) : original, startMs: start, endMs: roundEnd(end), source: "QRZ_RU",
    polish: looksPolish(original), vhf, modes: [...modesIn(`${modulation} ${original}`), ...(vhf ? ["VHF"] : [])], bands,
    originalName: russian ? original : null,
    rulesUrl: rules?.startsWith("http") ? rules : id ? `https://www.qrz.ru/contest/detail/${id}.html` : null,
    homepage: homepage?.startsWith("http") ? homepage : null, logDeadline: field("Отчеты принимаются до") ?? "",
  });
}

// ---------------------------------------------------------------- nazwy rosyjskie
const CYRILLIC = /[Ѐ-ӿ]/;
/** @param {string} s */
export const isCyrillic = (s) => CYRILLIC.test(s);
const LOOKALIKES = { a: "а", c: "с", e: "е", o: "о", p: "р", x: "х", y: "у" };

/** Klucz słownika znanych nazw. @param {string} s */
export function normalizeRussian(s) {
  return s.toLowerCase().replace(/ё/g, "е").replace(/[–—]/g, "-").split(/\s+/).filter(Boolean)
    .map((w) => (isCyrillic(w) ? [...w].map((c) => LOOKALIKES[/** @type {keyof typeof LOOKALIKES} */ (c)] ?? c).join("") : w)).join(" ")
    .replace(/[.\s]+$/, "");
}

const TRANSLIT = { а: "a", б: "b", в: "v", г: "g", д: "d", е: "e", ё: "yo", ж: "zh", з: "z", и: "i", й: "y", к: "k", л: "l", м: "m", н: "n", о: "o",
  п: "p", р: "r", с: "s", т: "t", у: "u", ф: "f", х: "kh", ц: "ts", ч: "ch", ш: "sh", щ: "shch", ъ: "", ы: "y", ь: "", э: "e", ю: "yu", я: "ya" };

/** @param {string} s */
export function transliterate(s) {
  return s.split(" ").map((word) => {
    const letters = [...word].filter((c) => /\p{L}/u.test(c));
    const allUpper = word.length > 1 && letters.length > 0 && letters.every((c) => c === c.toUpperCase() && c !== c.toLowerCase());
    let out = "";
    for (const ch of word) {
      const tr = TRANSLIT[/** @type {keyof typeof TRANSLIT} */ (ch.toLowerCase())];
      if (tr == null) out += ch;
      else out += ch !== ch.toLowerCase() ? tr.charAt(0).toUpperCase() + tr.slice(1) : tr;
    }
    return allUpper && isCyrillic(word) ? out.toUpperCase() : out;
  }).join(" ");
}

/** @param {string} ru */
const word = (ru) => new RegExp(`(?<!\\p{L})(?:${ru})(?!\\p{L})`, "giu");
/** @type {[RegExp, string][]} */
const PHRASES = [
  [word("по радиосвязи на КВ|на КВ"), "HF"], [word("по радиосвязи на УКВ|на УКВ"), "VHF"], [word("УКВ"), "VHF"], [word("КВ"), "HF"],
  [/\((\d+)\s*тур\)/gu, "(round $1)"], [word("Всероссийские|Всероссийский"), "All-Russian"], [word("Открытый|Открытые|Открытое"), "Open"],
  [word("Молодежн\\p{L}*|Молодёжн\\p{L}*"), "Youth"], [word("Чемпионат|Первенство"), "Championship"], [word("Кубок|кубок"), "Cup"],
  [word("Мемориал|памяти"), "Memorial"], [word("Соревнования|соревнования|Радиосоревнования|Контест"), "Contest"], [word("Минитест"), "Mini-test"],
  [word("Спринт"), "Sprint"], [word("Марафон"), "Marathon"], [word("радиоклуба"), "Radio Club"], [word("клуба"), "Club"], [word("области"), "Oblast"],
  [word("края"), "Krai"], [word("республики"), "Republic of"], [word("РФ|России"), "Russia"], [word("телеграф"), "CW"], [word("телефон"), "SSB"],
  [word("Зима"), "Winter"], [word("Весна"), "Spring"], [word("Лето"), "Summer"], [word("Осень"), "Autumn"], [word("им\\.|имени|на"), ""], [word("и"), "&"],
];

/** @param {string} name @param {Record<string, string>} [known] klucze po normalizeRussian */
export function translateRussian(name, known = {}) {
  if (!isCyrillic(name)) return name;
  const hit = known[normalizeRussian(name)];
  if (hit) return hit;
  let s = dashes(name).replace(/ё/g, "е").replace(/Ё/g, "Е");
  for (const [re, en] of PHRASES) s = s.replace(re, en);
  s = transliterate(s);
  s = s.replace(/(\p{L}+)sk(?:oy|aya|iy|ogo|ie|ikh)(?!\p{L})/gu, "$1sk").replace(/(\p{L}+)sk Krai/gu, "$1sky Krai");
  return s.replace(/\s+-\s+/g, " – ").replace(/\s+/g, " ").trim().replace(/^[–\s]+|[–\s]+$/g, "");
}

// ---------------------------------------------------------------- łączenie źródeł
const MODE_WORDS = new Set(["cw", "ssb", "rtty", "digi", "digital", "ft8", "ft4", "psk", "psk31", "psk63", "mix", "mixed", "phone", "fonia",
  "hf", "vhf", "uhf", "shf", "mhz", "ghz", "khz", "m", "up", "wyżej", "tura", "round", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii",
  "styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień"]);
const STOP_WORDS = new Set(["contest", "zawody", "test", "the", "dx", "international", "int", "of", "and", "w", "na"]);
/** @type {Record<string, string>} */
const ALIASES = { waedc: "wae" };

/** @param {string} name */
function words(name) {
  return name.toLowerCase().split(/[^\p{L}\p{N}]+|(?<=\p{N})(?=\p{L})|(?<=\p{L})(?=\p{N})/u)
    .filter((w) => w && !/^(19|20)\d\d$/.test(w)).map((w) => ALIASES[w] ?? w).filter((w) => !MODE_WORDS.has(w));
}
/** @param {string} name */
export function contestTokens(name) {
  const all = words(name);
  const sig = new Set(all.filter((w) => !STOP_WORDS.has(w)));
  return sig.size ? sig : new Set(all);
}
/** @param {string} name */
function initials(name) {
  const s = words(name).filter((w) => /^\p{L}/u.test(w)).map((w) => w[0]).join("");
  return s.length >= 3 ? s : null;
}

/** @param {ContestEntry} a @param {ContestEntry} b @param {(n: string) => Set<string>} [tokensOf] */
export function sameContest(a, b, tokensOf = contestTokens) {
  if (Math.abs(a.startMs - b.startMs) > HOUR_MS) return false;
  const ta = tokensOf(a.name), tb = tokensOf(b.name);
  if (!ta.size || !tb.size) return false;
  const common = [...ta].filter((x) => tb.has(x)).length;
  if (common / Math.min(ta.size, tb.size) >= 0.6) return true;
  const ia = initials(a.name), ib = initials(b.name);
  return (ia != null && tb.has(ia)) || (ib != null && ta.has(ib));
}

/** Listy w kolejności ważności; duplikaty uzupełniają pierwszy wpis. @param {ContestEntry[][]} lists */
export function mergeContests(lists) {
  /** @type {ContestEntry[]} */
  const result = [];
  /** @type {Map<string, Set<string>>} */
  const cache = new Map();
  const tokensOf = (/** @type {string} */ n) => { let s = cache.get(n); if (!s) cache.set(n, (s = contestTokens(n))); return s; };
  for (const list of lists) {
    for (const e of list) {
      const i = result.findIndex((x) => x.source !== e.source && sameContest(x, e, tokensOf));
      if (i < 0) { result.push(e); continue; }
      const b = result[i];
      result[i] = {
        ...b, polish: b.polish || e.polish, vhf: b.vhf || e.vhf, modes: union(b.modes, e.modes), bands: b.bands || e.bands,
        originalName: b.originalName ?? e.originalName, rulesUrl: b.rulesUrl ?? e.rulesUrl, rulesPlUrl: b.rulesPlUrl ?? e.rulesPlUrl,
        homepage: b.homepage ?? e.homepage, organizer: b.organizer || e.organizer, logDeadline: b.logDeadline || e.logDeadline,
        notes: union(b.notes, e.notes), sources: union(b.sources, e.sources),
      };
    }
  }
  return result.sort((a, b) => a.startMs - b.startMs);
}

/** Nazwy (małymi literami) zawodów co tydzień: ≥ 3 terminy, ≥ 2 odstępy ≤ 8 dni. @param {ContestEntry[]} entries */
export function cyclicNames(entries) {
  /** @type {Map<string, number[]>} */
  const groups = new Map();
  for (const e of entries) {
    const k = e.name.toLowerCase();
    groups.set(k, [...(groups.get(k) ?? []), e.startMs]);
  }
  const out = new Set();
  for (const [k, starts] of groups) {
    if (starts.length < 3) continue;
    starts.sort((a, b) => a - b);
    let close = 0;
    for (let i = 1; i < starts.length; i++) if (starts[i] - starts[i - 1] <= 8 * DAY_MS) close++;
    if (close >= 2) out.add(k);
  }
  return out;
}

// ---------------------------------------------------------------- kalendarz wbudowany (ContestCalendar.kt)
const SAT = 0, FRI_22 = -120;
const CWT_EX = "Imię + numer członkowski (lub kraj)";
const CWT_URL = "https://cwops.org/cwops-tests/";
/** id, nazwa, emisje, pasma, [miesiąc, weekend, przesunięcie min, godziny] albo [dzień tyg. 0=nd, minuta, czas min], wymiana, regulamin, polskie */
/** @type {[string, string, string[], string, number[], string, string | null, boolean?][]} */
const BUILTIN = [
  ["arrl-rtty-ru", "ARRL RTTY Roundup", ["RTTY", "DIGI"], "80–10 m", [1, 1, 18 * 60, 30], "RST + numer kolejny (W/VE: RST + stan/prowincja)", "https://www.arrl.org/rtty-roundup"],
  ["ha-dx", "HA DX Contest", ["CW", "SSB"], "160–10 m", [1, 3, 12 * 60, 24], "RS(T) + numer kolejny (HA: kod komitatu)", null],
  ["cq160-cw", "CQ WW 160-Meter Contest CW", ["CW"], "160 m", [1, -1, FRI_22, 48], "", "https://cq160.com"],
  ["cqwpx-rtty", "CQ WW WPX RTTY", ["RTTY"], "80–10 m", [2, 2, SAT, 48], "RST + numer kolejny", "https://www.cqwpxrtty.com"],
  ["arrl-dx-cw", "ARRL International DX CW", ["CW"], "160–10 m", [2, 3, SAT, 48], "DX: RST + moc nadajnika; łączności tylko z W/VE", "https://www.arrl.org/arrl-dx"],
  ["cq160-ssb", "CQ WW 160-Meter Contest SSB", ["SSB"], "160 m", [2, -1, FRI_22, 48], "", "https://cq160.com"],
  ["arrl-dx-ssb", "ARRL International DX SSB", ["SSB"], "160–10 m", [3, 1, SAT, 48], "DX: RS + moc nadajnika; łączności tylko z W/VE", "https://www.arrl.org/arrl-dx"],
  ["rdxc", "Russian DX Contest", ["CW", "SSB"], "160–10 m", [3, 3, 12 * 60, 24], "RS(T) + numer kolejny (UA: kod obwodu)", "https://www.rdxc.org"],
  ["cqwpx-ssb", "CQ WW WPX SSB", ["SSB"], "160–10 m", [3, -1, SAT, 48], "RS + numer kolejny", "https://www.cqwpx.com"],
  ["spdx", "SP DX Contest", ["CW", "SSB"], "160–10 m", [4, 1, 15 * 60, 24], "RS(T) + numer kolejny; stacje SP: RS(T) + litera województwa", null, true],
  ["spdx-rtty", "SP DX RTTY Contest", ["RTTY"], "80–10 m", [4, -1, 12 * 60, 24], "RST + numer kolejny; stacje SP: RST + litera województwa", null, true],
  ["cqwpx-cw", "CQ WW WPX CW", ["CW"], "160–10 m", [5, -1, SAT, 48], "RST + numer kolejny", "https://www.cqwpx.com"],
  ["aa-cw", "All Asian DX Contest CW", ["CW"], "160–10 m", [6, 3, SAT, 48], "RST + wiek operatora; łączności tylko z Azją", null],
  ["iaru-hf", "IARU HF World Championship", ["CW", "SSB"], "160–10 m", [7, 2, 12 * 60, 24], "RS(T) + strefa ITU (stacje HQ: skrót stowarzyszenia, np. PZK)", "https://www.arrl.org/iaru-hf-championship"],
  ["cqww-vhf", "CQ WW VHF Contest", ["VHF", "CW", "SSB", "DIGI"], "6 m, 2 m", [7, 3, 18 * 60, 27], "Lokator (4 znaki)", null],
  ["wae-cw", "WAE DX Contest CW", ["CW"], "80–10 m", [8, 2, SAT, 48], "RST + numer kolejny; stacje europejskie pracują z pozaeuropejskimi (QTC)", null],
  ["euhfc", "EU HF Championship", ["CW", "SSB"], "160–10 m", [8, 2, 12 * 60, 12], "RS(T) + dwie ostatnie cyfry roku pierwszej licencji", null],
  ["aa-ssb", "All Asian DX Contest SSB", ["SSB"], "160–10 m", [9, 1, SAT, 48], "RS + wiek operatora; łączności tylko z Azją", null],
  ["iaru-r1-vhf", "IARU Region 1 VHF Contest", ["VHF", "CW", "SSB"], "2 m", [9, 1, 14 * 60, 24], "RS(T) + numer kolejny + lokator (6 znaków)", null],
  ["wae-ssb", "WAE DX Contest SSB", ["SSB"], "80–10 m", [9, 2, SAT, 48], "RS + numer kolejny; stacje europejskie pracują z pozaeuropejskimi (QTC)", null],
  ["cqww-rtty", "CQ WW RTTY DX Contest", ["RTTY"], "80–10 m", [9, -1, SAT, 48], "RST + strefa CQ (W/VE także stan/prowincja)", "https://www.cqwwrtty.com"],
  ["iaru-r1-uhf", "IARU Region 1 UHF/Microwave Contest", ["VHF", "CW", "SSB"], "70 cm i wyżej", [10, 1, 14 * 60, 24], "RS(T) + numer kolejny + lokator (6 znaków)", null],
  ["cqww-ssb", "CQ WW DX Contest SSB", ["SSB"], "160–10 m", [10, -1, SAT, 48], "RS + strefa CQ (SP = 15)", "https://www.cqww.com"],
  ["wae-rtty", "WAE DX Contest RTTY", ["RTTY"], "80–10 m", [11, 2, SAT, 48], "RST + numer kolejny (QTC między wszystkimi stacjami)", null],
  ["okom-cw", "OK/OM DX Contest CW", ["CW"], "160–10 m", [11, 2, 12 * 60, 24], "RST + numer kolejny (OK/OM: kod powiatu)", null],
  ["cqww-cw", "CQ WW DX Contest CW", ["CW"], "160–10 m", [11, -1, SAT, 48], "RST + strefa CQ (SP = 15)", "https://www.cqww.com"],
  ["arrl-160", "ARRL 160-Meter Contest", ["CW"], "160 m", [12, 1, FRI_22, 42], "DX: RST; łączności tylko z W/VE", "https://www.arrl.org/160-meter"],
  ["arrl-10", "ARRL 10-Meter Contest", ["CW", "SSB"], "10 m", [12, 2, SAT, 48], "RS(T) + numer kolejny (W/VE: stan/prowincja)", "https://www.arrl.org/10-meter"],
  ["cwt-wed13", "CWops Test (CWT)", ["CW"], "160–10 m", [3, 13 * 60, 60], CWT_EX, CWT_URL],
  ["cwt-wed19", "CWops Test (CWT)", ["CW"], "160–10 m", [3, 19 * 60, 60], CWT_EX, CWT_URL],
  ["cwt-thu03", "CWops Test (CWT)", ["CW"], "160–10 m", [4, 3 * 60, 60], CWT_EX, CWT_URL],
  ["cwt-thu07", "CWops Test (CWT)", ["CW"], "160–10 m", [4, 7 * 60, 60], CWT_EX, CWT_URL],
];

/** Sobota n-tego pełnego weekendu (−1 = ostatni) jako ms północy UTC. @param {number} year @param {number} month @param {number} weekend */
export function fullWeekendSaturday(year, month, weekend) {
  if (weekend > 0) {
    const first = new Date(dayMs(year, month, 1)).getUTCDay();
    return dayMs(year, month, 1 + ((6 - first + 7) % 7) + 7 * (weekend - 1));
  }
  const len = lengthOfMonth(year, month);
  const lastDow = new Date(dayMs(year, month, len)).getUTCDay();
  let sat = len - ((lastDow - 6 + 7) % 7);
  // Sobota w ostatnim dniu miesiąca – weekend niepełny
  if (sat === len) sat -= 7;
  return dayMs(year, month, sat);
}

/** Terminy wbudowanych zawodów przecinające [fromMs, toMs). @param {number} fromMs @param {number} toMs */
export function builtinOccurrences(fromMs, toMs) {
  /** @type {(ContestEntry & {id: string})[]} */
  const out = [];
  const fromYear = new Date(fromMs).getUTCFullYear(), toYear = new Date(toMs).getUTCFullYear();
  for (const [id, name, modes, bands, s, exchange, rulesUrl, polish] of BUILTIN) {
    const add = (/** @type {number} */ start, /** @type {number} */ end) => {
      if (end > fromMs && start < toMs) {
        out.push({ ...makeContest({ name, startMs: start, endMs: end, source: "BUILTIN", polish: !!polish, vhf: modes.includes("VHF"), modes, bands, exchange, rulesUrl }), id });
      }
    };
    if (s.length === 4) {
      for (let y = fromYear - 1; y <= toYear; y++) {
        const start = fullWeekendSaturday(y, s[0], s[1]) + s[2] * 60_000;
        add(start, start + s[3] * HOUR_MS);
      }
    } else {
      const firstDay = Math.floor(fromMs / DAY_MS) - 1;
      const dow = new Date(firstDay * DAY_MS).getUTCDay();
      for (let d = firstDay + ((s[0] - dow + 7) % 7); d <= Math.floor(toMs / DAY_MS); d += 7) {
        const start = d * DAY_MS + s[1] * 60_000;
        add(start, start + s[2] * 60_000);
      }
    }
  }
  return out.sort((a, b) => a.startMs - b.startMs);
}

/** Plik iCalendar dla kalendarza w iOS. @param {ContestEntry} c @param {string} description */
export function contestIcs(c, description) {
  const stamp = (/** @type {number} */ ms) => new Date(ms).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  const esc = (/** @type {string} */ s) => s.replace(/\\/g, "\\\\").replace(/[,;]/g, (m) => `\\${m}`).replace(/\n/g, "\\n");
  return ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Ham Radio Toolkit//PL", "BEGIN:VEVENT",
    `UID:${c.startMs}-${encodeURIComponent(c.name).slice(0, 40)}@hamradiotoolkit`, `DTSTAMP:${stamp(Date.now())}`,
    `DTSTART:${stamp(c.startMs)}`, `DTEND:${stamp(c.endMs)}`, `SUMMARY:${esc(c.name)}`, `DESCRIPTION:${esc(description)}`,
    ...(c.rulesUrl ? [`URL:${c.rulesUrl}`] : []), "END:VEVENT", "END:VCALENDAR", ""].join("\r\n");
}
