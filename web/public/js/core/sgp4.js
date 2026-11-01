// @ts-check
// Propagator SGP4 (Vallado, "Revisiting Spacetrack Report #3", stałe WGS-72) i przeloty nad stacją.
// Na Androidzie liczy to biblioteka predict4java; w przeglądarce – ta implementacja (bez zależności).
// Satelity "głębokiego kosmosu" (okres ≥ 225 min, np. QO-100) liczymy tym samym modelem bez poprawek
// księżycowo-słonecznych – przy świeżych TLE błąd to pojedyncze kilometry, bez znaczenia dla anteny.

const TWO_PI = 2 * Math.PI;
const DEG = Math.PI / 180;
const MU = 398600.8;
const RE = 6378.135;
const XKE = 60 / Math.sqrt((RE * RE * RE) / MU);
const J2 = 0.001082616;
const J3 = -0.00000253881;
const J4 = -0.00000165597;
const J3OJ2 = J3 / J2;
const X2O3 = 2 / 3;
const VKMPERSEC = (RE * XKE) / 60;

/** @typedef {{name: string, line1: string, line2: string}} Tle */

/** Pole TLE z wykładnikiem w zapisie "12345-4" → 0.12345e-4. @param {string} s */
function expField(s) {
  const t = s.trim();
  if (!t) return 0;
  const m = /^([+-]?)(\d+)([+-]\d)$/.exec(t.replace(/\s/g, ""));
  if (!m) return Number(t) || 0;
  return Number(`${m[1]}0.${m[2]}e${m[3]}`);
}

/** @param {number} x */
const fmod2p = (x) => { const r = x % TWO_PI; return r < 0 ? r + TWO_PI : r; };

/** Średni czas gwiazdowy Greenwich [rad] dla daty juliańskiej. @param {number} jd */
export function gmst(jd) {
  const t = (jd - 2451545.0) / 36525.0;
  let temp = -6.2e-6 * t * t * t + 0.093104 * t * t + (876600.0 * 3600 + 8640184.812866) * t + 67310.54841;
  temp = ((temp * DEG) / 240.0) % TWO_PI;
  return temp < 0 ? temp + TWO_PI : temp;
}

/** @param {number} ms */
export const jdFromMs = (ms) => ms / 86_400_000 + 2440587.5;

/** Numer NORAD z drugiej linii. @param {string} line2 */
export const noradId = (line2) => line2.slice(2, 7).trim();

