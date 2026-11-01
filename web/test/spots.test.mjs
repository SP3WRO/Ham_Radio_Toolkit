import { test } from "node:test";
import assert from "node:assert/strict";
import { parsePota, parseWwff, filterDxSpots, guessModeFromSpot, matchesMode, qsoFrequency, parseCsvLine } from "../public/js/core/spots.js";

test("spoty POTA i WWFF (dane z testu aplikacji)", () => {
  const [pota] = parsePota(`[{"spotId": 1, "activator": "w0ss", "frequency": "14074", "mode": "FT8", "reference": "US-12398",
    "parkName": null, "spotTime": "2026-10-20T20:22:17", "spotter": "VE7CC-#", "comments": "RBN -7 dB",
    "name": "Olathe Prairie", "latitude": 38.8834, "longitude": -94.8905, "extra": 9}]`);
  assert.equal(pota.activator, "W0SS");
  assert.equal(pota.frequencyKhz, 14074);
  assert.equal(pota.reference, "US-12398");
  assert.equal(pota.referenceName, "Olathe Prairie");
  assert.equal(pota.timeEpochSec, 1792527737);
  const [wwff] = parseWwff(`[{"id": 159230, "activator": "W7B", "frequency_khz": 21063, "mode": "CW", "reference": "KFF-1572",
    "reference_name": "Franklin Point", "remarks": "Re-spotted via RBN", "spotter": "N7OR",
    "latitude": 38.81392, "longitude": -76.51221, "spot_time": 1792528013}]`);
  assert.equal(wwff.program, "WWFF");
  assert.equal(wwff.frequencyKhz, 21063);
  assert.equal(wwff.timeEpochSec, 1792528013);
  assert.equal(parsePota(`[{"activator":"X","frequency":""}]`).length, 0);
});

test("DX Cluster: filtry i emisja ze spotu", () => {
  const spots = [
    { dx_call: "JA1ABC", de_call: "SP3WRO", frequency: 14074, time: "2026-10-31 12:00:00", info: "FT8 -12" },
    { dx_call: "K1XX", de_call: "DL1A", frequency: 14025.5, time: "x", info: "" },
    { dx_call: "VK2AA", de_call: "G4B", frequency: 7150, time: "x", info: "up 5" },
  ];
  assert.equal(filterDxSpots(spots, { band: "20M", mode: "ALL", query: "" }).length, 2);
  assert.equal(filterDxSpots(spots, { band: "ALL", mode: "CW", query: "" })[0].dx_call, "K1XX");
  assert.equal(filterDxSpots(spots, { band: "ALL", mode: "SSB", query: "" })[0].dx_call, "VK2AA");
  assert.equal(filterDxSpots(spots, { band: "ALL", mode: "ALL", query: "sp3" })[0].dx_call, "JA1ABC");
  assert.ok(matchesMode(14074, "FT4", "DIGI"));
  assert.equal(guessModeFromSpot(14025.5, ""), "CW");
  assert.equal(guessModeFromSpot(14200, ""), "SSB");
  assert.equal(guessModeFromSpot(14074, "ft8"), "FT8");
  assert.equal(qsoFrequency(14025.5), "14025.5");
  assert.equal(qsoFrequency(14074), "14074");
});

test("CSV zgodny z RFC 4180", () => {
  assert.deepEqual(parseCsvLine('SP-0001,"Park, ""Stary""",1, 52.1 ,16.2'), ["SP-0001", 'Park, "Stary"', "1", "52.1", "16.2"]);
});

test("serwer: obiekty do aktywacji w promieniu (bez internetu – podstawiony fetch)", async () => {
  const files = {
    "https://pota.app/all_parks_ext.csv": "reference,name,active,entityId,locationDesc,latitude,longitude,grid\n" +
      Array.from({ length: 150 }, (_, i) => `SP-${String(i).padStart(4, "0")},Park ${i},${i === 1 ? 0 : 1},269,PL,${52 + i / 100},${16 + i / 100},JO82`).join("\n"),
    "https://www.sotadata.org.uk/summitslist.csv": "Summits List\r\nSummitCode,AssociationName,RegionName,SummitName,AltM,AltFt,GridRef1,GridRef2,Longitude,Latitude\r\n" +
      Array.from({ length: 120 }, (_, i) => `SP/BZ-${i},Poland,Beskidy,"Szczyt, ${i}",1000,3280,,,${19 + i / 1000},${49.5}`).join("\r\n"),
    "https://wwff.co/wwff-data/wwff_directory.csv": "reference,status,name,latitude,longitude\n" +
      Array.from({ length: 220 }, (_, i) => `SPFF-${i},${i % 2 ? "active" : "deleted"},Rezerwat ${i},52.5,16.5`).join("\n"),
  };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => new Response(files[url] ?? "", { status: files[url] ? 200 : 404 });
  try {
    const { nearby } = await import("../server/activations.mjs");
    const r = await nearby(52.5, 16.5, 100, "test");
    assert.equal(r.programs.POTA, 149); // nieaktywny park pominięty
    assert.equal(r.programs.SOTA, 120);
    assert.equal(r.programs.WWFF, 110); // tylko "active"
    assert.ok(r.spots.some((s) => s.reference === "SP-0050"));
    assert.ok(!r.spots.some((s) => s.program === "SOTA")); // Beskidy ~350 km dalej
    assert.ok(r.spots.find((s) => s.program === "SOTA") === undefined);
    const far = await nearby(49.5, 19.05, 20, "test");
    assert.equal(far.spots.filter((s) => s.program === "SOTA").length, 120);
    assert.equal(far.spots.find((s) => s.program === "SOTA").name, "Szczyt, 0");
  } finally {
    globalThis.fetch = realFetch;
  }
});
