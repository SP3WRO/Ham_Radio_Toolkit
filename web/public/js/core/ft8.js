// @ts-check
// Dekoder FT8/FT4 – przeniesienie Ft8Decoder.kt / Ft8Message.kt / Ft8Receiver.kt (te z kolei z ft8_lib, Kārlis Goba,
// licencja MIT): STFT do wodospadu, kandydaci po synchronizacji Costas, LDPC(174,91), CRC-14, rozpakowanie wiadomości.
import { MixedRadixFft } from "./fft.js";
import { NM, MN, NUM_ROWS } from "./ft8-tables.js";

/** @typedef {"FT8" | "FT4"} FtxMode */
export const FTX = {
  FT8: { label: "FT8", symbolPeriod: 0.16, slotTime: 15, numTones: 8 },
  FT4: { label: "FT4", symbolPeriod: 0.048, slotTime: 7.5, numTones: 4 },
};

const FT8 = { ND: 58, NN: 79, LENGTH_SYNC: 7, NUM_SYNC: 3, SYNC_OFFSET: 36, COSTAS: [3, 1, 4, 0, 6, 5, 2], GRAY: [0, 1, 3, 2, 5, 6, 4, 7] };
const FT4 = {
  ND: 87, NN: 105, LENGTH_SYNC: 4, NUM_SYNC: 4, SYNC_OFFSET: 33,
  COSTAS: [[0, 1, 3, 2], [1, 0, 2, 3], [2, 3, 1, 0], [3, 2, 0, 1]], GRAY: [0, 1, 3, 2],
  XOR: [0x4a, 0x5e, 0x89, 0xb4, 0xb0, 0x8a, 0x79, 0x55, 0xbe, 0x28],
};
/** @param {number} k */
export const ft4DataSymbolIndex = (k) => k + (k < 29 ? 5 : k < 58 ? 9 : 13);
const LDPC_N = 174, LDPC_K = 91, LDPC_M = 83;
const CRC_POLY = 0x2757, CRC_WIDTH = 14, TOPBIT = 1 << (CRC_WIDTH - 1);

// ---------------------------------------------------------------- wodospad (monitor.c)
export class Ft8Monitor {
  /** @param {number} sampleRate @param {FtxMode} mode */
  constructor(sampleRate = 12000, mode = "FT8", fMin = 200, fMax = 3000, timeOsr = 2, freqOsr = 2) {
    const m = FTX[mode];
    this.mode = mode;
    this.blockSize = Math.trunc(sampleRate * m.symbolPeriod);
    this.subblock = this.blockSize / timeOsr;
    this.nfft = this.blockSize * freqOsr;
    this.fft = new MixedRadixFft(this.nfft);
    this.window = new Float64Array(this.nfft);
    for (let i = 0; i < this.nfft; i++) { const x = Math.sin((Math.PI * i) / this.nfft); this.window[i] = (2 / this.nfft) * x * x; }
    this.lastFrame = new Float64Array(this.nfft);
    this.tRe = new Float64Array(this.nfft); this.tIm = new Float64Array(this.nfft);
    this.fRe = new Float64Array(this.nfft); this.fIm = new Float64Array(this.nfft);
    this.minBin = Math.trunc(fMin * m.symbolPeriod);
    this.maxBin = Math.trunc(fMax * m.symbolPeriod) + 1;
    const numBins = this.maxBin - this.minBin;
    this.wf = {
      maxBlocks: Math.trunc(m.slotTime / m.symbolPeriod), numBins, timeOsr, freqOsr, mode,
      blockStride: timeOsr * freqOsr * numBins, mag: new Uint8Array(Math.trunc(m.slotTime / m.symbolPeriod) * timeOsr * freqOsr * numBins), numBlocks: 0,
    };
  }
  reset() { this.wf.numBlocks = 0; }

