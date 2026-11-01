// @ts-check
// Odbiornik Horus Binary (4FSK 100 Bd) – port HorusFsk.kt, HorusModem.kt, HorusL2.kt i HorusPacket.kt,
// które przenoszą src/fsk.c, horus_api.c, horus_l2.c, golay23.c z horusdemodlib (David Rowe, Brady O'Brien,
// Tomas Härdin; licencja LGPL-2.1) oraz formaty z horusdemodlib/decoder.py (v1/v2) i HorusBinaryV3.asn1 (v3).
import { MixedRadixFft } from "./fft.js";

// ======================================================================= Demodulator 2/4-FSK (fsk.c)

export class HorusFsk {
  /** @param {{fs: number, rs: number, m: number, p?: number, nsym?: number, fsTx: number}} o */
  constructor({ fs, rs, m, p = 8, nsym = 50, fsTx }) {
    if (fs % rs !== 0 || (fs / rs) % p !== 0 || (m !== 2 && m !== 4)) throw new Error("Złe parametry FSK");
    this.fs = fs; this.rs = rs; this.m = m; this.p = p; this.nsym = nsym; this.fsTx = fsTx;
    this.ts = fs / rs;
    this.n = this.ts * nsym;
    this.nbits = m === 2 ? nsym : nsym * 2;
    this.nmem = this.n + 2 * this.ts;
    this.tc = 0.1;
    this.nin = this.n;
    this.estMin = 0;
    this.estMax = fs;
    this.estSpace = Math.trunc(0.75 * rs);
    /** 0 – szczyty widma, 1 – maska o znanym odstępie tonów */
    this.freqEstType = 0;
    this.ndft = 2 ** Math.ceil(Math.log(fs / (0.1 * rs)) / Math.log(2));
    const N = this.ndft;
    this.hann = Float64Array.from({ length: N }, (_, i) => 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (N - 1)));
    /** Uśrednione widmo amplitudowe (bin ndft/2 = 0 Hz) */
    this.sf = new Float64Array(N);
    this.fft = new MixedRadixFft(N);
    this.fftRe = new Float64Array(N); this.fftIm = new Float64Array(N);
    this.outRe = new Float64Array(N); this.outIm = new Float64Array(N);
    this.phiRe = new Float64Array(m).fill(1);
    this.phiIm = new Float64Array(m);
    this.fDcRe = new Float64Array(m * this.nmem);
    this.fDcIm = new Float64Array(m * this.nmem);
    const len = (nsym + 1) * p;
    this.fIntRe = Array.from({ length: m }, () => new Float64Array(len));
    this.fIntIm = Array.from({ length: m }, () => new Float64Array(len));
    this.fEst = new Float64Array(m);
    this.f2Est = new Float64Array(m);
    this.normRxTiming = 0;
    this.ppm = 0;
    this.ebNoDb = 0;
    this.snrEst = 0;
  }

  /** @param {Float32Array} x */
  freqEst(x) {
    const { ndft: N, fs, m, tc, sf } = this;
    let st = Math.trunc((this.estMin * N) / fs) + N / 2;
    if (st < 0) st = 0;
    let en = Math.trunc((this.estMax * N) / fs) + N / 2;
    if (en > N) en = N;
    const fZero = Math.trunc((this.estSpace * N) / fs);
    const work = new Float64Array(N);
    const numffts = Math.floor(this.nin / (N / 2)) - 1;
    for (let j = 0; j < numffts; j++) {
      const a = (j * N) / 2;
      for (let i = 0; i < N; i++) { this.fftRe[i] = this.hann[i] * x[i + a]; this.fftIm[i] = 0; }
      this.fft.transform(this.fftRe, this.fftIm, this.outRe, this.outIm);
      for (let i = 0; i < N; i++) {
        const src = (i + N / 2) % N;
        const mag = Math.sqrt(this.outRe[src] ** 2 + this.outIm[src] ** 2);
        sf[i] = sf[i] * (1 - tc) + mag * tc;
        work[i] = sf[i];
      }
    }
    const freqi = new Int32Array(m);
    for (let i = 0; i < m; i++) {
      let imax = 0, max = 0;
      for (let j = st; j < en; j++) if (work[j] > max) { max = work[j]; imax = j; }
      const lo = Math.max(0, imax - fZero), hi = Math.min(N, imax + fZero);
      for (let j = lo; j < hi; j++) work[j] = 0;
      freqi[i] = imax - N / 2;
    }
    freqi.sort();
    for (let i = 0; i < m; i++) this.fEst[i] = (freqi[i] * fs) / N;
    // Metoda 2: maska z jedynkami w odstępach tonów
    const mask = new Float64Array(N);
    for (let i = 0; i < 3; i++) mask[i] = 1;
    let bin = 0;
    for (let k = 1; k < m; k++) {
      bin = Math.round((k * this.fsTx * N) / fs) - 1;
      for (let i = bin; i <= bin + 2; i++) mask[i] = 1;
    }
    const lenMask = bin + 3;
    let bMax = st, corrMax = 0;
    for (let b = st; b < en - lenMask; b++) {
      let corr = 0;
      for (let i = 0; i < lenMask; i++) corr += mask[i] * sf[b + i];
      if (corr > corrMax) { corrMax = corr; bMax = b; }
    }
    const foff = Math.trunc(((bMax - N / 2) * fs) / N);
    for (let k = 0; k < m; k++) this.f2Est[k] = foff + k * this.fsTx;
  }

  /** Demodulacja [nin] próbek; zapisuje nbits bitów do rxBits od [offset]. @param {Float32Array} x @param {Uint8Array} rxBits @param {number} offset */
  demodulate(x, rxBits, offset) {
    const { m, nmem, ts, p, nsym, fs, rs } = this;
    const nold = nmem - this.nin;
    this.freqEst(x);
    const f = this.freqEstType !== 0 ? this.f2Est : this.fEst;
    for (let k = 0; k < m; k++) {
      this.fDcRe.copyWithin(k * nmem, k * nmem + nmem - nold, k * nmem + nmem);
      this.fDcIm.copyWithin(k * nmem, k * nmem + nmem - nold, k * nmem + nmem);
    }
    for (let k = 0; k < m; k++) {
      const w = 2 * Math.PI * (f[k] / fs);
      const dRe = Math.cos(w), dIm = Math.sin(w);
      let pr = this.phiRe[k], pi = this.phiIm[k];
      let j = 0;
      for (let i = nold; i < nmem; i++) {
        const nr = pr * dRe - pi * dIm, ni = pr * dIm + pi * dRe;
        pr = nr; pi = ni;
        this.fDcRe[k * nmem + i] = x[j] * pr;
        this.fDcIm[k * nmem + i] = -x[j] * pi;
        j++;
      }
      const mag = Math.hypot(pr, pi);
      this.phiRe[k] = pr / mag; this.phiIm[k] = pi / mag;
    }
    const len = (nsym + 1) * p;
    for (let i = 0; i < len; i++) {
      const st = Math.trunc((i * ts) / p), en = st + ts - 1;
      for (let k = 0; k < m; k++) {
        let re = 0, im = 0;
        for (let j = st; j <= en; j++) { re += this.fDcRe[k * nmem + j]; im += this.fDcIm[k * nmem + j]; }
        this.fIntRe[k][i] = re; this.fIntIm[k][i] = im;
      }
    }
    // Synchronizacja symbolowa: składowa |f_int|² o częstotliwości Rs
    const w = 2 * Math.PI * (rs / (p * rs));
    const dRe = Math.cos(w), dIm = Math.sin(w);
    let fr = 1, fi = 0, tcRe = 0, tcIm = 0;
    for (let i = 0; i < len; i++) {
      let ft1 = 0;
      for (let k = 0; k < m; k++) ft1 += this.fIntRe[k][i] ** 2 + this.fIntIm[k][i] ** 2;
      tcRe += ft1 * fr; tcIm += ft1 * fi;
      const nr = fr * dRe - fi * dIm, ni = fr * dIm + fi * dRe;
      fr = nr; fi = ni;
    }
    if (Number.isNaN(tcRe) || Number.isNaN(tcIm)) return;
    const norm = Math.atan2(tcIm, tcRe) / (2 * Math.PI);
    const rxTiming = norm * p;
    const dNorm = norm - this.normRxTiming;
    this.normRxTiming = norm;
    if (Math.abs(dNorm) < 0.2) this.ppm = 0.9 * this.ppm + 0.1 * ((1e6 * dNorm) / nsym);
    this.nin = norm > 0.25 ? this.n + ts / 2 : norm < -0.25 ? this.n - ts / 2 : this.n;
    const low = Math.floor(rxTiming), fract = rxTiming - low, high = Math.ceil(rxTiming);
    let meanEbNo = 0, stdEbNo = 0;
    const tmax = new Float64Array(m);
    for (let i = 0; i < nsym; i++) {
      const st = (i + 1) * p;
      for (let k = 0; k < m; k++) {
        const re = (1 - fract) * this.fIntRe[k][st + low] + fract * this.fIntRe[k][st + high];
        const im = (1 - fract) * this.fIntIm[k][st + low] + fract * this.fIntIm[k][st + high];
        tmax[k] = re * re + im * im;
      }
      let max = tmax[0], sym = 0;
      for (let k = 0; k < m; k++) if (tmax[k] > max) { max = tmax[k]; sym = k; }
      if (m === 2) rxBits[offset + i] = sym === 1 ? 1 : 0;
      else { rxBits[offset + i * 2 + 1] = sym & 1; rxBits[offset + i * 2] = (sym & 2) >> 1; }
      stdEbNo += max;
      meanEbNo += Math.sqrt(max);
    }
    meanEbNo /= nsym;
    stdEbNo = stdEbNo / nsym - meanEbNo * meanEbNo;
    stdEbNo = stdEbNo > 0 ? Math.sqrt(stdEbNo) : 0;
    this.ebNoDb = -6 + 20 * Math.log10((1e-6 + meanEbNo) / (1e-6 + stdEbNo));
    this.snrEst = 0.5 * this.snrEst + 0.5 * this.ebNoDb;
  }
}

