// Port AprsParserTest.kt + most WebSocket↔APRS-IS (podstawiony serwer APRS-IS na lokalnym porcie)
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer as tcpServer } from "node:net";
import { createServer as httpServer } from "node:http";
import { parseAprs, aprsPasscode, aprsLat, aprsLon, aprsBeacon } from "../public/js/core/aprs.js";

const near = (a, b, eps = 1e-5) => assert.ok(Math.abs(a - b) < eps, `${a} ≠ ${b}`);

test("stacja ruchoma: pozycja, kurs, prędkość i PHG", () => {
  const p = parseAprs("SP3XYZ-9>APRS,TCPIP*:=5224.50N/01654.30E>090/036PHG5130Mobile", 0);
  assert.equal(p.callsign, "SP3XYZ-9");
  near(p.latitude, 52.408333); near(p.longitude, 16.905);
  assert.equal(p.table, "/"); assert.equal(p.symbol, ">");
  assert.equal(p.courseDegrees, 90); near(p.speedKmh, 36 * 1.852);
  assert.equal(p.powerW, 25); near(p.antennaHeightM, 20 * 0.3048); assert.equal(p.antennaGainDb, 3); assert.equal(p.directivityDeg, 0);
  assert.equal(p.comment, "Mobile");
  const sw = parseAprs("LU1ABC>APRS:!3436.00S/05822.00W-", 0);
  near(sw.latitude, -34.6); near(sw.longitude, -58.366667);
});

test("stacja pogodowa", () => {
  const p = parseAprs("SP3WX>APRS:@201234z5224.50N/01654.30E_180/005g010t050r001p002P003h85b10132Test WX", 0);
  assert.equal(p.windDirDeg, 180); near(p.windSpeedKmh, 5 * 1.60934); near(p.windGustKmh, 10 * 1.60934);
  near(p.tempCelsius, 10); near(p.rain1hMm, 0.254); near(p.rain24hMm, 0.508); near(p.rainMidnightMm, 0.762);
  assert.equal(p.humidityPercent, 85); near(p.pressureHpa, 1013.2); assert.equal(p.comment, "Test WX"); assert.equal(p.courseDegrees, undefined);
  const m = parseAprs("SP3WX>APRS:!5224.50N/01654.30E_.../...g...t-05h00b.....", 0);
  assert.equal(m.windSpeedKmh, undefined); assert.equal(m.windGustKmh, undefined);
  near(m.tempCelsius, ((-5 - 32) * 5) / 9); assert.equal(m.humidityPercent, 100); assert.equal(m.pressureHpa, undefined); assert.equal(m.comment, "");
  const r = parseAprs("SP3R>APRS:!5224.50N/01654.30E#QRV r145 s123 t100 h50", 0);
  assert.equal(r.rain1hMm, undefined); assert.equal(r.tempCelsius, undefined); assert.equal(r.comment, "QRV r145 s123 t100 h50");
});

test("nakładki, skompresowane, Mic-E, wysokość", () => {
  const o = parseAprs("SR3D>APRS:!5224.50ND01654.30E#Digi", 0);
  assert.equal(o.table, "\\"); assert.equal(o.symbol, "#"); assert.equal(o.overlay, "D");
  const c = parseAprs("W1AW>APRS:!/5L!!<*e7>7P[Compressed", 0);
  near(c.latitude, 49.5, 1e-4); near(c.longitude, -72.75, 1e-4); assert.equal(c.symbol, ">"); assert.equal(c.courseDegrees, 88);
  near(c.speedKmh, (Math.pow(1.08, 47) - 1) * 1.852, 1e-6); assert.equal(c.comment, "Compressed");
  const co = parseAprs("SR3D>APRS:=b5L!!<*e7#  [", 0);
  assert.equal(co.table, "\\"); assert.equal(co.overlay, "1"); assert.equal(co.symbol, "#"); assert.equal(co.speedKmh, undefined);
  const m = parseAprs("SP3ABC-9>522T50,WIDE1-1:`,R:n\"O>/Hello", 0);
  near(m.latitude, 52.408333); near(m.longitude, 16.905); assert.equal(m.symbol, ">"); near(m.speedKmh, 20 * 1.852);
  assert.equal(m.courseDegrees, 251); assert.equal(m.comment, "Hello");
  const w = parseAprs("N0CALL>3325VT:`(#f\u001c\u001c\u001c>/", 0);
  near(w.latitude, -(33 + 25.64 / 60)); near(w.longitude, -(112 + 7.74 / 60)); assert.equal(w.speedKmh, undefined);
  const a = parseAprs("SP3XYZ>APRS:!5224.50N/01654.30E>Mobile /A=000328 QRV", 0);
  near(a.altitudeM, 328 * 0.3048); assert.equal(a.comment, "Mobile  QRV");
});

