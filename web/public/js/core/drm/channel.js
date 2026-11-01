// @ts-check
// Dekodowanie FAC i MSC HamDRM (DrmChannelDecoder.kt) oraz kod Reeda-Solomona plików .rs1–.rs4 QSSTV (ReedSolomon.kt).
import { NUM_FAC_CELLS, Occupancy, interleaverTable, ConvCode, energyDispersal, drmCrc, qamAxis } from "./tables.js";

/**
 * @typedef {{frameId: number, occupancy: import("./tables.js").DrmOccupancy, longInterleave: boolean, qamLevels: number,
 *   highProtection: boolean, isData: boolean, packetId: number, labelPart: string, wide: boolean, qamLabel: string}} DrmFacInfo
 * @typedef {{re: Float32Array, im: Float32Array, weight: Float32Array}} DrmCells
 * @typedef {{qamLevels: number, rates: number[], interleaverT0: number[]}} MscCoding
 */

const FAC_BITS = 48, FAC_CODED = 90, FAC_RATE = 6, WIDE_CRC_XOR = 0x5a;

/** @param {Uint8Array} bits @param {number} n */
const toBytes = (bits, n) => Array.from({ length: n }, (_, b) => { let v = 0; for (let k = 0; k < 8; k++) v = (v << 1) | bits[b * 8 + k]; return v; });

/** @param {DrmCells} cells @returns {DrmFacInfo | null} */
export function decodeFac(cells) {
  if (cells.re.length !== NUM_FAC_CELLS) throw new Error("FAC");
  const llr = new Float32Array(FAC_CODED);
  for (let i = 0; i < cells.re.length; i++) { llr[2 * i] = 2 * cells.re[i] * cells.weight[i]; llr[2 * i + 1] = 2 * cells.im[i] * cells.weight[i]; }
  const table = interleaverTable(FAC_CODED, 21);
  const orig = new Float32Array(FAC_CODED);
  for (let p = 0; p < FAC_CODED; p++) orig[table[p]] = llr[p];
  const bits = ConvCode.decode(orig, ConvCode.pattern(FAC_BITS, FAC_RATE, FAC_CODED, true), FAC_BITS);
  energyDispersal(bits);
  const bytes = toBytes(bits, 6);
  const crc = drmCrc(bytes, 0, 5, 8);
  let wide;
  if (bytes[5] === crc) wide = false; else if (bytes[5] === (crc ^ WIDE_CRC_XOR)) wide = true; else return null;
  /** @param {number} from @param {number} len */
  const field = (from, len) => { let v = 0; for (let i = from; i < from + len; i++) v = (v << 1) | bits[i]; return v; };
  const f0 = field(0, 2);
  const isData = field(6, 1) === 1;
  const qam = field(4, 1) === 0 ? 3 : isData && field(9, 1) === 1 ? 1 : 2;
  let label = "";
  for (let c = 0; c < 3; c++) { const ch = field(10 + c * 7, 7); if (ch >= 32 && ch <= 126) label += String.fromCharCode(ch); }
  return {
    frameId: f0 === 3 ? 0 : f0 === 1 ? 1 : f0 === 2 ? 2 : 0,
    occupancy: field(2, 1) === 1 ? Occupancy.SO_1 : Occupancy.SO_0,
    longInterleave: field(3, 1) === 0, qamLevels: qam, highProtection: field(5, 1) === 0, isData,
    packetId: isData ? field(7, 2) : 0, labelPart: label, wide, qamLabel: qam === 1 ? "4-QAM" : qam === 2 ? "16-QAM" : "64-QAM",
  };
}

/** @param {number} cells @param {MscCoding} coding */
export const mscInfoBits = (cells, coding) => coding.rates.reduce((a, r) => a + ConvCode.infoBits(2 * cells, r), 0);

