// Port Ft8DecoderTest.kt: nagrania wzorcowe ft8_lib (wyniki WSJT-X w plikach .txt) i syntetyczne FT4
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { MixedRadixFft } from "../public/js/core/fft.js";
import { parseWav } from "../public/js/core/dsp.js";
import { Ft8Monitor, Ft8SlotDecoder, findCandidates, decodeCandidate, unpackMessage, CallsignHashTable, crc14, tonesFromCodeword } from "../public/js/core/ft8.js";
import { NM, NUM_ROWS } from "../public/js/core/ft8-tables.js";

const DIR = new URL("../../app/src/test/resources/ft8/", import.meta.url);
const files = readdirSync(DIR).filter((f) => f.endsWith(".wav")).sort();
const key = (m) => m.split(" ").filter(Boolean).slice(0, 3).map((x) => (x.startsWith("<") && x.endsWith(">") ? "<...>" : x)).join(" ");
function parseExpected(line) {
  const parts = line.trim().split(/\s+/);
  if (parts.length < 6 || parts[4] !== "~") return null;
  const msg = line.slice(line.indexOf("~") + 1).trim().split(/\s{2,}/)[0];
  return { key: key(msg), snr: Number(parts[1]), dt: Number(parts[2]), freq: Number(parts[3]) };
}
const wav = (f) => parseWav(new Uint8Array(readFileSync(new URL(f, DIR))));

test("FFT zgodne z DFT", () => {
  const n = 60, fft = new MixedRadixFft(n);
  const re = Float64Array.from({ length: n }, (_, i) => Math.sin(i * 0.37) + 0.5 * Math.cos(i * 1.3));
  const im = Float64Array.from({ length: n }, (_, i) => 0.1 * i);
  const oR = new Float64Array(n), oI = new Float64Array(n);
  fft.transform(re, im, oR, oI);
  for (let k = 0; k < n; k++) {
    let sr = 0, si = 0;
    for (let j = 0; j < n; j++) { const a = (-2 * Math.PI * j * k) / n; sr += re[j] * Math.cos(a) - im[j] * Math.sin(a); si += re[j] * Math.sin(a) + im[j] * Math.cos(a); }
    assert.ok(Math.abs(sr - oR[k]) < 1e-9 && Math.abs(si - oI[k]) < 1e-9);
  }
  assert.equal(crc14(new Array(12).fill(0), 82), 0);
});

test("nagrania wzorcowe (WSJT-X)", () => {
  let total = 0, missed = 0, extra = 0, matched = 0, freqMismatch = 0;
  const snrDiff = [], dtDiff = [];
  for (const f of files) {
    const expected = readFileSync(new URL(f.replace(".wav", ".txt"), DIR), "utf8").split("\n").map(parseExpected).filter(Boolean);
    const { samples } = wav(f);
    const result = new Ft8SlotDecoder(12000).decode(samples, Math.min(samples.length, 15 * 12000), 0);
    const got = new Map(), want = new Map();
    for (const d of result) got.set(key(d.text), [...(got.get(key(d.text)) ?? []), d]);
    for (const e of expected) want.set(e.key, [...(want.get(e.key) ?? []), e]);
    total += want.size;
    missed += [...want.keys()].filter((k) => !got.has(k)).length;
    extra += [...got.keys()].filter((k) => !want.has(k)).length;
    for (const [k, list] of want) {
      const decodes = got.get(k);
      if (!decodes) continue;
      for (const e of list) {
        const d = decodes.reduce((a, b) => (Math.abs(b.freqHz - e.freq) < Math.abs(a.freqHz - e.freq) ? b : a));
        if (Math.abs(d.freqHz - e.freq) > 4) { freqMismatch++; continue; }
        matched++;
        snrDiff.push(e.snr - d.snr);
        dtDiff.push(e.dt - d.dt);
      }
    }
  }
  const avg = (a) => a.reduce((s, x) => s + x, 0) / a.length;
  const sd = (a) => { const m = avg(a); return Math.sqrt(avg(a.map((x) => (x - m) ** 2))); };
  const recall = (total - missed) / total;
  console.log(`FT8: wzorzec=${total}, brak=${missed}, nadmiar=${extra}, recall=${(recall * 100).toFixed(1)}%, SNR ${avg(snrDiff).toFixed(2)}±${sd(snrDiff).toFixed(2)}, DT ${avg(dtDiff).toFixed(3)}`);
  assert.ok(freqMismatch <= matched / 50 + 1, `częstotliwości ${freqMismatch}/${matched}`);
  assert.ok(recall >= 0.5, `recall ${recall}`);
  assert.ok(extra <= total / 20 + 1, `fałszywe ${extra}`);
  assert.ok(Math.abs(avg(snrDiff)) <= 2 && sd(snrDiff) <= 6);
  assert.ok(Math.abs(avg(dtDiff)) <= 0.05);
});

