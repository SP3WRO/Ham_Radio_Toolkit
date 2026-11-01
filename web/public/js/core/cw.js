// @ts-check
// Telegrafia (cw/*.kt): alfabet Morse'a, metoda Kocha, czasy (PARIS, Farnsworth), generator, ocena i dekoder.

/** @type {Record<string, string>} */
export const MORSE = {
  A: ".-", B: "-...", C: "-.-.", D: "-..", E: ".", F: "..-.", G: "--.", H: "....", I: "..", J: ".---", K: "-.-", L: ".-..", M: "--",
  N: "-.", O: "---", P: ".--.", Q: "--.-", R: ".-.", S: "...", T: "-", U: "..-", V: "...-", W: ".--", X: "-..-", Y: "-.--", Z: "--..",
  0: "-----", 1: ".----", 2: "..---", 3: "...--", 4: "....-", 5: ".....", 6: "-....", 7: "--...", 8: "---..", 9: "----.",
  ".": ".-.-.-", ",": "--..--", "?": "..--..", "/": "-..-.", "=": "-...-", "+": ".-.-.", "-": "-....-", "@": ".--.-.", "'": ".----.",
  "(": "-.--.", ")": "-.--.-", ":": "---...", "!": "-.-.--", "&": ".-...",
};
/** @type {Record<string, string>} */
export const MORSE_DECODE = Object.fromEntries(Object.entries(MORSE).map(([k, v]) => [v, k]));

/** Kolejność Kocha (jak w LCWO): lekcja 1 = K i M, każda następna dodaje znak. */
export const KOCH_ORDER = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X";
export const KOCH_LESSONS = 40;
/** @param {number} lesson */
export const kochCharacters = (lesson) => KOCH_ORDER.slice(0, Math.min(KOCH_LESSONS, Math.max(1, lesson)) + 1);

/** Czasy elementów [ms]: kropka = 1200/WPM; przy prędkości efektywnej niższej wydłużane są tylko odstępy (ARRL). */
export function cwTiming(charWpm, effectiveWpm = charWpm) {
  const ditMs = 1200 / charWpm;
  const unit = effectiveWpm >= charWpm ? ditMs : (((60 * charWpm - 37.2 * effectiveWpm) / (charWpm * effectiveWpm)) * 1000) / 19;
  return { ditMs, dahMs: 3 * ditMs, elementGapMs: ditMs, charGapMs: 3 * unit, wordGapMs: 7 * unit };
}

/**
 * Sygnał CW (próbki −1..1) z łagodnymi zboczami. @param {string} text @param {ReturnType<typeof cwTiming>} timing
 * @param {{sampleRate?: number, toneHz?: number, amplitude?: number, riseMs?: number, leadInMs?: number}} [o]
 */
export function renderCw(text, timing, o = {}) {
  const rate = o.sampleRate ?? 44100, tone = o.toneHz ?? 600, amp = o.amplitude ?? 0.6, riseMs = o.riseMs ?? 5;
  const n = (/** @type {number} */ ms) => Math.round((ms * rate) / 1000);
  /** @type {[boolean, number][]} */
  const parts = [[false, o.leadInMs ?? 300]];
  const words = text.toUpperCase().trim().split(/\s+/).filter(Boolean);
  words.forEach((word, wi) => {
    const chars = [...word].filter((c) => MORSE[c]);
    chars.forEach((ch, ci) => {
      const code = MORSE[ch];
      [...code].forEach((el, ei) => {
        parts.push([true, el === "." ? timing.ditMs : timing.dahMs]);
        if (ei < code.length - 1) parts.push([false, timing.elementGapMs]);
      });
      if (ci < chars.length - 1) parts.push([false, timing.charGapMs]);
    });
    if (wi < words.length - 1) parts.push([false, timing.wordGapMs]);
  });
  parts.push([false, timing.wordGapMs]);
  const out = new Float32Array(parts.reduce((s, [, ms]) => s + n(ms), 0));
  let pos = 0, phase = 0;
  const step = (2 * Math.PI * tone) / rate;
  for (const [on, ms] of parts) {
    const len = n(ms);
    if (on) {
      const rise = Math.max(1, Math.min(n(riseMs), Math.floor(len / 2)));
      for (let i = 0; i < len; i++) {
        const env = i < rise ? 0.5 - 0.5 * Math.cos((Math.PI * i) / rise) : i >= len - rise ? 0.5 - 0.5 * Math.cos((Math.PI * (len - 1 - i)) / rise) : 1;
        out[pos + i] = Math.sin(phase) * env * amp;
        phase = (phase + step) % (2 * Math.PI);
      }
    }
    pos += len;
  }
  return out;
}

/** Losowe grupy znaków. @param {string} chars @param {number} groups @param {number} [len] @param {() => number} [rnd] */
export const randomGroups = (chars, groups, len = 5, rnd = Math.random) =>
  Array.from({ length: groups }, () => Array.from({ length: len }, () => chars[Math.floor(rnd() * chars.length)]).join("")).join(" ");

/** Skuteczność odbioru 0..1 (odległość edycyjna). @param {string} expected @param {string} typed */
export function cwAccuracy(expected, typed) {
  const norm = (/** @type {string} */ s) => s.toUpperCase().trim().replace(/\s+/g, " ");
  const a = norm(expected), b = norm(typed);
  if (!a.length) return b.length ? 0 : 1;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i), cur = new Array(b.length + 1);
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i;
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    [prev, cur] = [cur, prev];
  }
  return Math.min(1, Math.max(0, 1 - prev[b.length] / a.length));
}

