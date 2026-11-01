// @ts-check
// HamDRM (DSSTV) – parametry sygnału i kodowanie kanałowe. Port DrmMode.kt i DrmCoding.kt (ES 201 980 rozdz. 7–8,
// specyfikacja "DRM mode HAM" HB9TLK; tabele pilotów i FAC – sygnał programów EasyPal/QSSTV).

/** @typedef {{label: string, index: number, fftSize48: number, guardNum: number, guardDen: number, symbolsPerFrame: number, scatFreqInt: number, scatTimeInt: number, guard48: number}} DrmRobustness */
/** @typedef {{label: string, index: number}} DrmOccupancy */

/** @param {string} label @param {number} index @param {number} fft @param {number} gn @param {number} gd @param {number} sym @param {number} sf @param {number} st @returns {DrmRobustness} */
const rob = (label, index, fft, gn, gd, sym, sf, st) => ({ label, index, fftSize48: fft, guardNum: gn, guardDen: gd, symbolsPerFrame: sym, scatFreqInt: sf, scatTimeInt: st, guard48: (fft * gn) / gd });
export const Robustness = { A: rob("A", 0, 1152, 1, 9, 15, 4, 5), B: rob("B", 1, 1024, 1, 4, 15, 2, 3), E: rob("E", 2, 640, 1, 2, 20, 1, 4) };
export const ROBUSTNESS = [Robustness.A, Robustness.B, Robustness.E];
export const Occupancy = { SO_0: { label: "2,3 kHz", index: 0 }, SO_1: { label: "2,5 kHz", index: 1 } };
export const OCCUPANCY = [Occupancy.SO_0, Occupancy.SO_1];

export const CellType = { MSC: 1, FAC: 2, SCAT: 4, TIME: 8, FREQ: 16, BOOSTED: 32, DC: 64 };
/** @param {number} t */
export const isPilot = (t) => (t & (CellType.SCAT | CellType.TIME | CellType.FREQ)) !== 0;

export const FRAMES_PER_SUPERFRAME = 3;
export const NUM_FAC_CELLS = 45;
export const DC_FREQ_HZ = 350;

const KMIN = [[2, 1, 1], [2, 1, 1]];
const KMAX = [[54, 45, 29], [58, 51, 31]];
/** @param {DrmRobustness} m @param {DrmOccupancy} o */
export const kMin = (m, o) => KMIN[o.index][m.index];
/** @param {DrmRobustness} m @param {DrmOccupancy} o */
export const kMax = (m, o) => KMAX[o.index][m.index];

