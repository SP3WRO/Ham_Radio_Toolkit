// Testy logiki wersji WWW – te same przypadki i wartości co w testach aplikacji na Androida (app/src/test).
// Uruchomienie: node --test web/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { Maidenhead, GreatCircle, parseWorldOutlines } from "../public/js/core/geo.js";
import { AntennaMath, FeedlineMath, NcdxfBeacons, bandForKhz, calculateBandFromFreq, Callsigns, COAX_DATABASE, attenuationForFreq } from "../public/js/core/radio.js";
import { Adif, CtyDatabase, LogStatistics, makeQso, filterLogs } from "../public/js/core/logbook.js";
import { IaruRegion, inPoland, PhraseLocalizer, parseSolarXml, sunInfo, formatDecimalHour } from "../public/js/core/world.js";
import { format } from "../public/js/i18n.js";

const data = (p) => readFileSync(fileURLToPath(new URL(`../public/data/${p}`, import.meta.url)), "utf8");
const near = (actual, expected, tol, msg) => assert.ok(Math.abs(actual - expected) <= tol, `${msg ?? ""} ${actual} ≠ ${expected} ±${tol}`);

const warsaw = { lat: 52.2297, lon: 21.0122 };
const newYork = { lat: 40.7128, lon: -74.006 };

test("lokator Maidenhead", () => {
  assert.equal(Maidenhead.fromLatLon(warsaw.lat, warsaw.lon, 6), "KO02mf");
  assert.equal(Maidenhead.fromLatLon(warsaw.lat, warsaw.lon, 4), "KO02");
  assert.equal(Maidenhead.fromLatLon(newYork.lat, newYork.lon, 6), "FN20xr");
  assert.equal(Maidenhead.fromLatLon(90, 180, 6), "RR99xx");
  assert.equal(Maidenhead.fromLatLon(-90, -180, 6), "AA00aa");
  const jo82 = Maidenhead.toLatLon("JO82");
  near(jo82.lat, 52.5, 1e-9); near(jo82.lon, 17.0, 1e-9);
  for (const loc of ["JO82", "jo82lk", "KO02mf", "FN20xr", "JO82lk34", "AA00aa", "RR99xx"]) {
    const c = Maidenhead.toLatLon(loc);
    assert.equal(Maidenhead.fromLatLon(c.lat, c.lon, loc.length).toUpperCase(), loc.toUpperCase());
  }
  assert.equal(Maidenhead.isValid("SP3ABC"), false);
  assert.equal(Maidenhead.isValid("JO8"), false);
  assert.equal(Maidenhead.isValid("jo82lk"), true);
  assert.equal(Maidenhead.toLatLon("ZZ00"), null);
});

test("ortodroma i rzut azymutalny", () => {
  const p = GreatCircle.path(warsaw, newYork);
  near(p.shortPathKm, 6854.2, 1); near(p.shortPathBearing, 300.9, 0.1);
  near(p.longPathKm, 2 * Math.PI * GreatCircle.EARTH_RADIUS_KM - 6854.2, 1); near(p.longPathBearing, 120.9, 0.1);
  assert.equal(GreatCircle.compassPoint(p.shortPathBearing), "WNW");
  assert.equal(GreatCircle.compassPoint(359), "N");
  const [x, y] = GreatCircle.azimuthalProject(warsaw, newYork);
  near(Math.hypot(x, y), 6854.2, 1);
  near(((Math.atan2(x, y) * 180) / Math.PI + 360) % 360, 300.9, 0.1);
  assert.equal(GreatCircle.azimuthalProject(warsaw, { lat: -warsaw.lat, lon: warsaw.lon - 180 }), null);
  assert.ok(parseWorldOutlines(data("world_path.txt")).length > 100);
});

