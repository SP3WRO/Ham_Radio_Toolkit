// Testy dekodera SSTV – syntetyczne sygnały jak w DecoderEndOfInputTest.java i Robot36SessionTest.kt
import { test } from "node:test";
import assert from "node:assert/strict";
import { SstvDecoder, SstvReceiver, PixelBuffer, SSTV_MODES, argbToRgba } from "../public/js/core/sstv.js";

class Signal {
  /** @param {number} rate */
  constructor(rate) { this.rate = rate; this.samples = []; this.phase = 0; }
  sweep(from, to, seconds) {
    const n = Math.round(seconds * this.rate);
    for (let i = 0; i < n; i++) {
      const f = from + ((to - from) * i) / n;
      this.samples.push(0.5 * Math.sin(this.phase));
      this.phase = (this.phase + (2 * Math.PI * f) / this.rate) % (2 * Math.PI);
    }
  }
  tone(f, s) { this.sweep(f, f, s); }
  header(vis) {
    this.tone(1900, 0.3); this.tone(1200, 0.01); this.tone(1900, 0.3); this.tone(1200, 0.03);
    let bits = 0;
    for (let v = vis; v; v >>= 1) bits += v & 1;
    const code = vis | ((bits & 1) << 7);
    for (let i = 0; i < 8; i++) this.tone(code & (1 << i) ? 1100 : 1300, 0.03);
    this.tone(1200, 0.03);
  }
  get size() { return this.samples.length; }
}

function signal(mode, rate) {
  const s = new Signal(rate);
  switch (mode) {
    case "PD 50": case "PD 290": {
      const long = mode === "PD 290";
      s.header(long ? 94 : 93);
      const ch = long ? 0.2288 : 0.09152;
      for (let i = 0; i < (long ? 308 : 128); i++) { s.tone(1200, 0.02); s.tone(1500, 0.00208); s.tone(2100, ch); s.tone(1900, ch); s.tone(1900, ch); s.tone(1700, ch); }
      break;
    }
    case "Robot 36 Color":
      s.header(8);
      for (let i = 0; i < 240; i++) { s.tone(1200, 0.009); s.tone(1500, 0.003); s.tone(2100, 0.088); s.tone(i % 2 === 0 ? 1500 : 2300, 0.0045); s.tone(1900, 0.0015); s.tone(1900, 0.044); }
      break;
    case "Martin 2":
      s.header(40);
      for (let i = 0; i < 256; i++) { s.tone(1200, 0.004862); s.tone(1500, 0.000572); for (let c = 0; c < 3; c++) { s.tone(1700 + 200 * c, 0.073216); s.tone(1500, 0.000572); } }
      break;
    case "Scottie 1": case "Scottie 2": {
      const s1 = mode === "Scottie 1";
      const ch = s1 ? 0.13824 : 0.088064;
      s.header(s1 ? 60 : 56); s.tone(1200, 0.009);
      for (let i = 0; i < 256; i++) { s.tone(1500, 0.0015); s.tone(1700, ch); s.tone(1500, 0.0015); s.tone(1900, ch); s.tone(1200, 0.009); s.tone(1500, 0.0015); s.tone(2100, ch); }
      break;
    }
    default: throw new Error(mode);
  }
  return s;
}

function decode(sig, size, image) {
  const d = new SstvDecoder(new PixelBuffer(800, 64), image, sig.rate);
  const block = Math.trunc(sig.rate / 50);
  for (let start = 0; start < size; start += block) d.process(Float32Array.from(sig.samples.slice(start, Math.min(size, start + block))));
  return d;
}

