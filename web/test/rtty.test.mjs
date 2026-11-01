// Port RttyDecoderTest.kt
import { test } from "node:test";
import assert from "node:assert/strict";
import { Baudot, RttyDecoder, RttyEngine, TuningSpectrum, estimateBaud, snapShift } from "../public/js/core/rtty.js";

const RATE = 12000;
function gauss(seed) {
  let s = (seed * 2654435761) >>> 0 || 1;
  const u = () => ((s = (s * 1664525 + 1013904223) >>> 0) + 1) / 4294967297;
  return () => Math.sqrt(-2 * Math.log(u())) * Math.cos(2 * Math.PI * u());
}

/** AFSK: mark, znaki 1 start + 5 danych (LSB) + 1,5 stopu. */
function afsk(text, markHz, spaceHz, noise, baud = 45.45, seed = 1) {
  /** @type {[boolean, number][]} */
  const bits = [[true, 20]];
  const ch = (code) => { bits.push([false, 1]); for (let i = 0; i < 5; i++) bits.push([((code >> i) & 1) === 1, 1]); bits.push([true, 1.5]); };
  let figures = false;
  ch(Baudot.LTRS);
  for (const c of text) {
    const e = Baudot.encode(c);
    if (!e) continue;
    const [code, needs] = e;
    if (c !== " " && needs !== figures) { ch(needs ? Baudot.FIGS : Baudot.LTRS); figures = needs; }
    ch(code);
    if (c === " ") figures = false;
  }
  bits.push([true, 20]);
  const bit = RATE / baud;
  const total = bits.reduce((s, [, l]) => s + l, 0) * bit;
  const out = new Float32Array(Math.floor(total) + 1);
  const g = gauss(seed);
  let phase = 0, t = 0, idx = 0;
  for (const [mark, len] of bits) {
    t += len * bit;
    const f = mark ? markHz : spaceHz;
    while (idx < t && idx < out.length) { phase += (2 * Math.PI * f) / RATE; out[idx++] = 0.3 * Math.sin(phase) + noise * g(); }
  }
  return out;
}
const feed = (signal, fn) => { for (let p = 0; p < signal.length; p += 600) fn(signal.subarray(p, Math.min(signal.length, p + 600))); };

function decodeManual(signal, center, reverse, baud = 45.45, shift = 170) {
  let s = "";
  const d = new RttyDecoder(RATE, (x) => { s += x; });
  d.autoBaud = false; d.autoPolarity = false; d.centerHz = center; d.shiftHz = shift; d.baud = baud; d.reverse = reverse;
  feed(signal, (c) => d.process(c));
  return s;
}

test("czysty sygnał z cyframi", () => {
  assert.equal(Baudot.L.length, 32); assert.equal(Baudot.F.length, 32);
  const msg = "CQ CQ DE SP3WRO SP3WRO PSE K 599 5NN 73";
  assert.equal(decodeManual(afsk(msg, 1585, 1415, 0), 1500, false).trim(), msg);
});

test("zaszumiony sygnał", () => {
  const msg = "RYRYRY THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 1234567890";
  const dec = decodeManual(afsk(msg, 2125, 2295, 0.2), 2210, true).trim();
  let errors = Math.abs(msg.length - dec.length);
  for (let i = 0; i < Math.min(msg.length, dec.length); i++) if (msg[i] !== dec[i]) errors++;
  assert.ok(errors <= 2, `${errors}: '${dec}'`);
});

test("ręczne ustawienia są respektowane", () => {
  const sig = afsk("CQ CQ DE SP3WRO SP3WRO K", 1585, 1415, 0);
  assert.ok(!decodeManual(sig, 1500, true).includes("SP3WRO"));
  assert.ok(!decodeManual(sig, 1500, false, 75).includes("SP3WRO"));
});

function concat(parts) {
  const all = new Float32Array(parts.reduce((s, p) => s + p.length, 0));
  let pos = 0;
  for (const p of parts) { all.set(p, pos); pos += p.length; }
  return all;
}
function drifting(text, c) {
  const tone = (center, seed, part) => {
    const hi = center + c.shift / 2, lo = center - c.shift / 2;
    return afsk(part, c.inverted ? lo : hi, c.inverted ? hi : lo, c.noise ?? 0.05, c.baud, seed);
  };
  if (!c.drift) return tone(c.center, c.center, text);
  const parts = [];
  let t = 0;
  for (let i = 0; i * 10 < text.length; i++) {
    const piece = tone(c.center + c.drift * t, i, text.slice(i * 10, i * 10 + 10));
    parts.push(piece);
    t += piece.length / RATE;
  }
  return concat(parts);
}

test("automatyczne wykrywanie częstotliwości, przesuwu, szybkości i polaryzacji", () => {
  const msg = "CQ CQ DE SP3WRO SP3WRO PSE K";
  const text = ("RYRYRYRYRY " + `${msg} `.repeat(8)).trim();
  for (const c of [
    { baud: 45.45, shift: 170, center: 1000, inverted: false }, { baud: 50, shift: 450, center: 1700, inverted: true },
    { baud: 75, shift: 850, center: 1500, inverted: false }, { baud: 100, shift: 170, center: 1300, inverted: false },
    { baud: 45.45, shift: 200, center: 1600, inverted: false }, { baud: 45.45, shift: 425, center: 1100, inverted: true },
    { baud: 45.45, shift: 170, center: 2210, inverted: true }, { baud: 45.45, shift: 170, center: 800, inverted: false, noise: 0.15 },
    { baud: 45.45, shift: 170, center: 1200, inverted: false, drift: 1 },
  ]) {
    const sig = drifting(text, c);
    const endCenter = c.center + ((c.drift ?? 0) * sig.length) / RATE;
    let s = "";
    const e = new RttyEngine(RATE, (x) => { s += x; });
    feed(sig, (x) => e.process(x));
    const d = e.decoder;
    const label = `${JSON.stringify(c)} → ${d.centerHz.toFixed(1)} Hz, ${d.shiftHz} Hz, ${d.baud} Bd, odwr.=${d.activeReverse}`;
    assert.ok(Math.abs(d.shiftHz - c.shift) < 0.1, label);
    assert.ok(Math.abs(d.baud - c.baud) < 0.01, label);
    assert.ok(Math.abs(d.centerHz - endCenter) < 3, label);
    assert.equal(d.activeReverse, c.inverted, label);
    assert.ok(s.slice(-80).includes(msg), `${label}: '${s.slice(-80)}'`);
  }
});

