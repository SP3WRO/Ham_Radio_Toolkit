// Kalendarz zawodów dla wersji WWW (ContestSources.kt): SP9CXN i PK-UKF / SP1CNV jako JSON z repozytorium, QRZ.RU po jednym
// pliku ICS na miesiąc (bieżący i dwa kolejne, pobierane po kolei). Każdy plik najwyżej raz na dobę; serwer parsuje, łączy
// i oddaje gotową listę: /api/contests[?refresh=1]
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { parseSp9cxn, parsePkUkf, parseQrzRu, mergeContests, SP9CXN_JSON, PK_UKF_JSON, qrzRuIcsUrl, qrzRuMonths, qrzRuPreviousMonth, contestKey } from "../public/js/core/contests.js";

const MAX_AGE = 24 * 3600 * 1000;
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

/** Miesiące QRZ.RU jako jedno źródło: czas najstarszej kopii; poprzedni miesiąc tylko z kopii (zawody na przełomie miesięcy). */
async function qrzRu(ua, force) {
  const now = Date.now();
  const months = qrzRuMonths(now);
  for (const key of copies.keys()) {
    const m = /^qrzru-(\d+)$/.exec(key);
    if (m && !months.includes(Number(m[1])) && Number(m[1]) !== qrzRuPreviousMonth(now)) copies.delete(key);
  }
  /** @type {any[]} */
  const results = [];
  // Po kolei – nie obciążamy QRZ.RU równoległymi zapytaniami
  for (const m of months) results.push(await source(`qrzru-${m}`, qrzRuIcsUrl(m), (t) => parseQrzRu(t, known), ua, force));
  const previous = copies.get(`qrzru-${qrzRuPreviousMonth(now)}`)?.entries ?? [];
  const seen = new Set();
  const entries = [...previous, ...results.flatMap((r) => r.entries)].filter((e) => {
    const k = contestKey(e);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  }).sort((a, b) => a.startMs - b.startMs);
  const times = results.map((r) => r.fetchedAt).filter((t) => t != null);
  return {
    entries, fetchedAt: times.length ? Math.min(...times) : null,
    fromCache: results.some((r) => r.fromCache || r.error), error: entries.length ? null : results.find((r) => r.error)?.error ?? null,
  };
}

export async function contests(ua, refresh = false) {
  const force = refresh && Date.now() - lastForced > FORCE_MIN;
  if (force) lastForced = Date.now();
  const [sp, pk, qrz] = await Promise.all([
    source("sp9cxn", SP9CXN_JSON, parseSp9cxn, ua, force),
    source("pkukf", PK_UKF_JSON, parsePkUkf, ua, force),
    qrzRu(ua, force),
  ]);
  const status = (src, r) => ({ source: src, fetchedAt: r.fetchedAt, fromCache: r.fromCache, error: r.error });
  return {
    entries: mergeContests([sp.entries, pk.entries, qrz.entries]),
    statuses: [status("SP9CXN", sp), status("PK_UKF", pk), status("QRZ_RU", qrz)],
  };
}
