// @ts-check
// Odbiornik HamDRM (DrmReceiver.kt): audio 12 kHz → synchronizacja OFDM (korelacja prefiksu, piloty częstotliwości
// i czasu) → estymacja kanału na pilotach → komórki FAC/MSC → bajty ramek MSC.
import { MixedRadixFft } from "../fft.js";
import { lowpassTaps } from "../dsp.js";
import { ROBUSTNESS, Robustness, Occupancy, DrmCellMap, CellType, isPilot, DC_FREQ_HZ, NUM_FAC_CELLS, freqPilots, timePilots, kMin, kMax, floorMod, interleaverTable, mscCoding } from "./tables.js";
import { decodeFac, decodeMsc } from "./channel.js";

/** @typedef {import("./tables.js").DrmRobustness} DrmRobustness */
/** @typedef {import("./tables.js").DrmOccupancy} DrmOccupancy */
/** @typedef {import("./channel.js").DrmFacInfo} DrmFacInfo */
/** @typedef {import("./channel.js").DrmCells} DrmCells */
/**
 * @typedef {{locked: boolean, mode?: DrmRobustness | null, occupancy?: DrmOccupancy | null, frequencyOffsetHz?: number,
 *   snrDb?: number | null, facOkPercent?: number, fac?: DrmFacInfo | null}} DrmSyncStatus
 */

const ACQ_MIX_HZ = 1575, NEVER = -1e9, ACQ_SECONDS = 2, ACQ_MIN_METRIC = 0.25, MAX_BAD_FAC = 6, MAX_KEEP_SECONDS = 20;

export class DrmReceiver {
  /**
   * @param {{sampleRate?: number, onStatus?: (s: DrmSyncStatus) => void, onFrame?: (bytes: number[], fac: DrmFacInfo) => void}} [o]
   */
  constructor({ sampleRate = 12000, onStatus = () => {}, onFrame = () => {} } = {}) {
    this.sampleRate = sampleRate;
    this.onStatus = onStatus;
    this.onFrame = onFrame;
    this.decim = 48000 / sampleRate;
    this.raw = new Float32Array(1 << 18);
    this.xRe = new Float32Array(this.raw.length); this.xIm = new Float32Array(this.raw.length);
    this.zRe = new Float32Array(this.raw.length); this.zIm = new Float32Array(this.raw.length);
    this.rawStart = 0; this.rawEnd = 0; this.xEnd = 0; this.zEnd = 0;
    this.ncoPhase = 0;
    this.mixHz = ACQ_MIX_HZ;
    this.taps = lowpassTaps(1700 / sampleRate, 97);
    this.locked = false;
    this.mode = Robustness.B;
    this.occupancy = Occupancy.SO_0;
    this.map = DrmCellMap.of(this.mode, this.occupancy);
    this.n = 0; this.g = 0; this.ts = 0; this.centerBin = 0;
    this.nextGuardStart = 0; this.drift = 0; this.tauSmooth = 0; this.frameSymbol = 0;
    /** @type {MixedRadixFft | null} */
    this.fft = null;
    this.deltaHz = 0;
    /** @type {{frameSym: number, re: Float32Array, im: Float32Array}[]} */
    this.history = [];
    this.facRe = new Float32Array(0); this.facIm = new Float32Array(0); this.facW = new Float32Array(0);
    this.mscRe = new Float32Array(0); this.mscIm = new Float32Array(0); this.mscW = new Float32Array(0);
    this.facCount = 0; this.mscCount = 0; this.frameStarted = false; this.badFacInRow = 0;
    this.facTotal = 0; this.facOk = 0;
    /** @type {DrmFacInfo | null} */
    this.lastFac = null;
    /** @type {number | null} */
    this.snrDb = null;
    this.noiseAcc = 0; this.noiseCount = 0;
    /** @type {DrmCells[]} */
    this.interleaveHistory = [];
    this.lastAcquireAttempt = NEVER;
    /** @type {Float32Array | null} */
    this.prevFp = null;
    /** @type {((s: string) => void) | null} */
    this.debugLog = null;
  }

