// Testy Horus Binary – nagrania wzorcowe horusdemodlib i wektory z HorusDecoderTest.kt
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { parseWav, resample } from "../public/js/core/dsp.js";
import {
  HorusModem, HorusStream, golay23Encode, golay23Decode, encodeTxPacket, decodeRxPacket, numTxDataBytes, crc16,
  decodeTelemetry, parsePayloadIds, parseCustomFields, balloonElevationDeg, secondsBetween,
} from "../public/js/core/horus.js";

const RES = new URL("../../app/src/test/resources/horus/", import.meta.url);
const ASSETS = new URL("../../app/src/main/assets/horus/", import.meta.url);
const rawS16 = (name) => {
  const b = readFileSync(new URL(name, RES));
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return Float32Array.from({ length: b.length / 2 }, (_, i) => v.getInt16(i * 2, true) / 32768);
};
function decodeAll(samples, rate) {
  const audio = resample(samples, rate, 12000);
  const frames = [];
  new HorusStream(new HorusModem(12000)).process(audio, (f) => frames.push(f));
  return frames;
}
const ids = parsePayloadIds(readFileSync(new URL("payload_id_list.txt", ASSETS), "utf8"));
const formats = parseCustomFields(readFileSync(new URL("custom_field_list.json", ASSETS), "utf8"));

test("Golay(23,12) poprawia do 3 błędów", () => {
  for (const c of [0, 1, 0x555, 0xabc, 0xfff]) {
    const code = golay23Encode(c);
    assert.equal(golay23Decode(code) >> 11, c);
    assert.equal(golay23Decode(code ^ (1 << 3) ^ (1 << 17) ^ (1 << 22)) >> 11, c);
  }
});

test("L2: nadanie i odbiór z serią błędów", () => {
  for (const size of [22, 32, 48, 128]) {
    const payload = Array.from({ length: size }, (_, i) => (i * 37 + 11) & 0xff);
    const tx = encodeTxPacket(payload);
    assert.equal(tx.length, numTxDataBytes(size));
    tx[5] ^= 0xff;
    assert.deepEqual(decodeRxPacket(tx.slice(), size), payload, `rozmiar ${size}`);
  }
});

test("v1: nagranie przy Eb/No 4,5 dB", () => {
  const wav = parseWav(new Uint8Array(readFileSync(new URL("horus_binary_ebno_4.5db.wav", RES))));
  const frames = decodeAll(wav.samples, wav.sampleRate);
  assert.ok(frames.length > 0);
  assert.ok(frames.every((f) => f.version === 1 && f.hex.endsWith("1C9A9545")));
});

test("v2: nagranie 100 Bd", () => {
  const frames = decodeAll(rawS16("horus_v2_100bd_8000_s16.raw"), 8000);
  assert.ok(frames.some((f) => f.version === 2 && f.hex.endsWith("0102030405060708091DBB")));
});

test("v3: nagranie i rozpakowanie UPER", () => {
  const frames = decodeAll(rawS16("horus_v3_100bd_8000_s16.raw"), 8000);
  assert.ok(frames.length > 0);
  assert.ok(frames.every((f) => f.version === 3 && f.payload.length === 128));
  const tel = frames.map((f) => decodeTelemetry(f, ids, formats));
  assert.ok(tel.every(Boolean));
  const t = /** @type {any} */ (tel[0]);
  assert.equal(t.callsign, "4FSKTEST-V2");
  assert.equal(t.timeUtc, "00:00:03");
  assert.ok(Math.abs(t.latitude - 0.00023) < 1e-9);
  assert.ok(Math.abs(t.longitude - 0.00034) < 1e-9);
  assert.equal(t.altitudeM, 56);
  assert.equal(t.extra.filter(([k, v]) => k === "meowmeow" && v === "123").length, 4);
  assert.equal(t.extra.find(([k]) => k === "Dane")[1], "574F4F46".repeat(8));
  const seqs = tel.map((x) => x.sequence);
  assert.ok(seqs.every((s, i) => i === 0 || s > seqs[i - 1]));
});

