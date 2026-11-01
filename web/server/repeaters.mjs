// Przemienniki dla wersji WWW: serwer pobiera i parsuje źródła (RepeaterDataSourceFetcher w aplikacji),
// trzyma kopie w pamięci i oddaje telefonowi gotową listę: /api/repeaters?source=..&lat=..&lon=..
import {
  RepeaterSources, parseChirpCsv, parseRadarXml, modesFromEu, parsePrzemiennikiEu, parseHearHam, parseRepeatermap,
  merge, geoBox, boxContains, boxTiles, FETCH_RADIUS_KM,
} from "../public/js/core/repeaters.js";

const HOUR = 3600 * 1000;
/** @type {Map<string, {value: any, time: number}>} */
const memo = new Map();
/** @type {Map<string, Promise<any>>} */
const pending = new Map();

/** Wartość z pamięci (świeża) albo nowo pobrana; przy błędzie – starsza kopia, jeśli jest. */
async function cached(key, maxAgeMs, load) {
  const hit = memo.get(key);
  if (hit && Date.now() - hit.time < maxAgeMs) return hit.value;
  if (!pending.has(key)) {
    pending.set(key, load().then((value) => { memo.set(key, { value, time: Date.now() }); return value; }).finally(() => pending.delete(key)));
  }
  try {
    return await pending.get(key);
  } catch (e) {
    if (hit) return hit.value;
    throw e;
  }
}

async function get(url, userAgent, timeout = 60000) {
  const res = await fetch(url, { headers: { "User-Agent": userAgent }, signal: AbortSignal.timeout(timeout) });
  if (!res.ok) throw new Error(`HTTP ${res.status} z ${new URL(url).host}`);
  return res.text();
}

const euJson = (ua) => cached("eu-json", 6 * HOUR, () => get("https://przemienniki.eu/api/export/json", ua));

async function przemiennikiNet(ua) {
  return cached("pl-net", 6 * HOUR, async () => {
    // Współrzędne z radar.xml i pełne emisje z przemienniki.eu tylko uzupełniają eksport CHIRP – ich brak nie blokuje listy
    const [coords, modes, csv] = await Promise.all([
      get("https://przemienniki.net/export/radar.xml", ua).then(parseRadarXml).catch(() => new Map()),
      euJson(ua).then(modesFromEu).catch(() => new Map()),
      get("https://przemienniki.net/export/chirp.csv", "CHIRP/1.0 (HamRadioToolkit)"),
    ]);
    return parseChirpCsv(csv, coords, modes);
  });
}

const przemiennikiEu = (ua) => cached("pl-eu", 6 * HOUR, async () => parsePrzemiennikiEu(await euJson(ua)));

/** Cała baza HearHam (świat) – odświeżana raz w tygodniu, jak w aplikacji. */
const hearHamAll = (ua) => cached("hearham", 7 * 24 * HOUR, async () => {
  const list = parseHearHam(await get("https://hearham.com/api/repeaters/v1", ua, 120000));
  if (list.length < 1000) throw new Error("Niepełna odpowiedź HearHam");
  return list;
});

async function hearHam(box, ua) {
  return (await hearHamAll(ua)).filter((r) => boxContains(box, r.latitude, r.longitude));
}

/** repeatermap.de – kafelki 2°×2°, każdy w pamięci przez dobę. */
async function repeatermap(box, ua) {
  const tiles = boxTiles(box, 2);
  // Kafelki zaokrąglone do siatki co 2°, żeby sąsiednie zapytania trafiały w te same kopie
  const keys = [...new Set(tiles.map((t) => `${Math.floor(t.south / 2) * 2},${Math.floor(t.west / 2) * 2}`))];
  let failures = 0;
  const parts = await Promise.all(keys.map(async (k) => {
    const [s, w] = k.split(",").map(Number);
    const url = `https://www.repeatermap.de/api/v1/map.php?bounds=${encodeURIComponent(`${s + 2},${w + 2},${s},${w}`)}`;
    try {
      return await cached(`rm:${k}`, 24 * HOUR, async () => parseRepeatermap(await get(url, ua)));
    } catch {
      failures++;
      return [];
    }
  }));
  if (keys.length && failures === keys.length) throw new Error("repeatermap.de niedostępny");
  // Ten sam przemiennik bywa osobnym wpisem dla FM i DMR
  return merge(parts.flat().filter((r) => boxContains(box, r.latitude, r.longitude)));
}

/**
 * Przemienniki ze źródła [source]. Bazy polskie – cały kraj; zagraniczne – obszar ok. 210 km wokół pozycji.
 * @param {string} source @param {number | null} lat @param {number | null} lon @param {string} ua
 */
export async function repeaters(source, lat, lon, ua) {
  if (source === RepeaterSources.PRZEMIENNIKI_NET) return merge(await przemiennikiNet(ua));
  if (source === RepeaterSources.PRZEMIENNIKI_EU) return merge(await przemiennikiEu(ua));
  if (source === RepeaterSources.REPEATERBOOK) throw new Error("RepeaterBook będzie dostępny w wersji WWW razem z kontami w serwisach");
  if (lat == null || lon == null) throw new Error("Brak pozycji – źródła zagraniczne wymagają lokalizacji");
  const box = geoBox(lat, lon, FETCH_RADIUS_KM);
  if (source === RepeaterSources.HEARHAM) return merge(await hearHam(box, ua));
  if (source === RepeaterSources.REPEATERMAP) return repeatermap(box, ua);
  // HearHam + repeatermap.de: każde źródło jest mocne w innych krajach; błąd jednego nie blokuje drugiego
  const [a, b] = await Promise.allSettled([hearHam(box, ua), repeatermap(box, ua)]);
  if (a.status === "rejected" && b.status === "rejected") throw a.reason;
  // repeatermap.de pierwszy – ma dokładniejsze współrzędne i uwagi w Europie
  return merge(b.status === "fulfilled" ? b.value : [], a.status === "fulfilled" ? a.value : []);
}
