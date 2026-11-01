// Generuje dane wersji WWW prosto ze źródeł aplikacji na Androida, żeby obie wersje miały te same teksty i tabele:
//  - tłumaczenia: tools/i18n/*.tsv  -> public/data/i18n/<język>.json
//  - bandplany IARU R1/R2/R3 i zwroty do tłumaczenia opisów -> public/data/bandplan.json
//  - baza DXCC (cty.csv), ściągi (vademecum/*.tsv), kontury lądów (world_path.txt), ikona aplikacji
// Uruchomienie: node web/tools/build-data.mjs   (bez zależności, Node 18+)
import { readFileSync, writeFileSync, mkdirSync, readdirSync, copyFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { normalizeRussian } from "../public/js/core/contests.js";

const WEB = join(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = join(WEB, "..");
const MAIN = join(ROOT, "app", "src", "main");
const KOTLIN = join(MAIN, "java", "io", "github", "sp3wro", "hamradiotoolkit");
const OUT = join(WEB, "public", "data");
const LANGS = ["en", "pl", "de", "cs", "sk", "ru"];

mkdirSync(join(OUT, "i18n"), { recursive: true });
mkdirSync(join(OUT, "vademecum"), { recursive: true });

// ---------------------------------------------------------------- tłumaczenia
/** Tekst z tabeli TSV: "\n" oznacza nową linię (tak jak w gen.ps1). */
const unescape = (s) => s.replace(/\\n/g, "\n").replace(/\\t/g, "\t");

const dictionaries = Object.fromEntries(LANGS.map((l) => [l, {}]));
const i18nDir = join(ROOT, "tools", "i18n");
// Tabele aplikacji + tabela tekstów tylko dla wersji WWW (web/i18n)
const tables = [
  ...readdirSync(i18nDir).filter((f) => f.endsWith(".tsv")).sort().map((f) => join(i18nDir, f)),
  ...readdirSync(join(WEB, "i18n")).filter((f) => f.endsWith(".tsv")).sort().map((f) => join(WEB, "i18n", f)),
];
let keyCount = 0;
for (const table of tables) {
  const lines = readFileSync(table, "utf8").replace(/^﻿/, "").split(/\r?\n/);
  for (const line of lines) {
    if (!line.trim() || line.startsWith("#")) continue;
    const cols = line.split("\t");
    const key = cols[0].trim();
    if (key === "key") continue;
    keyCount++;
    LANGS.forEach((lang, i) => {
      // Pusta komórka – tekst angielski (tak robi Android, gdy brak tłumaczenia)
      const raw = cols[i + 1] && cols[i + 1].length > 0 ? cols[i + 1] : cols[1] ?? "";
      if (key.startsWith("plural:")) {
        const forms = {};
        for (const part of raw.split("|")) {
          const idx = part.indexOf(":");
          if (idx > 0) forms[part.slice(0, idx).trim()] = unescape(part.slice(idx + 1));
        }
        dictionaries[lang][key] = forms;
      } else if (key.startsWith("array:")) {
        dictionaries[lang][key] = raw.split(" || ").map(unescape);
      } else {
        dictionaries[lang][key] = unescape(raw);
      }
    });
  }
}
for (const lang of LANGS) {
  writeFileSync(join(OUT, "i18n", `${lang}.json`), JSON.stringify(dictionaries[lang]));
}

// ---------------------------------------------------------------- bandplany
/** Obiekty Band(...)/BandSegment(...) z pliku Kotlina; zwraca listę pasm z obiektu [objectName]. */
function parseBands(source, objectName) {
  const start = source.indexOf(`object ${objectName}`);
  if (start < 0) throw new Error(`Brak obiektu ${objectName}`);
  const body = source.slice(start);
  // Koniec listy pasm: pierwsze wystąpienie kolejnego "object " albo koniec pliku
  const next = body.indexOf("\nobject ", 10);
  const text = next > 0 ? body.slice(0, next) : body;
  const str = String.raw`"((?:[^"\\]|\\.)*)"`;
  const bandRe = new RegExp(String.raw`Band\(\s*${str}\s*,\s*${str}\s*,\s*listOf\(`, "g");
  const segRe = new RegExp(String.raw`BandSegment\(\s*${str}\s*,\s*${str}\s*,\s*${str}\s*\)`, "g");
  const kt = (s) => s.replace(/\\"/g, '"').replace(/\\\\/g, "\\");
  const bands = [];
  const starts = [...text.matchAll(bandRe)];
  starts.forEach((m, i) => {
    const end = i + 1 < starts.length ? starts[i + 1].index : text.length;
    const chunk = text.slice(m.index, end);
    const segments = [...chunk.matchAll(segRe)].map((s) => ({ range: kt(s[1]), mode: kt(s[2]), description: kt(s[3]) }));
    bands.push({ name: kt(m[1]), range: kt(m[2]), segments });
  });
  return bands;
}

const bandplanR1 = readFileSync(join(KOTLIN, "data", "model", "BandplanData.kt"), "utf8");
const regional = readFileSync(join(KOTLIN, "data", "model", "RegionalBandplans.kt"), "utf8");
const bandplanText = readFileSync(join(KOTLIN, "data", "model", "BandplanText.kt"), "utf8");

/** Zwroty do tłumaczenia: "zwrot" to R.string.klucz – z obiektu [objectName] w BandplanText.kt. */
function parsePhrases(objectName) {
  const start = bandplanText.indexOf(`object ${objectName}`);
  const end = bandplanText.indexOf("\n)", start);
  const body = bandplanText.slice(start, end);
  return [...body.matchAll(/"((?:[^"\\]|\\.)*)"\s+to\s+R\.string\.(\w+)/g)].map((m) => [m[1], m[2]]);
}

const bandplan = {
  regions: {
    1: parseBands(bandplanR1, "BandplanProvider"),
    2: parseBands(regional, "Region2Bandplan"),
    3: parseBands(regional, "Region3Bandplan"),
  },
  phrases: parsePhrases("BandplanText"),
  contestPhrases: parsePhrases("ContestText"),
};
for (const [r, bands] of Object.entries(bandplan.regions)) {
  if (bands.length < 10) throw new Error(`Region ${r}: tylko ${bands.length} pasm – zmienił się format pliku?`);
}
writeFileSync(join(OUT, "bandplan.json"), JSON.stringify(bandplan));

// ---------------------------------------------------------------- tłumaczenia rosyjskich nazw zawodów (QRZ.RU)
{
  const src = readFileSync(join(KOTLIN, "data", "contests", "RussianContestNames.kt"), "utf8");
  const start = src.indexOf("private val KNOWN");
  const body = src.slice(start, src.indexOf(").mapKeys", start));
  /** @type {Record<string, string>} */
  const known = {};
  const unq = (/** @type {string} */ x) => x.replace(/\\"/g, '"').replace(/\\\\/g, "\\");
  for (const m of body.matchAll(/"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"/g)) known[normalizeRussian(unq(m[1]))] = unq(m[2]);
  if (Object.keys(known).length < 50) throw new Error("Za mało nazw w RussianContestNames.kt");
  writeFileSync(join(OUT, "contest_names_ru.json"), JSON.stringify(known));
  console.log(`zawody RU: ${Object.keys(known).length} nazw`);
}

// ---------------------------------------------------------------- wbudowane częstotliwości satelitów
{
  const src = readFileSync(join(KOTLIN, "data", "model", "SatelliteDatabase.kt"), "utf8");
  /** @type {Record<string, string[][]>} */
  const db = {};
  for (const m of src.matchAll(/"([^"]+)"\s+to\s+listOf\(([\s\S]*?)\n\s*\)/g)) {
    db[m[1]] = [...m[2].matchAll(/SatFrequency\("([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)"\)/g)].map((f) => [f[1], f[2], f[3], f[4]]);
  }
  if (Object.keys(db).length < 15) throw new Error("Za mało satelitów w SatelliteDatabase.kt");
  writeFileSync(join(OUT, "sat_freqs.json"), JSON.stringify(db));
  console.log(`satelity: ${Object.keys(db).length} z częstotliwościami wbudowanymi`);
}

// ---------------------------------------------------------------- tablice LDPC(174,91) dla FT8/FT4
{
  const src = readFileSync(join(KOTLIN, "ft8", "Ft8LdpcTables.kt"), "utf8");
  const block = (/** @type {string} */ name) => {
    const at = src.indexOf(`val ${name}`);
    const body = src.slice(at, src.indexOf("\n    )", at));
    return [...body.matchAll(/intArrayOf\(([^)]*)\)/g)].map((m) => m[1].split(",").map((x) => Number(x.trim())));
  };
  const nm = block("NM"), mn = block("MN");
  const rows = /NUM_ROWS[^(]*\(([^)]*)\)/.exec(src)?.[1].split(",").map((x) => Number(x.trim())) ?? [];
  if (nm.length !== 83 || mn.length !== 174 || rows.length !== 83) throw new Error(`Tablice LDPC: ${nm.length}/${mn.length}/${rows.length}`);
  writeFileSync(join(WEB, "public", "js", "core", "ft8-tables.js"),
    `// Wygenerowane przez tools/build-data.mjs z Ft8LdpcTables.kt (ft8_lib, MIT) – nie edytować\n` +
    `export const NM = ${JSON.stringify(nm)};\nexport const MN = ${JSON.stringify(mn)};\nexport const NUM_ROWS = ${JSON.stringify(rows)};\n`);
}

// ---------------------------------------------------------------- pliki kopiowane 1:1
copyFileSync(join(MAIN, "assets", "cty.csv"), join(OUT, "cty.csv"));
copyFileSync(join(MAIN, "assets", "activations.json"), join(OUT, "activations.json"));
for (const f of readdirSync(join(MAIN, "assets", "vademecum"))) {
  copyFileSync(join(MAIN, "assets", "vademecum", f), join(OUT, "vademecum", f));
}
copyFileSync(join(MAIN, "res", "raw", "world_path.txt"), join(OUT, "world_path.txt"));
mkdirSync(join(OUT, "horus"), { recursive: true });
for (const f of ["payload_id_list.txt", "custom_field_list.json"]) copyFileSync(join(MAIN, "assets", "horus", f), join(OUT, "horus", f));


// Wersja aplikacji (versionName z app/build.gradle.kts) – ekran "O programie"
const gradle = readFileSync(join(MAIN, "..", "..", "build.gradle.kts"), "utf8");
writeFileSync(join(OUT, "app_version.json"), JSON.stringify({ versionName: /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1] ?? "?" }) + "\n");