/** Inicjalizacja modelu z TLE. Zwraca null dla uszkodzonych danych. @param {Tle} tle */
export function satrec(tle) {
  const l1 = tle.line1, l2 = tle.line2;
  if (!l1.startsWith("1 ") || !l2.startsWith("2 ")) return null;
  const year2 = Number(l1.slice(18, 20));
  const day = Number(l1.slice(20, 32));
  const bstar = expField(l1.slice(53, 61));
  const inclo = Number(l2.slice(8, 16)) * DEG;
  const nodeo = Number(l2.slice(17, 25)) * DEG;
  const ecco = Number(`0.${l2.slice(26, 33).trim()}`);
  const argpo = Number(l2.slice(34, 42)) * DEG;
  const mo = Number(l2.slice(43, 51)) * DEG;
  const revs = Number(l2.slice(52, 63));
  if (![year2, day, inclo, nodeo, ecco, argpo, mo, revs].every(Number.isFinite) || revs <= 0) return null;
  const year = year2 < 57 ? 2000 + year2 : 1900 + year2;
  const epochMs = Date.UTC(year, 0, 1) + (day - 1) * 86_400_000;
  let no = (revs * TWO_PI) / 1440;

  // initl
  const eccsq = ecco * ecco;
  const omeosq = 1 - eccsq;
  const rteosq = Math.sqrt(omeosq);
  const cosio = Math.cos(inclo);
  const cosio2 = cosio * cosio;
  const ak = Math.pow(XKE / no, X2O3);
  const d1 = (0.75 * J2 * (3 * cosio2 - 1)) / (rteosq * omeosq);
  let del = d1 / (ak * ak);
  const adel = ak * (1 - del * del - del * (1 / 3 + (134 * del * del) / 81));
  del = d1 / (adel * adel);
  no /= 1 + del;
  const ao = Math.pow(XKE / no, X2O3);
  const sinio = Math.sin(inclo);
  const po = ao * omeosq;
  const con42 = 1 - 5 * cosio2;
  const con41 = -con42 - cosio2 - cosio2;
  const posq = po * po;
  const rp = ao * (1 - ecco);

  // sgp4init
  let isimp = rp < 220 / RE + 1 ? 1 : 0;
  let sfour = 78 / RE + 1;
  let qzms24 = Math.pow((120 - 78) / RE, 4);
  const perige = (rp - 1) * RE;
  if (perige < 156) {
    sfour = perige < 98 ? 20 : perige - 78;
    qzms24 = Math.pow((120 - sfour) / RE, 4);
    sfour = sfour / RE + 1;
  }
  const pinvsq = 1 / posq;
  const tsi = 1 / (ao - sfour);
  const eta = ao * ecco * tsi;
  const etasq = eta * eta;
  const eeta = ecco * eta;
  const psisq = Math.abs(1 - etasq);
  const coef = qzms24 * Math.pow(tsi, 4);
  const coef1 = coef / Math.pow(psisq, 3.5);
  const cc2 = coef1 * no * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq)) + ((0.375 * J2 * tsi) / psisq) * con41 * (8 + 3 * etasq * (8 + etasq)));
  const cc1 = bstar * cc2;
  const cc3 = ecco > 1e-4 ? (-2 * coef * tsi * J3OJ2 * no * sinio) / ecco : 0;
  const x1mth2 = 1 - cosio2;
  const cc4 = 2 * no * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecco * (0.5 + 2 * etasq)
    - ((J2 * tsi) / (ao * psisq)) * (-3 * con41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta)) + 0.75 * x1mth2 * (2 * etasq - eeta * (1 + etasq)) * Math.cos(2 * argpo)));
  const cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq);
  const cosio4 = cosio2 * cosio2;
  const temp1 = 1.5 * J2 * pinvsq * no;
  const temp2 = 0.5 * temp1 * J2 * pinvsq;
  const temp3 = -0.46875 * J4 * pinvsq * pinvsq * no;
  const mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4);
  const argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4) + temp3 * (3 - 36 * cosio2 + 49 * cosio4);
  const xhdot1 = -temp1 * cosio;
  const nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio;
  const omgcof = bstar * cc3 * Math.cos(argpo);
  const xmcof = ecco > 1e-4 ? (-X2O3 * coef * bstar) / eeta : 0;
  const nodecf = 3.5 * omeosq * xhdot1 * cc1;
  const t2cof = 1.5 * cc1;
  const xlcof = Math.abs(cosio + 1) > 1.5e-12 ? (-0.25 * J3OJ2 * sinio * (3 + 5 * cosio)) / (1 + cosio) : (-0.25 * J3OJ2 * sinio * (3 + 5 * cosio)) / 1.5e-12;
  const aycof = -0.5 * J3OJ2 * sinio;
  const delmo = Math.pow(1 + eta * Math.cos(mo), 3);
  const sinmao = Math.sin(mo);
  const x7thm1 = 7 * cosio2 - 1;
  const deep = TWO_PI / no >= 225;
  if (deep) isimp = 1;
  let d2 = 0, d3 = 0, d4 = 0, t3cof = 0, t4cof = 0, t5cof = 0;
  if (isimp !== 1) {
    const cc1sq = cc1 * cc1;
    d2 = 4 * ao * tsi * cc1sq;
    const temp = (d2 * tsi * cc1) / 3;
    d3 = (17 * ao + sfour) * temp;
    d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1;
    t3cof = d2 + 2 * cc1sq;
    t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq));
    t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq));
  }
  return {
    name: tle.name, norad: noradId(l2), epochMs, deep, periodMin: TWO_PI / no,
    no, ecco, inclo, nodeo, argpo, mo, bstar, isimp, mdot, argpdot, nodedot, nodecf, cc1, cc4, cc5, t2cof, omgcof, xmcof, eta, delmo,
    sinmao, d2, d3, d4, t3cof, t4cof, t5cof, aycof, xlcof, con41, x1mth2, x7thm1,
  };
}

