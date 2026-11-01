// @ts-check
// Dekoder SSTV – port Robot36 v2.17 (Ahmet Inan, licencja 0BSD, https://github.com/xdsopl/robot36),
// pakiet sstv/robot36: Demodulator, Decoder i tryby (Robot 36/72, PD, Martin, Scottie, Wraase, Raw, HF Fax).
// Piksele: Int32Array w formacie ARGB jak Android Bitmap. Wejście: mono float, dowolna częstotliwość próbkowania.

const PI = Math.PI;

/** Bufor pikseli (PixelBuffer.java). */
export class PixelBuffer {
  /** @param {number} width @param {number} height */
  constructor(width, height) {
    this.width = width;
    this.height = height;
    this.line = 0;
    this.pixels = new Int32Array(width * height);
  }
}

// ---------- Pomocnicze filtry ----------

class Ema {
  constructor() { this.alpha = 1; this.prev = 0; }
  /** @param {number} x */
  avg(x) { return (this.prev = Math.fround(this.prev * (1 - this.alpha) + this.alpha * x)); }
  /** @param {number} freq @param {number} rate @param {number} order */
  cutoff(freq, rate, order) {
    const x = Math.cos((2 * PI * freq) / rate);
    this.alpha = Math.fround(Math.pow(x - 1 + Math.sqrt(x * (x - 4) + 3), 1 / order));
  }
  reset() { this.prev = 0; }
}

/** Suma krocząca na drzewie (SimpleMovingSum) – bez dryfu zaokrągleń. */
class MovingAverage {
  /** @param {number} length */
  constructor(length) { this.length = length; this.tree = new Float32Array(2 * length); this.leaf = length; }
  /** @param {number} x */
  avg(x) {
    const t = this.tree;
    t[this.leaf] = x;
    for (let child = this.leaf, parent = this.leaf >> 1; parent > 0; child = parent, parent >>= 1) t[parent] = t[child] + t[child ^ 1];
    if (++this.leaf >= t.length) this.leaf = this.length;
    return Math.fround(t[1] / this.length);
  }
}

class Delay {
  /** @param {number} length */
  constructor(length) { this.buf = new Float32Array(length); this.pos = 0; }
  /** @param {number} x */
  push(x) { const v = this.buf[this.pos]; this.buf[this.pos] = x; if (++this.pos >= this.buf.length) this.pos = 0; return v; }
}

/** @param {number} x */
function i0(x) {
  const s = new Float64Array(35);
  s[0] = 1;
  let val = 1;
  for (let n = 1; n < s.length; n++) { val *= x / (2 * n); s[n] = val * val; }
  s.sort();
  let sum = 0;
  for (let n = s.length - 1; n >= 0; n--) sum += s[n];
  return sum;
}
/** @param {number} a @param {number} n @param {number} N */
const kaiser = (a, n, N) => i0(PI * a * Math.sqrt(1 - ((2 * n) / (N - 1) - 1) ** 2)) / i0(PI * a);
/** @param {number} x */
const sinc = (x) => (x === 0 ? 1 : Math.sin(PI * x) / (PI * x));
/** @param {number} cutoff @param {number} rate @param {number} n @param {number} N */
const lowPass = (cutoff, rate, n, N) => { const f = (2 * cutoff) / rate; return f * sinc(f * (n - (N - 1) / 2)); };

/** @param {number} v */
const clamp255 = (v) => Math.min(Math.max(v, 0), 255);
/** @param {number} level */
const f2i = (level) => clamp255(Math.round(255 * level));
/** @param {number} Y @param {number} U @param {number} V */
function yuvInt(Y, U, V) {
  Y -= 16; U -= 128; V -= 128;
  const R = clamp255((298 * Y + 409 * V + 128) >> 8);
  const G = clamp255((298 * Y - 100 * U - 208 * V + 128) >> 8);
  const B = clamp255((298 * Y + 516 * U + 128) >> 8);
  return 0xff000000 | (R << 16) | (G << 8) | B;
}
const Color = {
  /** @param {number} level */
  gray: (level) => 0xff000000 | (0x010101 * f2i(Math.fround(Math.sqrt(Math.min(Math.max(level, 0), 1))))),
  /** @param {number} r @param {number} g @param {number} b */
  rgb: (r, g, b) => 0xff000000 | (f2i(r) << 16) | (f2i(g) << 8) | f2i(b),
  /** @param {number} y @param {number} u @param {number} v */
  yuv: (y, u, v) => yuvInt(f2i(y), f2i(u), f2i(v)),
  /** @param {number} p */
  yuvPacked: (p) => yuvInt((p & 0xff0000) >> 16, (p & 0xff00) >> 8, p & 0xff),
};

/** @param {number} f @param {number} offset */
const freqToLevel = (f, offset) => Math.fround(0.5 * (f - offset + 1));
/** @param {number} s @param {number} rate */
const sec = (s, rate) => Math.round(s * rate);
/** @param {number} a @param {number} b */
const idiv = (a, b) => Math.trunc(a / b);

/**
 * Filtr dwukierunkowy (EMA w przód i wstecz) i zamiana na poziomy 0..1 – wspólne dla wszystkich trybów.
 * @param {Ema} lp @param {Float32Array} scratch @param {Float32Array} line @param {number} src indeks źródła dla scratch[from]
 * @param {number} from @param {number} to @param {number} offset
 */
