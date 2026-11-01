// Przemienniki dla wersji WWW: serwer pobiera i parsuje źródła (RepeaterDataSourceFetcher w aplikacji),
// trzyma kopie w pamięci i oddaje telefonowi gotową listę: /api/repeaters?source=..&lat=..&lon=..
import {
  RepeaterSources, parsePrzemiennikiNet, przemiennikiNetTimestamp, parsePrzemiennikiEu, parseHearHam, parseRepeatermap,
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

// Autor przemienniki.eu prosi o rzadkie pobieranie, bo dane zmieniają się rzadko: kopia ważna 7 dni, także przy odświeżaniu
const przemiennikiEu = (ua) => cached("pl-eu", 7 * 24 * HOUR, async () => {
  const list = parsePrzemiennikiEu(await get("https://przemienniki.eu/api/export/json", ua));
  if (!list.length) throw new Error("Pusta odpowiedź z przemienniki.eu");
  return list;
});

/**
 * przemienniki.net: rpts.json ma ok. 4 MB, więc raz na dobę pytamy tylko o timestamp.xml, a całą bazę pobieramy,
 * gdy się zmienił. radar.xml nie jest potrzebny – to podzbiór rpts.json.
 */
let netCopy = /** @type {{list: any[], stamp: string | null, checked: number} | null} */ (null);
/** @type {Promise<any[]> | null} */
let netLoading = null;
async function przemiennikiNet(ua) {
  if (netCopy && Date.now() - netCopy.checked < 24 * HOUR) return netCopy.list;
  netLoading ??= (async () => {
    const stamp = await get("https://przemienniki.net/export/timestamp.xml/", ua).then(przemiennikiNetTimestamp).catch(() => null);
    if (netCopy && stamp != null && stamp === netCopy.stamp) {
      netCopy.checked = Date.now();
      return netCopy.list;
    }
    try {
      const list = parsePrzemiennikiNet(await get("https://przemienniki.net/export/rpts.json/", ua, 120000));
      if (!list.length) throw new Error("Pusta odpowiedź z przemienniki.net");
      netCopy = { list, stamp, checked: Date.now() };
      return list;
    } catch (e) {
      if (netCopy) return netCopy.list;
      throw e;
    }
  })().finally(() => { netLoading = null; });
  return netLoading;
}

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
 * Przemienniki ze źródła [source]. Bazy polskie – cała baza; zagraniczne – obszar ok. 210 km wokół pozycji.
 * @param {string} source @param {number | null} lat @param {number | null} lon @param {string} ua
 */
export async function repeaters(source, lat, lon, ua) {
  if (source === RepeaterSources.PRZEMIENNIKI_NET) return merge(await przemiennikiNet(ua));
  if (source === RepeaterSources.PRZEMIENNIKI_EU) return merge(await przemiennikiEu(ua));
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