  reset() {
    this.rawStart = this.rawEnd = this.zEnd = this.xEnd = 0;
    this.locked = false;
    this.history = []; this.interleaveHistory = [];
    this.lastAcquireAttempt = NEVER;
    this.facTotal = 0; this.facOk = 0; this.lastFac = null;
    this.onStatus({ locked: false });
  }

  /** @param {Float32Array} samples */
  process(samples) {
    this.append(samples);
    if (!this.locked) {
      const window = ACQ_SECONDS * this.sampleRate;
      if (this.rawEnd - this.rawStart >= window && this.rawEnd - this.lastAcquireAttempt >= this.sampleRate / 2) {
        this.lastAcquireAttempt = this.rawEnd;
        this.acquire(this.rawEnd - window);
      }
    }
    if (this.locked) this.runSymbols();
  }

  /** @param {Float32Array} samples */
  append(samples) {
    const count = samples.length;
    const have = this.rawEnd - this.rawStart;
    const needed = have + count;
    if (needed > this.raw.length) {
      const keep = Math.min(have, MAX_KEEP_SECONDS * this.sampleRate);
      const drop = have - keep;
      if (needed - drop > this.raw.length) {
        let size = 1;
        while (size <= needed - drop) size <<= 1;
        const grow = (/** @type {Float32Array} */ a) => { const b = new Float32Array(size); b.set(a); return b; };
        this.raw = grow(this.raw); this.xRe = grow(this.xRe); this.xIm = grow(this.xIm); this.zRe = grow(this.zRe); this.zIm = grow(this.zIm);
      }
      if (drop > 0) {
        for (const a of [this.raw, this.xRe, this.xIm, this.zRe, this.zIm]) a.copyWithin(0, drop, drop + keep);
        this.rawStart += drop;
      }
    }
    this.raw.set(samples, this.rawEnd - this.rawStart);
    this.rawEnd += count;
  }

  /** @param {number} abs */
  idx(abs) { return abs - this.rawStart; }

  /** @param {number} from @param {number} hz */
  restartMixer(from, hz) {
    this.mixHz = hz;
    this.ncoPhase = 0;
    this.xEnd = Math.max(this.rawStart, from - this.taps.length);
    this.zEnd = from;
  }

  /** @param {number} upTo */
  computeZ(upTo) {
    const end = Math.min(upTo, this.rawEnd);
    if (this.xEnd < this.rawStart) this.xEnd = this.rawStart;
    if (this.zEnd < this.rawStart) this.zEnd = this.rawStart;
    const w = (2 * Math.PI * this.mixHz) / this.sampleRate;
    const { raw, xRe, xIm, zRe, zIm, taps } = this;
    while (this.xEnd < end) {
      const i = this.idx(this.xEnd);
      xRe[i] = raw[i] * Math.cos(this.ncoPhase);
      xIm[i] = -raw[i] * Math.sin(this.ncoPhase);
      this.ncoPhase += w;
      if (this.ncoPhase > 2 * Math.PI) this.ncoPhase -= 2 * Math.PI;
      this.xEnd++;
    }
    while (this.zEnd < end) {
      const base = this.idx(this.zEnd);
      let re = 0, im = 0;
      const tMax = Math.min(taps.length - 1, base);
      for (let t = 0; t <= tMax; t++) { re += taps[t] * xRe[base - t]; im += taps[t] * xIm[base - t]; }
      zRe[base] = re; zIm[base] = im;
      this.zEnd++;
    }
  }