  /** Jeden blok (symbol): frame[offset … offset + blockSize). @param {Float32Array} frame @param {number} offset */
  process(frame, offset) {
    const wf = this.wf;
    if (wf.numBlocks >= wf.maxBlocks) return;
    let out = wf.numBlocks * wf.blockStride;
    let fp = offset;
    const { nfft, subblock, lastFrame } = this;
    for (let ts = 0; ts < wf.timeOsr; ts++) {
      lastFrame.copyWithin(0, subblock, nfft);
      for (let pos = nfft - subblock; pos < nfft; pos++) lastFrame[pos] = frame[fp++];
      for (let pos = 0; pos < nfft; pos++) { this.tRe[pos] = this.window[pos] * lastFrame[pos]; this.tIm[pos] = 0; }
      this.fft.transform(this.tRe, this.tIm, this.fRe, this.fIm);
      for (let fs = 0; fs < wf.freqOsr; fs++) {
        for (let bin = this.minBin; bin < this.maxBin; bin++) {
          const src = bin * wf.freqOsr + fs;
          const mag2 = this.fIm[src] * this.fIm[src] + this.fRe[src] * this.fRe[src];
          const db = 10 * Math.log10(1e-12 + mag2);
          const scaled = Math.trunc(2 * db + 240);
          wf.mag[out++] = scaled < 0 ? 0 : scaled > 255 ? 255 : scaled;
        }
      }
    }
    wf.numBlocks++;
  }
}

/** @typedef {Ft8Monitor["wf"]} Waterfall */
/** @typedef {{score: number, timeOffset: number, freqOffset: number, timeSub: number, freqSub: number}} Candidate */

/** @param {Waterfall} wf @param {number} t @param {number} f @param {number} ts @param {number} fs */
function base(wf, t, f, ts, fs) { return ((t * wf.timeOsr + ts) * wf.freqOsr + fs) * wf.numBins + f; }
const magDb = (/** @type {Waterfall} */ wf, /** @type {number} */ i) => wf.mag[i] * 0.5 - 120;

/** @param {Waterfall} wf @param {number} t @param {number} f @param {number} ts @param {number} fs */
function syncScore(wf, t, f, ts, fs) {
  const ft4 = wf.mode === "FT4";
  const P = ft4 ? FT4 : FT8;
  const maxTone = ft4 ? 3 : 7;
  let score = 0, n = 0;
  const cand = base(wf, t, f, ts, fs);
  for (let m = 0; m < P.NUM_SYNC; m++) {
    for (let k = 0; k < P.LENGTH_SYNC; k++) {
      const block = (ft4 ? 1 : 0) + P.SYNC_OFFSET * m + k;
      const abs = t + block;
      if (abs < 0) continue;
      if (abs >= wf.numBlocks) break;
      const p = cand + block * wf.blockStride;
      const sm = ft4 ? FT4.COSTAS[m][k] : FT8.COSTAS[k];
      const ex = wf.mag[p + sm];
      if (sm > 0) { score += ex - wf.mag[p + sm - 1]; n++; }
      if (sm < maxTone) { score += ex - wf.mag[p + sm + 1]; n++; }
      if (k > 0 && abs > 0) { score += ex - wf.mag[p + sm - wf.blockStride]; n++; }
      if (k + 1 < P.LENGTH_SYNC && abs + 1 < wf.numBlocks) { score += ex - wf.mag[p + sm + wf.blockStride]; n++; }
    }
  }
  return n > 0 ? Math.trunc(score / n) : score;
}

/** @param {Waterfall} wf @param {number} maxCandidates @param {number} minScore @returns {Candidate[]} */
export function findCandidates(wf, maxCandidates, minScore) {
  /** @type {Candidate[]} */
  const all = [];
  const tones = FTX[wf.mode].numTones;
  for (let ts = 0; ts < wf.timeOsr; ts++) {
    for (let fs = 0; fs < wf.freqOsr; fs++) {
      for (let t = -10; t < 20; t++) {
        for (let f = 0; f + tones - 1 < wf.numBins; f++) {
          const score = syncScore(wf, t, f, ts, fs);
          if (score >= minScore) all.push({ score, timeOffset: t, freqOffset: f, timeSub: ts, freqSub: fs });
        }
      }
    }
  }
  // Stabilne sortowanie malejąco – ta sama kolejność co w aplikacji
  return all.sort((a, b) => b.score - a.score).slice(0, maxCandidates);
}

