// Kalendarz zawodów dla wersji WWW (ContestSources.kt): serwer pobiera SP9CXN, PK-UKF i QRZ.RU najwyżej co 12 godzin,
// parsuje, łączy i oddaje gotową listę: /api/contests[?refresh=1]
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { parseSp9cxn, parsePkUkf, parseQrzRu, mergeContests, pkUkfUrl, QRZ_RU_ICS, CONTEST_SOURCES } from "../public/js/core/contests.js";

const MAX_AGE = 12 * 3600 * 1000;
// Wymuszone odświeżenie ("Odśwież" na ekranie) – najwyżej raz na 10 minut dla całego serwera
const FORCE_MIN = 10 * 60 * 1000;
const HERE = dirname(fileURLToPath(import.meta.url));
let known = {};
try {
  known = JSON.parse(readFileSync(join(HERE, "..", "public", "data", "contest_names_ru.json"), "utf8"));
} catch {
  console.warn("Brak contest_names_ru.json – uruchom tools/build-data.mjs");
}

/** @type {Map<string, {entries: any[], time: number}>} */
const copies = new Map();
/** @type {Map<string, Promise<any>>} */
const pending = new Map();
let lastForced = 0;

async function get(url, ua) {
  const res = await fetch(url, { headers: { "User-Agent": ua }, signal: AbortSignal.timeout(30000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}

/** Jedno źródło: świeża kopia z pamięci albo nowe pobranie; przy błędzie – stara kopia z informacją o błędzie. */
async function source(key, url, parse, ua, force) {
  const copy = copies.get(key);
  if (copy && !force && Date.now() - copy.time < MAX_AGE) return { entries: copy.entries, fetchedAt: copy.time, fromCache: false, error: null };
  if (!pending.has(key)) {
    pending.set(key, (async () => {
      const entries = parse(await get(url, ua));
      if (!entries.length) throw new Error("Brak zawodów w odpowiedzi");
      copies.set(key, { entries, time: Date.now() });
      return entries;
    })().finally(() => pending.delete(key)));
  }
  try {
    const entries = await pending.get(key);
    return { entries, fetchedAt: copies.get(key)?.time ?? Date.now(), fromCache: false, error: null };
  } catch (e) {
    const msg = String(e?.message ?? e);
    return copy ? { entries: copy.entries, fetchedAt: copy.time, fromCache: true, error: null } : { entries: [], fetchedAt: null, fromCache: false, error: msg };
  }
}

export async function contests(ua, refresh = false) {
  const force = refresh && Date.now() - lastForced > FORCE_MIN;
  if (force) lastForced = Date.now();
  const now = new Date();
  const year = now.getUTCFullYear();
  // Od listopada także kalendarz PK-UKF na kolejny rok (jeśli już jest)
  const years = now.getUTCMonth() + 1 >= 11 ? [year, year + 1] : [year];
  const [sp, pk, qrz] = await Promise.all([
    source("sp9cxn", CONTEST_SOURCES.SP9CXN.pageUrl, parseSp9cxn, ua, force),
    Promise.all(years.map((y) => source(`pkukf_${y}`, pkUkfUrl(y), parsePkUkf, ua, force))),
    source("qrzru", QRZ_RU_ICS, (t) => parseQrzRu(t, known), ua, force),
  ]);
  const status = (src, r) => ({ source: src, fetchedAt: r.fetchedAt, fromCache: r.fromCache, error: r.error });
  return {
    entries: mergeContests([sp.entries, pk.flatMap((r) => r.entries), qrz.entries]),
    statuses: [status("SP9CXN", sp), status("PK_UKF", pk[0]), status("QRZ_RU", qrz)],
  };
}