test("koniec pliku domyka zbuforowane linie jak dalszy odbiór", () => {
  const modes = ["PD 50", "PD 290", "Robot 36 Color", "Martin 2", "Scottie 2", "PD 50", "Robot 36 Color", "Scottie 1", "Scottie 1"];
  const rates = [8000, 8000, 8000, 8000, 11025, 44100, 48000, 16000, 44100];
  for (let k = 0; k < modes.length; k++) {
    const sig = signal(modes[k], rates[k]);
    const image = new PixelBuffer(800, 616);
    const d = decode(sig, sig.size, image);
    assert.equal(d.currentMode.name, modes[k]);
    assert.ok(image.line >= 0 && image.line < image.height, `${modes[k]}: brakująca ostatnia linia`);
    assert.ok(d.finish(), modes[k]);
    assert.equal(image.line, image.height, `${modes[k]} @ ${rates[k]}`);
    assert.equal(d.finish(), false);

    const ref = new PixelBuffer(800, 616);
    const live = decode(sig, sig.size, ref);
    const block = Math.trunc(sig.rate / 50);
    for (let i = 0; i < 100 && ref.line < ref.height; i++) live.process(new Float32Array(block));
    assert.equal(ref.line, ref.height);
    assert.equal(live.finish(), false);
    assert.deepEqual(image.pixels.slice(0, image.width * image.height), ref.pixels.slice(0, ref.width * ref.height), modes[k]);
  }
});

test("obcięty plik i sam nagłówek nie kończą obrazu", () => {
  for (const mode of ["PD 50", "Robot 36 Color", "Martin 2", "Scottie 1", "Scottie 2"]) {
    const sig = signal(mode, 8000);
    const image = new PixelBuffer(800, 616);
    const d = decode(sig, sig.size - 2 * sig.rate, image);
    d.finish();
    assert.ok(image.line >= 0 && image.line < image.height, mode);
    const lines = image.line;
    assert.equal(d.finish(), false);
    assert.equal(image.line, lines);
  }
  const header = new Signal(8000);
  header.header(93);
  const image = new PixelBuffer(800, 616);
  const d = decode(header, header.size, image);
  assert.equal(d.finish(), false);
  assert.ok(image.line < image.height);
});

test("Robot 36: gradient jasności, szarość, jeden obraz przez SstvReceiver", () => {
  const rate = 16000;
  const s = new Signal(rate);
  s.header(8);
  for (let line = 0; line < 240; line++) {
    s.tone(1200, 0.009); s.tone(1500, 0.003); s.sweep(1500, 2300, 0.088);
    s.tone(line % 2 === 0 ? 1500 : 2300, 0.0045); s.tone(1900, 0.0015); s.tone(1900, 0.044);
  }
  const rx = new SstvReceiver();
  rx.start(rate);
  const images = [];
  const pcm = Float32Array.from(s.samples, (v) => Math.round(v * 32767) / 32768);
  // Nieregularne porcje jak z mikrofonu
  for (let i = 0; i < pcm.length; i += 1000) rx.process(pcm.subarray(i, i + 1000), (img) => images.push(img));
  rx.finish((img) => images.push(img));
  assert.equal(rx.modeLabel, "Robot 36 Color");
  assert.equal(images.length, 1);
  const img = images[0];
  assert.equal(img.width, 320);
  assert.equal(img.height, 240);
  const row = 120 * img.width;
  const left = img.pixels[row + 10] & 255, right = img.pixels[row + 310] & 255;
  assert.ok(left < 60, `lewa ${left}`);
  assert.ok(right > 190, `prawa ${right}`);
  const mid = img.pixels[row + 160];
  const r = (mid >> 16) & 255, g = (mid >> 8) & 255, b = mid & 255;
  assert.ok(Math.max(r, g, b) - Math.min(r, g, b) < 20, `${r}/${g}/${b}`);
  const rgba = argbToRgba(img.pixels.subarray(row, row + 1));
  assert.deepEqual([...rgba], [(img.pixels[row] >> 16) & 255, (img.pixels[row] >> 8) & 255, img.pixels[row] & 255, 255]);
  // Podgląd zawiera obraz
  const view = rx.scopeView(496);
  assert.equal(view.width, 640);
  assert.ok(view.pixels.some((p) => (p & 0xffffff) !== 0));
});

test("każda nazwa trybu z menu odpowiada trybowi dekodera", () => {
  for (const name of SSTV_MODES) {
    const d = new SstvDecoder(new PixelBuffer(640, 64), new PixelBuffer(800, 616), 16000);
    d.setMode(name);
    assert.equal(d.currentMode.name, name);
  }
});