const max4 = (a, b, c, d) => Math.max(Math.max(a, b), Math.max(c, d));

/** @param {Waterfall} wf @param {Candidate} c @param {Float64Array} log174 */
function extractLikelihood(wf, c, log174) {
  const mag = base(wf, c.timeOffset, c.freqOffset, c.timeSub, c.freqSub);
  const s2 = new Float64Array(8);
  if (wf.mode === "FT4") {
    for (let k = 0; k < FT4.ND; k++) {
      const sym = ft4DataSymbolIndex(k), bit = 2 * k, block = c.timeOffset + sym;
      if (block < 0 || block >= wf.numBlocks) { log174[bit] = 0; log174[bit + 1] = 0; continue; }
      const idx = mag + sym * wf.blockStride;
      for (let j = 0; j < 4; j++) s2[j] = magDb(wf, idx + FT4.GRAY[j]);
      log174[bit] = Math.max(s2[2], s2[3]) - Math.max(s2[0], s2[1]);
      log174[bit + 1] = Math.max(s2[1], s2[3]) - Math.max(s2[0], s2[2]);
    }
    return;
  }
  for (let k = 0; k < FT8.ND; k++) {
    const sym = k + (k < 29 ? 7 : 14), bit = 3 * k, block = c.timeOffset + sym;
    if (block < 0 || block >= wf.numBlocks) { log174[bit] = log174[bit + 1] = log174[bit + 2] = 0; continue; }
    const idx = mag + sym * wf.blockStride;
    for (let j = 0; j < 8; j++) s2[j] = magDb(wf, idx + FT8.GRAY[j]);
    log174[bit] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3]);
    log174[bit + 1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5]);
    log174[bit + 2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6]);
  }
}

/** @param {Float64Array} log174 */
function normalizeLogl(log174) {
  let sum = 0, sum2 = 0;
  for (const v of log174) { sum += v; sum2 += v * v; }
  const inv = 1 / LDPC_N;
  const variance = (sum2 - sum * sum * inv) * inv;
  const norm = Math.sqrt(24 / variance);
  for (let i = 0; i < log174.length; i++) log174[i] *= norm;
}

// ---------------------------------------------------------------- LDPC i CRC
/** @param {Uint8Array} cw */
function ldpcCheck(cw) {
  let errors = 0;
  for (let m = 0; m < LDPC_M; m++) {
    let x = 0;
    for (let i = 0; i < NUM_ROWS[m]; i++) x ^= cw[NM[m][i] - 1];
    if (x) errors++;
  }
  return errors;
}
const fastTanh = (x) => {
  if (x < -4.97) return -1;
  if (x > 4.97) return 1;
  const x2 = x * x;
  return (x * (945 + x2 * (105 + x2))) / (945 + x2 * (420 + x2 * 15));
};
const fastAtanh = (x) => { const x2 = x * x; return (x * (945 + x2 * (-735 + x2 * 64))) / (945 + x2 * (-1050 + x2 * 225)); };

