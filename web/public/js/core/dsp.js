// @ts-check
// Wspólne DSP dla dekoderów (dsp/AudioInput.kt): filtr dolnoprzepustowy, decymacja i zmiana częstotliwości próbkowania.

/** Współczynniki FIR (okno Blackmana). @param {number} cutoffRatio odcięcie / fs @param {number} length */
export function lowpassTaps(cutoffRatio, length) {
  const m = length - 1;
  const taps = new Float64Array(length);
  let sum = 0;
  for (let i = 0; i < length; i++) {
    const x = i - m / 2;
    const sinc = x === 0 ? 2 * cutoffRatio : Math.sin(2 * Math.PI * cutoffRatio * x) / (Math.PI * x);
    const w = 0.42 - 0.5 * Math.cos((2 * Math.PI * i) / m) + 0.08 * Math.cos((4 * Math.PI * i) / m);
    taps[i] = sinc * w;
    sum += taps[i];
  }
  return Float32Array.from(taps, (v) => v / sum);
}

/**
 * Zmiana częstotliwości próbkowania w strumieniu (np. 48 kHz albo 44,1 kHz z iPhone'a → 12 kHz dla FT8/RTTY):
 * filtr dolnoprzepustowy przed decymacją i interpolacja liniowa między próbkami.
 */
export class StreamResampler {
  /** @param {number} inputRate @param {number} outputRate */
  constructor(inputRate, outputRate) {
    this.ratio = inputRate / outputRate;
    this.passthrough = inputRate === outputRate;
    const cutoff = (0.45 * Math.min(inputRate, outputRate)) / inputRate;
    this.taps = lowpassTaps(cutoff, Math.max(31, Math.ceil(this.ratio * 16) | 1));
    this.history = new Float32Array(this.taps.length);
    this.pos = 0;
    this.prev = 0;
    this.t = 0; // pozycja następnej próbki wyjściowej względem bieżącej próbki wejściowej
  }

  /** @param {Float32Array} input @returns {Float32Array} */
  process(input) {
    if (this.passthrough) return input;
    const out = new Float32Array(Math.ceil(input.length / this.ratio) + 2);
    let n = 0;
    const { taps, history } = this;
    for (let i = 0; i < input.length; i++) {
      history[this.pos] = input[i];
      this.pos = (this.pos + 1) % history.length;
      let acc = 0;
      let idx = this.pos;
      for (let k = 0; k < taps.length; k++) { acc += taps[k] * history[idx]; idx = idx + 1 === history.length ? 0 : idx + 1; }
      // Próbki wyjściowe wypadające między poprzednią a bieżącą (przefiltrowaną) próbką wejściową
      while (this.t <= 1) {
        out[n++] = this.prev + (acc - this.prev) * this.t;
        this.t += this.ratio;
      }
      this.t -= 1;
      this.prev = acc;
    }
    return out.subarray(0, n);
  }
}

/** Całe nagranie do innej częstotliwości. @param {Float32Array} samples @param {number} inputRate @param {number} outputRate */
export function resample(samples, inputRate, outputRate) {
  return new StreamResampler(inputRate, outputRate).process(samples).slice();
}

/** WAV 16-bit PCM mono (zapis nagrań z dekoderów). @param {Float32Array} samples @param {number} rate */
export function wavBytes(samples, rate) {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buf);
  const str = (/** @type {number} */ o, /** @type {string} */ s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  str(0, "RIFF"); v.setUint32(4, 36 + samples.length * 2, true); str(8, "WAVE");
  str(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, "data"); v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) v.setInt16(44 + i * 2, Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767))), true);
  return new Uint8Array(buf);
}

/** Poziom sygnału 0..1 (RMS ×4, jak w aplikacji). @param {Float32Array} x */
export function audioLevel(x) {
  let s = 0;
  for (let i = 0; i < x.length; i++) s += x[i] * x[i];
  return Math.min(1, Math.sqrt(s / Math.max(1, x.length)) * 4);
}
