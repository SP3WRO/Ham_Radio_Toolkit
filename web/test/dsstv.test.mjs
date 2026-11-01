// Testy DSSTV (HamDRM) – jak DsstvDecoderTest.kt: mapy komórek, Viterbi, Reed-Solomon, nagranie z QSSTV, JPEG 2000
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { parseWav, resample } from "../public/js/core/dsp.js";
import { Robustness, Occupancy, DrmCellMap, ConvCode, mscCoding } from "../public/js/core/drm/tables.js";
import { mscInfoBits, ReedSolomon, rsFileEncode, rsFileDecode } from "../public/js/core/drm/channel.js";
import { DrmReceiver } from "../public/js/core/drm/receiver.js";
import { DrmDataDecoder } from "../public/js/core/drm/data.js";
import { decodeJpeg2000 } from "../public/js/core/jpeg2000.js";

const RES = new URL("../../app/src/test/resources/dsstv/", import.meta.url);
let seed = 3;
const rnd = (n) => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed % n; };

test("mapy komórek i przepływność", () => {
  const expected = [[Robustness.A, Occupancy.SO_1, 704], [Robustness.B, Occupancy.SO_1, 530], [Robustness.E, Occupancy.SO_1, 369],
    [Robustness.A, Occupancy.SO_0, 647], [Robustness.B, Occupancy.SO_0, 455], [Robustness.E, Occupancy.SO_0, 339]];
  for (const [m, o, cells] of expected) {
    const map = DrmCellMap.of(m, o);
    assert.equal(map.mscCellsPerFrame, cells, `${m.label} ${o.label}`);
    assert.equal(map.dummyCells, 0);
  }
  assert.equal(mscInfoBits(530, mscCoding(2, true)), 1047);
  assert.equal(mscInfoBits(530, mscCoding(3, true)), 1572);
  assert.equal(mscInfoBits(530, mscCoding(1, false)), 627);
});

test("Viterbi: kodowanie i dekodowanie dla wszystkich stóp", () => {
  for (let rate = 0; rate < 13; rate++) {
    const coded = 400, info = ConvCode.infoBits(coded, rate);
    const bits = Uint8Array.from({ length: info }, () => rnd(2));
    const pattern = ConvCode.pattern(info, rate, coded, false);
    const enc = ConvCode.encode(bits, pattern);
    assert.equal(enc.length, coded, `stopa ${rate}`);
    const llr = Float32Array.from(enc, (b) => (b === 0 ? 1 : -1));
    assert.deepEqual(ConvCode.decode(llr, pattern, info), bits);
  }
});

test("Reed-Solomon: wymazania i błędy, plik RS2 z brakami", () => {
  for (const k of [224, 192, 160, 128]) {
    const rs = new ReedSolomon(k);
    const code = rs.encode(Array.from({ length: k }, () => rnd(256)));
    const nroots = 255 - k;
    const positions = Array.from({ length: 255 }, (_, i) => i).sort(() => rnd(3) - 1);
    const erasures = positions.slice(0, nroots >> 1);
    const errors = positions.slice(nroots >> 1, (nroots >> 1) + (nroots >> 2) - 1);
    const bad = code.slice();
    for (const e of erasures) bad[e] = rnd(256);
    for (const e of errors) bad[e] ^= 1 + rnd(255);
    assert.ok(rs.decode(bad, erasures) >= 0, `k=${k}`);
    assert.deepEqual(bad, code);
  }
  const file = Uint8Array.from({ length: 5410 }, (_, i) => (i * 7 + 3) & 0xff);
  const enc = rsFileEncode(file, "rs2");
  const missing = new Uint8Array(enc.length);
  missing.fill(1, 1000, 1000 + 109 * 3); missing.fill(1, 4000, 4109);
  const damaged = enc.map((v, i) => (missing[i] ? 0 : v));
  assert.deepEqual(rsFileDecode(damaged, "rs2", missing)?.bytes, file);
});

test("nagranie QSSTV (tryb B, 16-QAM): plik zgodny bajt w bajt i obraz 48×32", () => {
  const wav = parseWav(new Uint8Array(readFileSync(new URL("hamdrm_b16_12k.wav", RES))));
  const samples = resample(wav.samples, wav.sampleRate, 12000);
  const files = [], status = [];
  const data = new DrmDataDecoder({ onFile: (f) => files.push(f) });
  const rx = new DrmReceiver({ sampleRate: 12000, onStatus: (s) => status.push(s), onFrame: (b) => data.pushPacket(b) });
  for (let p = 0; p < samples.length; p += 1200) rx.process(samples.slice(p, p + 1200));
  const last = status.filter((s) => s.fac).at(-1);
  assert.equal(last.mode, Robustness.B);
  assert.equal(last.fac.qamLevels, 2);
  assert.equal(files.length, 1);
  const expected = new Uint8Array(readFileSync(new URL("hamdrm_b16_expected.jp2", RES)));
  assert.deepEqual(files[0].bytes, expected);
  const img = decodeJpeg2000(files[0].bytes);
  assert.equal(img.width, 48);
  assert.equal(img.height, 32);
  const right = img.pixels[(16 * 48 + 47) * img.components], left = img.pixels[16 * 48 * img.components];
  assert.ok(right - left > 150, `R: ${left} → ${right}`);
  for (const cut of [300, 450, 600]) assert.equal(decodeJpeg2000(expected.slice(0, cut)).width, 48);
  assert.throws(() => decodeJpeg2000(expected.slice(0, 60)));
});