/** Belief propagation (ldpc.c: bp_decode); zwraca liczbę niespełnionych równań. @param {Float64Array} cw @param {number} maxIters @param {Uint8Array} plain */
export function bpDecode(cw, maxIters, plain) {
  const tov = Array.from({ length: LDPC_N }, () => new Float64Array(3));
  const toc = Array.from({ length: LDPC_M }, () => new Float64Array(7));
  let minErrors = LDPC_M;
  for (let iter = 0; iter < maxIters; iter++) {
    let plainSum = 0;
    for (let i = 0; i < LDPC_N; i++) {
      const bit = cw[i] + tov[i][0] + tov[i][1] + tov[i][2] > 0 ? 1 : 0;
      plain[i] = bit;
      plainSum += bit;
    }
    if (plainSum === 0) break;
    const errors = ldpcCheck(plain);
    if (errors < minErrors) { minErrors = errors; if (errors === 0) break; }
    for (let m = 0; m < LDPC_M; m++) {
      for (let n = 0; n < NUM_ROWS[m]; n++) {
        const bit = NM[m][n] - 1;
        let tnm = cw[bit];
        for (let mi = 0; mi < 3; mi++) if (MN[bit][mi] - 1 !== m) tnm += tov[bit][mi];
        toc[m][n] = fastTanh(-tnm / 2);
      }
    }
    for (let bit = 0; bit < LDPC_N; bit++) {
      for (let mi = 0; mi < 3; mi++) {
        const m = MN[bit][mi] - 1;
        let tmn = 1;
        for (let n = 0; n < NUM_ROWS[m]; n++) if (NM[m][n] - 1 !== bit) tmn *= toc[m][n];
        tov[bit][mi] = -2 * fastAtanh(tmn);
      }
    }
  }
  return minErrors;
}

/** CRC-14 (crc.c). @param {ArrayLike<number>} msg @param {number} numBits */
export function crc14(msg, numBits) {
  let rem = 0, idx = 0;
  for (let b = 0; b < numBits; b++) {
    if (b % 8 === 0) { rem ^= msg[idx] << (CRC_WIDTH - 8); idx++; }
    rem = rem & TOPBIT ? (rem << 1) ^ CRC_POLY : rem << 1;
    rem &= 0xffff;
  }
  return rem & ((TOPBIT << 1) - 1);
}

/** @param {Waterfall} wf @param {Candidate} c @param {number} maxIterations */
export function decodeCandidate(wf, c, maxIterations) {
  const log174 = new Float64Array(LDPC_N);
  extractLikelihood(wf, c, log174);
  normalizeLogl(log174);
  const plain = new Uint8Array(LDPC_N);
  if (bpDecode(log174, maxIterations, plain) > 0) return null;
  const a91 = new Array(12).fill(0);
  let mask = 0x80, bi = 0;
  for (let i = 0; i < LDPC_K; i++) {
    if (plain[i]) a91[bi] |= mask;
    mask >>= 1;
    if (!mask) { mask = 0x80; bi++; }
  }
  const crcExtracted = ((a91[9] & 0x07) << 11) | (a91[10] << 3) | (a91[11] >> 5);
  a91[9] &= 0xf8;
  a91[10] = 0;
  const crc = crc14(a91, 96 - 14);
  if (crcExtracted !== crc) return null;
  const payload = a91.slice(0, 10);
  if (wf.mode === "FT4") for (let i = 0; i < 10; i++) payload[i] ^= FT4.XOR[i];
  return { payload, hash: crc, codeword: plain };
}

/** Tony nadane przez stację, odtworzone ze słowa kodowego. @param {Uint8Array} cw @param {FtxMode} mode */
export function tonesFromCodeword(cw, mode = "FT8") {
  if (mode === "FT4") {
    const tones = new Array(FT4.NN).fill(0);
    for (let m = 0; m < FT4.NUM_SYNC; m++) for (let k = 0; k < FT4.LENGTH_SYNC; k++) tones[1 + FT4.SYNC_OFFSET * m + k] = FT4.COSTAS[m][k];
    for (let k = 0; k < FT4.ND; k++) tones[ft4DataSymbolIndex(k)] = FT4.GRAY[(cw[2 * k] << 1) | cw[2 * k + 1]];
    return tones;
  }
  const tones = new Array(FT8.NN).fill(0);
  for (let i = 0; i < 7; i++) tones[i] = tones[36 + i] = tones[72 + i] = FT8.COSTAS[i];
  for (let k = 0; k < FT8.ND; k++) tones[k + (k < 29 ? 7 : 14)] = FT8.GRAY[(cw[3 * k] << 2) | (cw[3 * k + 1] << 1) | cw[3 * k + 2]];
  return tones;
}

