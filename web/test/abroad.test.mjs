// Port InternationalTest.ceptPrefixes + serwerowe /api/country z podstawionym fetch
import { test } from "node:test";
import assert from "node:assert/strict";
import { ceptAdvice, ceptFind, flagOf, localizedLicences } from "../public/js/core/cept.js";

test("prefiksy CEPT", () => {
  const call = (iso, adminArea = "", position = null) => ceptAdvice({ iso, adminArea, position }, "sp3wro").callsign;
  assert.equal(call("DE"), "DL/SP3WRO");
  assert.equal(call("AT"), "OE/SP3WRO");
  assert.equal(call("CH"), "HB9/SP3WRO");
  assert.equal(call("GB", "England"), "M/SP3WRO");
  assert.equal(call("GB", "Scotland"), "MM/SP3WRO");
  assert.equal(call("IM"), "MD/SP3WRO");
  assert.equal(call("US", "California"), "W6/SP3WRO");
  assert.equal(call("US", "Hawaii"), "KH6/SP3WRO");
  assert.equal(call("PR"), "KP4/SP3WRO");
  assert.equal(call("CA", "British Columbia"), "VE7/SP3WRO");
  assert.equal(call("PT", "", { lat: 37.74, lon: -25.67 }), "CT8/SP3WRO");
  assert.equal(call("PT", "", { lat: 32.65, lon: -16.91 }), "CT9/SP3WRO");
  assert.equal(call("PT", "", { lat: 38.72, lon: -9.14 }), "CT7/SP3WRO");
  assert.equal(call("FR", "", { lat: 41.92, lon: 8.74 }), "TK/SP3WRO");
  assert.equal(call("AX"), "OH0/SP3WRO");
  assert.equal(call("PE"), "SP3WRO/OA");
  assert.equal(call("PL"), "SP3WRO");
  assert.equal(call("RU"), null);
  assert.equal(call("JP"), null);
  assert.equal(ceptAdvice({ iso: "DE" }, "NOCALL").callsign, null);
  assert.equal(ceptFind("AU")?.note, "cept_note_au");
  assert.equal(ceptFind("US")?.cept, false);
  assert.equal(flagOf("pl"), "🇵🇱");
  assert.equal(localizedLicences("1 i 2 (dawniej także 3)", (k) => ({ cept_and: "and", cept_formerly_also: "formerly also" })[k] ?? k), "1 and 2 (formerly also 3)");
});

test("serwer – kraj z pozycji", async () => {
  const realFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = /** @type {any} */ (async (url) => {
    calls++;
    assert.match(String(url), /nominatim\.openstreetmap\.org\/reverse/);
    return new Response(JSON.stringify(String(url).includes("lat=0.0000") ? { error: "Unable to geocode" } : { address: { country_code: "us", state: "California" } }));
  });
  try {
    const { countryAt } = await import("../server/country.mjs");
    assert.deepEqual(await countryAt(37.77, -122.42, "test"), { iso: "US", adminArea: "California" });
    assert.deepEqual(await countryAt(37.78, -122.41, "test"), { iso: "US", adminArea: "California" });
    assert.equal(calls, 1);
    assert.deepEqual(await countryAt(0, 0, "test"), { iso: null, adminArea: "" });
  } finally {
    globalThis.fetch = realFetch;
  }
});