function filterLevels(lp, scratch, line, src, from, to, offset) {
  lp.reset();
  for (let i = from; i < to; i++) scratch[i] = lp.avg(line[src + i - from]);
  lp.reset();
  for (let i = to - 1; i >= from; i--) scratch[i] = freqToLevel(lp.avg(scratch[i]), offset);
}

// ---------- Tryby ----------

/**
 * @typedef {object} Mode
 * @property {string} name
 * @property {number} code
 * @property {number} width
 * @property {number} height
 * @property {number} firstPixelSampleIndex
 * @property {number} firstSyncPulseIndex
 * @property {number} scanLineSamples
 * @property {() => void} resetState
 * @property {(pb: PixelBuffer, scratch: Float32Array, line: Float32Array, scopeWidth: number, sync: number, lineSamples: number, offset: number) => boolean} decodeScanLine
 * @property {number} [horizontalShift] tylko HF Fax
 */

/**
 * @param {string} name @param {number} code @param {number} w @param {number} hgt @param {number} firstSync @param {number} lineSec
 * @param {number} begin @param {number} rB @param {number} rE @param {number} gB @param {number} gE @param {number} bB @param {number} bE @param {number} end @param {number} rate
 * @returns {Mode}
 */
function rgbMode(name, code, w, hgt, firstSync, lineSec, begin, rB, rE, gB, gE, bB, bE, end, rate) {
  const lp = new Ema();
  const beginS = sec(begin, rate);
  const redBegin = sec(rB, rate) - beginS, redS = sec(rE - rB, rate);
  const greenBegin = sec(gB, rate) - beginS, greenS = sec(gE - gB, rate);
  const blueBegin = sec(bB, rate) - beginS, blueS = sec(bE - bB, rate);
  const endS = sec(end, rate);
  return {
    name, code, width: w, height: hgt, firstPixelSampleIndex: beginS, firstSyncPulseIndex: sec(firstSync, rate), scanLineSamples: sec(lineSec, rate),
    resetState() {},
    decodeScanLine(pb, scratch, line, _sw, sync, _ls, offset) {
      if (sync + beginS < 0 || sync + endS > line.length) return false;
      lp.cutoff(w, 2 * greenS, 2);
      filterLevels(lp, scratch, line, sync + beginS, 0, endS - beginS, offset);
      for (let i = 0; i < w; i++) {
        pb.pixels[i] = Color.rgb(scratch[redBegin + idiv(i * redS, w)], scratch[greenBegin + idiv(i * greenS, w)], scratch[blueBegin + idiv(i * blueS, w)]);
      }
      pb.width = w; pb.height = 1;
      return true;
    },
  };
}

/** @param {string} name @param {number} code @param {number} ch @param {number} rate */
function martin(name, code, ch, rate) {
  const sync = 0.004862, sep = 0.000572;
  const line = sync + sep + 3 * (ch + sep);
  const gB = sep, gE = gB + ch, bB = gE + sep, bE = bB + ch, rB = bE + sep, rE = rB + ch;
  return rgbMode(`Martin ${name}`, code, 320, 256, 0, line, gB, rB, rE, gB, gE, bB, bE, rE, rate);
}
/** @param {string} name @param {number} code @param {number} ch @param {number} rate */
function scottie(name, code, ch, rate) {
  const sync = 0.009, sep = 0.0015;
  const first = sync + 2 * (sep + ch);
  const line = sync + 3 * (ch + sep);
  const bE = -sync, bB = bE - ch, gE = bB - sep, gB = gE - ch, rB = sep, rE = rB + ch;
  return rgbMode(`Scottie ${name}`, code, 320, 256, first, line, gB, rB, rE, gB, gE, bB, bE, rE, rate);
}
/** @param {number} rate */
function wraase(rate) {
  const sync = 0.0055225, porch = 0.0005, ch = 0.235;
  const line = sync + porch + 3 * ch;
  const rB = porch, rE = rB + ch, gB = rE, gE = gB + ch, bB = gE, bE = bB + ch;
  return rgbMode("Wraase SC2–180", 55, 320, 256, 0, line, rB, rB, rE, gB, gE, bB, bE, bE, rate);
}