const SNR_SLOPE = 0.81, SNR_OFFSET = -29.4, SNR_OFFSET_FT4 = -25.2;
/** SNR w paśmie 2500 Hz (jak WSJT-X). @param {Waterfall} wf @param {Candidate} c @param {Uint8Array} cw */
export function estimateSnr(wf, c, cw) {
  const ft4 = wf.mode === "FT4";
  const tones = tonesFromCodeword(cw, wf.mode);
  const b0 = base(wf, c.timeOffset, c.freqOffset, c.timeSub, c.freqSub);
  const row = new Uint8Array(wf.numBins);
  let signal = 0, noise = 0, count = 0;
  const from = ft4 ? 1 : 0, to = ft4 ? FT4.NN - 1 : FT8.NN;
  for (let s = from; s < to; s++) {
    const block = c.timeOffset + s;
    if (block < 0 || block >= wf.numBlocks) continue;
    const idx = b0 + s * wf.blockStride;
    signal += Math.pow(10, magDb(wf, idx + tones[s]) / 10);
    const rs = idx - c.freqOffset;
    for (let b = 0; b < wf.numBins; b++) row[b] = wf.mag[rs + b];
    row.sort();
    noise += Math.pow(10, (row[row.length >> 1] * 0.5 - 120) / 10);
    count++;
  }
  if (!count || noise <= 0) return -24;
  const ratio = Math.max(1e-3, signal / noise - 1);
  return Math.min(40, Math.max(-30, Math.round(SNR_SLOPE * 10 * Math.log10(ratio) + (ft4 ? SNR_OFFSET_FT4 : SNR_OFFSET))));
}

// ---------------------------------------------------------------- wiadomości (message.c, text.c)
const MAX22 = 4194304, NTOKENS = 2063592, MAXGRID4 = 32400;

/** Tablica znaków z poprzednich wiadomości – odtwarzanie znaków przesłanych jako hasz. */
export class CallsignHashTable {
  constructor() { this.calls = /** @type {(string | null)[]} */ (new Array(256).fill(null)); this.hashes = new Int32Array(256); }
  /** @param {string} call @param {number} hash */
  add(call, hash) {
    let idx = (((hash >>> 12) & 0x3ff) * 23) % 256;
    for (let n = 0; n < 256; n++) {
      const ex = this.calls[idx];
      if (ex == null) { this.calls[idx] = call.slice(0, 11); this.hashes[idx] = hash; return; }
      if ((this.hashes[idx] & 0x3fffff) === hash && ex === call) { this.hashes[idx] &= 0x3fffff; return; }
      idx = (idx + 1) % 256;
    }
  }
  /** @param {"22" | "12" | "10"} type @param {number} hash */
  lookup(type, hash) {
    const shift = type === "10" ? 12 : type === "12" ? 10 : 0;
    let idx = (((hash >>> (12 - shift)) & 0x3ff) * 23) % 256;
    for (let n = 0; n < 256; n++) {
      const ex = this.calls[idx];
      if (ex == null) return null;
      if (((this.hashes[idx] & 0x3fffff) >>> shift) === hash) return ex;
      idx = (idx + 1) % 256;
    }
    return null;
  }
  /** @param {number} maxAge */
  cleanup(maxAge) {
    for (let i = 0; i < 256; i++) {
      if (this.calls[i] == null) continue;
      const age = this.hashes[i] >>> 24;
      if (age > maxAge) { this.calls[i] = null; this.hashes[i] = 0; }
      else this.hashes[i] = ((age + 1) << 24) | (this.hashes[i] & 0x3fffff);
    }
  }
}