function interference(signal, carrierHz = 2600, seed = 7) {
  const g = gauss(seed);
  return signal.map((v, i) => {
    const t = i / RATE;
    let hum = 0;
    for (let h = 1; h <= 12; h++) hum += (0.05 / h) * Math.sin(2 * Math.PI * 50 * h * t);
    return v + hum + 0.08 * Math.sin(2 * Math.PI * carrierHz * t) + 0.03 * g();
  });
}
const mix = (a, b) => Float32Array.from({ length: Math.max(a.length, b.length) }, (_, i) => (a[i] ?? 0) + (b[i] ?? 0));

test("szybkie złapanie mimo przydźwięku, nośnej i drugiej stacji", () => {
  const msg = "CQ CQ DE SP3WRO SP3WRO PSE K";
  const strong = afsk(("RYRYRYRYRY " + `${msg} `.repeat(6)).trim(), 2125, 2295, 0, 45.45, 3);
  const weak = afsk("RYRYRY ".repeat(15), 785, 615, 0, 45.45, 4).map((v) => v * 0.3);
  const sig = interference(mix(strong, weak));
  let s = "";
  const e = new RttyEngine(RATE, (x) => { s += x; });
  let acquiredAt = -1;
  for (let p = 0; p < sig.length; p += 600) {
    e.process(sig.subarray(p, Math.min(sig.length, p + 600)));
    if (acquiredAt < 0 && Math.abs(e.decoder.centerHz - 2210) < 5) acquiredAt = (p + 600) / RATE;
  }
  const label = `środek ${e.decoder.centerHz.toFixed(1)}, ${e.decoder.shiftHz} Hz, złapane po ${acquiredAt.toFixed(2)} s`;
  assert.ok(acquiredAt >= 0 && acquiredAt <= 2, label);
  assert.ok(Math.abs(e.decoder.centerHz - 2210) < 3, label);
  assert.equal(e.decoder.shiftHz, 170, label);
  assert.ok(s.slice(-80).includes(msg), `${label}: '${s.slice(-80)}'`);
});

test("dotknięcie widma wybiera słabszą stację", () => {
  const strong = afsk("RYRYRY ".repeat(60), 2125, 2295, 0, 45.45, 5);
  const msg = "CQ DE SP3WRO K";
  const weak = afsk(("RYRY " + `${msg} `.repeat(10)).trim(), 1085, 915, 0, 45.45, 6).map((v) => v * 0.4);
  const sig = interference(mix(strong, weak));
  let s = "";
  const e = new RttyEngine(RATE, (x) => { s += x; });
  let tapped = false, at20 = 0;
  for (let p = 0; p < sig.length; p += 600) {
    e.process(sig.subarray(p, Math.min(sig.length, p + 600)));
    const pos = p + 600;
    if (!tapped && pos > 3 * RATE) { e.setCenter(1040); tapped = true; }
    if (at20 === 0 && pos > 20 * RATE) at20 = e.decoder.centerHz;
  }
  assert.ok(Math.abs(at20 - 1000) < 3, `środek w 20 s: ${at20}`);
  assert.ok(s.includes(`${msg} ${msg}`), s);
  assert.ok(Math.abs(e.decoder.centerHz - 2210) < 3);
});

test("AFC nie błądzi na szumie i pojedynczej nośnej", () => {
  const sig = interference(new Float32Array(20 * RATE), 1530);
  const e = new RttyEngine(RATE);
  e.setCenter(1200);
  feed(sig, (x) => e.process(x));
  assert.ok(Math.abs(e.decoder.centerHz - 1200) <= 1);
  assert.equal(e.decoder.shiftHz, 170);
});

test("estymator szybkości i przesuwy", () => {
  const bit = RATE / 50;
  const pattern = [1, 2, 1, 3, 1.5, 1, 2.5, 1, 4, 1];
  assert.equal(estimateBaud(Int32Array.from({ length: 60 }, (_, i) => Math.floor(pattern[i % pattern.length] * bit) + (i % 3) - 1), RATE), 50);
  assert.equal(estimateBaud(Int32Array.from({ length: 60 }, (_, i) => 20 + (i % 7)), RATE), null);
  assert.equal(snapShift(176), 170); assert.equal(snapShift(441), 450); assert.equal(snapShift(838), 850); assert.equal(snapShift(302), 300);
});

test("widmo znajduje parę tonów i przesuw", () => {
  const sig = afsk("RYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRY", 1885, 1715, 0.02);
  const sp = new TuningSpectrum(RATE);
  for (let p = 0; p + 600 < sig.length - 20 * 264; p += 600) sp.process(sig.slice(p, p + 600));
  const f = sp.findRttySignal();
  assert.ok(f && Math.abs(f.centerHz - 1800) <= 6, JSON.stringify(f));
  assert.equal(f.shiftHz, 170);
  assert.equal(sp.findRttySignal(null, 850), null);
});