/** @typedef {NonNullable<ReturnType<typeof satrec>>} SatRec */

/**
 * Pozycja i prędkość w układzie TEME [km, km/s] po [tsince] minutach od epoki TLE; null, gdy model się rozbiega.
 * @param {SatRec} s @param {number} tsince
 */
export function sgp4(s, tsince) {
  const t = tsince;
  const xmdf = s.mo + s.mdot * t;
  const argpdf = s.argpo + s.argpdot * t;
  const nodedf = s.nodeo + s.nodedot * t;
  let argpm = argpdf;
  let mm = xmdf;
  const t2 = t * t;
  let nodem = nodedf + s.nodecf * t2;
  let tempa = 1 - s.cc1 * t;
  let tempe = s.bstar * s.cc4 * t;
  let templ = s.t2cof * t2;
  if (s.isimp !== 1) {
    const delomg = s.omgcof * t;
    const delm = s.xmcof * (Math.pow(1 + s.eta * Math.cos(xmdf), 3) - s.delmo);
    const temp = delomg + delm;
    mm = xmdf + temp;
    argpm = argpdf - temp;
    const t3 = t2 * t, t4 = t3 * t;
    tempa = tempa - s.d2 * t2 - s.d3 * t3 - s.d4 * t4;
    tempe += s.bstar * s.cc5 * (Math.sin(mm) - s.sinmao);
    templ += s.t3cof * t3 + t4 * (s.t4cof + t * s.t5cof);
  }
  let nm = s.no;
  let em = s.ecco;
  const inclm = s.inclo;
  if (nm <= 0) return null;
  const am = Math.pow(XKE / nm, X2O3) * tempa * tempa;
  nm = XKE / Math.pow(am, 1.5);
  em -= tempe;
  if (em >= 1 || em < -0.001 || am < 0.95) return null;
  if (em < 1e-6) em = 1e-6;
  mm += s.no * templ;
  let xlm = mm + argpm + nodem;
  nodem = fmod2p(nodem);
  argpm = fmod2p(argpm);
  xlm = fmod2p(xlm);
  mm = fmod2p(xlm - argpm - nodem);
  const sinip = Math.sin(inclm), cosip = Math.cos(inclm);
  const ep = em, argpp = argpm, nodep = nodem, mp = mm;

  // Okresowe dalekie (long-period)
  const axnl = ep * Math.cos(argpp);
  let temp = 1 / (am * (1 - ep * ep));
  const aynl = ep * Math.sin(argpp) + temp * s.aycof;
  const xl = mp + argpp + nodep + temp * s.xlcof * axnl;

  // Równanie Keplera
  const u = fmod2p(xl - nodep);
  let eo1 = u, tem5 = 9999.9, ktr = 1, sineo1 = 0, coseo1 = 0;
  while (Math.abs(tem5) >= 1e-12 && ktr <= 10) {
    sineo1 = Math.sin(eo1);
    coseo1 = Math.cos(eo1);
    tem5 = 1 - coseo1 * axnl - sineo1 * aynl;
    tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
    if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0 ? 0.95 : -0.95;
    eo1 += tem5;
    ktr++;
  }

  // Okresowe krótkie (short-period)
  const ecose = axnl * coseo1 + aynl * sineo1;
  const esine = axnl * sineo1 - aynl * coseo1;
  const el2 = axnl * axnl + aynl * aynl;
  const pl = am * (1 - el2);
  if (pl < 0) return null;
  const rl = am * (1 - ecose);
  const rdotl = (Math.sqrt(am) * esine) / rl;
  const rvdotl = Math.sqrt(pl) / rl;
  const betal = Math.sqrt(1 - el2);
  temp = esine / (1 + betal);
  const sinu = (am / rl) * (sineo1 - aynl - axnl * temp);
  const cosu = (am / rl) * (coseo1 - axnl + aynl * temp);
  let su = Math.atan2(sinu, cosu);
  const sin2u = (cosu + cosu) * sinu;
  const cos2u = 1 - 2 * sinu * sinu;
  temp = 1 / pl;
  const temp1 = 0.5 * J2 * temp;
  const temp2 = temp1 * temp;
  const mrt = rl * (1 - 1.5 * temp2 * betal * s.con41) + 0.5 * temp1 * s.x1mth2 * cos2u;
  su -= 0.25 * temp2 * s.x7thm1 * sin2u;
  const xnode = nodep + 1.5 * temp2 * cosip * sin2u;
  const xinc = inclm + 1.5 * temp2 * cosip * sinip * cos2u;
  const mvt = rdotl - (nm * temp1 * s.x1mth2 * sin2u) / XKE;
  const rvdot = rvdotl + (nm * temp1 * (s.x1mth2 * cos2u + 1.5 * s.con41)) / XKE;
  if (mrt < 1) return null; // pod powierzchnią Ziemi – satelita spłonął

  const sinsu = Math.sin(su), cossu = Math.cos(su), snod = Math.sin(xnode), cnod = Math.cos(xnode);
  const sini = Math.sin(xinc), cosi = Math.cos(xinc);
  const xmx = -snod * cosi, xmy = cnod * cosi;
  const ux = xmx * sinsu + cnod * cossu, uy = xmy * sinsu + snod * cossu, uz = sini * sinsu;
  const vx = xmx * cossu - cnod * sinsu, vy = xmy * cossu - snod * sinsu, vz = sini * cossu;
  return {
    r: [mrt * ux * RE, mrt * uy * RE, mrt * uz * RE],
    v: [(mvt * ux + rvdot * vx) * VKMPERSEC, (mvt * uy + rvdot * vy) * VKMPERSEC, (mvt * uz + rvdot * vz) * VKMPERSEC],
  };
}