  /** @param {number} start */
  acquire(start) {
    this.restartMixer(start, ACQ_MIX_HZ);
    this.computeZ(this.rawEnd);
    const len = this.rawEnd - start;
    const off = this.idx(start) + this.taps.length;
    const { zRe, zIm } = this;
    /** @type {DrmRobustness | null} */
    let best = null;
    let bestMetric = 0, bestT = 0, bestAngle = 0;
    for (const m of ROBUSTNESS) {
      const nn = m.fftSize48 / this.decim, gg = m.guard48 / this.decim, tt = nn + gg;
      const foldRe = new Float64Array(tt), foldIm = new Float64Array(tt), foldE = new Float64Array(tt);
      const usable = len - this.taps.length - nn - gg;
      if (usable < tt * 8) continue;
      let sRe = 0, sIm = 0, sE = 0;
      /** @param {number} a */
      const cr = (a) => zRe[a] * zRe[a + nn] + zIm[a] * zIm[a + nn];
      /** @param {number} a */
      const ci = (a) => zIm[a] * zRe[a + nn] - zRe[a] * zIm[a + nn];
      /** @param {number} a */
      const ce = (a) => 0.5 * (zRe[a] * zRe[a] + zIm[a] * zIm[a] + zRe[a + nn] * zRe[a + nn] + zIm[a + nn] * zIm[a + nn]);
      for (let i = 0; i < gg; i++) { const a = off + i; sRe += cr(a); sIm += ci(a); sE += ce(a); }
      for (let i = 0; i < usable; i++) {
        const k = i % tt;
        foldRe[k] += sRe; foldIm[k] += sIm; foldE[k] += sE;
        const a = off + i, b = a + gg;
        sRe += cr(b) - cr(a); sIm += ci(b) - ci(a); sE += ce(b) - ce(a);
      }
      for (let k = 0; k < tt; k++) {
        const metric = Math.hypot(foldRe[k], foldIm[k]) / (foldE[k] + 1e-12);
        if (metric > bestMetric) { bestMetric = metric; best = m; bestT = k; bestAngle = Math.atan2(foldIm[k], foldRe[k]); }
      }
    }
    this.debugLog?.(`acq start=${start} len=${len} best=${best?.label} metric=${bestMetric} t=${bestT}`);
    const m = best;
    if (!m) return;
    if (bestMetric < ACQ_MIN_METRIC) { this.onStatus({ locked: false }); return; }

    const nn = m.fftSize48 / this.decim, gg = m.guard48 / this.decim, tt = nn + gg;
    const df = this.sampleRate / nn;
    let deltaFrac = ACQ_MIX_HZ - DC_FREQ_HZ - (bestAngle / (2 * Math.PI)) * df;
    deltaFrac -= df * Math.round(deltaFrac / df);
    const c = m.guardDen * Math.round(1225 / df / m.guardDen);

    this.restartMixer(start, DC_FREQ_HZ + deltaFrac + c * df);
    this.computeZ(this.rawEnd);
    const f = new MixedRadixFft(nn);
    const backoff = Math.trunc(gg / 4);
    /** @type {[Float64Array, Float64Array][]} */
    const symbols = [];
    for (let s0 = off + bestT; s0 + tt <= len + this.idx(start); s0 += tt) symbols.push(this.fftAt(f, s0 + gg - backoff, nn));
    if (symbols.length < m.symbolsPerFrame + 4) return;

    // Całkowita odchyłka – piloty częstotliwości
    const fp = freqPilots(m);
    const maxShift = Math.trunc(250 / df) + 1;
    let bestQ = 0, bestQMetric = -1;
    for (let q = -maxShift; q <= maxShift; q++) {
      let num = 0, den = 1e-12;
      for (let p = 0; p < fp.length / 2; p++) {
        const b = floorMod(fp[p * 2] - c + q, nn);
        let aRe = 0, aIm = 0;
        for (let j = 0; j < symbols.length - 2; j++) {
          const [r1, i1] = symbols[j], [r2, i2] = symbols[j + 2];
          aRe += r1[b] * r2[b] + i1[b] * i2[b];
          aIm += i1[b] * r2[b] - r1[b] * i2[b];
          den += r1[b] * r1[b] + i1[b] * i1[b];
        }
        num += Math.hypot(aRe, aIm);
      }
      const metric = num / den;
      if (metric > bestQMetric) { bestQMetric = metric; bestQ = q; }
    }

    // Ramka: piloty czasu w symbolu 0
    const tp = timePilots(m);
    const score = new Float64Array(m.symbolsPerFrame);
    for (let j = 0; j < symbols.length; j++) {
      const [r, i] = symbols[j];
      let aRe = 0, aIm = 0, pw = 1e-12;
      for (let p = 0; p < tp.length / 2 - 1; p++) {
        const k1 = tp[p * 2], k2 = tp[p * 2 + 2];
        if (k2 - k1 > 2) continue;
        const b1 = floorMod(k1 - c + bestQ, nn), b2 = floorMod(k2 - c + bestQ, nn);
        const yr = r[b1] * r[b2] + i[b1] * i[b2], yi = i[b1] * r[b2] - r[b1] * i[b2];
        const dp = (2 * Math.PI * (tp[p * 2 + 1] - tp[p * 2 + 3])) / 1024;
        aRe += yr * Math.cos(dp) + yi * Math.sin(dp);
        aIm += yi * Math.cos(dp) - yr * Math.sin(dp);
        pw += Math.hypot(r[b1], i[b1]) * Math.hypot(r[b2], i[b2]);
      }
      score[j % m.symbolsPerFrame] += aRe / pw;
    }
    let phase = 0;
    for (let k = 0; k < score.length; k++) if (score[k] > score[phase]) phase = k;

    // Zajętość pasma
    const k0min = kMin(m, Occupancy.SO_0), k0max = kMax(m, Occupancy.SO_0), k1max = kMax(m, Occupancy.SO_1);
    let inBand = 0, extra = 0;
    for (const [r, i] of symbols) {
      for (let k = k0min; k <= k0max; k++) { const b = floorMod(k - c + bestQ, nn); inBand += r[b] * r[b] + i[b] * i[b]; }
      for (let k = k0max + 1; k <= k1max; k++) { const b = floorMod(k - c + bestQ, nn); extra += r[b] * r[b] + i[b] * i[b]; }
    }
    const occ = extra / (k1max - k0max) > (0.3 * inBand) / (k0max - k0min + 1) ? Occupancy.SO_1 : Occupancy.SO_0;
    this.debugLog?.(`lock: deltaFrac=${deltaFrac} q=${bestQ} phase=${phase} occ=${occ.label}`);

    this.mode = m;
    this.occupancy = occ;
    this.map = DrmCellMap.of(m, occ);
    this.n = nn; this.g = gg; this.ts = tt;
    this.centerBin = c;
    this.fft = f;
    this.deltaHz = deltaFrac + bestQ * df;
    this.restartMixer(start, DC_FREQ_HZ + this.deltaHz + c * df);
    this.prevFp = null;
    this.drift = 0;
    this.tauSmooth = backoff;
    this.nextGuardStart = start + this.taps.length + bestT;
    this.frameSymbol = floorMod(-phase, m.symbolsPerFrame);
    this.history = []; this.interleaveHistory = [];
    this.frameStarted = false;
    this.badFacInRow = 0;
    this.locked = true;
    this.onStatus({ locked: true, mode: m, occupancy: occ, frequencyOffsetHz: this.deltaHz });
  }