// ---------------------------------------------------------------- lista plików dla service workera
// Wersja = skrót zawartości wszystkich plików, więc każda zmiana w aplikacji daje nową wersję (aktualizacja na iPhonie)
const PUBLIC = join(WEB, "public");
const SKIP = new Set(["sw.js", "sw-assets.js"]);
/** @param {string} dir @returns {string[]} */
function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });
}
const assets = walk(PUBLIC).map((p) => p.slice(PUBLIC.length + 1).split("\\").join("/")).filter((p) => !SKIP.has(p) && !p.startsWith(".")).sort();
const hash = createHash("sha256");
for (const a of assets) hash.update(a).update(readFileSync(join(PUBLIC, a)));
const version = hash.digest("hex").slice(0, 12);
writeFileSync(join(PUBLIC, "sw-assets.js"),
  `// Wygenerowane przez tools/build-data.mjs – nie edytować\nself.HRT_VERSION = "${version}";\nself.HRT_ASSETS = ${JSON.stringify(["./", ...assets], null, 1)};\n`);

console.log(`service worker: ${assets.length} plików, wersja ${version}`);
console.log(
  `i18n: ${keyCount} kluczy × ${LANGS.length} języków; bandplan R1/R2/R3: ` +
    `${bandplan.regions[1].length}/${bandplan.regions[2].length}/${bandplan.regions[3].length} pasm, ` +
    `${bandplan.phrases.length} zwrotów`
);