// ======================================================================= Warstwa 2 (horus_l2.c, golay23.c)

const UW_BYTES = 2;
const GOLAY_POLY = 0xc75;

/** @param {number} c */
export function golay23Syndrome(c) {
  for (let x = 11; x >= 0; x--) if (c & ((1 << 11) << x)) c ^= GOLAY_POLY << x;
  return c;
}
const GOLAY_TABLE = (() => {
  const t = new Int32Array(2048);
  for (let x = 0; x < 23; x++) { const d = 1 << x; t[golay23Syndrome(d)] = d; }
  for (let x = 0; x < 22; x++) for (let y = x + 1; y < 23; y++) { const d = (1 << x) | (1 << y); t[golay23Syndrome(d)] = d; }
  for (let x = 0; x < 21; x++) for (let y = x + 1; y < 22; y++) for (let z = y + 1; z < 23; z++) { const d = (1 << x) | (1 << y) | (1 << z); t[golay23Syndrome(d)] = d; }
  return t;
})();
/** @param {number} c */
export const golay23Decode = (c) => c ^ GOLAY_TABLE[golay23Syndrome(c)];
/** @param {number} c */
export const golay23Encode = (c) => golay23Syndrome(c << 11) | (c << 11);

/** Liczba bajtów ramki (z UW) dla [payloadBytes] bajtów treści. @param {number} payloadBytes */
export function numTxDataBytes(payloadBytes) {
  const bits = payloadBytes * 8;
  const codewords = Math.ceil(bits / 12);
  return Math.ceil((UW_BYTES * 8 + bits + codewords * 11) / 8);
}

