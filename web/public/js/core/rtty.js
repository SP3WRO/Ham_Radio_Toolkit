// @ts-check
// Odbiór RTTY (rtty/RttyDecoder.kt): Baudot, demodulator z filtrami dopasowanymi, automatyczna szybkość i polaryzacja,
// widmo do strojenia i silnik z automatycznym strojeniem (AFC) i przesuwem.
import { MixedRadixFft } from "./fft.js";

/** Kod Baudot (ITA2, wariant US-TTY). Indeks = 5 bitów, pierwszy bit to LSB. */
export const Baudot = {
  LTRS: 0x1f,
  FIGS: 0x1b,
  L: "\u0000E\nA SIU\rDRJNFCKTZLWHYPQOBG\u0000MXV\u0000",
  F: "\u00003\n- \u000787\r$4',!:(5\")2#6019?&\u0000./;\u0000",
  /** @param {string} c @returns {[number, boolean] | null} kod i czy wymaga trybu cyfr */
  encode(c) {
    const u = c.toUpperCase();
    if (u === "\u0000") return null;
    const l = this.L.indexOf(u);
    if (l > 0) return [l, false];
    const f = this.F.indexOf(u);
    if (f > 0) return [f, true];
    return null;
  },
};

/** Cztery sumy kroczące (I/Q dla tonu wyższego i niższego) o długości n – filtr dopasowany do bitu. */
class IqBoxcar {
  /** @param {number} n */
  constructor(n) {
    this.n = n;
    this.b0 = new Float64Array(n); this.b1 = new Float64Array(n); this.b2 = new Float64Array(n); this.b3 = new Float64Array(n);
    this.sums = new Float64Array(4);
    this.pos = 0;
  }
  push(a, b, c, d) {
    const p = this.pos, s = this.sums;
    s[0] += a - this.b0[p]; this.b0[p] = a;
    s[1] += b - this.b1[p]; this.b1[p] = b;
    s[2] += c - this.b2[p]; this.b2[p] = c;
    s[3] += d - this.b3[p]; this.b3[p] = d;
    this.pos = p + 1 === this.n ? 0 : p + 1;
  }
  discriminator() {
    const s = this.sums;
    const hi = Math.sqrt(s[0] * s[0] + s[1] * s[1]), lo = Math.sqrt(s[2] * s[2] + s[3] * s[3]);
    return (hi - lo) / (hi + lo + 1e-9);
  }
}

const AFC_RING = 128;
const MIN_COHERENCE = 0.7;
export const STANDARD_BAUDS = [45.45, 50, 75, 100];

/** Odbiornik znaków start-stop (1 start, 5 danych od LSB, stop). */
class Uart {
  /** @param {RttyDecoder} dec @param {boolean} inverted */
  constructor(dec, inverted) {
    this.dec = dec; this.inverted = inverted;
    this.prevD = 0; this.inChar = false; this.charStart = 0; this.nextBit = 0; this.bits = 0; this.qualitySum = 0;
    this.goodRate = 0.5; this.frames = 0;
  }
  reset() { this.inChar = false; this.goodRate = 0.5; this.frames = 0; }
  /** @param {number} raw @returns {number} kod znaku albo −1 */
  step(raw) {
    const dec = this.dec;
    const d = this.inverted ? -raw : raw;
    const prev = this.prevD;
    this.prevD = d;
    if (!this.inChar) {
      if (prev >= 0 && d < 0) { this.inChar = true; this.charStart = dec.sampleIndex; this.nextBit = 0; this.bits = 0; this.qualitySum = 0; }
      return -1;
    }
    if (dec.sampleIndex < this.charStart + (this.nextBit + 0.5) * dec.bitSamples) return -1;
    this.qualitySum += Math.abs(d);
    if (this.nextBit === 0) {
      if (d >= 0) { this.inChar = false; return -1; }
    } else if (this.nextBit <= 5) {
      if (d > 0) this.bits |= 1 << (this.nextBit - 1);
    } else {
      this.inChar = false;
      const stopOk = d > 0;
      this.frames++;
      this.goodRate = this.goodRate * 0.9 + (stopOk ? 0.1 : 0);
      const q = this.qualitySum / 7;
      const usesThis = (dec.autoPolarity ? dec.activeReverse : dec.reverse) === this.inverted;
      if (usesThis) dec.quality = dec.quality * 0.8 + q * 0.2;
      return stopOk && q >= dec.squelch ? this.bits : -1;
    }
    this.nextBit++;
    return -1;
  }
}

