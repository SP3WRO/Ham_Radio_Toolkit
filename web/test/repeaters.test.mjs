// Te same przypadki co RepeaterGroupTest.kt i InternationalTest.kt w aplikacji
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  makeRepeater, groupRepeaters, normalizeModesPl, parseHearHam, parseRepeatermap, merge, parseRadioId, pickRadioId,
  geoBox, boxTiles, boxContains, parsePrzemiennikiNet, przemiennikiNetTimestamp, parsePrzemiennikiEu, buildRepeaterList, listKey,
} from "../public/js/core/repeaters.js";

const near = (a, b, tol = 1e-9) => assert.ok(Math.abs(a - b) <= tol, `${a} ≠ ${b}`);
const rep = (call, rx, lat, lon, km) => makeRepeater({ callsign: call, modes: ["FM"], userRx: rx, userTx: rx - 0.6, latitude: lat, longitude: lon, distanceKm: km });

test("grupy przemienników", () => {
  const g = groupRepeaters([rep("SR3WR", 438.825, 52.4, 16.92, 3.0), rep("SR3PO", 145.6, 52.1, 16.5, 5.0), rep("SR3WR", 145.6875, 52.401, 16.921, 3.1)]);
  assert.equal(g.length, 2);
  assert.equal(g[0].callsign, "SR3WR");
  assert.deepEqual(g[0].channels.map((c) => c.userRx), [145.6875, 438.825]);
  near(g[0].distanceKm, 3.0);
  assert.equal(groupRepeaters([rep("SR3XX", 145.6, 52.4, 16.9, 1), rep("SR3XX", 439, 52.9, 17.5, 60)]).length, 2);
});

test("emisje z polskich baz", () => {
  assert.deepEqual(normalizeModesPl(["DSTAR/DV+C4FM/Fusion"]), ["D-STAR", "C4FM"]);
  assert.deepEqual(normalizeModesPl(["FM", "DMR"]), ["FM", "DMR"]);
  assert.deepEqual(normalizeModesPl(["C4FM", "FM"]), ["C4FM", "FM"]);
  assert.deepEqual(normalizeModesPl(["DV"]), ["C4FM"]);
  assert.deepEqual(normalizeModesPl(["NFM"]), ["FM"]);
});

const hearHamJson = `[{"id":681,"callsign":"GB3DV","latitude":53.424857,"longitude":-1.19,"city":"Rotherham, SY England","group":"IRLP",
  "internet_node":"5130","mode":"FM","encode":"71.90","decode":"71.90","frequency":433025000,"offset":1600000,
  "description":"Internet-connected IRLP node owned by G0EPX.\\nhttps://qsl.net/gb3dv/","power":"unknown","operational":1,"restriction":""},
 {"id":9001,"callsign":"DB0XYZ","latitude":52.5,"longitude":13.4,"city":"Berlin","group":"DMR","mode":"DMR    ",
  "encode":"CC1","decode":"CC1","frequency":439562500,"offset":-7600000,"description":"","operational":1},
 {"id":9002,"callsign":"OE1ABC","latitude":48.2,"longitude":16.4,"city":"Wien","group":"","mode":"YSF/FM",
  "encode":"0","decode":"0","frequency":145650000,"offset":-600000,"description":"","operational":0},
 {"id":9003,"callsign":"","latitude":1.0,"longitude":1.0,"mode":"FM","frequency":145000000,"offset":0,"operational":1}]`;

test("HearHam", () => {
  const list = parseHearHam(hearHamJson);
  assert.equal(list.length, 3);
  near(list[0].userRx, 433.025); near(list[0].userTx, 434.625);
  assert.equal(list[0].requiredCtcss, "71.9");
  assert.ok(list[0].note.startsWith("IRLP • Internet-connected"));
  assert.deepEqual(list[1].modes, ["DMR"]); assert.equal(list[1].requiredCtcss, "-"); assert.equal(list[1].dmrColorCode, "1");
  near(list[1].userTx, 431.9625);
  assert.deepEqual(list[2].modes, ["C4FM", "FM"]); assert.equal(list[2].status, "off");
});

const repeatermapJson = `{"bounds":{},"locations":[{"qth":"Wildau","position":{"lon":13.61973,"lat":52.31613},"relais":[
  {"id":3623,"call":"DB0LDS","locator":"JO62TH","mode":"FM","rx":144.8,"tx":144.8,"remarks":"APRS Digi","qth":"Wildau","url":"http://www.db0lds.de","has_events":false},
  {"id":3604,"call":"DB0LDS","locator":"JO62TH","mode":"DMR","rx":431.9625,"tx":439.5625,"remarks":"CC:1, Brandmeister","qth":"Wildau","url":"http://www.db0lds.de/","has_events":false},
  {"id":3603,"call":"DB0LDS","locator":"JO62TH","mode":"FM","rx":431.9625,"tx":439.5625,"remarks":"CTCSS 67,0 (RX+TX),FM-N narrow, schmal,","qth":"Wildau","url":"http://www.db0lds.de/","has_events":false}
]}],"mode_detail_defs":{}}`;