/** @param {number} x */
function isPrime(x) { for (let i = 2; i < x; i++) if (x % i === 0) return false; return true; }

/** Interleaver algebraiczny: bit i → (b·i) mod n; dir 1 – odwrotny. @param {number[]} data @param {number} start @param {number} nbytes @param {number} dir */
export function interleave(data, start, nbytes, dir) {
  const nbits = nbytes * 8;
  let b = nbytes === 43 ? 337 : nbytes === 63 ? 389 : 0;
  if (!b) { b = Math.floor(nbits / 1.62) + 1; while (!isPrime(b)) b++; }
  const out = new Array(nbytes).fill(0);
  for (let n = 0; n < nbits; n++) {
    let i = n, j = (b * i) % nbits;
    if (dir) [i, j] = [j, i];
    const bit = (data[start + (i >> 3)] >> (i % 8)) & 1;
    out[j >> 3] |= bit << (j % 8);
  }
  for (let k = 0; k < nbytes; k++) data[start + k] = out[k];
}

/** Scrambler DVB (start 0x4A80) – w obie strony. @param {number[]} data @param {number} start @param {number} nbytes */
export function scramble(data, start, nbytes) {
  let s = 0x4a80;
  for (let i = 0; i < nbytes * 8; i++) {
    const so = ((s & 2) >> 1) ^ (s & 1);
    const ib = start + (i >> 3), sh = i % 8;
    const bit = ((data[ib] >> sh) & 1) ^ so;
    data[ib] = (data[ib] & ~(1 << sh)) | (bit << sh);
    s = (s >> 1) | (so << 14);
  }
}

/** CRC16-CCITT (0xFFFF). @param {ArrayLike<number>} data @param {number} offset @param {number} length */
export function crc16(data, offset, length) {
  let crc = 0xffff;
  for (let k = 0; k < length; k++) {
    let x = ((crc >> 8) ^ data[offset + k]) & 0xff;
    x ^= x >> 4;
    crc = ((crc << 8) ^ (x << 12) ^ (x << 5) ^ x) & 0xffff;
  }
  return crc;
}

/** Dekodowanie ramki (z UW; modyfikowana w miejscu). @param {number[]} rx @param {number} payloadBytes @returns {number[]} */
export function decodeRxPacket(rx, payloadBytes) {
  const numTx = numTxDataBytes(payloadBytes);
  scramble(rx, UW_BYTES, numTx - 2);
  interleave(rx, UW_BYTES, numTx - 2, 1);
  const out = new Array(payloadBytes).fill(0);
  let pout = 0, pin = UW_BYTES + payloadBytes;
  const nextByte = () => (pin < rx.length ? rx[pin++] : 0);
  const payloadBits = payloadBytes * 8;
  let ninbit = 0, ingolay = 0, ningolay = 0, nparity = 0, paritybyte = nextByte(), noutbits = 0, outbyte = 0;
  const readParity = () => {
    let v = 0;
    for (let i = 0; i < 11; i++) {
      v |= (paritybyte >> (7 - (nparity % 8))) & 1;
      if (i !== 10) v <<= 1;
      nparity++;
      if (nparity % 8 === 0) paritybyte = nextByte();
    }
    return v;
  };
  /** @param {number} outdata @param {number} count @param {number} topShift */
  const emit = (outdata, count, topShift) => {
    for (let i = 0; i < count; i++) {
      outbyte |= (outdata >> (topShift - i)) & 1;
      noutbits++;
      if (noutbits % 8 !== 0) outbyte <<= 1;
      else { out[pout++] = outbyte & 0xff; outbyte = 0; }
    }
  };
  while (ninbit < payloadBits) {
    const inbit = (rx[(ninbit >> 3) + UW_BYTES] >> (7 - (ninbit % 8))) & 1;
    ninbit++;
    ingolay |= inbit;
    ningolay++;
    ingolay <<= 1;
    if (ningolay % 12 === 0) {
      // ingolay ma 12 bitów danych przesuniętych o 1; dokładamy 11 bitów parzystości
      for (let i = 0; i < 11; i++) {
        ingolay |= (paritybyte >> (7 - (nparity % 8))) & 1;
        if (i !== 10) ingolay <<= 1;
        nparity++;
        if (nparity % 8 === 0) paritybyte = nextByte();
      }
      emit(golay23Decode(ingolay) >> 11, 12, 11);
      ingolay = 0;
    }
  }
  if (ningolay % 12 !== 0) {
    const parity = readParity();
    ingolay >>= 1;
    const outdata = golay23Decode((ingolay << 12) + parity) >> 11;
    const ntogo = payloadBits - noutbits;
    emit(outdata, ntogo, ntogo);
  }
  if (pout !== payloadBytes) throw new Error("L2");
  return out;
}