/** @param {number} c @param {"FULL"|"ASS"|"AS"|"LS"|"AN"|"NUM"} table */
function charn(c, table) {
  if (table !== "AN" && table !== "NUM") { if (c === 0) return " "; c -= 1; }
  if (table !== "LS") { if (c < 10) return String.fromCharCode(48 + c); c -= 10; }
  if (table !== "NUM") { if (c < 26) return String.fromCharCode(65 + c); c -= 26; }
  if (table === "FULL") { if (c < 5) return "+-./?"[c]; }
  else if (table === "ASS") { if (c === 0) return "/"; }
  return "_";
}
/** @param {string} ch @param {"FULL"|"ASS"|"AS"|"LS"|"AN"|"NUM"} table */
function nchar(ch, table) {
  let n = 0;
  if (table !== "AN" && table !== "NUM") { if (ch === " ") return n; n += 1; }
  if (table !== "LS") { if (ch >= "0" && ch <= "9") return n + ch.charCodeAt(0) - 48; n += 10; }
  if (table !== "NUM") { if (ch >= "A" && ch <= "Z") return n + ch.charCodeAt(0) - 65; n += 26; }
  if (table === "FULL") { const i = "+-./?".indexOf(ch); if (i >= 0) return n + i; }
  else if (table === "ASS") { if (ch === "/") return n; }
  return -1;
}

/** Hasz 22-bitowy znaku zapisany w tablicy. @param {CallsignHashTable | null} ht @param {string} call */
export function saveCallsign(ht, call) {
  let n58 = 0n, i = 0;
  for (; i < call.length && i < 11; i++) {
    const j = nchar(call[i], "ASS");
    if (j < 0) return false;
    n58 = 38n * n58 + BigInt(j);
  }
  for (; i < 11; i++) n58 *= 38n;
  const n22 = Number(((47055833459n * n58) & 0xffffffffffffffffn) >> 42n) & 0x3fffff;
  ht?.add(call, n22);
  return true;
}

/** @param {CallsignHashTable} ht @param {"22" | "12" | "10"} type @param {number} hash */
const lookupCall = (ht, type, hash) => { const f = ht.lookup(type, hash); return f ? `<${f}>` : "<...>"; };

/** @param {number} n28 @param {number} ip @param {number} i3 @param {CallsignHashTable} ht */
function unpack28(n28, ip, i3, ht) {
  if (n28 < NTOKENS) {
    if (n28 <= 2) return n28 === 0 ? "DE" : n28 === 1 ? "QRZ" : "CQ";
    if (n28 <= 1002) return `CQ ${String(n28 - 3).padStart(3, "0")}`;
    if (n28 <= 532443) {
      let n = n28 - 1003;
      const a = ["", "", "", ""];
      for (let i = 3; i >= 0; i--) { a[i] = charn(n % 27, "LS"); if (i === 0) break; n = Math.floor(n / 27); }
      return `CQ ${a.join("").trimStart()}`;
    }
    return null;
  }
  n28 -= NTOKENS;
  if (n28 < MAX22) return lookupCall(ht, "22", n28);
  let n = n28 - MAX22;
  const c = new Array(6);
  c[5] = charn(n % 27, "LS"); n = Math.floor(n / 27);
  c[4] = charn(n % 27, "LS"); n = Math.floor(n / 27);
  c[3] = charn(n % 27, "LS"); n = Math.floor(n / 27);
  c[2] = charn(n % 10, "NUM"); n = Math.floor(n / 10);
  c[1] = charn(n % 36, "AN"); n = Math.floor(n / 36);
  c[0] = charn(n % 37, "AS");
  const cs = c.join("");
  let result = cs.startsWith("3D0") && cs[3] !== " " ? `3DA0${cs.slice(3).trim()}`
    : cs[0] === "Q" && /[A-Za-z]/.test(cs[1]) ? `3X${cs.slice(1).trim()}` : cs.trim();
  if (result.length < 3) return null;
  if (ip) {
    if (i3 === 1) result += "/R"; else if (i3 === 2) result += "/P"; else return null;
  }
  saveCallsign(ht, result);
  return result;
}