  /** @param {MixedRadixFft} f @param {number} startIdx @param {number} nn @returns {[Float64Array, Float64Array]} */
  fftAt(f, startIdx, nn) {
    const inRe = Float64Array.from(this.zRe.subarray(startIdx, startIdx + nn));
    const inIm = Float64Array.from(this.zIm.subarray(startIdx, startIdx + nn));
    const outRe = new Float64Array(nn), outIm = new Float64Array(nn);
    f.transform(inRe, inIm, outRe, outIm);
    return [outRe, outIm];
  }

  runSymbols() {
    const f = this.fft;
    if (!f) return;
    const backoff = Math.trunc(this.g / 4);
    for (;;) {
      const winStart = this.nextGuardStart + this.g - backoff + this.drift;
      if (winStart + this.n + this.ts > this.rawEnd) break;
      this.computeZ(winStart + this.n);
      if (winStart < this.rawStart + this.taps.length) {
        this.nextGuardStart += this.ts;
        this.frameSymbol = (this.frameSymbol + 1) % this.mode.symbolsPerFrame;
        continue;
      }
      const [yr, yi] = this.fftAt(f, this.idx(winStart), this.n);
      const map = this.map, nc = map.numCarriers;
      const re = new Float32Array(nc), im = new Float32Array(nc);
      for (let i = 0; i < nc; i++) {
        const k = map.kMin + i;
        const b = floorMod(k - this.centerBin, this.n);
        const a = (-2 * Math.PI * (k - this.centerBin) * this.drift) / this.n;
        const cr = Math.cos(a), ci = Math.sin(a);
        re[i] = yr[b] * cr - yi[b] * ci;
        im[i] = yr[b] * ci + yi[b] * cr;
      }
      this.afc(re, im);
      this.history.push({ frameSym: this.frameSymbol, re, im });
      if (this.history.length > 2 * this.mode.scatTimeInt + 1) this.history.shift();
      const delay = this.mode.scatTimeInt - 1;
      if (this.history.length > delay) this.equalizeAndCollect(this.history.length - 1 - delay);
      this.nextGuardStart += this.ts;
      this.frameSymbol = (this.frameSymbol + 1) % this.mode.symbolsPerFrame;
      if (!this.locked) break;
    }
  }