test("wzory antenowe i linie zasilające", () => {
  const d = AntennaMath.dipole(14.175);
  near(d.total, 142.5 / 14.175, 0.02); near(d.leg, d.total / 2, 1e-12);
  const m = AntennaMath.moxon(14.15, 0.002);
  near(m.a, 7.72, 0.05); near(m.b, 1.18, 0.03); near(m.c, 0.2, 0.02); near(m.d, 1.44, 0.03); near(m.e, m.b + m.c + m.d, 1e-9);
  const e = AntennaMath.efhw(7.1);
  assert.deepEqual(e.harmonics.map((h) => h[1]), ["20m", "15m", "10m"]);
  near(e.counterpoise, 0.05 * AntennaMath.wavelength(7.1), 1e-9);
  const j = AntennaMath.jPole(146);
  near(j.longElement, 1.46, 0.02); near(j.stub, 0.49, 0.01);
  near(AntennaMath.loop(14.2).perimeter, (1005 * 0.3048) / 14.2, 1e-9);
  const yg = AntennaMath.yagi3(28.5);
  assert.ok(yg.reflector > yg.driven && yg.driven > yg.director);
  near(yg.driven, (468 * 0.3048) / 28.5, 1e-9);
  const g = FeedlineMath.gammaFromPower(100, 100 / 9);
  near(FeedlineMath.swr(g), 2, 1e-9); near(FeedlineMath.mismatchLossDb(g), 0.51, 0.01);
  near(FeedlineMath.swr(FeedlineMath.gammaFromImpedance(100, 0, 50)), 2, 1e-9);
  near(FeedlineMath.swr(FeedlineMath.gammaFromImpedance(50, 0, 50)), 1, 1e-9);
  near(FeedlineMath.returnLossDb(1 / 3), 9.54, 0.01);
  const r = FeedlineMath.radiated(100, 3, 2.15);
  near(r.txDbm, 50, 1e-9); near(r.atAntennaW, 50.1, 0.1); near(r.erpW, r.atAntennaW, 1e-6);
  const b = FeedlineMath.battery(20, 90, 1, 4, 25);
  near(b.averageA, 1.75, 1e-9); near(b.hours, 18 / 1.75, 1e-9);
  near(FeedlineMath.quarterWaveZ0(50, 100), 70.7, 0.1);
  near(FeedlineMath.stubs(7.1, 0.66).quarter, (AntennaMath.wavelength(7.1) * 0.66) / 4, 1e-9);
});

test("kable: interpolacja tłumienności", () => {
  const rg213 = COAX_DATABASE.find((c) => c.name.startsWith("RG213"));
  near(attenuationForFreq(rg213, 145), 8.2, 1e-9);
  near(attenuationForFreq(rg213, 75), 5.8, 1e-9);
  near(attenuationForFreq(rg213, 5), 0.9, 1e-9);
  assert.equal(attenuationForFreq(rg213, 2000), 10000);
});

test("beacony NCDXF zgodnie z oficjalnym harmonogramem", () => {
  const at = (mm, ss) => NcdxfBeacons.nowTransmitting(mm * 60 + ss).map((s) => s.beacon.call);
  assert.deepEqual(at(0, 0), ["4U1UN", "YV5B", "OA4B", "LU4AA", "CS3B"]);
  assert.deepEqual(at(0, 10), ["VE8AT", "4U1UN", "YV5B", "OA4B", "LU4AA"]);
  assert.deepEqual(at(2, 10), ["OH2B", "4X6TU", "5Z4B", "ZS6DN", "4S7B"]);
  assert.deepEqual(at(2, 50), ["YV5B", "OA4B", "LU4AA", "CS3B", "OH2B"]);
  assert.deepEqual(NcdxfBeacons.nowTransmitting(13 * 3600 + 3 * 60).map((s) => s.beacon.call), at(0, 0));
  assert.equal(NcdxfBeacons.nowTransmitting(0)[0].secondsLeft, 10);
  assert.equal(NcdxfBeacons.nowTransmitting(9)[0].secondsLeft, 1);
  const w6wx = NcdxfBeacons.BEACONS.find((x) => x.call === "W6WX");
  assert.equal(NcdxfBeacons.secondsUntil(w6wx, 0, 0), 20);
  assert.equal(NcdxfBeacons.secondsUntil(w6wx, 0, 25), 0);
  assert.equal(NcdxfBeacons.secondsUntil(w6wx, 0, 30), 170);
  assert.equal(NcdxfBeacons.secondsUntil(w6wx, 4, 0), 60);
});