/** Nadajnik (do testów). @param {number[]} payload @returns {number[]} */
export function encodeTxPacket(payload) {
  const numTx = numTxDataBytes(payload.length);
  const out = new Array(numTx).fill(0);
  out[0] = 0x24; out[1] = 0x24;
  payload.forEach((b, i) => { out[UW_BYTES + i] = b & 0xff; });
  let pout = UW_BYTES + payload.length;
  let ingolay = 0, ningolay = 0, paritybyte = 0, nparity = 0;
  /** @param {number} g */
  const writeParity = (g) => {
    for (let i = 0; i < 11; i++) {
      paritybyte |= (g >> (10 - i)) & 1;
      nparity++;
      if (nparity % 8 !== 0) paritybyte <<= 1;
      else { out[pout++] = paritybyte & 0xff; paritybyte = 0; }
    }
  };
  for (let n = 0; n < payload.length * 8; n++) {
    ingolay |= (payload[n >> 3] >> (7 - (n % 8))) & 1;
    ningolay++;
    if (ningolay % 12 !== 0) ingolay <<= 1;
    else { writeParity(golay23Syndrome(ingolay << 11)); ingolay = 0; }
  }
  if (ningolay % 12 !== 0) { ingolay >>= 1; writeParity(golay23Syndrome(ingolay << 12)); }
  if (nparity % 8 !== 0) out[pout++] = (paritybyte << (7 - (nparity % 8))) & 0xff;
  if (pout !== numTx) throw new Error("L2 tx");
  interleave(out, UW_BYTES, numTx - 2, 0);
  scramble(out, UW_BYTES, numTx - 2);
  return out;
}

// ======================================================================= Modem (horus_api.c)

/** @typedef {{payload: number[], version: number, hex: string}} HorusRawFrame */

const UW = [0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0]; // "$$"
const V1_CODED_BITS = 360, V1_BYTES = 22, V2_BYTES = 32, V2_256_CODED_BITS = 520, MAX_UW = 32;
const V3_SIZES = [32, 48, 64, 96, 128];
export const HORUS_BAUD = 100;
export const HORUS_TONE_SPACING = 270;

/** @param {number[]} payload @param {number} version @returns {HorusRawFrame} */
const rawFrame = (payload, version) => ({ payload, version, hex: payload.map((b) => b.toString(16).toUpperCase().padStart(2, "0")).join("") });

export class HorusModem {
  /** @param {number} fs @param {number} [rs] @param {number} [toneSpacing] -1 = nieznany */
  constructor(fs, rs = HORUS_BAUD, toneSpacing = -1) {
    this.fsk = new HorusFsk({ fs, rs, m: 4, fsTx: toneSpacing === -1 ? HORUS_TONE_SPACING : toneSpacing });
    this.fsk.freqEstType = toneSpacing === -1 ? 0 : 1;
    this.rs = rs;
    this.rxBitsLen = V2_256_CODED_BITS * 7 + this.fsk.nbits;
    this.rxBits = new Uint8Array(this.rxBitsLen);
    this.uw = UW.map((b) => 2 * b - 1);
    this.uwThresh = UW.length - 2;
    this.uwLoc = new Int32Array(MAX_UW);
    this.uwCount = 0;
  }
  get nin() { return this.fsk.nin; }
  /** SNR w paśmie 3 kHz. */
  get snrDb() { return this.fsk.snrEst + 10 * Math.log10((this.rs * 2) / 3000); }
  get toneFrequencies() { return this.fsk.freqEstType !== 0 ? this.fsk.f2Est : this.fsk.fEst; }
  /** @param {number} lower @param {number} upper */
  setFreqEstLimits(lower, upper) { this.fsk.estMin = lower; this.fsk.estMax = upper; }

  /** Dokładnie [nin] próbek. @param {Float32Array} samples @returns {HorusRawFrame | null} */
  rx(samples) {
    const nbits = this.fsk.nbits, L = this.rxBitsLen;
    this.rxBits.copyWithin(0, nbits, L);
    this.fsk.demodulate(samples, this.rxBits, L - nbits);
    if (this.uwCount > 0) {
      const old = this.uwCount;
      this.uwCount = 0;
      for (let i = 0; i < old; i++) if (this.uwLoc[i] - nbits >= 0 && this.uwCount < MAX_UW) this.uwLoc[this.uwCount++] = this.uwLoc[i] - nbits;
    }
    const n = nbits + this.uw.length;
    for (let i = 0; i < n - this.uw.length; i++) {
      let corr = 0;
      for (let j = 0; j < this.uw.length; j++) corr += (2 * this.rxBits[L - n + i + j] - 1) * this.uw[j];
      if (corr >= this.uwThresh && this.uwCount < MAX_UW) this.uwLoc[this.uwCount++] = L - n + i;
    }
    for (let i = 0; i < this.uwCount; i++) {
      const loc = this.uwLoc[i];
      let frame = this.v1(loc);
      if (!frame) for (const size of V3_SIZES) { frame = this.v2(loc, size); if (frame) break; }
      if (frame) { this.uwLoc[i] = -1; return frame; }
    }
    return null;
  }

  /** @param {number} start @param {number} count */
  bytesAt(start, count) {
    const out = new Array(count);
    for (let b = 0; b < count; b++) {
      let v = 0;
      for (let j = 0; j < 8; j++) v = (v << 1) | this.rxBits[start + b * 8 + j];
      out[b] = v;
    }
    return out;
  }