/** Demodulator RTTY z automatyczną polaryzacją i szybkością (RttyDecoder.kt). */
export class RttyDecoder {
  /** @param {number} sampleRate @param {(s: string) => void} onText */
  constructor(sampleRate = 12000, onText = () => {}) {
    this.sampleRate = sampleRate;
    this.onText = onText;
    this.centerHz = 1500; this.shiftHz = 170; this.baud = 45.45;
    this.reverse = false; this.autoPolarity = true; this.autoBaud = true; this.unshiftOnSpace = true;
    this.squelch = 0.45; this.quality = 0; this.activeReverse = false;
    this.configuredBaud = 0; this.configuredShift = 0; this.bitSamples = 0;
    /** @type {IqBoxcar} */ this.slow = new IqBoxcar(1);
    /** @type {IqBoxcar} */ this.fast = new IqBoxcar(1);
    this.hiPhase = 0; this.loPhase = 0; this.sampleIndex = 0;
    this.normal = new Uart(this, false);
    this.inverted = new Uart(this, true);
    this.figures = false;
    this.runs = new Int32Array(400); this.runCount = 0; this.runPos = 0; this.runState = 1; this.runStart = 0;
    this.sinceEstimate = 0; /** @type {number | null} */ this.lastEstimate = null; this.estimateRepeats = 0;
    this.afc = [0, 1, 2, 3, 4].map(() => new Float64Array(AFC_RING));
    this.afcPos = 0; this.afcLag = 16;
    this.hiRe = 0; this.hiIm = 0; this.loRe = 0; this.loIm = 0; this.hiCount = 0; this.loCount = 0; this.hiMag = 0; this.loMag = 0;
  }

  /** Błąd strojenia z fazy (środek i przesuw, Hz) albo null, gdy filtry nie stoją na sygnale. Zeruje akumulatory. */
  takeFrequencyError() {
    const minCount = Math.floor(2 * this.bitSamples);
    const cohHi = this.hiMag > 0 && Math.hypot(this.hiRe, this.hiIm) / this.hiMag > MIN_COHERENCE;
    const cohLo = this.loMag > 0 && Math.hypot(this.loRe, this.loIm) / this.loMag > MIN_COHERENCE;
    let result = null;
    if (this.hiCount >= minCount && this.loCount >= minCount && cohHi && cohLo) {
      const k = this.sampleRate / (2 * Math.PI * this.afcLag);
      const dHi = -Math.atan2(this.hiIm, this.hiRe) * k, dLo = -Math.atan2(this.loIm, this.loRe) * k;
      result = { centerHz: (dHi + dLo) / 2, shiftHz: dHi - dLo };
    }
    this.hiRe = this.hiIm = this.loRe = this.loIm = this.hiMag = this.loMag = 0;
    this.hiCount = this.loCount = 0;
    return result;
  }

  reset() {
    this.configuredBaud = 0; this.figures = false; this.quality = 0;
    this.normal.reset(); this.inverted.reset(); this.clearRuns();
  }
  clearRuns() { this.runCount = 0; this.runPos = 0; this.lastEstimate = null; this.estimateRepeats = 0; }