test("pasma i znaki", () => {
  assert.equal(bandForKhz(14074), "20m");
  assert.equal(bandForKhz(5357), "60m");
  assert.equal(bandForKhz(11000), null);
  assert.equal(calculateBandFromFreq("abc"), "20m");
  for (const c of ["SP3WRO", "sp3wro", "SQ9ABC", "W1AW", "K1JT", "DL1ABC", "VK2ABC", "9A1A", "E73A", "4X1AB", "3Z0X", "3DA0RU",
    "SN100PZK", "HF0POL", "VP2EX", "SP3WRO/P", "SP3WRO/M", "DL/SP3WRO", "W1AW/4"]) assert.ok(Callsigns.isValidAmateur(c), c);
  for (const c of ["", "  ", "NOCALL", "N0CALL", "MYCALL", "JAN", "KOWALSKI", "TEST", "12345", "SP3", "SP3WRO//P", "/SP3WRO", "SP3-WRO"])
    assert.ok(!Callsigns.isValidAmateur(c), c);
  assert.equal(Callsigns.baseCall("DL/SP3WRO/P"), "SP3WRO");
});

const cty = CtyDatabase.parse(`DL,Fed. Rep. of Germany,230,EU,14,28,51.00,-10.00,-1.0,DA DB DC DD DF DG DH DJ DK DL DM DN DO DP DQ DR;
*IG9,African Italy,248,AF,33,37,35.67,-12.67,-1.0,IG9 IH9;
K,United States,291,NA,5,8,37.60,91.87,5.0,AA AB AC AD AE AF AG AI AJ AK K N W =4U1WB(5)[8] =AH0AB(3)[6];
KH6,Hawaii,110,OC,31,61,21.12,157.48,10.0,AH6 AH7 KH6 KH7 NH6 NH7 WH6 WH7 =K2GT =WB7N;
SP,Poland,269,EU,15,28,52.28,-18.67,-1.0,3Z HF SN SO SP SQ SR;
VK,Australia,150,OC,30,59,-23.70,-132.33,-10.0,AX VI VJ VK VL AX4[55] VI4[55] VJ4[55] VK4[55] VL4[55] =VI9POL =VK6TX[55];`);

test("baza DXCC cty.csv", () => {
  const pl = cty.resolve("SP3ABC");
  assert.equal(pl.name, "Poland"); assert.equal(pl.dxcc, 269);
  near(pl.lat, 52.28, 1e-9); near(pl.lon, 18.67, 1e-9); near(pl.utcOffsetHours, 1, 1e-9);
  near(cty.resolve("W1AW").lon, -91.87, 1e-9);
  near(cty.resolve("W1AW").utcOffsetHours, -5, 1e-9);
  near(cty.resolve("VK2ABC").utcOffsetHours, 10, 1e-9);
  assert.equal(cty.entities.length, 5);
  assert.equal(cty.resolve("KH6XYZ").name, "Hawaii");
  assert.equal(cty.resolve("K2ABC").name, "United States");
  assert.equal(cty.resolve("K2GT").name, "Hawaii");
  assert.equal(cty.resolve("HF0POL").name, "Poland");
  assert.equal(cty.resolve("VK4ABC").ituZone, 55);
  assert.equal(cty.resolve("VK2ABC").ituZone, 59);
  assert.equal(cty.resolve("4U1WB").cqZone, 5);
  assert.equal(cty.resolve("SP/DL1ABC").name, "Poland");
  assert.equal(cty.resolve("DL1ABC/SP").name, "Poland");
  assert.equal(cty.resolve("DL1ABC/P").name, "Fed. Rep. of Germany");
  assert.equal(cty.resolve("KH6/W1AW/P").name, "Hawaii");
  assert.equal(cty.resolve("W1AW/4").name, "United States");
  assert.equal(cty.resolve("SP3ABC/MM"), null);
  assert.equal(cty.resolve(""), null);
  assert.equal(cty.resolve("XX9ZZZ"), null);
  const full = CtyDatabase.parse(data("cty.csv"));
  assert.ok(full.entities.length > 300);
  assert.equal(full.resolve("SP3WRO").dxcc, 269);
  assert.equal(full.resolve("DL1ABC").dxcc, 230);
  assert.equal(full.resolve("W1AW").dxcc, 291);
});

