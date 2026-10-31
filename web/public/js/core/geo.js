// @ts-check
// Lokator Maidenhead, ortodroma i rzut azymutalny – port utils/Maidenhead.kt i utils/GreatCircle.kt

/** @typedef {{lat: number, lon: number}} LatLon */
/** @typedef {{shortPathKm: number, shortPathBearing: number, longPathKm: number, longPathBearing: number}} PathInfo */

const VALID_LOCATOR = /^[A-R]{2}([0-9]{2}([A-X]{2}([0-9]{2})?)?)?$/i;
const A = "A".charCodeAt(0);
const a = "a".charCodeAt(0);
const ZERO = "0".charCodeAt(0);

export const Maidenhead = {
  /** @param {string} locator */
  isValid(locator) {
    return VALID_LOCATOR.test(String(locator ?? "").trim());
  },

  /**
   * Lokator 4, 6 lub 8 znaków dla podanej pozycji.
   * @param {number} lat @param {number} lon @param {4|6|8} [precision]
   */
  fromLatLon(lat, lon, precision = 6) {
    if (![4, 6, 8].includes(precision)) throw new Error("Precyzja 4, 6 lub 8");
    // Krawędź 180°/90° należy do ostatniego pola
    let x = Math.min(Math.max(lon + 180, 0), 359.9999999);
    let y = Math.min(Math.max(lat + 90, 0), 179.9999999);
    let s = String.fromCharCode(A + Math.floor(x / 20), A + Math.floor(y / 10));
    x %= 20; y %= 10;
    s += String.fromCharCode(ZERO + Math.floor(x / 2), ZERO + Math.floor(y));
    if (precision >= 6) {
      x %= 2; y %= 1;
      s += String.fromCharCode(a + Math.floor(x * 12), a + Math.floor(y * 24));
    }
    if (precision === 8) {
      x %= 2 / 24; y %= 1 / 24;
      s += String.fromCharCode(ZERO + Math.floor(x * 120), ZERO + Math.floor(y * 240));
    }
    return s;
  },

  /**
   * Środek pola/kwadratu opisanego lokatorem albo null dla błędnego lokatora.
   * @param {string} locator @returns {LatLon | null}
   */
  toLatLon(locator) {
    const loc = String(locator ?? "").trim().toUpperCase();
    if (!this.isValid(loc)) return null;
    const c = (i) => loc.charCodeAt(i);
    let lon = (c(0) - A) * 20 - 180;
    let lat = (c(1) - A) * 10 - 90;
    let lonSize = 20, latSize = 10;
    if (loc.length >= 4) {
      lon += (c(2) - ZERO) * 2; lat += c(3) - ZERO;
      lonSize = 2; latSize = 1;
    }
    if (loc.length >= 6) {
      lon += (c(4) - A) * (2 / 24); lat += (c(5) - A) * (1 / 24);
      lonSize = 2 / 24; latSize = 1 / 24;
    }
    if (loc.length === 8) {
      lon += (c(6) - ZERO) * (2 / 240); lat += (c(7) - ZERO) * (1 / 240);
      lonSize = 2 / 240; latSize = 1 / 240;
    }
    return { lat: lat + latSize / 2, lon: lon + lonSize / 2 };
  },
};

const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;

// Ortodroma na kuli o średnim promieniu Ziemi – dokładność ~0.5%, w zupełności wystarczająca w radiu
export const GreatCircle = {
  EARTH_RADIUS_KM: 6371.0,

  /** @param {LatLon} from @param {LatLon} to */
  distanceKm(from, to) {
    const lat1 = rad(from.lat), lat2 = rad(to.lat);
    const dLat = lat2 - lat1, dLon = rad(to.lon - from.lon);
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
    return 2 * this.EARTH_RADIUS_KM * Math.asin(Math.sqrt(Math.min(Math.max(h, 0), 1)));
  },

  /** Azymut początkowy (0° = północ, zgodnie z ruchem wskazówek zegara). @param {LatLon} from @param {LatLon} to */
  bearingDeg(from, to) {
    const lat1 = rad(from.lat), lat2 = rad(to.lat), dLon = rad(to.lon - from.lon);
    const y = Math.sin(dLon) * Math.cos(lat2);
    const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
    return (deg(Math.atan2(y, x)) + 360) % 360;
  },

  /** @param {LatLon} from @param {LatLon} to @returns {PathInfo} */
  path(from, to) {
    const sp = this.distanceKm(from, to);
    const bearing = this.bearingDeg(from, to);
    return {
      shortPathKm: sp,
      shortPathBearing: bearing,
      longPathKm: 2 * Math.PI * this.EARTH_RADIUS_KM - sp,
      longPathBearing: (bearing + 180) % 360,
    };
  },

  /**
   * Rzut azymutalny równoodległościowy wokół [center]: (x, y) w km, x na wschód, y na północ.
   * null dla punktu antypodycznego.
   * @param {LatLon} center @param {LatLon} point @returns {[number, number] | null}
   */
  azimuthalProject(center, point) {
    const lat0 = rad(center.lat), lon0 = rad(center.lon);
    const lat = rad(point.lat), lon = rad(point.lon);
    const cosC = Math.sin(lat0) * Math.sin(lat) + Math.cos(lat0) * Math.cos(lat) * Math.cos(lon - lon0);
    const c = Math.acos(Math.min(Math.max(cosC, -1), 1));
    if (c > Math.PI - 1e-6) return null;
    const k = c < 1e-9 ? 1 : c / Math.sin(c);
    const x = k * Math.cos(lat) * Math.sin(lon - lon0);
    const y = k * (Math.cos(lat0) * Math.sin(lat) - Math.sin(lat0) * Math.cos(lat) * Math.cos(lon - lon0));
    return [x * this.EARTH_RADIUS_KM, y * this.EARTH_RADIUS_KM];
  },

  /** @param {number} bearing */
  compassPoint(bearing) {
    const points = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
    return points[Math.floor((bearing + 11.25) / 22.5) % 16];
  },
};

/** Kontury lądów z world_path.txt (ścieżka SVG w rzucie walcowym 1000×500) jako wielokąty lat/lon. */
export function parseWorldOutlines(svgPath) {
  /** @type {LatLon[][]} */
  const polygons = [];
  /** @type {LatLon[]} */
  let current = [];
  const token = /([MLZ])\s*(?:(-?[\d.]+),(-?[\d.]+))?/g;
  for (const m of svgPath.matchAll(token)) {
    if (m[1] === "M" || m[1] === "Z") {
      if (current.length > 2) polygons.push(current);
      current = [];
      if (m[1] === "Z") continue;
    }
    if (m[2] === undefined) continue;
    const x = Number(m[2]), y = Number(m[3]);
    current.push({ lat: 90 - (y / 500) * 180, lon: (x / 1000) * 360 - 180 });
  }
  if (current.length > 2) polygons.push(current);
  return polygons;
}