const FAC = [
  [1, 10, 1, 22, 1, 30, 1, 50, 2, 14, 2, 26, 2, 34, 3, 18, 3, 30, 3, 38, 4, 22, 4, 34, 4, 42,
    5, 18, 5, 26, 5, 38, 5, 46, 6, 22, 6, 30, 6, 42, 6, 50, 7, 26, 7, 34, 7, 46, 8, 10, 8, 30, 8, 38, 8, 50,
    9, 14, 9, 34, 9, 42, 10, 18, 10, 38, 10, 46, 11, 10, 11, 22, 11, 42, 11, 50, 12, 14, 12, 26, 12, 46,
    13, 18, 13, 30, 14, 22, 14, 34],
  [0, 21, 1, 11, 1, 23, 1, 35, 2, 13, 2, 25, 2, 37, 3, 15, 3, 27, 3, 39, 4, 5, 4, 17, 4, 29, 4, 41,
    5, 7, 5, 19, 5, 31, 6, 9, 6, 21, 6, 33, 7, 11, 7, 23, 7, 35, 8, 13, 8, 25, 8, 37, 9, 15, 9, 27, 9, 39,
    10, 5, 10, 17, 10, 29, 10, 41, 11, 7, 11, 19, 11, 31, 12, 9, 12, 21, 12, 33, 13, 11, 13, 23, 13, 35,
    14, 13, 14, 25, 14, 37],
  [1, 7, 1, 23, 2, 8, 2, 16, 2, 24, 3, 9, 3, 17, 4, 10, 4, 18, 5, 11, 5, 19, 6, 4, 6, 12, 7, 13, 7, 21,
    8, 6, 8, 14, 8, 22, 9, 7, 9, 23, 10, 8, 10, 16, 10, 24, 11, 9, 11, 13, 11, 17, 12, 10, 12, 18,
    13, 11, 13, 19, 14, 4, 14, 12, 14, 16, 15, 13, 15, 21, 16, 6, 16, 14, 16, 22, 17, 7, 17, 23,
    18, 8, 18, 16, 18, 24, 19, 9, 19, 17],
];
const TIME = [
  [6, 973, 7, 205, 11, 717, 12, 264, 15, 357, 16, 357, 23, 952, 29, 440, 30, 856, 33, 88, 34, 88, 38, 68, 39, 836, 41, 836, 45, 836, 46, 1008],
  [6, 304, 10, 331, 11, 108, 14, 620, 17, 192, 18, 704, 27, 44, 28, 432, 30, 588, 33, 844, 34, 651, 38, 651, 40, 651, 41, 460, 44, 944],
  [7, 432, 8, 331, 13, 108, 14, 620, 21, 192, 22, 704, 26, 44, 27, 304],
];
const FREQ = [[9, 205, 27, 836, 36, 215], [8, 331, 24, 651, 32, 555], [5, 788, 15, 1014, 20, 332]];
const SCAT_CONST = [[4, 5, 2], [2, 3, 1], [1, 4, 1]];
const W = [
  [[228, 341, 455], [455, 569, 683], [683, 796, 910], [910, 0, 114], [114, 228, 341]],
  [[512, 0, 512, 0, 512], [0, 512, 0, 512, 0], [512, 0, 512, 0, 512]],
  [[512, 0, 512, 0, 512], [0, 512, 0, 512, 0], [512, 0, 512, 0, 512], [0, 512, 0, 512, 0]],
];
const Z = [
  [[0, 81, 248], [18, 106, 106], [122, 116, 31], [129, 129, 39], [33, 32, 111]],
  [[0, 57, 164, 64, 12], [168, 255, 161, 106, 118], [25, 232, 132, 233, 38]],
  [[0, 57, 164, 64, 12], [168, 255, 161, 106, 118], [25, 232, 132, 233, 38], [168, 255, 161, 106, 118]],
];
const Q = [36, 12, 10];
const BOOSTED = [[[2, 4, 50, 54], [2, 6, 54, 58]], [[1, 3, 43, 45], [1, 3, 49, 51]], [[1, 29], [1, 31]]];

/** @param {number} a @param {number} b */
export const floorMod = (a, b) => ((a % b) + b) % b;
/** @param {DrmRobustness} m */
export const facTable = (m) => FAC[m.index];
/** @param {DrmRobustness} m */
export const timePilots = (m) => TIME[m.index];
/** @param {DrmRobustness} m */
export const freqPilots = (m) => FREQ[m.index];

/** @param {DrmRobustness} mode @param {number} sym @param {number} car */
export function scatteredPhase(mode, sym, car) {
  const c = SCAT_CONST[mode.index], w = W[mode.index], z = Z[mode.index];
  const n = sym % c[1], m = Math.trunc(sym / c[1]);
  const p = Math.trunc((car - c[2] - n * c[0]) / (c[0] * c[1]));
  return floorMod(4 * z[n][m] + p * w[n][m] + p * p * (1 + sym) * Q[mode.index], 1024);
}