test("błędne pakiety, współrzędne ramki i passcode", () => {
  for (const l of ["garbage", "SP3XYZ>APRS::SP3ABC   :message", "SP3XYZ>APRS:!5224.50N/01654", "SP3XYZ>APRS:!52x4.50N/01654.30E>"]) assert.equal(parseAprs(l, 0), null, l);
  assert.ok(parseAprs("SP3XYZ>APRS:!5224.50N/01654.30E>", 0));
  assert.equal(aprsLat(52.99996), "5300.00N"); assert.equal(aprsLon(16.99996), "01700.00E");
  assert.equal(aprsLat(-34.6), "3436.00S"); assert.equal(aprsLon(-58.366667), "05822.00W");
  const p = parseAprs(`SP3XYZ>APRS:=${aprsLat(52.99996)}/${aprsLon(16.99996)}>`, 0);
  near(p.latitude, 53); near(p.longitude, 17);
  assert.equal(aprsPasscode("SP3ABC"), aprsPasscode("sp3abc-7"));
  assert.ok(aprsPasscode("SP3ABC") >= 0 && aprsPasscode("SP3ABC") <= 0x7fff);
  assert.equal(aprsBeacon({ call: "SP3WRO", ssid: "-7", icon: "/>", comment: "Hi", lat: 52.4, lon: 16.9 }), "SP3WRO-7>APRS,TCPIP*:=5224.00N/01654.00E>Hi");
});

test("most WebSocket ↔ APRS-IS", async () => {
  /** @type {string[]} */
  const received = [];
  const fake = tcpServer((sock) => {
    sock.setEncoding("latin1");
    sock.write("# aprsc 2.1\r\n");
    sock.on("data", (d) => {
      for (const l of String(d).split("\r\n").filter(Boolean)) {
        received.push(l);
        if (l.startsWith("user ")) {
          sock.write(`# logresp SP3WRO verified, server T2TEST\r\n`);
          sock.write("SP3XYZ-9>APRS,TCPIP*:=5224.50N/01654.30E>090/036Mobile\r\n");
          sock.write("SP3XYZ>APRS::SP3ABC   :wiadomość\r\n");
        }
      }
    });
  });
  await new Promise((r) => fake.listen(0, "127.0.0.1", r));
  process.env.APRS_HOST = "127.0.0.1";
  process.env.APRS_PORT = String(fake.address().port);
  const { handleAprsUpgrade } = await import("../server/aprs.mjs");
  const http = httpServer((_, res) => res.end());
  http.on("upgrade", (req, socket) => handleAprsUpgrade(req, socket, "1.2.3.4", "test"));
  await new Promise((r) => http.listen(0, "127.0.0.1", r));
  try {
    const ws = new WebSocket(`ws://127.0.0.1:${http.address().port}/api/aprs?lat=52.4&lon=16.9&radius=50&call=sp3wro`);
    /** @type {any[]} */
    const msgs = [];
    /** @type {Set<() => void>} */
    const waiters = new Set();
    ws.addEventListener("message", (e) => { msgs.push(JSON.parse(String(e.data))); waiters.forEach((w) => w()); });
    const waitFor = (pred) => new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(JSON.stringify(msgs))), 3000);
      const check = () => { const m = msgs.find(pred); if (m) { clearTimeout(t); waiters.delete(check); resolve(m); } };
      waiters.add(check);
      check();
    });
    const conn = await waitFor((m) => m.type === "connected");
    assert.equal(conn.canTransmit, true);
    const pkt = await waitFor((m) => m.type === "packet");
    assert.match(pkt.line, /^SP3XYZ-9>/);
    assert.equal(msgs.filter((m) => m.type === "packet").length, 1, "wiadomości bez pozycji nie są przekazywane");
    assert.match(received[0], /^user SP3WRO pass \d+ vers HamRadioToolkit-Web 1\.0 filter r\/52\.4000\/16\.9000\/50$/);
    ws.send(JSON.stringify({ type: "beacon", ssid: "-7", icon: "/>", comment: "Test", lat: 52.4, lon: 16.9 }));
    const b = await waitFor((m) => m.type === "beacon");
    assert.equal(b.ok, true);
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(received[1], "SP3WRO-7>APRS,TCPIP*:=5224.00N/01654.00E>Test");
    ws.send(JSON.stringify({ type: "beacon", ssid: "-7", icon: "/>", comment: "", lat: 52.4, lon: 16.9 }));
    await waitFor((m) => m.type === "beacon" && m.message === "rate");
    ws.close();
  } finally {
    http.close();
    fake.close();
  }
});