/** Dekodowanie wielopoziomowe ramki MSC – bajty po zdjęciu PRBS. @param {DrmCells} cells @param {MscCoding} coding @returns {number[]} */
export function decodeMsc(cells, coding) {
  const n = cells.re.length, levels = coding.qamLevels, axis = qamAxis(levels), coded = 2 * n;
  const known = Array.from({ length: levels }, () => new Uint8Array(coded));
  /** @type {Uint8Array[]} */
  const info = [];
  const llr = new Float32Array(coded), orig = new Float32Array(coded);
  for (let j = 0; j < levels; j++) {
    const bitOfLevel = levels - 1 - j;
    for (let p = 0; p < coded; p++) {
      const v = p % 2 === 0 ? cells.re[p >> 1] : cells.im[p >> 1];
      let d0 = Infinity, d1 = Infinity;
      for (let idx = 0; idx < axis.length; idx++) {
        let ok = true;
        for (let lower = 0; lower < j; lower++) if (((idx >> (levels - 1 - lower)) & 1) !== known[lower][p]) { ok = false; break; }
        if (!ok) continue;
        const d = (v - axis[idx]) ** 2;
        if (((idx >> bitOfLevel) & 1) === 0) { if (d < d0) d0 = d; } else if (d < d1) d1 = d;
      }
      llr[p] = cells.weight[p >> 1] * (d1 - d0);
    }
    const t0 = coding.interleaverT0[j];
    const table = t0 >= 0 ? interleaverTable(coded, t0) : null;
    if (table) for (let p = 0; p < coded; p++) orig[table[p]] = llr[p];
    else orig.set(llr);
    const rate = coding.rates[j];
    const infoBits = ConvCode.infoBits(coded, rate);
    const pattern = ConvCode.pattern(infoBits, rate, coded, false);
    const bits = ConvCode.decode(orig, pattern, infoBits);
    info.push(bits);
    if (j < levels - 1) {
      const enc = ConvCode.encode(bits, pattern);
      for (let p = 0; p < coded; p++) known[j][p] = (table ? enc[table[p]] : enc[p]) ?? 0;
    }
  }
  const total = info.reduce((a, b) => a + b.length, 0);
  const all = new Uint8Array(total);
  let pos = 0;
  for (const b of info) { all.set(b, pos); pos += b.length; }
  energyDispersal(all);
  return toBytes(all, Math.trunc(total / 8));
}

// ---------------------------------------------------------------- Reed-Solomon (255, k) GF(2^8), 0x11D

const EXP = new Int32Array(512), LOG = new Int32Array(256);
{
  let x = 1;
  for (let i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
}
const Gf = {
  /** @param {number} e */
  exp: (e) => EXP[((e % 255) + 255) % 255],
  /** @param {number} a @param {number} b */
  mul: (a, b) => (a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]]),
  /** @param {number} a @param {number} b */
  div: (a, b) => (a === 0 ? 0 : EXP[(LOG[a] - LOG[b] + 255) % 255]),
  /** @param {number} a */
  inv: (a) => EXP[(255 - LOG[a]) % 255],
};

export class ReedSolomon {
  /** @param {number} k */
  constructor(k) {
    this.k = k; this.n = 255; this.nroots = 255 - k;
    const g = new Int32Array(this.nroots + 1);
    g[0] = 1;
    for (let i = 0; i < this.nroots; i++) {
      const root = Gf.exp(1 + i);
      for (let j = i + 1; j >= 1; j--) g[j] = g[j - 1] ^ Gf.mul(g[j], root);
      g[0] = Gf.mul(g[0], root);
    }
    this.gen = g;
  }
  /** @param {ArrayLike<number>} data @returns {number[]} */
  encode(data) {
    const { nroots, gen, k } = this;
    const parity = new Array(nroots).fill(0);
    for (let i = k - 1; i >= 0; i--) {
      const fb = data[i] ^ parity[nroots - 1];
      for (let j = nroots - 1; j >= 1; j--) parity[j] = parity[j - 1] ^ Gf.mul(fb, gen[j]);
      parity[0] = Gf.mul(fb, gen[0]);
    }
    return [...Array.from({ length: k }, (_, i) => data[i]), ...parity];
  }
  /** Poprawia blok w miejscu; liczba poprawionych albo −1. @param {number[]} block @param {number[]} [erasures] */
  decode(block, erasures = []) {
    const { n, nroots } = this;
    const s = Array.from({ length: nroots }, (_, i) => {
      let acc = 0;
      const a = Gf.exp(i + 1);
      for (let j = n - 1; j >= 0; j--) acc = Gf.mul(acc, a) ^ block[j];
      return acc;
    });
    if (s.every((v) => v === 0)) return 0;
    if (erasures.length > nroots) return -1;
    let lambda = new Array(nroots + 1).fill(0);
    lambda[0] = 1;
    for (const e of erasures) {
      const x = Gf.exp(e);
      for (let j = nroots; j >= 1; j--) lambda[j] ^= Gf.mul(lambda[j - 1], x);
    }
    /** @param {number[]} p */
    const shiftUp = (p) => p.map((_, i) => (i === 0 ? 0 : p[i - 1]));
    let b = lambda.slice();
    let el = erasures.length;
    const noEras = erasures.length;
    for (let r = noEras + 1; r <= nroots; r++) {
      let delta = 0;
      for (let i = 0; i < r; i++) delta ^= Gf.mul(lambda[i], s[r - i - 1]);
      if (delta === 0) { b = shiftUp(b); continue; }
      const t = new Array(nroots + 1).fill(0);
      t[0] = lambda[0];
      for (let i = 0; i < nroots; i++) t[i + 1] = lambda[i + 1] ^ Gf.mul(delta, b[i]);
      if (2 * el <= r + noEras - 1) {
        el = r + noEras - el;
        const inv = Gf.inv(delta);
        b = lambda.map((v) => Gf.mul(v, inv));
      } else b = shiftUp(b);
      lambda = t;
    }
    let deg = 0;
    for (let i = 0; i < lambda.length; i++) if (lambda[i] !== 0) deg = i;
    /** @type {number[]} */
    const positions = [];
    for (let pos = 0; pos < n; pos++) {
      const xinv = Gf.exp(255 - pos);
      let v = 0, p = 1;
      for (let i = 0; i <= deg; i++) { v ^= Gf.mul(lambda[i], p); p = Gf.mul(p, xinv); }
      if (v === 0) positions.push(pos);
    }
    if (positions.length !== deg) return -1;
    const omega = new Array(nroots).fill(0);
    for (let i = 0; i < nroots; i++) {
      let acc = 0;
      for (let j = 0; j <= Math.min(i, deg); j++) acc ^= Gf.mul(s[i - j], lambda[j]);
      omega[i] = acc;
    }
    for (const pos of positions) {
      const xinv = Gf.exp(255 - pos);
      let num = 0, p = 1;
      for (let i = 0; i < nroots; i++) { num ^= Gf.mul(omega[i], p); p = Gf.mul(p, xinv); }
      let den = 0;
      p = 1;
      const x2 = Gf.mul(xinv, xinv);
      for (let i = 1; i <= deg; i += 2) { den ^= Gf.mul(lambda[i], p); p = Gf.mul(p, x2); }
      if (den === 0) return -1;
      block[pos] ^= Gf.div(num, den);
    }
    return positions.length;
  }
}