test("repeatermap.de i scalanie", () => {
  const list = parseRepeatermap(repeatermapJson);
  assert.equal(list.length, 3);
  near(list[1].userRx, 439.5625); near(list[1].userTx, 431.9625);
  assert.equal(list[1].dmrColorCode, "1");
  assert.equal(list[2].requiredCtcss, "67.0");
  const merged = merge(list);
  assert.equal(merged.length, 2);
  const uhf = merged.find((r) => r.userRx > 400);
  assert.deepEqual(new Set(uhf.modes), new Set(["DMR", "FM"]));
  assert.equal(uhf.requiredCtcss, "67.0"); assert.equal(uhf.dmrColorCode, "1");

  const raw = parseRepeatermap(`{"bounds":[52,10,50,8],"locations":[
    {"qth":"Bovenden","position":{"lon":9.95603,"lat":51.58294},"relais":[
      {"id":492,"call":"DB0GOE","mode":"FM","rx":431.1,"tx":438.7,"remarks":null,"qth":"Bovenden","url":null,"mode_details":{"ctcss_rx":"88.5","open_1750":false}},
      {"id":1193,"call":"DB0GOE","mode":"DMR","rx":431.1,"tx":438.7,"remarks":"BM 262","qth":"Bovenden","url":null,"mode_details":[]}]},
    {"qth":"Bovenden","position":{"lon":9.95603,"lat":51.58294},"relais":[
      {"id":492,"call":"DB0GOE","mode":"FM","rx":431.1,"tx":438.7,"remarks":null,"qth":"Bovenden","url":null}]}]}`);
  assert.equal(raw.length, 3);
  const m2 = merge(raw);
  assert.equal(m2.length, 1); assert.deepEqual(m2[0].modes, ["FM", "DMR"]); assert.equal(m2[0].requiredCtcss, "88.5");

  const b = list.map((r) => ({ ...r, source: "HearHam", latitude: 0.5, requiredCtcss: "-", note: "Allstar" }));
  const across = merge(list, b);
  assert.equal(across.length, 2);
  assert.ok(across.every((r) => r.source === "repeatermap.de + HearHam" && r.latitude > 50 && r.note.includes("Allstar")));
});

test("RadioID i RepeaterBook", () => {
  const [[freq, d]] = parseRadioId(`{"count":1,"results":[{"callsign":"SR7LDA","city":"Lodz","color_code":1,"country":"Poland",
    "frequency":"438.45000","ipsc_network":"Brandmeister","offset":"-7.600","status":"on-air",
    "talkgroups":[{"description":"Poland","talkgroup":260,"timeslot":1},{"description":"Lodz","talkgroup":26071,"timeslot":2}],"trustee":["SQ7SCC"]}]}`);
  near(freq, 438.45);
  assert.equal(d.colorCode, "1"); assert.equal(d.network, "Brandmeister");
  assert.deepEqual(d.talkgroups, ["TS1: 260 (Poland)", "TS2: 26071 (Lodz)"]); assert.equal(d.trustee, "SQ7SCC");
  assert.equal(pickRadioId([[438.45, d]], 438.45), d);
});

test("kafelki obszaru", () => {
  const box = geoBox(52.4, 16.9, 210);
  const tiles = boxTiles(box, 2);
  assert.ok(tiles.every((t) => t.north - t.south <= 2 + 1e-9 && t.east - t.west <= 2 + 1e-9));
  for (const lat of [box.south, 52.4, box.north - 1e-6]) for (const lon of [box.west, 16.9, box.east - 1e-6]) assert.ok(tiles.some((t) => boxContains(t, lat, lon)));
});

const RPTS = JSON.stringify({ masks: { status: [{ type: "status", mask: "1" }] }, repeaters: [
  { id: "1", qra: "SR3DAB", status: "1", visible: "1", qrg_tx: 439.05, qrg_rx: 431.45, band: 16384, mode_txt: "FM+DSTAR/DV+C4FM/Fusion",
    locator: "JO82", qth: "Zalesie", lat: "52.1", lng: "16.9", loclat: "52.0", loclng: "16.8", ctcss_rx: "71.9", ctcss_tx: "79.7", deleted: "0" },
  { id: "2", qra: "SR3OFF", status: "2", visible: "1", qrg_tx: 145.6625, qrg_rx: 145.0625, mode_txt: "", locator: "JO91", qth: null,
    latitude: null, lat: null, lng: null, loclat: "51.5", loclng: "17.0", ctcss_rx: null, deleted: "0" },
  { id: "3", qra: "SR3NOPOS", status: "1", visible: "1", qrg_tx: 145.6, qrg_rx: 145.0, lat: null, lng: null, loclat: null, loclng: null },
  { id: "4", qra: "SR3DEL", status: "1", visible: "1", qrg_tx: 145.6, qrg_rx: 145.0, lat: "52.0", lng: "17.0", deleted: "1" },
  { id: "5", qra: "SR3PLAN", status: "32", visible: "1", qrg_tx: 145.6, qrg_rx: 145.0, lat: "52.0", lng: "17.0", ctcss_rx: "0" },
], generated: "2026-10-25 15:51:00" });