/** LDPC(174,91) przez eliminację Gaussa na macierzy H. */
function ldpcEncode(msg) {
  const k = 91, m = 83;
  const rows = Array.from({ length: m }, () => new Array(m + 1).fill(0));
  for (let i = 0; i < m; i++) for (let j = 0; j < NUM_ROWS[i]; j++) {
    const bit = NM[i][j] - 1;
    if (bit >= k) rows[i][bit - k] ^= 1; else rows[i][m] ^= msg[bit];
  }
  for (let col = 0; col < m; col++) {
    let piv = col;
    while (rows[piv][col] !== 1) piv++;
    [rows[col], rows[piv]] = [rows[piv], rows[col]];
    for (let r = 0; r < m; r++) if (r !== col && rows[r][col] === 1) for (let c = 0; c <= m; c++) rows[r][c] ^= rows[col][c];
  }
  return Uint8Array.from({ length: 174 }, (_, i) => (i < k ? msg[i] : rows[i - k][m]));
}

test("syntetyczne FT4", () => {
  const { samples } = wav(files[0]);
  const mon = new Ft8Monitor(12000);
  for (let p = 0; p + mon.blockSize <= Math.min(samples.length, 15 * 12000); p += mon.blockSize) mon.process(samples, p);
  const ht = new CallsignHashTable();
  const seen = new Set();
  const messages = [];
  for (const c of findCandidates(mon.wf, 140, 10)) {
    const raw = decodeCandidate(mon.wf, c, 25);
    if (!raw || seen.has(raw.payload.join())) continue;
    seen.add(raw.payload.join());
    const m = unpackMessage(raw.payload, ht);
    if (m && !m.text.includes("<")) messages.push([raw.payload, m.text]);
    if (messages.length === 4) break;
  }
  assert.ok(messages.length >= 2);
  const XOR = [0x4a, 0x5e, 0x89, 0xb4, 0xb0, 0x8a, 0x79, 0x55, 0xbe, 0x28];
  let s = 12345;
  const rnd = () => ((s = (s * 1664525 + 1013904223) >>> 0) + 1) / 4294967297;
  const signal = Float32Array.from({ length: 90000 }, () => Math.sqrt(-2 * Math.log(rnd())) * Math.cos(2 * Math.PI * rnd()) * 0.05);
  const freqs = messages.map((_, i) => 600 + i * 450);
  messages.forEach(([payload], i) => {
    const a = new Array(12).fill(0);
    for (let j = 0; j < 10; j++) a[j] = payload[j] ^ XOR[j];
    a[9] &= 0xf8;
    const crc = crc14(a, 82);
    const bits = Array.from({ length: 91 }, (_, j) => (j < 77 ? (a[j >> 3] >> (7 - (j % 8))) & 1 : (crc >> (90 - j)) & 1));
    const tones = tonesFromCodeword(ldpcEncode(bits), "FT4");
    let ph = 0;
    for (let sIdx = 0; sIdx < tones.length; sIdx++) {
      const dphi = (2 * Math.PI * (freqs[i] + (tones[sIdx] * 12000) / 576)) / 12000;
      for (let q = 0; q < 576; q++) { const idx = 6000 + sIdx * 576 + q; if (idx < signal.length) signal[idx] += 0.1 * Math.sin(ph); ph += dphi; }
    }
  });
  const result = new Ft8SlotDecoder(12000, "FT4").decode(signal, signal.length, 0);
  messages.forEach(([, text], i) => {
    const d = result.find((r) => r.text === text);
    assert.ok(d, `brak FT4: ${text}`);
    assert.ok(Math.abs(d.freqHz - freqs[i]) <= 11 && Math.abs(d.dt) <= 0.1);
    assert.equal(d.mode, "FT4");
  });
  assert.equal(result.length, messages.length);
});