  /** @param {number} loc */
  v1(loc) {
    if (loc < 0 || loc + V1_CODED_BITS > this.rxBitsLen) return null;
    const p = decodeRxPacket(this.bytesAt(loc, numTxDataBytes(V1_BYTES)), V1_BYTES);
    return crc16(p, 0, V1_BYTES - 2) === p[V1_BYTES - 2] + (p[V1_BYTES - 1] << 8) ? rawFrame(p, 1) : null;
  }

  /** @param {number} loc @param {number} size */
  v2(loc, size) {
    const tx = numTxDataBytes(size);
    if (loc < 0 || loc + tx * 8 > this.rxBitsLen) return null;
    const p = decodeRxPacket(this.bytesAt(loc, tx), size);
    if (size === V2_BYTES && crc16(p, 0, size - 2) === p[size - 2] + (p[size - 1] << 8)) return rawFrame(p, 2);
    return crc16(p, 2, size - 2) === p[0] + (p[1] << 8) ? rawFrame(p, 3) : null;
  }
}

/** Strumień próbek dowolnej długości → bloki po [nin]. */
export class HorusStream {
  /** @param {HorusModem} modem */
  constructor(modem) { this.modem = modem; this.buffer = new Float32Array(modem.fsk.n * 2); this.fill = 0; }
  /** @param {Float32Array} x @param {(f: HorusRawFrame) => void} onFrame @param {() => void} [onBlock] */
  process(x, onFrame, onBlock) {
    if (this.fill + x.length > this.buffer.length) {
      const b = new Float32Array(Math.max(this.buffer.length * 2, this.fill + x.length));
      b.set(this.buffer.subarray(0, this.fill));
      this.buffer = b;
    }
    this.buffer.set(x, this.fill);
    this.fill += x.length;
    let pos = 0;
    while (this.fill - pos >= this.modem.nin) {
      const nin = this.modem.nin;
      const f = this.modem.rx(this.buffer.slice(pos, pos + nin));
      pos += nin;
      if (f) onFrame(f);
      onBlock?.();
    }
    this.buffer.copyWithin(0, pos, this.fill);
    this.fill -= pos;
  }
}

// ======================================================================= Telemetria (decoder.py, HorusBinaryV3.asn1)

/**
 * @typedef {object} HorusTelemetry
 * @property {number} version
 * @property {number | null} payloadId
 * @property {string} callsign
 * @property {number} sequence
 * @property {string | null} timeUtc
 * @property {number} latitude
 * @property {number} longitude
 * @property {number | null} altitudeM
 * @property {number | null} speedKmh
 * @property {number | null} satellites
 * @property {number | null} temperatureC
 * @property {number | null} batteryV
 * @property {number | null} ascentRateMs
 * @property {[string, string][]} extra etykiety zaczynające się od "@" to klucze tłumaczeń
 * @property {string} raw
 */
/** @typedef {{struct: string, fields: [string, string][]}} HorusCustomFormat */

/** @param {HorusTelemetry} t */
export const hasPosition = (t) => !(t.latitude === 0 && t.longitude === 0);

/** "1234, ZNAK" w każdym wierszu. @param {string} text @returns {Map<number, string>} */
export function parsePayloadIds(text) {
  const out = new Map();
  for (const line of text.split(/\r?\n/)) {
    const tr = line.trim();
    if (!tr || tr.startsWith("#")) continue;
    const i = tr.indexOf(",");
    const id = Number.parseInt((i < 0 ? tr : tr.slice(0, i)).trim(), 10);
    const name = i < 0 ? "" : tr.slice(i + 1).trim();
    if (Number.isFinite(id) && /^\s*-?\d+\s*$/.test(i < 0 ? tr : tr.slice(0, i)) && name) out.set(id, name);
  }
  return out;
}

/** @param {string} text @returns {Map<string, HorusCustomFormat>} */
export function parseCustomFields(text) {
  const out = new Map();
  const root = JSON.parse(text);
  if (!root || typeof root !== "object" || Array.isArray(root)) return out;
  for (const [name, o] of Object.entries(root)) {
    if (!o || typeof o.struct !== "string" || !Array.isArray(o.fields)) continue;
    /** @type {[string, string][]} */
    const fields = [];
    for (const f of o.fields) if (Array.isArray(f) && typeof f[0] === "string") fields.push([f[0], typeof f[1] === "string" ? f[1] : "none"]);
    out.set(name, { struct: o.struct, fields });
  }
  return out;
}

class LeReader {
  /** @param {ArrayLike<number>} p */
  constructor(p) { this.p = p; this.pos = 0; }
  u8() { if (this.pos >= this.p.length) throw new RangeError("koniec"); return this.p[this.pos++] & 0xff; }
  i8() { return (this.u8() << 24) >> 24; }
  u16() { return this.u8() | (this.u8() << 8); }
  i16() { return (this.u16() << 16) >> 16; }
  i32() { return this.u16() | (this.u16() << 16); }
  f32() { const dv = new DataView(new ArrayBuffer(4)); dv.setInt32(0, this.i32()); return dv.getFloat32(0); }
}

/** @param {number} h @param {number} m @param {number} s */
function timeHms(h, m, s) {
  if (!(h < 24 && m < 60 && s < 60)) throw new RangeError("czas");
  return [h, m, s].map((v) => String(v).padStart(2, "0")).join(":");
}
/** @param {number} v */
function degrees(v) { if (!(v >= -180 && v <= 180)) throw new RangeError("współrzędna"); return v; }