  configure() {
    const b = this.baud, s = this.shiftHz;
    if (b === this.configuredBaud && s === this.configuredShift) return;
    if (b !== this.configuredBaud) {
      this.bitSamples = this.sampleRate / b;
      this.slow = new IqBoxcar(Math.round(this.bitSamples));
      this.afcLag = Math.min(AFC_RING - 1, Math.max(4, Math.round(this.bitSamples / 4)));
      this.normal.reset(); this.inverted.reset();
    }
    if (s !== this.configuredShift) {
      this.fast = new IqBoxcar(Math.max(4, Math.round(this.sampleRate / s)));
      this.clearRuns();
    }
    this.configuredBaud = b; this.configuredShift = s;
  }

  /** @param {Float32Array} samples */
  process(samples) {
    this.configure();
    const TWO_PI = 2 * Math.PI;
    const hiStep = (TWO_PI * (this.centerHz + this.shiftHz / 2)) / this.sampleRate;
    const loStep = (TWO_PI * (this.centerHz - this.shiftHz / 2)) / this.sampleRate;
    for (let i = 0; i < samples.length; i++) {
      const x = samples[i];
      this.hiPhase += hiStep; this.loPhase += loStep;
      if (this.hiPhase > TWO_PI) this.hiPhase -= TWO_PI;
      if (this.loPhase > TWO_PI) this.loPhase -= TWO_PI;
      const hi = x * Math.cos(this.hiPhase), hq = x * Math.sin(this.hiPhase), li = x * Math.cos(this.loPhase), lq = x * Math.sin(this.loPhase);
      this.slow.push(hi, hq, li, lq);
      this.fast.push(hi, hq, li, lq);
      const d = this.slow.discriminator();
      this.trackPhase(d);
      const useInv = this.autoPolarity ? this.activeReverse : this.reverse;
      const cn = this.normal.step(d), ci = this.inverted.step(d);
      const code = useInv ? ci : cn;
      if (code >= 0) this.emit(code);
      this.collectRun(this.fast.discriminator());
      this.sampleIndex++;
    }
    if (this.autoPolarity) this.choosePolarity(); else this.activeReverse = this.reverse;
    this.sinceEstimate += samples.length;
    if (this.autoBaud && this.sinceEstimate >= this.sampleRate / 2) { this.sinceEstimate = 0; this.updateBaud(); }
  }

  /** @param {number} d */
  trackPhase(d) {
    const s = this.slow.sums, h = this.afc;
    const past = (this.afcPos - this.afcLag + AFC_RING) % AFC_RING;
    const pd = h[4][past];
    if (d > 0.6 && pd > 0.6) {
      const pi = h[0][past], pq = h[1][past];
      this.hiRe += s[0] * pi + s[1] * pq;
      this.hiIm += s[1] * pi - s[0] * pq;
      this.hiMag += Math.hypot(s[0], s[1]) * Math.hypot(pi, pq);
      this.hiCount++;
    } else if (d < -0.6 && pd < -0.6) {
      const pi = h[2][past], pq = h[3][past];
      this.loRe += s[2] * pi + s[3] * pq;
      this.loIm += s[3] * pi - s[2] * pq;
      this.loMag += Math.hypot(s[2], s[3]) * Math.hypot(pi, pq);
      this.loCount++;
    }
    h[0][this.afcPos] = s[0]; h[1][this.afcPos] = s[1]; h[2][this.afcPos] = s[2]; h[3][this.afcPos] = s[3]; h[4][this.afcPos] = d;
    this.afcPos = (this.afcPos + 1) % AFC_RING;
  }

  choosePolarity() {
    const active = this.activeReverse ? this.inverted : this.normal;
    const other = this.activeReverse ? this.normal : this.inverted;
    if (other.frames >= 8 && other.goodRate > active.goodRate + 0.25) { this.activeReverse = !this.activeReverse; this.figures = false; }
  }

