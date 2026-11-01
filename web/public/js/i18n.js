// @ts-check
// Teksty interfejsu w 6 językach (generowane z tools/i18n/*.tsv) i formatowanie jak String.format w Androidzie

export const LANGUAGES = [
  { tag: "", nativeName: "" }, // język systemu
  { tag: "pl", nativeName: "Polski" },
  { tag: "en", nativeName: "English" },
  { tag: "de", nativeName: "Deutsch" },
  { tag: "cs", nativeName: "Čeština" },
  { tag: "sk", nativeName: "Slovenčina" },
  { tag: "ru", nativeName: "Русский" },
];
const SUPPORTED = ["pl", "en", "de", "cs", "sk", "ru"];

/** @type {Record<string, any>} */
let dict = {};
/** @type {Record<string, any>} */
let fallback = {};
let lang = "en";

/** Język interfejsu: wybrany w ustawieniach albo systemowy (bez tłumaczenia – angielski, jak w Androidzie). */
export function resolveLanguage(chosen) {
  if (chosen && SUPPORTED.includes(chosen)) return chosen;
  const nav = typeof navigator !== "undefined" ? navigator.languages ?? [navigator.language] : [];
  for (const l of nav) {
    const base = String(l).toLowerCase().split("-")[0];
    if (SUPPORTED.includes(base)) return base;
  }
  return "en";
}

/** @param {string} chosen tag z ustawień ("" = systemowy) */
export async function loadLanguage(chosen) {
  lang = resolveLanguage(chosen);
  const load = (l) => fetch(`data/i18n/${l}.json`).then((r) => r.json());
  [dict, fallback] = await Promise.all([load(lang), lang === "en" ? Promise.resolve({}) : load("en")]);
  if (typeof document !== "undefined") document.documentElement.lang = lang;
  return lang;
}

export const currentLanguage = () => lang;
/** Locale do liczb i dat (UiLocale w aplikacji). */
export const uiLocale = () => ({ pl: "pl-PL", en: "en-GB", de: "de-DE", cs: "cs-CZ", sk: "sk-SK", ru: "ru-RU" })[lang] ?? "en-GB";

/** Tekst w języku interfejsu. @param {string} key @param {...any} args */
export function t(key, ...args) {
  const s = dict[key] ?? fallback[key];
  if (typeof s !== "string") return key;
  return args.length ? formatIn(uiLocale(), s, ...args) : s.replace(/%%/g, "%");
}

/** Liczba mnoga (plurals w Androidzie). @param {string} key bez "plural:" @param {number} n @param {...any} args */
export function plural(key, n, ...args) {
  const forms = dict[`plural:${key}`] ?? fallback[`plural:${key}`];
  if (!forms) return key;
  const cat = new Intl.PluralRules(lang).select(n);
  const s = forms[cat] ?? forms.other ?? Object.values(forms)[0];
  return formatIn(uiLocale(), s, ...args);
}

/** Tablica tekstów (string-array). @param {string} key */
export function array(key) {
  return dict[`array:${key}`] ?? fallback[`array:${key}`] ?? [];
}

/** String.format z Locale.US. @param {string} pattern @param {...any} args */
export function format(pattern, ...args) {
  return formatIn("en-US", pattern, ...args);
}

/**
 * Podzbiór String.format: %[n$][,][.p](s|d|f) i %%. Liczby w podanym locale (separator dziesiętny),
 * grupowanie tysięcy tylko z flagą ",".
 * @param {string} locale @param {string} pattern @param {...any} args
 */
export function formatIn(locale, pattern, ...args) {
  let next = 0;
  return pattern.replace(/%(?:(\d+)\$)?(,)?(?:\.(\d+))?([sdf%])/g, (_, pos, comma, prec, conv) => {
    if (conv === "%") return "%";
    const v = args[pos ? Number(pos) - 1 : next++];
    if (conv === "s") return String(v ?? "");
    const n = Number(v);
    if (conv === "d") {
      return comma ? new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(Math.trunc(n)) : String(Math.trunc(n));
    }
    const p = prec == null ? 6 : Number(prec);
    return new Intl.NumberFormat(locale, { minimumFractionDigits: p, maximumFractionDigits: p, useGrouping: !!comma }).format(n);
  });
}

/** Liczba w języku interfejsu (UiLocale): fmt("%.2f m", 1.5). @param {string} pattern @param {...any} args */
export function fmt(pattern, ...args) {
  return formatIn(uiLocale(), pattern, ...args);
}