const DEFAULT_CUSTOM = "4FSKTEST-V2";
/** @type {HorusCustomFormat} */
const RS41NG_CUSTOM = { struct: "<hhBHxx", fields: [["ascent_rate", "divide_by_100"], ["ext_temperature", "divide_by_10"], ["ext_humidity", "none"], ["ext_pressure", "divide_by_10"]] };
/** @type {Record<string, string>} */
const FIELD_LABELS = { ext_temperature: "@hp_temp_ext", ext_humidity: "@hp_humidity", ext_pressure: "@hp_pressure", cutdown_battery_voltage: "@hp_cutdown_battery" };
/** @type {Record<string, string>} */
const FIELD_UNITS = { ext_temperature: "°C", ext_humidity: "%", ext_pressure: "hPa", cutdown_battery_voltage: "V" };

/** Rozpakowanie pól dodatkowych wg łańcucha struct Pythona. @param {number[]} data @param {HorusCustomFormat} format @returns {[string, any][]} */
export function decodeCustom(data, format) {
  /** @type {any[]} */
  const values = [];
  const r = new LeReader(data);
  let count = "";
  for (const c of format.struct) {
    if (c === "<") continue;
    if (/\d/.test(c)) { count += c; continue; }
    const n = count ? Number(count) : 1;
    count = "";
    if (c === "x") for (let i = 0; i < n; i++) r.u8();
    else if (c === "s") values.push(Array.from({ length: n }, () => r.u8()));
    else for (let i = 0; i < n; i++) {
      switch (c) {
        case "b": values.push({ int: r.i8() }); break;
        case "B": values.push({ int: r.u8() }); break;
        case "h": values.push({ int: r.i16() }); break;
        case "H": values.push({ int: r.u16() }); break;
        case "i": values.push({ int: r.i32() }); break;
        case "I": values.push({ int: r.i32() >>> 0 }); break;
        case "f": values.push(r.f32()); break;
        default: throw new RangeError(`typ ${c}`);
      }
    }
  }
  if (values.length !== format.fields.length) throw new RangeError("liczba pól");
  return format.fields.map(([name, type], i) => {
    const v = values[i];
    const num = typeof v === "object" && v && "int" in v ? v.int : v;
    if (type === "battery_5v_byte") return [name, (5 * num) / 255];
    if (type === "divide_by_10") return [name, num / 10];
    if (type === "divide_by_100") return [name, num / 100];
    return [name, v];
  });
}

/** @param {number} v */
const formatDouble3 = (v) => v.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
/** @param {string} name @param {any} value */
function formatValue(name, value) {
  const unit = FIELD_UNITS[name] ? ` ${FIELD_UNITS[name]}` : "";
  if (typeof value === "number") return value.toFixed(Math.abs(value) >= 100 ? 1 : 2).replace(/0+$/, "").replace(/\.$/, "") + unit;
  if (Array.isArray(value)) return value.map((b) => b.toString(16).toUpperCase().padStart(2, "0")).join("");
  return `${value.int}${unit}`;
}

/** @param {number[]} p @param {Map<number, string>} ids @param {string} raw @returns {HorusTelemetry} */
function decodeV1(p, ids, raw) {
  const r = new LeReader(p);
  const id = r.u8(), seq = r.u16();
  const time = timeHms(r.u8(), r.u8(), r.u8());
  const lat = degrees(r.f32()), lon = degrees(r.f32());
  return {
    version: 1, payloadId: id, callsign: ids.get(id) ?? `ID ${id}`, sequence: seq, timeUtc: time, latitude: lat, longitude: lon,
    altitudeM: r.u16(), speedKmh: r.u8(), satellites: r.u8(), temperatureC: r.i8(), batteryV: (5 * r.u8()) / 255, ascentRateMs: null, extra: [], raw,
  };
}

/** @param {number[]} p @param {Map<number, string>} ids @param {Map<string, HorusCustomFormat>} formats @param {string} raw @returns {HorusTelemetry} */
function decodeV2(p, ids, formats, raw) {
  const r = new LeReader(p);
  const id = r.u16(), seq = r.u16();
  const time = timeHms(r.u8(), r.u8(), r.u8());
  const lat = degrees(r.f32()), lon = degrees(r.f32());
  const alt = r.u16(), speed = r.u8(), sats = r.u8(), temp = r.i8(), batt = r.u8();
  const custom = Array.from({ length: 9 }, () => r.u8());
  const callsign = ids.get(id) ?? `ID ${id}`;
  const format = formats.get(callsign) ?? formats.get(DEFAULT_CUSTOM) ?? RS41NG_CUSTOM;
  /** @type {[string, any][]} */
  let fields = [];
  try { fields = decodeCustom(custom, format); } catch { fields = []; }
  const asc = fields.find(([n]) => n === "ascent_rate")?.[1];
  return {
    version: 2, payloadId: id, callsign, sequence: seq, timeUtc: time, latitude: lat, longitude: lon, altitudeM: alt, speedKmh: speed,
    satellites: sats, temperatureC: temp, batteryV: (5 * batt) / 255, ascentRateMs: typeof asc === "number" ? asc : null,
    extra: fields.filter(([n]) => n !== "ascent_rate").map(([n, v]) => [FIELD_LABELS[n] ?? n.replace(/_/g, " "), formatValue(n, v)]), raw,
  };
}

// ---- ASN.1 UPER (tylko konstrukcje z HorusBinaryV3.asn1)

