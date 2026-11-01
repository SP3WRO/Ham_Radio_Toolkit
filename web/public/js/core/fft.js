// @ts-check
// Zespolone FFT dowolnej długości (mieszane podstawy, jak kiss_fft) – MixedRadixFft.kt. FT8 potrzebuje N = 3840.

export class MixedRadixFft {
  /** @param {number} n */
  constructor(n) {
    this.n = n;
    this.twRe = Float64Array.from({ length: n }, (_, i) => Math.cos((-2 * Math.PI * i) / n));
    this.twIm = Float64Array.from({ length: n }, (_, i) => Math.sin((-2 * Math.PI * i) / n));
    /** @type {number[]} */
    const f = [];
    let rest = n, p = 4;
    while (rest > 1) {
      while (rest % p !== 0) {
        p = p === 4 ? 2 : p === 2 ? 3 : p + 2;
        if (p * p > rest) p = rest;
      }
      rest /= p;
      f.push(p, rest);
    }
    if (!f.length) f.push(1, 1);
    this.factors = f;
    const maxP = Math.max(...f.filter((_, i) => i % 2 === 0));
    this.sRe = new Float64Array(maxP);
    this.sIm = new Float64Array(maxP);
    this.inRe = this.inIm = this.outRe = this.outIm = new Float64Array(0);
  }

  /**
   * out[k] = Σ in[j]·e^(−2πijk/N) (bez normalizacji).
   * @param {ArrayLike<number>} inRe @param {ArrayLike<number>} inIm @param {Float64Array} outRe @param {Float64Array} outIm
   */
  transform(inRe, inIm, outRe, outIm) {
    this.inRe = /** @type {any} */ (inRe); this.inIm = /** @type {any} */ (inIm); this.outRe = /** @type {any} */ (outRe); this.outIm = /** @type {any} */ (outIm);
    if (this.n === 1) { outRe[0] = inRe[0]; outIm[0] = inIm[0]; return; }
    this.work(0, 0, 1, 0);
  }

  /** @param {number} outOff @param {number} inOff @param {number} fstride @param {number} fi */
  work(outOff, inOff, fstride, fi) {
    const p = this.factors[fi], m = this.factors[fi + 1];
    if (m === 1) {
      for (let q = 0; q < p; q++) {
        const src = inOff + q * fstride;
        this.outRe[outOff + q] = this.inRe[src];
        this.outIm[outOff + q] = this.inIm[src];
      }
    } else {
      for (let q = 0; q < p; q++) this.work(outOff + q * m, inOff + q * fstride, fstride * p, fi + 2);
    }
    this.butterfly(outOff, fstride, m, p);
  }

  /** @param {number} off @param {number} fstride @param {number} m @param {number} p */
  butterfly(off, fstride, m, p) {
    const { outRe, outIm, sRe, sIm, twRe, twIm, n } = this;
    for (let u = 0; u < m; u++) {
      let k = u;
      for (let q1 = 0; q1 < p; q1++) { sRe[q1] = outRe[off + k]; sIm[q1] = outIm[off + k]; k += m; }
      k = u;
      for (let q1 = 0; q1 < p; q1++) {
        let tw = 0, ar = sRe[0], ai = sIm[0];
        for (let q = 1; q < p; q++) {
          tw += fstride * k;
          if (tw >= n) tw -= n;
          const wr = twRe[tw], wi = twIm[tw];
          ar += sRe[q] * wr - sIm[q] * wi;
          ai += sRe[q] * wi + sIm[q] * wr;
        }
        outRe[off + k] = ar; outIm[off + k] = ai;
        k += m;
      }
    }
  }
}