/** Mapa komórek ramki i wartości pilotów (ES 201 980 8.4). Indeks nośnej: k − kMin. */
export class DrmCellMap {
  /** @param {DrmRobustness} mode @param {DrmOccupancy} occupancy */
  constructor(mode, occupancy) {
    this.mode = mode; this.occupancy = occupancy;
    this.kMin = kMin(mode, occupancy); this.kMax = kMax(mode, occupancy);
    this.numCarriers = this.kMax - this.kMin + 1;
    const S = (this.symbols = mode.symbolsPerFrame), N = this.numCarriers;
    this.type = Array.from({ length: S }, () => new Int32Array(N));
    this.pilotRe = Array.from({ length: S }, () => new Float32Array(N));
    this.pilotIm = Array.from({ length: S }, () => new Float32Array(N));
    this.mscPerSymbol = new Int32Array(S);
    this.facPerSymbol = new Int32Array(S);
    const fac = facTable(mode), timeP = timePilots(mode), freqP = freqPilots(mode), boosted = BOOSTED[mode.index][occupancy.index];
    const x = mode.scatFreqInt, y = mode.scatTimeInt, k0 = Math.trunc((x + 1) / 2);
    /** @param {number} s @param {number} i @param {number} gain @param {number} ph */
    const setPilot = (s, i, gain, ph) => { const a = (2 * Math.PI * ph) / 1024; this.pilotRe[s][i] = gain * Math.cos(a); this.pilotIm[s][i] = gain * Math.sin(a); };
    for (let s = 0; s < S; s++) {
      for (let k = this.kMin; k <= this.kMax; k++) {
        const i = k - this.kMin;
        let t = CellType.MSC;
        for (let f = 0; f < NUM_FAC_CELLS; f++) if (fac[f * 2] === s && fac[f * 2 + 1] === k) t = CellType.FAC;
        if (floorMod(k - k0 - x * (s % y), x * y) === 0 && k >= k0 + x * (s % y)) {
          t = CellType.SCAT;
          const b = boosted.includes(k);
          if (b) t |= CellType.BOOSTED;
          setPilot(s, i, b ? 2 : Math.SQRT2, scatteredPhase(mode, s, k));
        }
        if (s === 0) {
          for (let p = 0; p < timeP.length / 2; p++) if (timeP[p * 2] === k) {
            t = t & CellType.SCAT ? t | CellType.TIME : CellType.TIME;
            setPilot(s, i, Math.SQRT2, timeP[p * 2 + 1]);
          }
        }
        for (let p = 0; p < freqP.length / 2; p++) if (freqP[p * 2] === k) {
          t = t & (CellType.TIME | CellType.SCAT) ? t | CellType.FREQ : CellType.FREQ;
          const special = mode === Robustness.E && p !== freqP.length / 2 - 1 && s % 2 === 1;
          setPilot(s, i, Math.SQRT2, special ? (freqP[p * 2 + 1] + 512) % 1024 : freqP[p * 2 + 1]);
        }
        if (k === 0 || (mode === Robustness.A && (k === 1 || k === -1))) t = CellType.DC;
        this.type[s][i] = t;
      }
    }
    let msc = 0;
    for (let s = 0; s < S; s++) {
      let m = 0, f = 0;
      for (let i = 0; i < N; i++) { if (this.type[s][i] === CellType.MSC) m++; if (this.type[s][i] === CellType.FAC) f++; }
      this.mscPerSymbol[s] = m; this.facPerSymbol[s] = f; msc += m;
    }
    const facSum = this.facPerSymbol.reduce((a, b) => a + b, 0);
    if (facSum !== NUM_FAC_CELLS) throw new Error(`FAC: ${facSum} komórek`);
    const per = msc * FRAMES_PER_SUPERFRAME;
    this.mscCellsPerFrame = Math.trunc(per / FRAMES_PER_SUPERFRAME);
    this.dummyCells = per - this.mscCellsPerFrame * FRAMES_PER_SUPERFRAME;
  }
  /** @param {number} s @param {number} k */
  isScatteredCarrier(s, k) { return (this.type[s][k - this.kMin] & CellType.SCAT) !== 0; }

  /** @param {DrmRobustness} mode @param {DrmOccupancy} occ @returns {DrmCellMap} */
  static of(mode, occ) {
    const key = `${mode.label}${occ.index}`;
    let m = CELL_CACHE.get(key);
    if (!m) { m = new DrmCellMap(mode, occ); CELL_CACHE.set(key, m); }
    return m;
  }
}
/** @type {Map<string, DrmCellMap>} */
const CELL_CACHE = new Map();

// ---------------------------------------------------------------- kodowanie kanałowe (DrmCoding.kt)