  /** @param {number} df */
  collectRun(df) {
    const flip = (this.runState > 0 && df < -0.3) || (this.runState < 0 && df > 0.3);
    if (!flip) return;
    this.runs[this.runPos] = this.sampleIndex - this.runStart;
    this.runPos = (this.runPos + 1) % this.runs.length;
    if (this.runCount < this.runs.length) this.runCount++;
    this.runState = -this.runState;
    this.runStart = this.sampleIndex;
  }

  updateBaud() {
    if (this.runCount < 40) return;
    const est = estimateBaud(this.runs.slice(0, this.runCount), this.sampleRate);
    if (est == null) return;
    this.estimateRepeats = est === this.lastEstimate ? this.estimateRepeats + 1 : 1;
    this.lastEstimate = est;
    if (this.estimateRepeats >= 2 && est !== this.baud) this.baud = est;
  }

  /** @param {number} code */
  emit(code) {
    if (code === Baudot.LTRS) { this.figures = false; return; }
    if (code === Baudot.FIGS) { this.figures = true; return; }
    const c = this.figures ? Baudot.F[code] : Baudot.L[code];
    if (c === " " && this.unshiftOnSpace) this.figures = false;
    if (c === "\u0000" || c === "\u0007" || c === "\r") return;
    this.onText(c);
  }
}

/** Szybkość z długości odcinków (w próbkach) – dopasowanie do siatki standardowych szybkości. @param {ArrayLike<number>} runs @param {number} sampleRate */
export function estimateBaud(runs, sampleRate) {
  let best = null, bestScore = 0;
  for (const b of STANDARD_BAUDS) {
    const bit = sampleRate / b;
    let considered = 0, score = 0, ones = 0;
    for (let i = 0; i < runs.length; i++) {
      const ratio = runs[i] / bit;
      if (ratio < 0.6 || ratio > 8.5) continue;
      considered++;
      const nearest = Math.max(1, Math.round(ratio * 2) / 2);
      const dev = (ratio - nearest) / 0.12;
      score += Math.exp(-dev * dev);
      if (ratio >= 0.8 && ratio <= 1.25) ones++;
    }
    if (considered < 30) continue;
    let s = score / considered;
    if (ones < considered * 0.08) s *= 0.5;
    if (s > bestScore) { bestScore = s; best = b; }
  }
  return bestScore >= 0.6 ? best : null;
}

export const STANDARD_SHIFTS = [170, 200, 425, 450, 850];
/** @param {number} hz @param {number} mains */
const onHarmonic = (hz, mains) => Math.abs(hz - Math.round(hz / mains) * mains) <= 4;
/** Najbliższy standardowy przesuw (do 20 %) albo zaokrąglenie do 5 Hz. @param {number} measured */
export function snapShift(measured) {
  const near = STANDARD_SHIFTS.filter((s) => Math.abs(measured - s) <= s * 0.2).sort((a, b) => Math.abs(measured - a) - Math.abs(measured - b));
  return near.length ? near[0] : Math.round(measured / 5) * 5;
}