// ---------------------------------------------------------------- obserwator
/** @typedef {{lat: number, lon: number, alt?: number}} Site  lat/lon w stopniach, alt w metrach */

/** Pozycja stacji w ECEF [km] (WGS-84). @param {Site} site */
function siteEcef(site) {
  const a = 6378.137, f = 1 / 298.257223563, e2 = f * (2 - f);
  const lat = site.lat * DEG, lon = site.lon * DEG, h = (site.alt ?? 0) / 1000;
  const n = a / Math.sqrt(1 - e2 * Math.sin(lat) ** 2);
  return [(n + h) * Math.cos(lat) * Math.cos(lon), (n + h) * Math.cos(lat) * Math.sin(lon), (n * (1 - e2) + h) * Math.sin(lat)];
}

/**
 * Azymut, elewacja [°] i odległość [km] satelity widzianego ze stacji w chwili [ms]; null, gdy model się rozbiega.
 * @param {SatRec} s @param {Site} site @param {number} ms @param {number[]} [ecef] pozycja stacji (przyspiesza pętle)
 */
export function lookAngles(s, site, ms, ecef = siteEcef(site)) {
  const pv = sgp4(s, (ms - s.epochMs) / 60_000);
  if (!pv) return null;
  const g = gmst(jdFromMs(ms));
  const cg = Math.cos(g), sg = Math.sin(g);
  const [x, y, z] = pv.r;
  // TEME → ECEF (bez ruchu bieguna)
  const ex = x * cg + y * sg, ey = -x * sg + y * cg, ez = z;
  const dx = ex - ecef[0], dy = ey - ecef[1], dz = ez - ecef[2];
  const lat = site.lat * DEG, lon = site.lon * DEG;
  const sl = Math.sin(lat), cl = Math.cos(lat), so = Math.sin(lon), co = Math.cos(lon);
  const south = sl * co * dx + sl * so * dy - cl * dz;
  const east = -so * dx + co * dy;
  const up = cl * co * dx + cl * so * dy + sl * dz;
  const range = Math.sqrt(south * south + east * east + up * up);
  let az = Math.atan2(east, -south) / DEG;
  if (az < 0) az += 360;
  return { az, el: Math.asin(up / range) / DEG, range };
}