test("ADIF: eksport", () => {
  const qso = makeQso({ id: "1", callsign: "sp3abc", datetimeUtc: "2026-10-20 18:45", frequency: "14195", band: "20m", mode: "ssb", rstSent: "59", rstRcvd: "57", comment: "Łódź, żółw" });
  const adif = Adif.generate([qso]);
  for (const s of ["<PROGRAMID:17>Ham Radio Toolkit", "<EOH>", "<CALL:6>SP3ABC ", "<QSO_DATE:8>20261020 ", "<TIME_ON:4>1845 ",
    "<FREQ:7>14.1950 ", "<BAND:3>20m ", "<MODE:3>SSB ", "<COMMENT:10>Lodz, zolw "]) assert.ok(adif.includes(s), s);
  assert.ok(adif.trimEnd().endsWith("<EOR>"));
  const ascii = Adif.generate([{ ...qso, comment: "ąćęłńóśźż ĄĆĘŁŃÓŚŹŻ ü\nnowa linia" }]);
  assert.ok([...ascii].every((c) => (c.charCodeAt(0) >= 32 && c.charCodeAt(0) <= 126) || c === "\n"));
  assert.ok(ascii.includes("acelnoszz ACELNOSZZ u nowa linia"));
  // Długości pól zgodne z treścią
  for (const m of ascii.matchAll(/<(\w+):(\d+)>/g)) {
    const next = ascii.charAt(m.index + m[0].length + Number(m[2]));
    assert.ok(next === " " || next === "\n", m[1]);
  }
});

test("ADIF: podtryby, POTA/WWFF i import z powrotem", () => {
  const qso = makeQso({ id: "1", callsign: "sp3abc", datetimeUtc: "2026-10-20 18:45", frequency: "14074", band: "20m", mode: "FT4",
    rstSent: "-10", rstRcvd: "-12", comment: "Łódź", name: "Jan", qth: "Poznań", gridSquare: "JO82lk", dxcc: 269, country: "Poland", huntedRef: "SP-0012" });
  const rec = Adif.record(qso, "SP9XYZ");
  for (const s of ["<MODE:4>MFSK <SUBMODE:3>FT4 ", "<POTA_REF:7>SP-0012 ", "<SIG:4>POTA <SIG_INFO:7>SP-0012 ", "<QTH:6>Poznan ", "<STATION_CALLSIGN:6>SP9XYZ "])
    assert.ok(rec.includes(s), s);
  assert.ok(Adif.record({ ...qso, huntedRef: "SPFF-0001" }).includes("<WWFF_REF:9>SPFF-0001 "));
  assert.ok(Adif.record({ ...qso, mode: "SSB" }).includes("<MODE:3>SSB "));
  const result = Adif.parse(Adif.generate([qso, { ...qso, id: "2", callsign: "DL1ABC", mode: "CW", huntedRef: "" }]));
  assert.equal(result.skipped, 0); assert.equal(result.entries.length, 2);
  const back = result.entries[0];
  assert.equal(back.callsign, "SP3ABC"); assert.equal(back.datetimeUtc, "2026-10-20 18:45"); assert.equal(back.frequency, "14074");
  assert.equal(back.band, "20m"); assert.equal(back.mode, "FT4"); assert.equal(back.name, "Jan"); assert.equal(back.gridSquare, "JO82LK");
  assert.equal(back.dxcc, 269); assert.equal(back.huntedRef, "SP-0012"); assert.equal(result.entries[1].mode, "CW");
});

test("ADIF: import z innego loggera", () => {
  const text = `Exported by SomeLogger
<adif_ver:5>3.1.0 <eoh>
<call:5>K1ABC <qso_date:8:D>20250102 <time_on:6>235959 <band:3>40M <mode:3>SSB <submode:3>LSB
<freq:5>7.185 <rst_sent:2>59 <rst_rcvd:2>57 <qrzcom_qso_upload_status:1>Y <eor>
<call:4>XX1X <qso_date:8>20250102 <mode:2>CW <eor>`;
  const r = Adif.parse(text);
  assert.equal(r.entries.length, 1); assert.equal(r.skipped, 1);
  const e = r.entries[0];
  assert.equal(e.datetimeUtc, "2025-01-02 23:59"); assert.equal(e.frequency, "7185"); assert.equal(e.band, "40m");
  assert.equal(e.mode, "SSB"); assert.equal(e.qrzUploaded, true);
});

