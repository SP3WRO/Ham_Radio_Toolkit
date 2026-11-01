// SGP4: wartości kontrolne ze Spacetrack Report #3 (satelita 88888) i geometria widoku ze stacji
import { test } from "node:test";
import assert from "node:assert/strict";
import { satrec, sgp4, gmst, jdFromMs, lookAngles, nextPass, skyTrack, parseTles } from "../public/js/core/sgp4.js";

const T88888 = { name: "TEST", line1: "1 88888U          80275.98708465  .00073094  13844-3  66816-4 0     8", line2: "2 88888  72.8435 115.9689 0086731  52.6988 110.5714 16.05824518   105" };
const ISS = { name: "ISS (ZARYA)", line1: "1 25544U 98067A   26300.50000000  .00016789  00000-0  30283-3 0  9993", line2: "2 25544  51.6412 128.9321 0005432  32.1234 328.0987 15.49812345445678" };

test("SGP4 – Spacetrack Report #3", () => {
  const s = satrec(T88888);
  assert.ok(s);
  const expect = { 0: [2328.97048951, -5995.22076416, 1719.97067261], 360: [2456.10705566, -6071.93853760, 1222.89727783],
    720: [2567.56195068, -6112.50384522, 713.96397400], 1080: [2663.09078980, -6115.48229980, 196.39640427] };
  for (const [t, r] of Object.entries(expect)) {
    const p = sgp4(s, Number(t));
    r.forEach((v, i) => assert.ok(Math.abs(p.r[i] - v) < 0.05, `t=${t} r${i}: ${p.r[i]} vs ${v}`));
  }
  const v0 = sgp4(s, 0).v;
  [2.91207230, -0.98341546, -7.09081703].forEach((v, i) => assert.ok(Math.abs(v0[i] - v) < 1e-4));
});

test("geometria: satelita w zenicie nad punktem podsatelitarnym", () => {
  const s = satrec(ISS);
  const ms = s.epochMs + 3_600_000;
  const p = sgp4(s, 60).r;
  const g = gmst(jdFromMs(ms));
  const x = p[0] * Math.cos(g) + p[1] * Math.sin(g), y = -p[0] * Math.sin(g) + p[1] * Math.cos(g);
  const lon = (Math.atan2(y, x) * 180) / Math.PI;
  // Szerokość geodezyjna (iteracja Bowringa uproszczona)
  const rxy = Math.hypot(x, y), e2 = 0.00669437999;
  let lat = Math.atan2(p[2], rxy);
  for (let i = 0; i < 5; i++) { const n = 6378.137 / Math.sqrt(1 - e2 * Math.sin(lat) ** 2); lat = Math.atan2(p[2] + n * e2 * Math.sin(lat), rxy); }
  const look = lookAngles(s, { lat: (lat * 180) / Math.PI, lon, alt: 0 }, ms);
  assert.ok(look.el > 89.5, `el ${look.el}`);
  assert.ok(look.range > 300 && look.range < 450, `range ${look.range}`);
  assert.ok(Math.abs(gmst(2451545.0) - 4.894961) < 1e-5);
});

test("przeloty ISS nad Poznaniem", () => {
  const s = satrec(ISS);
  const site = { lat: 52.4, lon: 16.9, alt: 80 };
  let from = s.epochMs;
  const passes = [];
  for (let i = 0; i < 6; i++) {
    const p = nextPass(s, site, from);
    assert.ok(p, "brak przelotu");
    assert.ok(p.los > p.aos && p.los - p.aos < 15 * 60_000, `czas przelotu ${(p.los - p.aos) / 60000} min`);
    assert.ok(p.maxEl > 0 && p.maxEl <= 90);
    assert.ok(Math.abs(lookAngles(s, site, p.aos).el) < 0.2 && Math.abs(lookAngles(s, site, p.los).el) < 0.2);
    assert.ok(p.maxElAt > p.aos && p.maxElAt < p.los);
    passes.push(p);
    from = p.los + 60_000;
  }
  // Kolejne przeloty nie nachodzą na siebie, a trajektoria jest nad horyzontem
  for (let i = 1; i < passes.length; i++) assert.ok(passes[i].aos > passes[i - 1].los);
  const track = skyTrack(s, site, passes[0].aos, passes[0].los);
  assert.ok(track.length > 30 && track.every((x) => x.el >= 0 && x.az >= 0 && x.az < 360));
  // Trwający przelot: start w połowie zwraca ten sam AOS
  const mid = nextPass(s, site, (passes[0].aos + passes[0].los) / 2);
  assert.ok(Math.abs(mid.aos - passes[0].aos) < 2000);
});

test("satelita geostacjonarny nie ma przelotów", () => {
  const geo = satrec({ name: "QO-100", line1: "1 43700U 18090A   26300.50000000  .00000150  00000-0  00000+0 0  9990",
    line2: "2 43700   0.0170 247.0000 0001500 200.0000 270.0000  1.00271000 18000" });
  assert.ok(geo.deep);
  const site = { lat: 52.4, lon: 16.9 };
  const look = lookAngles(geo, site, geo.epochMs);
  assert.ok(look.range > 35000 && look.range < 49000, `range ${look.range}`);
  const later = lookAngles(geo, site, geo.epochMs + 12 * 3_600_000);
  assert.ok(Math.abs(later.el - look.el) < 0.5 && Math.abs(later.az - look.az) < 0.5);
});

test("plik TLE", () => {
  const list = parseTles(`ISS (ZARYA)\n${ISS.line1}\n${ISS.line2}\r\nśmieci\nAO-7\n1 07530U 74089B   26300.1  -.00000030  00000-0  10000-3 0  9990\n2 07530 101.9 1.0 0012 1.0 1.0 12.53\n`);
  assert.deepEqual(list.map((x) => x.name), ["ISS (ZARYA)", "AO-7"]);
  assert.equal(list[1].line1.length, 69);
});