test("v1: pakiet jak w decoder.py, godzina 24 odrzucona", () => {
  const p = [0x01, 0x12, 0x00, 0x00, 0x00, 0x23, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x1c, 0x9a, 0x95, 0x45];
  const t = decodeTelemetry({ payload: p, version: 1, hex: "" }, ids, formats);
  assert.equal(t?.callsign, "HORUSBINARY");
  assert.equal(t?.sequence, 18);
  assert.equal(t?.timeUtc, "00:00:35");
  assert.equal(t?.temperatureC, 28);
  assert.ok(Math.abs(/** @type {number} */ (t?.batteryV) - (5 * 0x9a) / 255) < 1e-9);
  const bad = p.slice(); bad[3] = 0x18;
  assert.equal(decodeTelemetry({ payload: bad, version: 1, hex: "" }, ids, formats), null);
});

test("v2: pola dodatkowe RS41ng", () => {
  const p = [0x00, 0x01, 0x02, 0x00, 0x0c, 0x22, 0x38, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0xb4, 0xc6];
  const t = decodeTelemetry({ payload: p, version: 2, hex: "" }, ids, formats);
  assert.equal(t?.payloadId, 256);
  assert.equal(t?.callsign, "4FSKTEST-V2");
  assert.equal(t?.timeUtc, "12:34:56");
  assert.ok(Math.abs(/** @type {number} */ (t?.ascentRateMs) - 5.13) < 1e-9);
  const extra = new Map(t?.extra);
  assert.equal(extra.get("@hp_temp_ext"), "102.7 °C");
  assert.equal(extra.get("@hp_humidity"), "5 %");
  assert.equal(extra.get("@hp_pressure"), "179.8 hPa");
});

test("szum nie daje fałszywych ramek", () => {
  let seed = 1;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  const gauss = () => Math.sqrt(-2 * Math.log(rnd() || 1e-9)) * Math.cos(2 * Math.PI * rnd());
  const noise = Float32Array.from({ length: 12000 * 60 }, () => gauss() * 0.1);
  let frames = 0;
  new HorusStream(new HorusModem(12000)).process(noise, () => frames++);
  assert.equal(frames, 0);
});

test("sygnał syntetyczny v2 z przesunięciem częstotliwości i szumem", () => {
  const payload = new Array(32).fill(0);
  payload[0] = 1; payload[1] = 1; payload[2] = 42;
  const crc = crc16(payload, 0, 30);
  payload[30] = crc & 0xff; payload[31] = crc >> 8;
  const tx = encodeTxPacket(payload);
  const bits = [];
  for (let r = 0; r < 4; r++) {
    for (let i = 0; i < 16; i++) bits.push(0, 1);
    for (const b of tx) for (let k = 7; k >= 0; k--) bits.push((b >> k) & 1);
  }
  const rate = 12000, sps = rate / 100;
  const out = new Float32Array(rate + (bits.length / 2) * sps + rate);
  let phase = 0, pos = rate;
  for (let s = 0; s < bits.length / 2; s++) {
    const f = 1137 + 270 * (bits[2 * s] * 2 + bits[2 * s + 1]);
    for (let i = 0; i < sps; i++) { phase += (2 * Math.PI * f) / rate; out[pos++] = 0.3 * Math.sin(phase); }
  }
  let seed = 5;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  for (let i = 0; i < out.length; i++) out[i] += Math.sqrt(-2 * Math.log(rnd() || 1e-9)) * Math.cos(2 * Math.PI * rnd()) * 0.05;
  const frames = decodeAll(out, rate);
  assert.ok(frames.length >= 3, `zdekodowano ${frames.length}`);
  assert.ok(frames.every((f) => f.version === 2 && f.payload.join() === payload.join()));
  const t = decodeTelemetry(frames[0], ids, formats);
  assert.equal(t?.payloadId, 257);
});

test("pomocnicze: elewacja i różnica czasu", () => {
  assert.ok(Math.abs(balloonElevationDeg(0.001, 30000) - 90) < 0.1);
  assert.ok(balloonElevationDeg(300, 30000) > 3 && balloonElevationDeg(300, 30000) < 5);
  assert.equal(secondsBetween("23:59:50", "00:00:10"), 20);
  assert.equal(secondsBetween(null, "00:00:10"), null);
});