/** @type {Record<string, number>} */
const RS_DATA = { rs1: 224, rs2: 192, rs3: 160, rs4: 128 };
/** @param {string} ext */
export const isRsExtension = (ext) => ext.toLowerCase() in RS_DATA;

/**
 * Plik .rs1–.rs4: przeplot bajtów, bloki RS, nagłówek 7 bajtów z rozszerzeniem.
 * @param {Uint8Array} received @param {string} ext @param {Uint8Array | null} missing 1 = bajt z brakującego segmentu
 * @returns {{bytes: Uint8Array, extension: string, corrected: number} | null}
 */
export function rsFileDecode(received, ext, missing) {
  const k = RS_DATA[ext.toLowerCase()];
  if (!k) return null;
  const rows = Math.ceil(received.length / 255);
  if (!rows) return null;
  const total = rows * 255;
  const rs = new ReedSolomon(k);
  const blocks = Array.from({ length: rows }, () => new Array(255).fill(0));
  const erased = Array.from({ length: rows }, () => /** @type {number[]} */ ([]));
  for (let i = 0; i < total; i++) {
    blocks[i % rows][Math.trunc(i / rows)] = i < received.length ? received[i] : 0;
    if (missing && i < missing.length && missing[i]) erased[i % rows].push(Math.trunc(i / rows));
  }
  let corrected = 0;
  const data = new Uint8Array(rows * k);
  for (let r = 0; r < rows; r++) {
    const res = rs.decode(blocks[r], erased[r]);
    if (res < 0) return null;
    corrected += res;
    for (let j = 0; j < k; j++) data[r * k + j] = blocks[r][j];
  }
  if (data.length < 7) return null;
  if (data[1] + data[2] * 256 !== rows) return null;
  const size = rows * k - data[0];
  let extension = String.fromCharCode(data[3], data[4], data[5]).replace(/\0+$/, "");
  if (extension === "avi") extension = "avif";
  return { bytes: data.slice(7, Math.min(7 + size, data.length)), extension, corrected };
}

/** Kodowanie jak w nadajniku (testy). @param {Uint8Array} file @param {string} ext */
export function rsFileEncode(file, ext) {
  const k = RS_DATA[ext];
  const got = file.length;
  let chunks = Math.trunc((got + 7) / k);
  if ((got + 7) % k > 0) chunks++;
  const plain = new Uint8Array(chunks * k);
  plain[0] = (k - (got % k)) & 0xff; plain[1] = chunks % 256; plain[2] = Math.trunc(chunks / 256);
  const suffix = ext.padEnd(3, "\0");
  for (let i = 0; i < 3; i++) plain[3 + i] = suffix.charCodeAt(i);
  plain.set(file.subarray(0, Math.min(got, plain.length - 7)), 7);
  const rs = new ReedSolomon(k);
  const ec = new Array(chunks * 255);
  for (let c = 0; c < chunks; c++) rs.encode(plain.subarray(c * k, c * k + k)).forEach((v, i) => { ec[c * 255 + i] = v; });
  const out = new Uint8Array(chunks * 255);
  for (let n = 0; n < ec.length; n++) out[(n % 255) * chunks + Math.trunc(n / 255)] = ec[n];
  return out;
}