test("przemienniki.net (rpts.json) i przemienniki.eu", () => {
  const list = Object.fromEntries(parsePrzemiennikiNet(RPTS).map((r) => [r.callsign, r]));
  assert.deepEqual(Object.keys(list).sort(), ["SR3DAB", "SR3OFF", "SR3PLAN"]);
  const dab = list.SR3DAB;
  near(dab.userRx, 439.05); near(dab.userTx, 431.45);
  assert.deepEqual(dab.modes, ["FM", "D-STAR", "C4FM"]);
  assert.equal(dab.requiredCtcss, "71.9"); assert.equal(dab.status, "working"); near(dab.latitude, 52.1);
  // Bez dokładnej pozycji – środek lokatora; bez emisji – FM; wyłączony nie jest "working"
  const off = list.SR3OFF;
  near(off.latitude, 51.5); assert.equal(off.qth, "JO91"); assert.deepEqual(off.modes, ["FM"]);
  assert.equal(off.requiredCtcss, "-"); assert.equal(off.status, "off");
  assert.equal(list.SR3PLAN.status, "planned"); assert.equal(list.SR3PLAN.requiredCtcss, "-");
  assert.equal(przemiennikiNetTimestamp(`<?xml version="1.0" encoding="UTF-8"?><timestamp>2026-10-25 14:43:54</timestamp>`), "2026-10-25 14:43:54");
  assert.equal(przemiennikiNetTimestamp("<html>błąd</html>"), null);
  const eu = parsePrzemiennikiEu(`[{"callsign":"SR3PO","modes":["FM"],"tx_frequency":145.6,"rx_frequency":145.0,"rx_ctcss":false,"status":"","coordinates":[52.4,16.9],"qth":"Poznań"},
    {"callsign":"SR3NO","modes":["FM"],"coordinates":[]}]`);
  assert.equal(eu.length, 1); assert.equal(eu[0].requiredCtcss, "-"); assert.equal(eu[0].status, "working");
});

test("lista ekranu i najbliższy przemiennik", () => {
  const db = [rep("SR3WR", 145.6875, 52.718, 16.374, 0), rep("SR3WR", 438.825, 52.718, 16.374, 0), rep("SR3PO", 145.6, 52.4, 16.9, 0),
    makeRepeater({ callsign: "SR3DMR", modes: ["DMR"], userRx: 439.1, latitude: 52.5, longitude: 16.6 })];
  const pos = { lat: 52.7122, lon: 16.4154 };
  const r = buildRepeaterList(db, pos, { radiusKm: 50, band: "ALL", mode: "ALL", onlyWorking: false });
  assert.equal(r.nearest.callsign, "SR3WR");
  assert.equal(r.nearest.channels.length, 2);
  assert.deepEqual(r.groups.map((g) => g.callsign), ["SR3WR", "SR3DMR", "SR3PO"]);
  assert.equal(buildRepeaterList(db, pos, { radiusKm: 50, band: "70CM", mode: "DMR", onlyWorking: false }).groups[0].callsign, "SR3DMR");
  assert.equal(new Set(r.groups.map((g) => g.listKey)).size, r.groups.length);
  assert.ok(listKey(db[0]).startsWith("SR3WR_145.6875"));
});

test("serwer: przemienniki.net i świat (podstawiony fetch)", async () => {
  const realFetch = globalThis.fetch;
  const hh = JSON.parse(hearHamJson);
  const many = Array.from({ length: 1200 }, (_, i) => ({ ...hh[0], id: i, callsign: `GB${i}XX`, latitude: 52.3 + i / 10000, longitude: 13.6 }));
  const files = {
    "https://przemienniki.net/export/timestamp.xml/": "<timestamp>2026-10-25 14:43:54</timestamp>",
    "https://przemienniki.net/export/rpts.json/": RPTS,
    "https://hearham.com/api/repeaters/v1": JSON.stringify(many),
  };
  /** @type {string[]} */
  const urls = [];
  globalThis.fetch = async (url) => {
    urls.push(String(url));
    if (String(url).startsWith("https://www.repeatermap.de/")) return new Response(repeatermapJson, { status: 200 });
    return new Response(files[url] ?? "", { status: files[url] ? 200 : 404 });
  };
  try {
    const { repeaters } = await import("../server/repeaters.mjs");
    const pl = await repeaters("przemienniki_net", null, null, "test");
    assert.equal(pl.length, 3); assert.equal(pl[0].source, "przemienniki.net");
    // Druga prośba tego samego dnia – bez pytania serwisu
    await repeaters("przemienniki_net", null, null, "test");
    assert.equal(urls.filter((u) => u.includes("przemienniki.net")).length, 2);
    const world = await repeaters("international", 52.31, 13.62, "test");
    assert.ok(world.some((r) => r.callsign === "DB0LDS" && r.source === "repeatermap.de"));
    assert.ok(world.filter((r) => r.source === "HearHam").length >= 1000);
    await assert.rejects(repeaters("hearham", null, null, "test"));
  } finally {
    globalThis.fetch = realFetch;
  }
});
