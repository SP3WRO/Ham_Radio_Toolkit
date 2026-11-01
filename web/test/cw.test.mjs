// Port CwTest.kt
import { test } from "node:test";
import assert from "node:assert/strict";
import { MORSE, MORSE_DECODE, KOCH_ORDER, kochCharacters, cwTiming, renderCw, randomGroups, cwAccuracy, CwDecoder } from "../public/js/core/cw.js";

/** Powtarzalny szum gaussowski. */
function gauss(seed) {
  let s = seed >>> 0;
  const u = () => ((s = (s * 1664525 + 1013904223) >>> 0) + 1) / 4294967297;
  return () => Math.sqrt(-2 * Math.log(u())) * Math.cos(2 * Math.PI * u());
}

function decode(pcm, tone, wpm, autoTone = false, noise = 0) {
  let out = "";
  const d = new CwDecoder(44100, tone, wpm, (s) => { out += s; });
  d.autoTone = autoTone;
  const g = gauss(1);
  const x = pcm.map((v) => v + noise * g());
  for (let i = 0; i < x.length; i += 882) d.process(x.subarray(i, Math.min(x.length, i + 882)));
  return { text: out.trim(), wpm: d.wpm };
}

test("czasy PARIS i Farnsworth", () => {
  const t = cwTiming(20);
  assert.equal(t.ditMs, 60);
  assert.ok(Math.abs(t.charGapMs - 180) < 1e-9 && Math.abs(t.wordGapMs - 420) < 1e-9);
  const f = cwTiming(20, 10);
  assert.equal(f.ditMs, 60);
  assert.ok(f.charGapMs > 3 * f.ditMs);
  assert.ok(Math.abs(f.wordGapMs / 7 - f.charGapMs / 3) < 1e-9);
});

test("tablice Morse'a i Koch", () => {
  assert.equal(Object.keys(MORSE).length, Object.keys(MORSE_DECODE).length);
  assert.equal(KOCH_ORDER.length, 41);
  assert.equal(new Set(KOCH_ORDER).size, 41);
  assert.ok([...KOCH_ORDER].every((c) => MORSE[c]));
  assert.equal(kochCharacters(1), "KM");
  assert.equal(kochCharacters(5), "KMURES");
  assert.equal(kochCharacters(40), KOCH_ORDER);
});

test("dekoder czyta wygenerowane CW przy kilku prędkościach", () => {
  for (const wpm of [12, 20, 30]) {
    assert.equal(decode(renderCw("CQ CQ DE SP3ABC K", cwTiming(wpm), { toneHz: 650 }), 650, 18).text, "CQ CQ DE SP3ABC K", `${wpm} WPM`);
  }
});

test("dekoder uczy się prędkości i radzi sobie z szumem", () => {
  const r = decode(renderCw("TEST 5NN 73 = TU", cwTiming(25), { toneHz: 700 }), 700, 15, false, 0.05);
  assert.ok(r.text.endsWith("EST 5NN 73 = TU"), r.text);
  assert.ok(r.wpm >= 23 && r.wpm <= 27, String(r.wpm));
});

test("dekoder sam znajduje ton", () => {
  const r = decode(renderCw("PARIS PARIS PARIS PARIS", cwTiming(20), { toneHz: 820, leadInMs: 1500 }), 500, 20, true);
  assert.ok(r.text.endsWith("PARIS PARIS"), r.text);
});

test("dekoder przy 48 kHz (iPhone)", () => {
  let out = "";
  const d = new CwDecoder(48000, 600, 20, (s) => { out += s; });
  d.autoTone = false;
  d.process(renderCw("SP3WRO 73", cwTiming(22), { sampleRate: 48000 }));
  assert.equal(out.trim(), "SP3WRO 73");
});

test("ocena trenera", () => {
  assert.equal(cwAccuracy("KMKMR RKMUM", "KMKMR RKMUM"), 1);
  assert.equal(cwAccuracy("KMKMR RKMUM", "kmkmr  rkmum "), 1);
  assert.ok(Math.abs(cwAccuracy("KMKMR RKMUM", "KMKMR RKMU") - (1 - 1 / 11)) < 1e-9);
  assert.equal(cwAccuracy("KMKMR", ""), 0);
  const g = randomGroups("KM", 3, 5);
  assert.equal(g.split(" ").length, 3);
  assert.ok([...g.replace(/ /g, "")].every((c) => "KM".includes(c)));
});

test("zmiana częstotliwości próbkowania zachowuje ton", async () => {
  const { resample } = await import("../public/js/core/dsp.js");
  const n = 48000;
  const x = Float32Array.from({ length: n }, (_, i) => Math.sin((2 * Math.PI * 1000 * i) / 48000));
  for (const [inRate, outRate] of [[48000, 12000], [44100, 12000]]) {
    const y = resample(x, inRate, outRate);
    assert.ok(Math.abs(y.length - (n * outRate) / inRate) <= 2, `${y.length}`);
    // Przejścia przez zero → częstotliwość po zmianie (ton był "1000 Hz przy inRate")
    let zc = 0;
    for (let i = 200; i < y.length; i++) if (y[i - 1] < 0 && y[i] >= 0) zc++;
    const hz = zc / ((y.length - 200) / outRate);
    assert.ok(Math.abs(hz - (1000 * inRate) / 48000) < 15, `${inRate}→${outRate}: ${hz}`);
  }
});