  /** @param {Float32Array} re @param {Float32Array} im */
  afc(re, im) {
    const fp = freqPilots(this.mode);
    const cur = new Float32Array(fp.length);
    for (let p = 0; p < fp.length / 2; p++) { const i = fp[p * 2] - this.map.kMin; cur[p * 2] = re[i]; cur[p * 2 + 1] = im[i]; }
    const prev = this.prevFp;
    this.prevFp = cur;
    if (!prev) return;
    let aRe = 0, aIm = 0;
    for (let p = 0; p < fp.length / 2; p++) {
      const sign = this.mode === Robustness.E && p !== fp.length / 2 - 1 ? -1 : 1;
      aRe += sign * (cur[p * 2] * prev[p * 2] + cur[p * 2 + 1] * prev[p * 2 + 1]);
      aIm += sign * (cur[p * 2 + 1] * prev[p * 2] - cur[p * 2] * prev[p * 2 + 1]);
    }
    const err = ((Math.atan2(aIm, aRe) / (2 * Math.PI)) * this.sampleRate) / this.ts;
    if (Math.abs(err) < this.sampleRate / this.n / 3) {
      const step = 0.1 * err;
      this.mixHz += step;
      this.deltaHz += step;
    }
  }

  /** @param {{frameSym: number, re: Float32Array, im: Float32Array}} sym @param {number} i @returns {[number, number]} */
  pilotH(sym, i) {
    const pr = this.map.pilotRe[sym.frameSym][i], pi = this.map.pilotIm[sym.frameSym][i];
    const p2 = pr * pr + pi * pi;
    return [(sym.re[i] * pr + sym.im[i] * pi) / p2, (sym.im[i] * pr - sym.re[i] * pi) / p2];
  }

