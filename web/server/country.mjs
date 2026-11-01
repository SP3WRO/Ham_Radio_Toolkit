// Kraj z pozycji (odpowiednik systemowego Geocodera w Androidzie): Nominatim (OpenStreetMap), najwyżej
// 1 zapytanie na sekundę dla całego serwera, wyniki w pamięci po zaokrągleniu pozycji do 0,1°.
const cache = new Map();
const MAX = 5000;
let queue = Promise.resolve();
let last = 0;

export async function countryAt(lat, lon, ua) {
  const key = `${lat.toFixed(1)},${lon.toFixed(1)}`;
  if (cache.has(key)) return cache.get(key);
  const run = queue.then(async () => {
    if (cache.has(key)) return cache.get(key);
    const wait = 1100 - (Date.now() - last);
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
    last = Date.now();
    const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=5&accept-language=en&lat=${lat.toFixed(4)}&lon=${lon.toFixed(4)}`;
    const res = await fetch(url, { headers: { "User-Agent": ua }, signal: AbortSignal.timeout(10000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    const iso = String(data?.address?.country_code ?? "").toUpperCase();
    const result = /^[A-Z]{2}$/.test(iso) ? { iso, adminArea: String(data.address.state ?? data.address.province ?? data.address.region ?? "") } : { iso: null, adminArea: "" };
    if (cache.size >= MAX) cache.delete(cache.keys().next().value);
    cache.set(key, result);
    return result;
  });
  queue = run.catch(() => {});
  return run;
}