test("statystyki i wyszukiwanie w logu", () => {
  const logs = [
    makeQso({ callsign: "SP3ABC", band: "20m", mode: "SSB", dxcc: 269, country: "Poland", huntedRef: "SP-0012", datetimeUtc: "2026-10-20 18:45" }),
    makeQso({ callsign: "SP3ABC/P", band: "40m", mode: "CW", dxcc: 269, country: "Poland", huntedRef: "SPFF-0001", datetimeUtc: "2026-10-21 10:00" }),
    makeQso({ callsign: "DL1ABC", band: "20m", mode: "FT8", dxcc: 230, country: "Germany", datetimeUtc: "2026-10-22 11:00" }),
    makeQso({ callsign: "XX9X", band: "20m", mode: "FT8", datetimeUtc: "2026-10-22 12:00" }),
  ];
  const s = LogStatistics.compute(logs);
  assert.equal(s.totalQso, 4); assert.equal(s.uniqueCalls, 3); assert.equal(s.dxccCount, 2); assert.equal(s.withoutDxcc, 1);
  assert.deepEqual([...s.potaRefs], ["SP-0012"]); assert.deepEqual([...s.wwffRefs], ["SPFF-0001"]);
  assert.deepEqual(s.bandModeCounts["20m"], { PHONE: 1, DIGI: 2 });
  assert.equal(s.dxccPerBand["20m"], 2);
  assert.equal(filterLogs(logs, "sp 40m cw").length, 1);
  assert.equal(filterLogs(logs, "2026-10-22").length, 2);
});

test("region IARU, Polska, Słońce", () => {
  assert.equal(IaruRegion.forCountry("US"), 2); assert.equal(IaruRegion.forCountry("JP"), 3); assert.equal(IaruRegion.forCountry("PL"), 1);
  assert.equal(IaruRegion.forPosition({ lat: 40, lon: -100 }), 2);
  assert.equal(IaruRegion.forPosition({ lat: 35, lon: 139 }), 3);
  assert.ok(inPoland({ lat: 52.71, lon: 16.41 })); assert.ok(!inPoland({ lat: 52.52, lon: 13.4 }));
  // 21 czerwca: deklinacja ok. +23,4°
  near(sunInfo(new Date(Date.UTC(2026, 5, 21, 12)), 52, 21).declinationDeg, 23.4, 0.3);
  assert.equal(formatDecimalHour(25.5), "01:30 UTC");
});

test("tłumaczenie opisów bandplanu i dane słoneczne", () => {
  const bp = JSON.parse(data("bandplan.json"));
  const en = JSON.parse(data("i18n/en.json"));
  const loc = new PhraseLocalizer(bp.phrases, (k) => en[k], "en");
  assert.equal(loc.localize("Tylko CW"), "CW only");
  assert.ok(!/[ąęłśżź]/.test(loc.localize(bp.regions[1][0].segments[1].description)));
  const solar = parseSolarXml(`<solar><solardata><updated> 31 Oct 2026 2200 GMT</updated><solarflux>142</solarflux><aindex>8</aindex>
    <kindex>2</kindex><sunspots>101</sunspots><calculatedconditions><band name="80m-40m" time="day">Fair</band>
    <band name="80m-40m" time="night">Good</band></calculatedconditions></solardata></solar>`);
  assert.equal(solar.solarFlux, "142"); assert.equal(solar.updated, "31 Oct 2026 2200 GMT");
  assert.equal(solar.hfConditions.length, 2); assert.equal(solar.hfConditions[1].condition, "Good");
});

test("format tekstów jak String.format w Androidzie", () => {
  assert.equal(format("%1$s  •  local time %2$s", "2026-10-31", "12:01"), "2026-10-31  •  local time 12:01");
  assert.equal(format("humidity %1$s%%", "60"), "humidity 60%");
  assert.equal(format("%1$d stations within %2$d km", 3, 50), "3 stations within 50 km");
  assert.equal(format("%.1f W", 50.123), "50.1 W");
});