  /** @param {number} h */
  equalizeAndCollect(h) {
    const { history, map, mode } = this;
    const sym = history[h], s = sym.frameSym, nc = map.numCarriers;
    const hRe = new Float32Array(nc), hIm = new Float32Array(nc);
    const known = new Uint8Array(nc);
    const x = mode.scatFreqInt, k0 = Math.trunc((x + 1) / 2);
    for (let i = 0; i < nc; i++) {
      if (isPilot(map.type[s][i])) {
        [hRe[i], hIm[i]] = this.pilotH(sym, i);
        known[i] = 1;
        continue;
      }
      if (floorMod(map.kMin + i - k0, x) !== 0) continue;
      let before = -1, after = -1;
      for (let d = 1; d < history.length; d++) {
        if (before < 0 && h - d >= 0 && map.type[history[h - d].frameSym][i] & CellType.SCAT) before = h - d;
        if (after < 0 && h + d < history.length && map.type[history[h + d].frameSym][i] & CellType.SCAT) after = h + d;
      }
      if (before < 0 && after < 0) continue;
      const [bRe, bIm] = before >= 0 ? this.pilotH(history[before], i) : this.pilotH(history[after], i);
      const [aRe, aIm] = after >= 0 ? this.pilotH(history[after], i) : [bRe, bIm];
      const w = before >= 0 && after >= 0 ? (h - before) / (after - before) : 0;
      hRe[i] = bRe + (aRe - bRe) * w;
      hIm[i] = bIm + (aIm - bIm) * w;
      known[i] = 1;
    }
    const tau0 = this.tauSmooth;
    this.rotateByDelay(hRe, hIm, known, tau0);
    let last = -1;
    for (let i = 0; i < nc; i++) {
      if (!known[i]) continue;
      if (last >= 0 && i - last > 1) {
        for (let j = last + 1; j < i; j++) {
          const w = (j - last) / (i - last);
          hRe[j] = hRe[last] + (hRe[i] - hRe[last]) * w;
          hIm[j] = hIm[last] + (hIm[i] - hIm[last]) * w;
        }
      } else if (last < 0) for (let j = 0; j < i; j++) { hRe[j] = hRe[i]; hIm[j] = hIm[i]; }
      last = i;
    }
    if (last < 0) return;
    for (let j = last + 1; j < nc; j++) { hRe[j] = hRe[last]; hIm[j] = hIm[last]; }
    this.trackTiming(hRe, hIm, tau0);
    this.rotateByDelay(hRe, hIm, null, -tau0);

    if (s === 0 && !this.frameStarted) this.startFrame();
    if (!this.frameStarted) return;
    for (let i = 0; i < nc; i++) {
      const t = map.type[s][i];
      if (t !== CellType.FAC && t !== CellType.MSC) continue;
      const p = hRe[i] * hRe[i] + hIm[i] * hIm[i];
      if (p < 1e-20) continue;
      const er = (sym.re[i] * hRe[i] + sym.im[i] * hIm[i]) / p;
      const ei = (sym.im[i] * hRe[i] - sym.re[i] * hIm[i]) / p;
      if (t === CellType.FAC && this.facCount < this.facRe.length) {
        this.facRe[this.facCount] = er; this.facIm[this.facCount] = ei; this.facW[this.facCount] = p;
        this.facCount++;
        const dr = Math.min(Math.abs(Math.abs(er) - 0.7071), 1), di = Math.min(Math.abs(Math.abs(ei) - 0.7071), 1);
        this.noiseAcc += dr * dr + di * di;
        this.noiseCount++;
      } else if (t === CellType.MSC && this.mscCount < this.mscRe.length) {
        this.mscRe[this.mscCount] = er; this.mscIm[this.mscCount] = ei; this.mscW[this.mscCount] = p;
        this.mscCount++;
      }
    }
    if (s === mode.symbolsPerFrame - 1) this.finishFrame();
  }

  /** @param {Float32Array} hRe @param {Float32Array} hIm @param {Uint8Array | null} only @param {number} tau */
  rotateByDelay(hRe, hIm, only, tau) {
    for (let i = 0; i < hRe.length; i++) {
      if (only && !only[i]) continue;
      const a = (2 * Math.PI * (this.map.kMin + i - this.centerBin) * tau) / this.n;
      const c = Math.cos(a), s = Math.sin(a);
      const r = hRe[i] * c - hIm[i] * s;
      hIm[i] = hRe[i] * s + hIm[i] * c;
      hRe[i] = r;
    }
  }

  /** @param {Float32Array} hRe @param {Float32Array} hIm @param {number} tau0 */
  trackTiming(hRe, hIm, tau0) {
    let aRe = 0, aIm = 0;
    for (let i = 0; i < hRe.length - 1; i++) {
      aRe += hRe[i + 1] * hRe[i] + hIm[i + 1] * hIm[i];
      aIm += hIm[i + 1] * hRe[i] - hRe[i + 1] * hIm[i];
    }
    const tau = tau0 - (Math.atan2(aIm, aRe) * this.n) / (2 * Math.PI);
    this.tauSmooth = 0.9 * this.tauSmooth + 0.1 * tau;
    const err = this.tauSmooth - this.drift - Math.trunc(this.g / 4);
    if (err > 1.5) this.drift++; else if (err < -1.5) this.drift--;
  }