/** CRC-8 / CRC-16 DRM: rejestr od jedynek, wynik zanegowany. @param {ArrayLike<number>} data @param {number} offset @param {number} length @param {8|16} degree */
export function drmCrc(data, offset, length, degree) {
  const poly = degree === 8 ? 0x1d : 0x1021, top = 1 << (degree - 1), mask = (1 << degree) - 1;
  let reg = mask;
  for (let i = offset; i < offset + length; i++) {
    const b = data[i];
    for (let bit = 7; bit >= 0; bit--) {
      const fb = ((reg & top) !== 0) !== (((b >> bit) & 1) === 1);
      reg = (reg << 1) & mask;
      if (fb) reg ^= poly;
    }
  }
  return ~reg & mask;
}

/** PRBS x^9+x^5+1 nakładany XOR. @param {Uint8Array} bits @param {number} [count] */
export function energyDispersal(bits, count = bits.length) {
  let reg = 0x1ff;
  for (let i = 0; i < count; i++) {
    const prbs = ((reg >> 4) & 1) ^ ((reg >> 8) & 1);
    reg = ((reg << 1) | prbs) & 0x1ff;
    bits[i] ^= prbs;
  }
}

/** @type {Map<string, Int32Array>} */
const INTERLEAVER_CACHE = new Map();
/** Przeplatacz blokowy 7.3.3. @param {number} size @param {number} t0 */
export function interleaverTable(size, t0) {
  const key = `${size}/${t0}`;
  let t = INTERLEAVER_CACHE.get(key);
  if (t) return t;
  let s = 1;
  while (s <= size) s <<= 1;
  const q = s / 4 - 1;
  t = new Int32Array(size);
  for (let i = 1; i < size; i++) {
    let v = (t0 * t[i - 1] + q) % s;
    while (v >= size) v = (t0 * v + q) % s;
    t[i] = v;
  }
  INTERLEAVER_CACHE.set(key, t);
  return t;
}

const GEN = [0b1101101, 0b1001111, 0b1010011, 0b1101101];
const P1111 = 15, P0111 = 7, P0011 = 3, P0001 = 1, P0101 = 5;
const PUNCTURING = [
  [1, 4, P1111], [3, 10, P1111, P0111, P0111], [1, 3, P0111], [4, 11, P0111, P0111, P0111, P0011], [1, 2, P0011],
  [4, 7, P0011, P0101, P0011, P0001], [3, 5, P0011, P0001, P0011], [2, 3, P0011, P0001],
  [8, 11, P0011, P0001, P0001, P0011, P0001, P0001, P0011, P0001], [3, 4, P0011, P0001, P0001], [4, 5, P0011, P0001, P0001, P0001],
  [7, 8, P0011, P0001, P0001, P0001, P0001, P0001, P0001], [8, 9, P0011, P0001, P0001, P0001, P0001, P0001, P0001, P0001],
];
const TAIL = [
  [P0011, P0011, P0011, P0011, P0011, P0011], [P0111, P0011, P0011, P0011, P0011, P0011], [P0111, P0011, P0011, P0111, P0011, P0011],
  [P0111, P0111, P0011, P0111, P0011, P0011], [P0111, P0111, P0011, P0111, P0111, P0011], [P0111, P0111, P0111, P0111, P0111, P0011],
  [P0111, P0111, P0111, P0111, P0111, P0111], [P1111, P0111, P0111, P0111, P0111, P0111], [P1111, P0111, P0111, P1111, P0111, P0111],
  [P1111, P1111, P0111, P1111, P0111, P0111], [P1111, P1111, P0111, P1111, P0111, P1111], [P1111, P1111, P1111, P1111, P0111, P1111],
];
/** @param {number} x */
const parity = (x) => { x ^= x >> 4; x ^= x >> 2; x ^= x >> 1; return x & 1; };
const OUT_BITS = GEN.map((g) => Uint8Array.from({ length: 128 }, (_, reg) => parity(reg & g)));