/** @param {number} rate @returns {Mode} */
function robot36(rate) {
  const lp = new Ema();
  const w = 320;
  const syncP = 0.009, porch = 0.003, lum = 0.088, sep = 0.0045, porch2 = 0.0015, chroma = 0.044;
  const lumS = sec(lum, rate), sepS = sec(sep, rate), chromaS = sec(chroma, rate);
  const lumB = sec(porch, rate);
  const sepB = sec(porch + lum, rate);
  const chromaB = sec(porch + lum + sep + porch2, rate);
  const endS = sec(porch + lum + sep + porch2 + chroma, rate);
  let lastEven = false;
  return {
    name: "Robot 36 Color", code: 8, width: w, height: 240, firstPixelSampleIndex: lumB, firstSyncPulseIndex: 0,
    scanLineSamples: sec(syncP + porch + lum + sep + porch2 + chroma, rate),
    resetState() { lastEven = false; },
    decodeScanLine(pb, scratch, line, _sw, sync, _ls, offset) {
      if (sync + lumB < 0 || sync + endS > line.length) return false;
      let s = 0;
      for (let i = 0; i < sepS; i++) s = Math.fround(s + line[sync + sepB + i]);
      s = Math.fround(Math.fround(s / sepS) - offset);
      let even = s < 0;
      if (s < -1.1 || (s > -0.9 && s < 0.9) || s > 1.1) even = !lastEven;
      lastEven = even;
      lp.cutoff(w, 2 * lumS, 2);
      filterLevels(lp, scratch, line, sync + lumB, lumB, endS, offset);
      for (let i = 0; i < w; i++) {
        const y = scratch[lumB + idiv(i * lumS, w)], c = scratch[chromaB + idiv(i * chromaS, w)];
        if (even) pb.pixels[i] = Color.rgb(y, 0, c);
        else {
          const evenYUV = pb.pixels[i];
          const oddYUV = Color.rgb(y, c, 0);
          pb.pixels[i] = Color.yuvPacked((evenYUV & 0x00ff00ff) | (oddYUV & 0x0000ff00));
          pb.pixels[i + w] = Color.yuvPacked((oddYUV & 0x00ffff00) | (evenYUV & 0x000000ff));
        }
      }
      pb.width = w; pb.height = 2;
      return !even;
    },
  };
}

/** @param {number} rate @returns {Mode} */
function robot72(rate) {
  const lp = new Ema();
  const w = 320;
  const syncP = 0.009, porch = 0.003, lum = 0.138, sep = 0.0045, porch2 = 0.0015, chroma = 0.069;
  const lumS = sec(lum, rate), chromaS = sec(chroma, rate);
  const yB = sec(porch, rate);
  const vBs = porch + lum + sep + porch2, vB = sec(vBs, rate);
  const uBs = vBs + chroma + sep + porch2, uB = sec(uBs, rate);
  const endS = sec(uBs + chroma, rate);
  return {
    name: "Robot 72 Color", code: 12, width: w, height: 240, firstPixelSampleIndex: yB, firstSyncPulseIndex: 0,
    scanLineSamples: sec(syncP + porch + lum + 2 * (sep + porch2 + chroma), rate),
    resetState() {},
    decodeScanLine(pb, scratch, line, _sw, sync, _ls, offset) {
      if (sync + yB < 0 || sync + endS > line.length) return false;
      lp.cutoff(w, 2 * lumS, 2);
      filterLevels(lp, scratch, line, sync + yB, yB, endS, offset);
      for (let i = 0; i < w; i++) {
        pb.pixels[i] = Color.yuv(scratch[yB + idiv(i * lumS, w)], scratch[uB + idiv(i * chromaS, w)], scratch[vB + idiv(i * chromaS, w)]);
      }
      pb.width = w; pb.height = 1;
      return true;
    },
  };
}

/** @param {string} name @param {number} code @param {number} w @param {number} hgt @param {number} ch @param {number} rate @returns {Mode} */
function paulDon(name, code, w, hgt, ch, rate) {
  const lp = new Ema();
  const syncP = 0.02, porch = 0.00208;
  const chS = sec(ch, rate);
  const yEven = sec(porch, rate), vAvg = sec(porch + ch, rate), uAvg = sec(porch + 2 * ch, rate), yOdd = sec(porch + 3 * ch, rate);
  const endS = sec(porch + 4 * ch, rate);
  return {
    name: `PD ${name}`, code, width: w, height: hgt, firstPixelSampleIndex: yEven, firstSyncPulseIndex: 0,
    scanLineSamples: sec(syncP + porch + 4 * ch, rate),
    resetState() {},
    decodeScanLine(pb, scratch, line, _sw, sync, _ls, offset) {
      if (sync + yEven < 0 || sync + endS > line.length) return false;
      lp.cutoff(w, 2 * chS, 2);
      filterLevels(lp, scratch, line, sync + yEven, yEven, endS, offset);
      for (let i = 0; i < w; i++) {
        const p = idiv(i * chS, w);
        const u = scratch[p + uAvg], v = scratch[p + vAvg];
        pb.pixels[i] = Color.yuv(scratch[p + yEven], u, v);
        pb.pixels[i + w] = Color.yuv(scratch[p + yOdd], u, v);
      }
      pb.width = w; pb.height = 2;
      return true;
    },
  };
}

/** @param {string} name @param {number} rate @returns {Mode} */
function rawMode(name, rate) {
  const lp = new Ema();
  const small = sec(0.125, rate), medium = sec(0.175, rate);
  return {
    name, code: -1, width: -1, height: -1, firstPixelSampleIndex: 0, firstSyncPulseIndex: -1, scanLineSamples: -1,
    resetState() {},
    decodeScanLine(pb, scratch, line, scopeWidth, sync, ls, offset) {
      if (sync < 0 || sync + ls > line.length) return false;
      let w = scopeWidth;
      if (ls < small) w = idiv(w, 2);
      if (ls < medium) w = idiv(w, 2);
      lp.cutoff(w, 2 * ls, 2);
      filterLevels(lp, scratch, line, sync, 0, ls, offset);
      for (let i = 0; i < w; i++) pb.pixels[i] = Color.gray(scratch[idiv(i * ls, w)]);
      pb.width = w; pb.height = 1;
      return true;
    },
  };
}