/** Uśrednione widmo 0–3 kHz (FFT 2048, okno Hanna) do strojenia. */
export class TuningSpectrum {
  constructor(sampleRate = 12000, size = 2048) {
    this.sampleRate = sampleRate; this.size = size;
    this.fft = new MixedRadixFft(size);
    this.window = Float64Array.from({ length: size }, (_, i) => 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / size));
    this.ring = new Float32Array(size); this.ringPos = 0; this.sinceLast = 0;
    this.re = new Float64Array(size); this.im = new Float64Array(size); this.outRe = new Float64Array(size); this.outIm = new Float64Array(size);
    this.binHz = sampleRate / size;
    this.bins = Math.floor(3000 / this.binHz);
    this.avgDb = new Float32Array(this.bins).fill(-100);
  }
  spectrum() { return this.avgDb.slice(); }

  /** @param {Float32Array} samples */
  process(samples) {
    const { size, ring } = this;
    for (let i = 0; i < samples.length; i++) { ring[this.ringPos] = samples[i]; this.ringPos = (this.ringPos + 1) % size; }
    this.sinceLast += samples.length;
    if (this.sinceLast < size / 2) return;
    this.sinceLast = 0;
    for (let i = 0; i < size; i++) { this.re[i] = ring[(this.ringPos + i) % size] * this.window[i]; this.im[i] = 0; }
    this.fft.transform(this.re, this.im, this.outRe, this.outIm);
    for (let b = 0; b < this.bins; b++) {
      const p = this.outRe[b] * this.outRe[b] + this.outIm[b] * this.outIm[b];
      const db = 10 * Math.log10(p + 1e-12);
      this.avgDb[b] = this.avgDb[b] <= -99 ? db : this.avgDb[b] * 0.5 + db * 0.5;
    }
  }

  /** @param {number | null} [nearHz] @param {number | null} [fixedShift] */
  findRttySignal(nearHz = null, fixedShift = null) {
    let best = null;
    for (const p of this.findPairs(fixedShift)) if ((nearHz == null || Math.abs(p.centerHz - nearHz) <= 150) && (!best || p.score > best.score)) best = p;
    return best;
  }

  /** Pary szczytów wyglądające jak RTTY. @param {number | null} [fixedShift] @returns {{centerHz: number, shiftHz: number, score: number}[]} */
  findPairs(fixedShift = null) {
    const spec = this.avgDb, binHz = this.binHz;
    if (spec.every((v) => v <= -99)) return [];
    const sorted = Float32Array.from(spec).sort();
    const noise = sorted[Math.floor(sorted.length / 2)];
    /** @type {[number, number][]} */
    const peaks = [];
    const first = Math.max(2, Math.floor(250 / binHz)), last = Math.min(spec.length - 3, Math.floor(2950 / binHz));
    for (let b = first; b <= last; b++) {
      const v = spec[b];
      if (v < noise + 9) continue;
      if (v < spec[b - 1] || v < spec[b + 1] || v < spec[b - 2] || v < spec[b + 2]) continue;
      const den = spec[b - 1] - 2 * v + spec[b + 1];
      const off = den !== 0 ? Math.min(0.5, Math.max(-0.5, (0.5 * (spec[b - 1] - spec[b + 1])) / den)) : 0;
      peaks.push([(b + off) * binHz, v]);
    }
    const out = [];
    for (let i = 0; i < peaks.length; i++) {
      for (let j = i + 1; j < peaks.length; j++) {
        const [fa, da] = peaks[i], [fb, db] = peaks[j];
        const sep = fb - fa;
        if (fixedShift != null) { if (Math.abs(sep - fixedShift) > Math.max(15, 0.08 * fixedShift)) continue; }
        else if (sep < 80 || sep > 1000) continue;
        const weaker = Math.min(da, db);
        if (Math.abs(da - db) > 12) continue;
        // Prążek między tonami prawie tak silny jak one – to grzebień, nie para RTTY
        let comb = false;
        for (let k = i + 1; k < j; k++) if (peaks[k][1] > weaker - 6) { comb = true; break; }
        if (comb) continue;
        // Oba tony na harmonicznych sieci 50/60 Hz (mikrofon przy zasilaczu) – też nie RTTY
        if (fb < 1300 && [50, 60].some((m) => onHarmonic(fa, m) && onHarmonic(fb, m))) continue;
        const shift = fixedShift ?? snapShift(sep);
        const standard = STANDARD_SHIFTS.some((s) => Math.abs(sep - s) <= s * 0.2);
        out.push({ centerHz: (fa + fb) / 2, shiftHz: shift, score: weaker - noise - Math.abs(da - db) * 0.5 + (standard ? 3 : 0) });
      }
    }
    return out;
  }
}

const TARGET_CYCLES = 40;