/**
 * Najbliższy przelot (trwający albo następny) w ciągu [hours] godzin od [fromMs]. Dla satelitów stale nad
 * horyzontem (geostacjonarne) albo bez przelotu – null.
 * @param {SatRec} s @param {Site} site @param {number} fromMs @param {number} [hours]
 * @returns {{aos: number, los: number, maxEl: number, maxElAt: number} | null}
 */
export function nextPass(s, site, fromMs, hours = 24) {
  const ecef = siteEcef(site);
  const el = (/** @type {number} */ ms) => lookAngles(s, site, ms, ecef)?.el ?? -90;
  // Krok zależny od okresu orbity: LEO co 30 s, wyższe orbity rzadziej
  const step = Math.min(300_000, Math.max(30_000, s.periodMin * 60_000 / 180));
  const end = fromMs + hours * 3_600_000;
  // Trwający przelot – szukamy jego początku do 30 minut wstecz
  let t = fromMs;
  if (el(t) > 0) {
    let back = t;
    while (back > fromMs - 30 * 60_000 && el(back - step) > 0) back -= step;
    if (el(back - step) > 0) return null; // stale nad horyzontem
    t = back - step;
  } else {
    while (t < end && el(t + step) <= 0) t += step;
    if (t >= end) return null;
  }
  // Bisekcja AOS z dokładnością do 1 s
  let lo = t, hi = t + step;
  while (hi - lo > 1000) { const mid = (lo + hi) / 2; if (el(mid) > 0) hi = mid; else lo = mid; }
  const aos = hi;
  let u = aos;
  while (u < aos + 24 * 3_600_000 && el(u + step) > 0) u += step;
  if (u >= aos + 24 * 3_600_000) return null;
  lo = u; hi = u + step;
  while (hi - lo > 1000) { const mid = (lo + hi) / 2; if (el(mid) > 0) lo = mid; else hi = mid; }
  const los = lo;
  // Maksymalna elewacja: próbki co ~10 s i doprecyzowanie złotym podziałem
  const samples = Math.max(8, Math.ceil((los - aos) / 10_000));
  let best = aos, bestEl = -90;
  for (let i = 0; i <= samples; i++) {
    const ms = aos + ((los - aos) * i) / samples;
    const e = el(ms);
    if (e > bestEl) { bestEl = e; best = ms; }
  }
  let a = Math.max(aos, best - (los - aos) / samples), b = Math.min(los, best + (los - aos) / samples);
  const gr = (Math.sqrt(5) - 1) / 2;
  for (let i = 0; i < 30 && b - a > 200; i++) {
    const c = b - gr * (b - a), d = a + gr * (b - a);
    if (el(c) > el(d)) b = d; else a = c;
  }
  const maxElAt = (a + b) / 2;
  return { aos, los, maxEl: Math.max(bestEl, el(maxElAt)), maxElAt };
}

/** Trajektoria przelotu na niebie (punkty nad horyzontem). @param {SatRec} s @param {Site} site @param {number} aos @param {number} los @param {number} [steps] */
export function skyTrack(s, site, aos, los, steps = 40) {
  const ecef = siteEcef(site);
  /** @type {{az: number, el: number}[]} */
  const out = [];
  for (let i = 0; i <= steps; i++) {
    const p = lookAngles(s, site, aos + ((los - aos) * i) / steps, ecef);
    if (p && p.el >= 0) out.push({ az: p.az, el: p.el });
  }
  return out;
}

/** Plik TLE (nazwa + dwie linie) → lista. @param {string} raw @returns {Tle[]} */
export function parseTles(raw) {
  const lines = raw.split(/\r?\n/).map((l) => l.replace(/\s+$/, "")).filter((l) => l.trim());
  /** @type {Tle[]} */
  const out = [];
  for (let i = 0; i + 2 < lines.length + 0;) {
    const name = lines[i].trim(), l1 = lines[i + 1]?.trimStart() ?? "", l2 = lines[i + 2]?.trimStart() ?? "";
    if (l1.startsWith("1 ") && l2.startsWith("2 ")) {
      out.push({ name, line1: l1.padEnd(69, " "), line2: l2.padEnd(69, " ") });
      i += 3;
    } else i++;
  }
  return out;
}