/** HF Fax, IOC 576, 120 linii/min. @param {number} rate @returns {Mode} */
function hfFax(rate) {
  const lp = new Ema();
  const w = 640;
  const cumulated = new Float32Array(w);
  /** @type {Mode} */
  const m = {
    name: "HF Fax", code: -1, width: w, height: 1200, firstPixelSampleIndex: 0, firstSyncPulseIndex: -1, scanLineSamples: idiv(rate, 2),
    horizontalShift: 0,
    resetState() {},
    decodeScanLine(pb, scratch, line, _sw, sync, ls, offset) {
      if (sync < 0 || sync + ls > line.length) return false;
      lp.cutoff(w, 2 * ls, 2);
      // Jak w Robot36: linia faksu zaczyna się od początku bufora
      filterLevels(lp, scratch, line, 0, 0, ls, offset);
      for (let i = 0; i < w; i++) {
        const color = Color.gray(scratch[idiv(i * ls, w)]);
        pb.pixels[i] = color;
        const lum = (0.2126 * ((color >> 16) & 255) + 0.7152 * ((color >> 8) & 255) + 0.0722 * (color & 255)) / 255;
        cumulated[i] = cumulated[i] * 0.99 + lum * 0.01;
      }
      let best = 0, bestValue = 0;
      for (let x = 0; x < w; x++) if (cumulated[x] > bestValue) { best = x; bestValue = cumulated[x]; }
      m.horizontalShift = best;
      pb.width = w; pb.height = 1;
      return true;
    },
  };
  return m;
}

// ---------- Demodulator ----------

const SYNC_FREQ = 1200, BLACK = 1500, WHITE = 2300;

class Demodulator {
  /** @param {number} rate */
  constructor(rate) {
    this.bandwidth = WHITE - BLACK;
    this.fmScale = rate / (this.bandwidth * PI);
    this.fmPrev = 0;
    const s5 = 0.005, s9 = 0.009, s20 = 0.02;
    this.min5 = sec(s5 / 2, rate);
    this.max5 = sec((s5 + s9) / 2, rate);
    this.max9 = sec((s9 + s20) / 2, rate);
    this.max20 = sec(s20 + s5, rate);
    const filt = sec(s5 / 2, rate) | 1;
    this.filterDelay = (filt - 1) / 2;
    this.syncFilter = new MovingAverage(filt);
    this.syncDelay = new Delay(filt);
    const lo = 1000, hi = 2800;
    const n = sec(0.002, rate) | 1;
    this.taps = Float32Array.from({ length: n }, (_, i) => kaiser(2, i, n) * lowPass((hi - lo) / 2, rate, i, n));
    this.re = new Float32Array(n);
    this.im = new Float32Array(n);
    this.pos = 0;
    this.center = (lo + hi) / 2;
    const omega = (2 * PI * -this.center) / rate;
    this.dRe = Math.fround(Math.cos(omega)); this.dIm = Math.fround(Math.sin(omega));
    this.oRe = 1; this.oIm = 0;
    this.syncValue = Math.fround(this.norm(SYNC_FREQ));
    this.syncTolerance = Math.fround((50 * 2) / this.bandwidth);
    const syncHigh = (SYNC_FREQ + BLACK) / 2, syncLow = (SYNC_FREQ + syncHigh) / 2;
    this.trigLow = Math.fround(this.norm(syncLow));
    this.trigHigh = Math.fround(this.norm(syncHigh));
    this.trig = false;
    this.counter = 0;
    /** 5, 9 lub 20 (ms) */
    this.syncWidth = 0;
    this.syncOffset = 0;
    this.frequencyOffset = 0;
  }
  /** @param {number} f */
  norm(f) { return ((f - this.center) * 2) / this.bandwidth; }

  /** Nadpisuje [buf] znormalizowaną częstotliwością. @param {Float32Array} buf */
  process(buf) {
    let detected = false;
    const { taps, re, im } = this;
    const n = taps.length;
    for (let i = 0; i < buf.length; i++) {
      // Oscylator z normalizacją amplitudy
      const r = this.oRe * this.dRe - this.oIm * this.dIm, q = this.oRe * this.dIm + this.oIm * this.dRe;
      const a = Math.hypot(r, q);
      this.oRe = Math.fround(r / a); this.oIm = Math.fround(q / a);
      const x = buf[i];
      re[this.pos] = x * this.oRe; im[this.pos] = x * this.oIm;
      if (++this.pos >= n) this.pos = 0;
      let sr = 0, si = 0, p = this.pos;
      for (let k = 0; k < n; k++) {
        sr += taps[k] * re[p]; si += taps[k] * im[p];
        if (++p >= n) p = 0;
      }
      this.pos = p;
      const phase = Math.atan2(si, sr);
      let delta = phase - this.fmPrev;
      if (delta < -PI) delta += 2 * PI; else if (delta > PI) delta -= 2 * PI;
      this.fmPrev = phase;
      const freq = Math.fround(this.fmScale * delta);
      const sv = this.syncFilter.avg(freq);
      const delayed = this.syncDelay.push(sv);
      buf[i] = freq;
      if (this.trig) { if (sv < this.trigLow) this.trig = false; } else if (sv > this.trigHigh) this.trig = true;
      if (!this.trig) {
        this.counter++;
      } else if (this.counter < this.min5 || this.counter > this.max20 || Math.abs(delayed - this.syncValue) > this.syncTolerance) {
        this.counter = 0;
      } else {
        this.syncWidth = this.counter < this.max5 ? 5 : this.counter < this.max9 ? 9 : 20;
        this.syncOffset = i - this.filterDelay;
        this.frequencyOffset = Math.fround(delayed - this.syncValue);
        detected = true;
        this.counter = 0;
      }
    }
    return detected;
  }
}