/** Kompletny tor odbioru: widmo + AFC + przesuw + dekoder (RttyEngine). */
export class RttyEngine {
  /** @param {number} sampleRate @param {(s: string) => void} onText */
  constructor(sampleRate = 12000, onText = () => {}) {
    this.sampleRate = sampleRate;
    this.decoder = new RttyDecoder(sampleRate, onText);
    this.spectrum = new TuningSpectrum(sampleRate);
    this.autoFrequency = true; this.autoShift = true; this.locked = false;
    this.sinceTune = 0; /** @type {number | null} */ this.pendingShift = null; /** @type {number | null} */ this.pendingCenter = null;
    /** @type {number | null} */ this.userTargetHz = null; this.userTargetCycles = 0; this.userChosen = false;
    this.cyclesWithoutPair = 0; this.sinceFine = 1000;
  }

  /** @param {Float32Array} samples */
  process(samples) {
    this.spectrum.process(samples);
    this.decoder.process(samples);
    this.sinceTune += samples.length;
    if (this.sinceTune >= this.sampleRate / 4) { this.sinceTune = 0; this.autoTune(); }
  }

  /** Wskazanie stacji (dotknięcie widma). @param {number} hz */
  setCenter(hz) {
    const c = Math.min(2800, Math.max(200, hz));
    this.decoder.centerHz = c;
    this.userTargetHz = c;
    this.userTargetCycles = TARGET_CYCLES;
    this.userChosen = true;
    this.pendingCenter = null;
  }

  autoTune() {
    const dec = this.decoder;
    const fine = dec.takeFrequencyError();
    this.sinceFine = fine ? 0 : this.sinceFine + 1;
    if (!this.autoFrequency && !this.autoShift) { this.locked = false; return; }
    const pairs = this.spectrum.findPairs(this.autoShift ? null : dec.shiftHz);
    const center = dec.centerHz;
    let current = null;
    for (const p of pairs) if (Math.abs(p.centerHz - center) <= 30 && (!current || p.score > current.score)) current = p;
    this.locked = current != null;
    this.cyclesWithoutPair = current ? 0 : this.cyclesWithoutPair + 1;
    if (this.cyclesWithoutPair > 12 && this.userTargetCycles === 0) this.userChosen = false;

    if (this.autoFrequency) {
      const target = this.userTargetCycles > 0 ? this.userTargetHz : null;
      if (this.userTargetCycles > 0) this.userTargetCycles--;
      let best = null;
      for (const p of pairs) if ((target == null || Math.abs(p.centerHz - target) <= 150) && (!best || p.score > best.score)) best = p;
      const shouldJump = best != null && Math.abs(best.centerHz - center) > 30 &&
        (current == null || target != null || (!this.userChosen && best.score > current.score + 6));
      if (shouldJump && best) {
        const prev = this.pendingCenter;
        if (prev != null && Math.abs(prev - best.centerHz) < 20) {
          dec.centerHz = best.centerHz;
          if (this.autoShift) dec.shiftHz = best.shiftHz;
          this.pendingCenter = null;
          this.pendingShift = null;
          return;
        }
        this.pendingCenter = best.centerHz;
      } else this.pendingCenter = null;

      if (current) {
        const diff = current.centerHz - center;
        if (fine) dec.centerHz += Math.min(8, Math.max(-8, fine.centerHz * 0.7));
        else if (Math.abs(diff) >= 20 || (this.sinceFine > 8 && Math.abs(diff) >= 3)) dec.centerHz += diff * 0.5;
      }
    }
    if (this.autoShift && current) {
      if (Math.abs(current.shiftHz - dec.shiftHz) > dec.shiftHz * 0.2) this.proposeShift(current.shiftHz);
      else if (fine) this.proposeShift(snapShift(dec.shiftHz + fine.shiftHz));
    }
  }

  /** @param {number} shift */
  proposeShift(shift) {
    if (Math.abs(shift - this.decoder.shiftHz) <= 3) { this.pendingShift = null; return; }
    if (this.pendingShift === shift) { this.decoder.shiftHz = shift; this.pendingShift = null; } else this.pendingShift = shift;
  }
}