  startFrame() {
    this.frameStarted = true;
    this.facCount = 0; this.mscCount = 0;
    if (this.facRe.length !== NUM_FAC_CELLS) { this.facRe = new Float32Array(NUM_FAC_CELLS); this.facIm = new Float32Array(NUM_FAC_CELLS); this.facW = new Float32Array(NUM_FAC_CELLS); }
    const nm = this.map.mscCellsPerFrame;
    if (this.mscRe.length !== nm) { this.mscRe = new Float32Array(nm); this.mscIm = new Float32Array(nm); this.mscW = new Float32Array(nm); }
    this.noiseAcc = 0; this.noiseCount = 0;
  }

  finishFrame() {
    const complete = this.facCount === this.facRe.length && this.mscCount === this.mscRe.length;
    /** @type {DrmCells} */
    const facCells = { re: this.facRe.slice(), im: this.facIm.slice(), weight: this.facW.slice() };
    /** @type {DrmCells} */
    const mscCells = { re: this.mscRe.slice(), im: this.mscIm.slice(), weight: this.mscW.slice() };
    const noise = this.noiseCount > 0 ? this.noiseAcc / this.noiseCount : 1;
    this.startFrame();
    if (!complete) return;
    normalizeWeights(facCells);
    normalizeWeights(mscCells);
    this.facTotal++;
    const fac = decodeFac(facCells);
    if (!fac) {
      this.badFacInRow++;
      this.report();
      if (this.badFacInRow >= MAX_BAD_FAC) {
        this.locked = false;
        this.lastAcquireAttempt = NEVER;
        this.onStatus({ locked: false });
      }
      return;
    }
    this.badFacInRow = 0;
    this.facOk++;
    this.snrDb = 10 * Math.log10(1 / Math.max(noise, 1e-6));
    this.lastFac = fac;
    if (fac.occupancy !== this.occupancy) {
      this.occupancy = fac.occupancy;
      this.map = DrmCellMap.of(this.mode, this.occupancy);
      this.interleaveHistory = []; this.history = [];
      this.frameStarted = false;
      this.report();
      return;
    }
    this.report();
    if (!fac.isData || mscCells.re.length !== this.map.mscCellsPerFrame) return;
    const depth = fac.longInterleave ? 5 : 1;
    this.interleaveHistory.push(mscCells);
    while (this.interleaveHistory.length > depth) this.interleaveHistory.shift();
    if (this.interleaveHistory.length < depth) return;
    const nm = mscCells.re.length;
    const table = interleaverTable(nm, 5);
    /** @type {DrmCells} */
    const out = { re: new Float32Array(nm), im: new Float32Array(nm), weight: new Float32Array(nm) };
    for (let i = 0; i < nm; i++) {
      const src = this.interleaveHistory[i % depth];
      out.re[table[i]] = src.re[i]; out.im[table[i]] = src.im[i]; out.weight[table[i]] = src.weight[i];
    }
    this.onFrame(decodeMsc(out, mscCoding(fac.qamLevels, fac.highProtection)), fac);
  }

  report() {
    this.onStatus({
      locked: this.locked, mode: this.mode, occupancy: this.occupancy, frequencyOffsetHz: this.deltaHz, snrDb: this.snrDb,
      facOkPercent: this.facTotal > 0 ? Math.trunc((this.facOk * 100) / this.facTotal) : 0, fac: this.lastFac,
    });
  }
}

/** @param {DrmCells} cells */
function normalizeWeights(cells) {
  let sum = 0;
  for (const w of cells.weight) sum += w;
  const mean = sum / Math.max(1, cells.weight.length);
  if (mean <= 0) return;
  for (let i = 0; i < cells.weight.length; i++) cells.weight[i] /= mean;
}