export const ConvCode = {
  /** @param {number} rate */
  rateNumerator: (rate) => PUNCTURING[rate][0],
  /** @param {number} rate */
  rateDenominator: (rate) => PUNCTURING[rate][1],
  /** @param {number} codedBits @param {number} rate */
  infoBits: (codedBits, rate) => PUNCTURING[rate][0] * Math.trunc((codedBits - 12) / PUNCTURING[rate][1]),
  /** @param {number} infoBits @param {number} rate @param {number} codedBits @param {boolean} facTail */
  pattern(infoBits, rate, codedBits, facTail) {
    const p = PUNCTURING[rate], period = p[0];
    const tailIndex = codedBits - 12 - p[1] * Math.trunc((codedBits - 12) / p[1]);
    return Int32Array.from({ length: infoBits + 6 }, (_, i) => (i < infoBits || facTail ? p[2 + (i % period)] : TAIL[tailIndex][i - infoBits]));
  },
  /** @param {Uint8Array} info @param {Int32Array} pattern */
  encode(info, pattern) {
    /** @type {number[]} */
    const out = [];
    let state = 0;
    for (let i = 0; i < pattern.length; i++) {
      state = (state << 1) & 0x7f;
      if (i < info.length && info[i]) state |= 1;
      for (let g = 0; g < 4; g++) if (pattern[i] & (1 << g)) out.push(parity(state & GEN[g]));
    }
    return Uint8Array.from(out);
  },
  /** Viterbi (miękkie decyzje, llr > 0 = bit 0). @param {Float32Array} llr @param {Int32Array} pattern @param {number} infoBits */
  decode(llr, pattern, infoBits) {
    const steps = pattern.length;
    let metric = new Float64Array(64).fill(-1e30);
    metric[0] = 0;
    let nm = new Float64Array(64);
    const decisions = new Uint8Array(steps * 64);
    const local = new Float64Array(4);
    let pos = 0;
    for (let i = 0; i < steps; i++) {
      const pat = pattern[i];
      for (let g = 0; g < 4; g++) {
        if (pat & (1 << g)) { local[g] = pos < llr.length ? llr[pos] : 0; pos++; } else local[g] = 0;
      }
      nm.fill(-1e30);
      const maxBit = i >= infoBits ? 0 : 1;
      for (let s = 0; s < 64; s++) {
        const m = metric[s];
        if (m <= -1e29) continue;
        for (let bit = 0; bit <= maxBit; bit++) {
          const reg = (s << 1) | bit;
          let bm = 0;
          for (let g = 0; g < 4; g++) if (pat & (1 << g)) bm += OUT_BITS[g][reg] === 0 ? local[g] : -local[g];
          const ns = reg & 0x3f, cand = m + bm;
          if (cand > nm[ns]) { nm[ns] = cand; decisions[i * 64 + ns] = s >> 5; }
        }
      }
      const tmp = metric; metric = nm; nm = tmp;
    }
    const bits = new Uint8Array(steps);
    let s = 0;
    for (let i = steps - 1; i >= 0; i--) {
      bits[i] = s & 1;
      s = (s >> 1) | (decisions[i * 64 + s] << 5);
    }
    return bits.slice(0, infoBits);
  },
};

export const QAM4 = [0.7071067811, -0.7071067811];
export const QAM16 = [0.948683298, -0.316227766, 0.316227766, -0.948683298];
export const QAM64 = [1.0801234497, -0.1543033499, 0.4629100498, -0.7715167498, 0.7715167498, -0.4629100498, 0.1543033499, -1.0801234497];
/** @param {number} levels */
export const qamAxis = (levels) => (levels === 1 ? QAM4 : levels === 2 ? QAM16 : QAM64);

/** Schemat kodowania MSC. @param {number} qamLevels @param {boolean} highProtection */
export function mscCoding(qamLevels, highProtection) {
  return {
    qamLevels,
    rates: qamLevels === 1 ? [6] : qamLevels === 2 ? (highProtection ? [2, 7] : [4, 9]) : highProtection ? [0, 4, 9] : [2, 7, 10],
    interleaverT0: qamLevels === 1 ? [21] : qamLevels === 2 ? [13, 21] : [-1, 13, 21],
  };
}