/** @param {number} igrid4 @param {number} ir */
function unpackGrid(igrid4, ir) {
  if (igrid4 <= MAXGRID4) {
    let n = igrid4;
    const d3 = n % 10; n = Math.floor(n / 10);
    const d2 = n % 10; n = Math.floor(n / 10);
    const d1 = n % 18; n = Math.floor(n / 18);
    const d0 = n % 18;
    const grid = `${String.fromCharCode(65 + d0)}${String.fromCharCode(65 + d1)}${d2}${d3}`;
    return ir > 0 ? `R ${grid}` : grid;
  }
  const irpt = igrid4 - MAXGRID4;
  if (irpt === 1) return "";
  if (irpt === 2) return "RRR";
  if (irpt === 3) return "RR73";
  if (irpt === 4) return "73";
  const v = irpt - 35;
  return `${ir > 0 ? "R" : ""}${v < 0 ? "-" : "+"}${String(Math.abs(v)).padStart(2, "0")}`;
}

/** Przesunięcie o bit w prawo (71 bitów danych tekstu dowolnego / telemetrii). @param {number[]} p */
function telemetry(p) {
  const out = new Array(9);
  let carry = 0;
  for (let i = 0; i < 9; i++) { out[i] = ((carry << 7) | (p[i] >> 1)) & 0xff; carry = p[i] & 1; }
  return out;
}

/**
 * @typedef {{field1: string, field2: string | null, field3: string | null, text: string}} Ft8Text
 * @param {string} f1 @param {string | null} [f2] @param {string | null} [f3] @returns {Ft8Text}
 */
const text = (f1, f2 = null, f3 = null) => ({ field1: f1, field2: f2, field3: f3, text: f2 == null ? f1 : `${f1} ${f2}${f3 ? ` ${f3}` : ""}` });

/** Rozpakowanie 77 bitów (10 bajtów) – null dla nieobsługiwanych typów. @param {number[]} p @param {CallsignHashTable} ht */
export function unpackMessage(p, ht) {
  const i3 = (p[9] >> 3) & 7;
  if (i3 === 0) {
    const n3 = ((p[8] << 2) & 4) | ((p[9] >> 6) & 3);
    if (n3 === 0) {
      const b71 = telemetry(p);
      const c = new Array(13);
      for (let idx = 12; idx >= 0; idx--) {
        let rem = 0;
        for (let i = 0; i < 9; i++) { rem = (rem << 8) | b71[i]; b71[i] = Math.floor(rem / 42); rem %= 42; }
        c[idx] = charn(rem, "FULL");
      }
      return text(c.join("").trim());
    }
    if (n3 === 5) return text(telemetry(p).map((x) => x.toString(16).toUpperCase().padStart(2, "0")).join(""));
    return null;
  }
  if (i3 === 1 || i3 === 2) {
    const n29a = (p[0] << 21) | (p[1] << 13) | (p[2] << 5) | (p[3] >> 3);
    const n29b = ((p[3] & 7) << 26) | (p[4] << 18) | (p[5] << 10) | (p[6] << 2) | (p[7] >> 6);
    const ir = (p[7] & 0x20) >> 5;
    const igrid4 = ((p[7] & 0x1f) << 10) | (p[8] << 2) | (p[9] >> 6);
    const to = unpack28(n29a >>> 1, n29a & 1, i3, ht);
    if (to == null) return null;
    const de = unpack28(n29b >>> 1, n29b & 1, i3, ht);
    if (de == null) return null;
    return text(to, de, unpackGrid(igrid4, ir));
  }
  if (i3 === 4) {
    const n12 = (p[0] << 4) | (p[1] >> 4);
    let n58 = BigInt(p[1] & 0x0f) << 54n;
    for (const [i, sh] of /** @type {[number, bigint][]} */ ([[2, 46n], [3, 38n], [4, 30n], [5, 22n], [6, 14n], [7, 6n]])) n58 |= BigInt(p[i]) << sh;
    n58 |= BigInt(p[8]) >> 2n;
    const iflip = (p[8] >> 1) & 1, nrpt = ((p[8] & 1) << 1) | (p[9] >> 7), icq = (p[9] >> 6) & 1;
    const c11 = new Array(11);
    for (let i = 10; i >= 0; i--) { c11[i] = charn(Number(n58 % 38n), "ASS"); if (i === 0) break; n58 /= 38n; }
    const decoded = c11.join("").trim();
    if (decoded.length >= 3) saveCallsign(ht, decoded);
    const call3 = lookupCall(ht, "12", n12);
    const c1 = iflip ? decoded : call3, c2 = iflip ? call3 : decoded;
    if (icq === 0) return text(c1, c2, nrpt === 1 ? "RRR" : nrpt === 2 ? "RR73" : nrpt === 3 ? "73" : "");
    return text("CQ", c2, "");
  }
  return null;
}