class UperReader {
  /** @param {number[]} data @param {number} startByte */
  constructor(data, startByte) { this.data = data; this.position = startByte * 8; }
  bit() {
    const byte = this.position >> 3;
    if (byte >= this.data.length) throw new RangeError("koniec danych");
    const b = (this.data[byte] >> (7 - (this.position % 8))) & 1;
    this.position++;
    return b === 1;
  }
  /** @param {number} n */
  bits(n) { let v = 0; for (let i = 0; i < n; i++) v = v * 2 + (this.bit() ? 1 : 0); return v; }
  /** @param {number} range */
  static bitsFor(range) { let n = 0; while (2 ** n < range) n++; return n; }
  /** @param {number} lb @param {number} ub */
  constrained(lb, ub) {
    const v = lb + this.bits(UperReader.bitsFor(ub - lb + 1));
    if (v > ub) throw new RangeError("wartość poza zakresem");
    return v;
  }
  length() {
    if (!this.bit()) return this.bits(7);
    if (!this.bit()) return this.bits(14);
    throw new RangeError("fragmentacja");
  }
  normallySmallLength() { return !this.bit() ? this.bits(6) + 1 : this.length(); }
  unconstrainedInt() {
    const len = this.length();
    if (len < 1 || len > 8) throw new RangeError("liczba");
    let v = this.bits(8);
    if (v >= 128) v -= 256;
    for (let i = 1; i < len; i++) v = v * 256 + this.bits(8);
    return v;
  }
  real() {
    const len = this.length();
    if (len === 0) return 0;
    const first = this.bits(8);
    if ((first & 0x80) === 0) {
      if (first & 0x40) return first === 0x40 ? Infinity : first === 0x41 ? -Infinity : first === 0x43 ? -0 : NaN;
      let s = "";
      for (let i = 0; i < len - 1; i++) s += String.fromCharCode(this.bits(8));
      return Number(s.trim().replace(",", "."));
    }
    const negative = (first & 0x40) !== 0;
    const baseCode = (first >> 4) & 3;
    if (baseCode === 3) throw new RangeError("podstawa REAL");
    const base = [2, 8, 16][baseCode];
    const scale = (first >> 2) & 3;
    let used = 1;
    let expLen = (first & 3) + 1;
    if ((first & 3) === 3) { expLen = this.bits(8); used++; }
    let exp = this.bits(8);
    if (exp >= 128) exp -= 256;
    for (let i = 1; i < expLen; i++) exp = exp * 256 + this.bits(8);
    used += expLen;
    let mant = 0;
    for (let i = 0; i < len - used; i++) mant = mant * 256 + this.bits(8);
    const v = mant * 2 ** scale * base ** exp;
    return negative ? -v : v;
  }
  /** @param {string} alphabet @param {number} minLen @param {number} maxLen */
  restrictedString(alphabet, minLen, maxLen) {
    const len = this.constrained(minLen, maxLen);
    const sorted = [...alphabet].sort();
    const b = UperReader.bitsFor(sorted.length);
    const useCodes = sorted[sorted.length - 1].charCodeAt(0) <= 2 ** b - 1;
    let s = "";
    for (let i = 0; i < len; i++) {
      const v = this.bits(b);
      if (useCodes) s += String.fromCharCode(v);
      else { if (v >= sorted.length) throw new RangeError("zły znak"); s += sorted[v]; }
    }
    return s;
  }
}

const CALLSIGN_ALPHABET = "-/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
const SENSOR_NAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789-";
const STR_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ +/=-.";
const GNSS_STATES = ["psmNotActive", "enabled", "acquisition", "tracking", "optimised", "inactive"];
/** @param {number[]} b */
const hexOf = (b) => b.map((x) => x.toString(16).toUpperCase().padStart(2, "0")).join("");