// ---------- Dekoder ----------

export const RAW_MODE = "Raw";
export const AUTO_MODE = "Auto";
/** Tryby w kolejności menu Robot36. */
export const SSTV_MODES = [RAW_MODE, "HF Fax", "Robot 36 Color", "Robot 72 Color", "PD 50", "PD 90", "PD 120", "PD 160", "PD 180", "PD 240", "PD 290",
  "Martin 1", "Martin 2", "Scottie 1", "Scottie 2", "Scottie DX", "Wraase SC2–180"];

/** Kolory znaczników w podglądzie (ARGB). */
const BLACK_PX = 0xff000000 | 0, RED_PX = 0xffff0000 | 0, GREEN_PX = 0xff00ff00 | 0, CYAN_PX = 0xff00ffff | 0;

export class SstvDecoder {
  /** @param {PixelBuffer} scopeBuffer @param {PixelBuffer} imageBuffer @param {number} rate */
  constructor(scopeBuffer, imageBuffer, rate) {
    this.rate = rate;
    this.scope = scopeBuffer;
    this.image = imageBuffer;
    imageBuffer.line = -1;
    this.pixelBuffer = new PixelBuffer(800, 2);
    this.demod = new Demodulator(rate);
    const pf = sec(0.0025, rate) | 1;
    this.pulseFilterDelay = (pf - 1) / 2;
    this.pulseFilter = new MovingAverage(pf);
    this.line = new Float32Array(sec(7, rate));
    this.scratch = new Float32Array(sec(1.1, rate));
    this.leaderTone = sec(0.3, rate);
    this.leaderTol = sec(0.3 * 0.2, rate);
    this.transition = sec(0.0005, rate);
    this.visBit = sec(0.03, rate);
    this.visCode = sec(0.3, rate);
    this.visFreqs = new Float32Array(10);
    const lines = 4;
    /** @type {Record<number, {pulses: Int32Array, lines: Int32Array, offsets: Float32Array, modes: Mode[]}>} */
    this.groups = {};
    this.lineMin = sec(0.05, rate);
    this.syncTol = sec(0.03, rate);
    this.lineTol = sec(0.001, rate);
    this.raw = rawMode(RAW_MODE, rate);
    this.fax = hfFax(rate);
    const r36 = robot36(rate);
    this.currentMode = r36;
    this.currentLineSamples = r36.scanLineSamples;
    const group = (/** @type {Mode[]} */ modes) => ({ pulses: new Int32Array(lines + 1), lines: new Int32Array(lines), offsets: new Float32Array(lines + 1), modes });
    this.groups[5] = group([wraase(rate), martin("1", 44, 0.146432, rate), martin("2", 40, 0.073216, rate)]);
    this.groups[9] = group([r36, robot72(rate), scottie("1", 60, 0.13824, rate), scottie("2", 56, 0.088064, rate), scottie("DX", 76, 0.3456, rate)]);
    this.groups[20] = group([
      paulDon("50", 93, 320, 256, 0.09152, rate), paulDon("90", 99, 320, 256, 0.17024, rate), paulDon("120", 95, 640, 496, 0.1216, rate),
      paulDon("160", 98, 512, 400, 0.195584, rate), paulDon("180", 96, 640, 496, 0.18304, rate), paulDon("240", 97, 640, 496, 0.24448, rate),
      paulDon("290", 94, 800, 616, 0.2288, rate)]);
    this.lockMode = false;
    this.currentSample = 0;
    this.leaderBreakIndex = 0;
    this.lastSyncPulseIndex = 0;
    this.lastFrequencyOffset = 0;
    this.inputEnded = false;
  }

  /** @returns {Mode[]} */
  allModes() { return [...this.groups[5].modes, ...this.groups[9].modes, ...this.groups[20].modes]; }

  /** @param {Mode[]} modes @param {number} line */
  detectMode(modes, line) {
    let best = this.raw, bestDist = Infinity;
    for (const m of modes) {
      const d = Math.abs(line - m.scanLineSamples);
      if (d <= this.lineTol && d < bestDist) { bestDist = d; best = m; }
    }
    return best;
  }

  /** Kopia do przewijanego podglądu (połowa bufora zdublowana – ciągły odczyt ostatnich linii). */
  scopeRow() {
    const s = this.scope;
    const half = s.height / 2;
    const at = s.width * s.line;
    s.pixels.copyWithin(s.width * (s.line + half), at, at + s.width);
    s.line = (s.line + 1) % half;
  }