// ---------------------------------------------------------------- slot (demo/decode_ft8.c)
/**
 * @typedef {{slotStartMs: number, snr: number, dt: number, freqHz: number, message: Ft8Text, text: string, mode: FtxMode, fromFile: boolean}} Ft8Decode
 */
export class Ft8SlotDecoder {
  /** @param {number} sampleRate @param {FtxMode} mode */
  constructor(sampleRate = 12000, mode = "FT8") {
    this.mode = mode;
    this.monitor = new Ft8Monitor(sampleRate, mode);
    this.hashTable = new CallsignHashTable();
    this.nominalStart = 0.5 + FTX[mode].symbolPeriod;
  }

  /** @param {Float32Array} samples @param {number} count @param {number} slotStartMs @param {boolean} [fromFile] @returns {Ft8Decode[]} */
  decode(samples, count, slotStartMs, fromFile = false) {
    const mon = this.monitor;
    mon.reset();
    for (let pos = 0; pos + mon.blockSize <= count; pos += mon.blockSize) mon.process(samples, pos);
    const wf = mon.wf;
    const sp = FTX[this.mode].symbolPeriod;
    /** @type {{hash: number, payload: number[]}[]} */
    const decoded = [];
    /** @type {Ft8Decode[]} */
    const result = [];
    for (const c of findCandidates(wf, 140, 10)) {
      const raw = decodeCandidate(wf, c, 25);
      if (!raw) continue;
      if (decoded.some((d) => d.hash === raw.hash && d.payload.every((v, i) => v === raw.payload[i]))) continue;
      if (decoded.length >= 50) break;
      decoded.push(raw);
      const msg = unpackMessage(raw.payload, this.hashTable);
      if (!msg) continue;
      const freq = (mon.minBin + c.freqOffset + c.freqSub / wf.freqOsr) / sp;
      const time = (c.timeOffset + c.timeSub / wf.timeOsr) * sp;
      result.push({ slotStartMs, snr: estimateSnr(wf, c, raw.codeword), dt: time - this.nominalStart, freqHz: Math.round(freq), message: msg, text: msg.text, mode: this.mode, fromFile });
    }
    this.hashTable.cleanup(10);
    return result.sort((a, b) => a.freqHz - b.freqHz);
  }
}

/** Znak nadawcy (bez nawiasów hasza). @param {Ft8Text} m */
export const senderCall = (m) => (m.field2 && m.field2 !== "<...>" ? m.field2.replace(/^<|>$/g, "") : null);
/** @param {Ft8Text} m */
export const isCq = (m) => m.field2 != null && (m.field1 === "CQ" || m.field1.startsWith("CQ "));
/** Lokator z wiadomości. @param {Ft8Text} m */
export function messageGrid(m) {
  const g = m.field3?.replace(/^R /, "");
  return g && /^[A-R]{2}[0-9]{2}$/.test(g) && g !== "RR73" ? g : null;
}
/** @param {number} dt */
export const dtLabel = (dt) => { const v = Math.abs(dt) < 0.05 ? 0 : dt; return `${v >= 0 ? "+" : ""}${v.toFixed(1)}`; };