/** @param {number[]} p @param {string} raw @returns {HorusTelemetry} */
function decodeV3(p, raw) {
  const u = new UperReader(p, 2);
  const extended = u.bit();
  const opt = Array.from({ length: 11 }, () => u.bit());
  const callsign = u.restrictedString(CALLSIGN_ALPHABET, 1, 15);
  const seq = u.constrained(0, 65535);
  const tod = u.constrained(-1, 86400);
  const lat = u.constrained(-9000000, 9000000) / 100000;
  const lon = u.constrained(-18000000, 18000000) / 100000;
  const alt = u.constrained(-1000, 50000);
  /** @type {[string, string][]} */
  const extra = [];
  if (opt[0]) {
    const n = u.constrained(1, 4);
    for (let s = 0; s < n; s++) {
      const hasName = u.bit(), hasValues = u.bit();
      const name = hasName ? u.restrictedString(SENSOR_NAME_ALPHABET, 1, 20) : `czujnik ${s + 1}`;
      if (hasValues) {
        const kind = u.constrained(0, 3);
        let value;
        if (kind === 0) value = u.restrictedString(STR_ALPHABET, 0, 255);
        else if (kind === 1) value = Array.from({ length: u.constrained(1, 4) }, () => u.unconstrainedInt()).join(", ");
        else if (kind === 2) value = Array.from({ length: u.constrained(1, 4) }, () => formatDouble3(u.real())).join(", ");
        else value = Array.from({ length: 8 }, () => (u.bit() ? "1" : "0")).join("");
        extra.push([name, value]);
      } else extra.push([name, "–"]);
    }
  }
  const speed = opt[1] ? u.constrained(0, 512) : null;
  const sats = opt[2] ? u.constrained(0, 31) : null;
  const ascent = opt[3] ? u.constrained(-32767, 32767) / 100 : null;
  if (opt[4]) extra.push(["@hp_pressure", `${formatDouble3(u.constrained(0, 12000) / 10)} hPa`]);
  /** @type {number | null} */
  let temperature = null;
  if (opt[5]) {
    const present = Array.from({ length: 4 }, () => u.bit());
    const names = [null, "@hp_temp_ext", "@hp_temp1", "@hp_temp2"];
    for (let i = 0; i < 4; i++) {
      if (!present[i]) continue;
      const tv = u.constrained(-1023, 1023) / 10;
      if (i === 0) temperature = tv; else extra.push([/** @type {string} */ (names[i]), `${formatDouble3(tv)} °C`]);
    }
  }
  if (opt[6]) extra.push(["@hp_humidity", `${u.constrained(0, 100)} %`]);
  /** @type {number | null} */
  let battery = null;
  if (opt[7]) {
    const present = Array.from({ length: 4 }, () => u.bit());
    const names = [null, "@hp_solar", "@hp_voltage1", "@hp_voltage2"];
    for (let i = 0; i < 4; i++) {
      if (!present[i]) continue;
      const v = u.constrained(0, 16383) / 1000;
      if (i === 0) battery = v; else extra.push([/** @type {string} */ (names[i]), `${formatDouble3(v)} V`]);
    }
  }
  if (opt[8]) { const n = u.constrained(1, 8); for (let i = 0; i < n; i++) extra.push([`Licznik ${i + 1}`, String(u.unconstrainedInt())]); }
  if (opt[9]) extra.push(["GNSS", GNSS_STATES[u.constrained(0, 5)] ?? "?"]);
  if (opt[10]) { const n = u.constrained(0, 255); extra.push(["Dane", hexOf(Array.from({ length: n }, () => u.bits(8)))]); }
  if (extended) {
    const count = u.normallySmallLength();
    const present = Array.from({ length: count }, () => u.bit());
    for (let i = 0; i < count; i++) {
      if (!present[i]) continue;
      const len = u.length();
      const start = u.position;
      if (i === 0) { const via = u.constrained(0, 7); extra.push(["Via", via === 0 ? "sondehub" : via === 1 ? "nohub" : "?"]); }
      u.position = start + len * 8;
    }
  }
  let time = null;
  if (tod >= 0) { const tt = tod % 86400; time = timeHms(Math.floor(tt / 3600), Math.floor(tt / 60) % 60, tt % 60); }
  return {
    version: 3, payloadId: null, callsign, sequence: seq, timeUtc: time, latitude: lat, longitude: lon, altitudeM: alt === -1000 ? null : alt,
    speedKmh: speed, satellites: sats, temperatureC: temperature, batteryV: battery, ascentRateMs: ascent, extra, raw,
  };
}

/**
 * Rozpakowanie treści ramki; null, gdy pola są poza zakresem (jak ValueError w decoder.py).
 * @param {HorusRawFrame} frame @param {Map<number, string>} [ids] @param {Map<string, HorusCustomFormat>} [formats] @returns {HorusTelemetry | null}
 */
export function decodeTelemetry(frame, ids = new Map(), formats = new Map()) {
  try {
    if (frame.version === 1) return decodeV1(frame.payload, ids, frame.hex);
    if (frame.version === 2) return decodeV2(frame.payload, ids, formats, frame.hex);
    if (frame.version === 3) return decodeV3(frame.payload, frame.hex);
  } catch (e) {
    if (!(e instanceof RangeError)) throw e;
  }
  return null;
}

/** Kąt elewacji do balonu na wysokości [altitudeM] w odległości [groundKm]. @param {number} groundKm @param {number} altitudeM */
export function balloonElevationDeg(groundKm, altitudeM) {
  const r = 6371, theta = groundKm / r;
  return (Math.atan2(Math.cos(theta) - r / (r + altitudeM / 1000), Math.sin(theta)) * 180) / Math.PI;
}

/** "HH:MM:SS" → różnica w sekundach (przez północ). @param {string | null} a @param {string | null} b */
export function secondsBetween(a, b) {
  /** @param {string | null} s */
  const parse = (s) => {
    const m = /^(\d+):(\d+):(\d+)$/.exec(s ?? "");
    return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) : null;
  };
  const ta = parse(a), tb = parse(b);
  if (ta == null || tb == null) return null;
  let d = tb - ta;
  if (d < -43200) d += 86400;
  return d;
}

/**
 * Widmo 0…maxHz w dB względem szczytu (wykres strojenia). @param {HorusFsk} fsk @param {number} maxHz @param {number} points
 */
export function tuningSpectrum(fsk, maxHz, points) {
  const binHz = fsk.fs / fsk.ndft;
  const bins = Math.trunc(maxHz / binHz);
  const out = new Float32Array(points);
  let peak = 1e-12;
  for (let i = 0; i < points; i++) {
    let v = 0;
    const from = fsk.ndft / 2 + Math.trunc((i * bins) / points);
    const to = fsk.ndft / 2 + Math.trunc(((i + 1) * bins) / points);
    for (let b = from; b < Math.max(to, from + 1); b++) v = Math.max(v, fsk.sf[b]);
    out[i] = v;
    peak = Math.max(peak, v);
  }
  for (let i = 0; i < points; i++) out[i] = Math.max(-60, 20 * Math.log10(out[i] / peak + 1e-6));
  return out;
}