  /** @param {number} scale */
  copyToScope(scale) {
    const pb = this.pixelBuffer, s = this.scope;
    for (let row = 0; row < pb.height; row++) {
      const at = s.width * s.line;
      if (scale <= 1) {
        const w = Math.min(s.width, pb.width);
        s.pixels.set(pb.pixels.subarray(row * pb.width, row * pb.width + w), at);
        s.pixels.fill(0, at + w, at + s.width);
        this.scopeRow();
      } else {
        for (let col = 0; col < pb.width; col++) s.pixels.fill(pb.pixels[pb.width * row + col], at + col * scale, at + (col + 1) * scale);
        s.pixels.fill(0, at + pb.width * scale, at + s.width);
        this.scopeRow();
        for (let i = 1; i < scale; i++) {
          s.pixels.copyWithin(s.width * s.line, at, at + s.width);
          this.scopeRow();
        }
      }
    }
  }

  /** @param {boolean} okay */
  copyLines(okay) {
    if (!okay) return;
    const pb = this.pixelBuffer, img = this.image;
    let finish = false;
    if (img.line >= 0 && img.line < img.height && img.width === pb.width) {
      const w = img.width;
      for (let row = 0; row < pb.height && img.line < img.height; row++, img.line++) img.pixels.set(pb.pixels.subarray(row * w, row * w + w), img.line * w);
      finish = img.line === img.height;
    }
    this.copyToScope(idiv(this.scope.width, pb.width));
    if (finish) this.drawLines(BLACK_PX, 10);
  }

  /** @param {number} color @param {number} count */
  drawLines(color, count) {
    const s = this.scope;
    for (let i = 0; i < count; i++) {
      s.pixels.fill(color, s.line * s.width, (s.line + 1) * s.width);
      this.scopeRow();
    }
  }

  /** @param {number} shift */
  shiftSamples(shift) {
    if (shift <= 0 || shift > this.currentSample) return;
    this.currentSample -= shift;
    this.leaderBreakIndex -= shift;
    this.lastSyncPulseIndex -= shift;
    for (const k of [5, 9, 20]) { const p = this.groups[k].pulses; for (let i = 0; i < p.length; i++) p[i] -= shift; }
    this.line.copyWithin(0, shift, shift + this.currentSample);
  }

  handleHeader() {
    const L = this.line;
    const { visBit, leaderTol, leaderTone, transition, visCode: visCodeS } = this;
    if (this.leaderBreakIndex < visBit + leaderTol || this.currentSample < this.leaderBreakIndex + leaderTone + leaderTol + visCodeS + visBit) return false;
    const breakIdx = this.leaderBreakIndex;
    this.leaderBreakIndex = 0;
    const leaderF = 1900, centerF = 1900, tolF = 50, halfBw = 400;
    let pre = 0;
    for (let i = 0; i < leaderTol; i++) pre = Math.fround(pre + L[breakIdx - visBit - leaderTol + i]);
    pre = (pre * halfBw) / leaderTol + centerF;
    if (Math.abs(pre - leaderF) > tolF) return false;
    let lf = 0;
    for (let i = transition; i < leaderTone - leaderTol; i++) lf = Math.fround(lf + L[breakIdx + i]);
    const leaderOff = Math.fround(lf / (leaderTone - transition - leaderTol));
    if (Math.abs(leaderOff * halfBw + centerF - leaderF) > tolF) return false;
    const stopF = 1200;
    const pulseThr = ((stopF + leaderF) / 2 - centerF) / halfBw;
    let visBegin = breakIdx + leaderTone - leaderTol;
    let visEnd = breakIdx + leaderTone + leaderTol + visBit;
    const pf = this.pulseFilter;
    for (let i = 0; i < pf.length; i++) pf.avg(L[visBegin++] - leaderOff);
    while (++visBegin < visEnd) if (pf.avg(L[visBegin] - leaderOff) < pulseThr) break;
    if (visBegin >= visEnd) return false;
    visBegin -= this.pulseFilterDelay;
    visEnd = visBegin + visCodeS;
    const vf = this.visFreqs;
    vf.fill(0);
    for (let j = 0; j < 10; j++) for (let i = transition; i < visBit - transition; i++) vf[j] += L[visBegin + visBit * j + i] - leaderOff;
    for (let i = 0; i < 10; i++) vf[i] = (vf[i] * halfBw) / (visBit - 2 * transition) + centerF;
    if (Math.abs(vf[0] - stopF) > tolF || Math.abs(vf[9] - stopF) > tolF) return false;
    for (let i = 1; i < 9; i++) if (Math.abs(vf[i] - 1100) > tolF && Math.abs(vf[i] - 1300) > tolF) return false;
    let code = 0;
    for (let i = 0; i < 8; i++) code |= (vf[i + 1] < stopF ? 1 : 0) << i;
    let check = true;
    for (let i = 0; i < 8; i++) check = check !== ((code & (1 << i)) !== 0);
    code &= 127;
    if (!check) return false;
    const syncThr = ((1200 + 1500) / 2 - centerF) / halfBw;
    let syncIdx = visEnd - visBit;
    const syncMax = visEnd + visBit;
    for (let i = 0; i < pf.length; i++) pf.avg(L[syncIdx++] - leaderOff);
    while (++syncIdx < syncMax) if (pf.avg(L[syncIdx] - leaderOff) > syncThr) break;
    if (syncIdx >= syncMax) return false;
    syncIdx -= this.pulseFilterDelay;
    /** @type {Mode | undefined} */
    let mode;
    let g;
    for (const k of [5, 9, 20]) { mode = this.groups[k].modes.find((m) => m.code === code); if (mode) { g = this.groups[k]; break; } }
    if (!mode || !g) {
      if (!this.lockMode) this.drawLines(RED_PX, 8);
      return false;
    }
    if (this.lockMode && mode !== this.currentMode) return false;
    mode.resetState();
    this.image.width = mode.width;
    this.image.height = mode.height;
    this.image.line = 0;
    this.currentMode = mode;
    this.lastSyncPulseIndex = syncIdx + mode.firstSyncPulseIndex;
    this.currentLineSamples = mode.scanLineSamples;
    this.lastFrequencyOffset = leaderOff;
    let oldest = this.lastSyncPulseIndex - (g.pulses.length - 1) * this.currentLineSamples;
    if (mode.firstSyncPulseIndex > 0) oldest -= this.currentLineSamples;
    for (let i = 0; i < g.pulses.length; i++) g.pulses[i] = oldest + i * this.currentLineSamples;
    g.lines.fill(this.currentLineSamples);
    this.shiftSamples(this.lastSyncPulseIndex + mode.firstPixelSampleIndex);
    this.drawLines(GREEN_PX, 8);
    this.drawLines(BLACK_PX, 10);
    return true;
  }