/** @param {Float32Array} data @param {number} n @param {number} c */
function goertzel(data, n, c) {
  let s1 = 0, s2 = 0;
  for (let i = 0; i < n; i++) { const s0 = data[i] + c * s1 - s2; s2 = s1; s1 = s0; }
  return Math.sqrt(Math.max(s1 * s1 + s2 * s2 - c * s1 * s2, 0)) / n;
}

/**
 * Dekoder CW: Goertzel w blokach ~8 ms → obwiednia → próg z histerezą → kropki/kreski z samouczącą się
 * długością kropki → tekst (CwDecoder.kt).
 */
export class CwDecoder {
  /** @param {number} sampleRate @param {number} toneHz @param {number} initialWpm @param {(s: string) => void} onText */
  constructor(sampleRate = 44100, toneHz = 600, initialWpm = 20, onText = () => {}) {
    this.sampleRate = sampleRate;
    this.onText = onText;
    this.blockSize = Math.round(sampleRate * 0.008);
    this.blockMs = (this.blockSize * 1000) / sampleRate;
    this.autoTone = true;
    this.shortMs = 1200 / initialWpm;
    this.longMs = 3 * this.shortMs;
    this.marksSeen = 0;
    this.signalLevel = 0;
    this.block = new Float32Array(this.blockSize);
    this.blockFill = 0;
    this.search = new Float32Array(4096);
    this.searchFill = 0;
    this.searchCountdownMs = 0;
    this.setTone(toneHz);
    this.reset();
  }

  /** @param {number} hz */
  setTone(hz) { this.toneHz = hz; this.coeff = 2 * Math.cos((2 * Math.PI * hz) / this.sampleRate); }
  get ditMs() { return (this.shortMs + this.longMs / 3) / 2; }
  get wpm() { return Math.round(1200 / this.ditMs); }

  reset() {
    this.symbol = "";
    this.keyDown = false;
    this.stateMs = 0;
    this.charFlushed = true;
    this.wordFlushed = true;
    this.noise = 1e-6;
    this.peak = 1e-6;
  }

  /** @param {Float32Array} samples */
  process(samples) {
    for (let i = 0; i < samples.length; i++) {
      const s = samples[i];
      this.block[this.blockFill++] = s;
      if (this.autoTone) { this.search[this.searchFill] = s; this.searchFill = (this.searchFill + 1) % this.search.length; }
      if (this.blockFill === this.blockSize) { this.processBlock(); this.blockFill = 0; }
    }
  }

  processBlock() {
    const m = goertzel(this.block, this.blockSize, this.coeff);
    this.noise = m < this.noise ? this.noise * 0.7 + m * 0.3 : this.noise * 0.995 + m * 0.005;
    this.peak = m > this.peak ? this.peak * 0.5 + m * 0.5 : this.peak * 0.998 + this.noise * 0.002;
    const snrOk = this.peak > this.noise * 4;
    const high = this.noise + (this.peak - this.noise) * 0.55, low = this.noise + (this.peak - this.noise) * 0.35;
    this.signalLevel = this.peak > this.noise ? Math.min(1, Math.max(0, (m - this.noise) / (this.peak - this.noise))) : 0;
    const down = this.keyDown ? snrOk && m > low : snrOk && m > high;
    if (down !== this.keyDown) {
      if (this.keyDown) this.endMark(this.stateMs); else this.checkSilence(this.stateMs);
      this.keyDown = down;
      this.stateMs = 0;
    }
    this.stateMs += this.blockMs;
    if (!this.keyDown) this.checkSilence(this.stateMs);
    if (this.autoTone) {
      this.searchCountdownMs -= this.blockMs;
      if (this.searchCountdownMs <= 0) { this.searchCountdownMs = 1000; this.detectTone(); }
    }
  }

  /** @param {number} ms */
  endMark(ms) {
    if (ms < this.blockMs * 1.5) return;
    const rate = this.marksSeen < 8 ? 0.5 : 0.2;
    this.marksSeen++;
    if (ms < Math.sqrt(this.shortMs * this.longMs)) {
      this.symbol += ".";
      this.shortMs += (ms - this.shortMs) * rate;
      this.longMs += (3 * this.shortMs - this.longMs) * rate * 0.3;
    } else {
      this.symbol += "-";
      this.longMs += (ms - this.longMs) * rate;
      this.shortMs += (this.longMs / 3 - this.shortMs) * rate * 0.3;
    }
    this.shortMs = Math.min(1200 / 5, Math.max(1200 / 60, this.shortMs));
    this.longMs = Math.min(4.5 * this.shortMs, Math.max(2 * this.shortMs, this.longMs));
    this.charFlushed = false;
    this.wordFlushed = false;
  }

  /** @param {number} ms */
  checkSilence(ms) {
    if (!this.charFlushed && ms > 2 * this.ditMs) {
      const code = this.symbol;
      this.symbol = "";
      this.charFlushed = true;
      if (code) this.onText(MORSE_DECODE[code] ?? "*");
    }
    if (this.charFlushed && !this.wordFlushed && ms > 5 * this.ditMs) {
      this.wordFlushed = true;
      this.onText(" ");
    }
  }

  detectTone() {
    let bestHz = this.toneHz, best = 0, sum = 0, count = 0;
    for (let hz = 300; hz <= 1200; hz += 10) {
      const m = goertzel(this.search, this.search.length, 2 * Math.cos((2 * Math.PI * hz) / this.sampleRate));
      sum += m; count++;
      if (m > best) { best = m; bestHz = hz; }
    }
    if (count && best > 6 * (sum / count) && Math.abs(bestHz - this.toneHz) > 15) this.setTone(bestHz);
  }
}