  /** @param {number} width @param {number} latest */
  processSyncPulse(width, latest) {
    const { pulses, lines, offsets, modes } = this.groups[width];
    pulses.copyWithin(0, 1); pulses[pulses.length - 1] = latest;
    lines.copyWithin(0, 1); lines[lines.length - 1] = pulses[pulses.length - 1] - pulses[pulses.length - 2];
    offsets.copyWithin(0, 1); offsets[pulses.length - 1] = this.demod.frequencyOffset;
    if (lines[0] === 0) return false;
    let mean = 0;
    for (const d of lines) mean += d;
    mean /= lines.length;
    const lineSamples = Math.round(mean);
    if (lineSamples < this.lineMin || lineSamples > this.scratch.length) return false;
    let sd = 0;
    for (const d of lines) sd += (d - mean) ** 2;
    if (Math.sqrt(sd / lines.length) > this.lineTol) return false;
    let changed = false;
    const img = this.image;
    if (this.lockMode || (img.line >= 0 && img.line < img.height)) {
      if (this.currentMode !== this.raw && Math.abs(lineSamples - this.currentMode.scanLineSamples) > this.lineTol) return false;
    } else {
      const prev = this.currentMode;
      this.currentMode = this.detectMode(modes, lineSamples);
      changed = this.currentMode !== prev
        || Math.abs(this.currentLineSamples - lineSamples) > this.lineTol
        || Math.abs(this.lastSyncPulseIndex + lineSamples - pulses[pulses.length - 1]) > this.syncTol;
    }
    if (changed) { this.drawLines(BLACK_PX, 10); this.drawLines(CYAN_PX, 8); this.drawLines(BLACK_PX, 10); }
    let off = 0;
    for (const o of offsets) off += o;
    const freqOff = Math.fround(off / offsets.length);
    const m = this.currentMode;
    if (pulses[0] >= lineSamples && changed) {
      const end = pulses[0];
      const first = end - idiv(end, lineSamples) * lineSamples;
      for (let p = first; p < end; p += lineSamples) this.copyLines(m.decodeScanLine(this.pixelBuffer, this.scratch, this.line, this.scope.width, p, lineSamples, freqOff));
    }
    for (let i = changed ? 0 : lines.length - 1; i < lines.length; i++) this.copyLines(m.decodeScanLine(this.pixelBuffer, this.scratch, this.line, this.scope.width, pulses[i], lines[i], freqOff));
    this.lastSyncPulseIndex = pulses[pulses.length - 1];
    this.currentLineSamples = lineSamples;
    this.lastFrequencyOffset = freqOff;
    this.shiftSamples(this.lastSyncPulseIndex + m.firstPixelSampleIndex);
    return true;
  }

  /** Blok próbek mono (najlepiej ~20 ms – jak w Robot36). Zwraca true, gdy pojawiły się nowe linie. @param {Float32Array} input */
  process(input) {
    const buf = Float32Array.from(input);
    let newLines = false;
    const detected = this.demod.process(buf);
    let syncIdx = this.currentSample + this.demod.syncOffset;
    for (let j = 0; j < buf.length; j++) {
      this.line[this.currentSample++] = buf[j];
      if (this.currentSample >= this.line.length) { this.shiftSamples(this.currentLineSamples); syncIdx -= this.currentLineSamples; }
    }
    if (detected) {
      if (this.demod.syncWidth !== 5) this.leaderBreakIndex = syncIdx;
      newLines = this.processSyncPulse(this.demod.syncWidth, syncIdx);
    } else if (this.handleHeader()) {
      newLines = true;
    } else if (this.currentSample > this.lastSyncPulseIndex + idiv(this.currentLineSamples * 5, 4)) {
      this.copyLines(this.currentMode.decodeScanLine(this.pixelBuffer, this.scratch, this.line, this.scope.width, this.lastSyncPulseIndex, this.currentLineSamples, this.lastFrequencyOffset));
      this.lastSyncPulseIndex += this.currentLineSamples;
      newLines = true;
    }
    return newLines;
  }

  /** Koniec pliku: sekunda ciszy przez dekoder (Decoder.finish). */
  finish() {
    if (this.inputEnded) return false;
    this.inputEnded = true;
    if (this.image.line < 0 || this.image.line >= this.image.height) return false;
    const total = sec(1, this.rate), block = Math.max(1, idiv(this.rate, 50));
    let out = false;
    for (let o = 0; o < total; o += block) out = this.process(new Float32Array(Math.min(block, total - o))) || out;
    return out;
  }

  /** @param {string} name */
  setMode(name) {
    if (name === this.raw.name) { this.lockMode = true; this.image.line = -1; this.currentMode = this.raw; return; }
    let mode = this.allModes().find((m) => m.name === name);
    if (!mode && name === this.fax.name) mode = this.fax;
    if (mode === this.currentMode) { this.lockMode = true; return; }
    if (mode) {
      this.lockMode = true;
      this.image.line = -1;
      this.currentMode = mode;
      this.currentLineSamples = mode.scanLineSamples;
      return;
    }
    this.lockMode = false;
  }
}

/**
 * Sesja odbioru (Robot36Session + Robot36Receiver): nowy dekoder na sesję, bufory podglądu i obrazu współdzielone.
 * Podgląd 640 × 1280 linii (przewijany), obraz do 800 × 616.
 */
export class SstvReceiver {
  constructor() {
    this.scope = new PixelBuffer(640, 2 * 1280);
    this.image = new PixelBuffer(800, 616);
    this.modeName = AUTO_MODE;
    /** @type {SstvDecoder | null} */
    this.decoder = null;
    this.block = 0;
    this.carry = new Float32Array(0);
    this.carryFill = 0;
  }

  /** @param {number} rate */
  start(rate) {
    this.decoder = new SstvDecoder(this.scope, this.image, rate);
    this.decoder.setMode(this.modeName);
    this.block = Math.max(1, idiv(rate, 50));
  }

  /** @param {string} name */
  setMode(name) { this.modeName = name; this.decoder?.setMode(name); }

  get modeLabel() { return this.decoder?.currentMode.name ?? null; }

  /**
   * Próbki dowolnej długości – dzielone na bloki po 20 ms jak w Robot36.
   * @param {Float32Array} x @param {(img: {pixels: Int32Array, width: number, height: number, mode: string}) => void} [onImage]
   * @returns {boolean} czy pojawiły się nowe linie
   */
  process(x, onImage) {
    const d = this.decoder;
    if (!d) return false;
    let any = false;
    if (this.carry.length !== this.block) { this.carry = new Float32Array(this.block); this.carryFill = 0; }
    let i = 0;
    while (i < x.length) {
      const n = Math.min(this.block - this.carryFill, x.length - i);
      this.carry.set(x.subarray(i, i + n), this.carryFill);
      this.carryFill += n;
      i += n;
      if (this.carryFill === this.block) {
        this.carryFill = 0;
        if (d.process(this.carry)) { any = true; this.takeImage(onImage); }
      }
    }
    return any;
  }

  /** @param {(img: {pixels: Int32Array, width: number, height: number, mode: string}) => void} [onImage] */
  finish(onImage) {
    const d = this.decoder;
    if (!d) return;
    if (this.carryFill) { d.process(this.carry.slice(0, this.carryFill)); this.carryFill = 0; }
    d.finish();
    this.takeImage(onImage);
  }

  /** @param {(img: {pixels: Int32Array, width: number, height: number, mode: string}) => void} [onImage] */
  takeImage(onImage) {
    const img = this.image;
    if (img.line < img.height) return;
    img.line = -1;
    onImage?.({ pixels: img.pixels.slice(0, img.width * img.height), width: img.width, height: img.height, mode: this.modeLabel ?? "" });
  }

  clearScope() { this.scope.pixels.fill(0); }

  /** Ostatnie [height] linii podglądu (z bufora zdublowanego). @param {number} height */
  scopeView(height) {
    const s = this.scope;
    const hh = Math.max(1, Math.min(height, s.height / 2));
    const off = s.width * (s.line + s.height / 2 - hh);
    return { pixels: s.pixels.subarray(off, off + s.width * hh), width: s.width, height: hh };
  }

  /** Przesunięcie poziome dla HF Fax (postProcessScopeImage) – 0 dla innych trybów. */
  get faxShift() { return this.decoder?.currentMode.horizontalShift ?? 0; }
}

/**
 * ARGB → RGBA (ImageData).
 * @param {Int32Array} argb @returns {Uint8ClampedArray}
 */
export function argbToRgba(argb) {
  const out = new Uint8ClampedArray(argb.length * 4);
  for (let i = 0, o = 0; i < argb.length; i++, o += 4) {
    const p = argb[i];
    out[o] = (p >> 16) & 255; out[o + 1] = (p >> 8) & 255; out[o + 2] = p & 255; out[o + 3] = (p >>> 24) & 255;
  }
  return out;
}
