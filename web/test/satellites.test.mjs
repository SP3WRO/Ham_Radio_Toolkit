// Serwerowe /api/satellites z podstawionym fetch: TLE z zapasowego źródła, skrócone dane SatNOGS i AMSAT
import { test } from "node:test";
import assert from "node:assert/strict";

const TLE = Array.from({ length: 12 }, (_, i) => `SAT-${i}\n1 ${String(40000 + i)}U 98067A   26300.50000000  .00001000  00000-0  10000-4 0  9990\n2 ${String(40000 + i)}  51.6412 128.9321 0005432  32.1234 328.0987 15.49812345    10`).join("\n");

test("serwer – dane satelitów", async () => {
  const realFetch = globalThis.fetch;
  /** @type {string[]} */
  const urls = [];
  globalThis.fetch = /** @type {any} */ (async (url) => {
    const u = String(url);
    urls.push(u);
    if (u.includes("amsat.org")) return new Response("błąd", { status: 503 });
    if (u.includes("celestrak")) return new Response(TLE);
    if (u.includes("satnogs")) return new Response(JSON.stringify([
      { norad_cat_id: 40001, alive: true, status: "active", mode: "FM", uplink_low: 145850000, downlink_low: 436795000, description: "FM", baud: 0 },
      { norad_cat_id: 40002, alive: false, status: "dead", mode: "CW", uplink_low: null, downlink_low: 145900000, description: "" },
      { norad_cat_id: null, alive: true, status: "active" },
    ]));
    if (u.includes("amateur-satellite-database")) return new Response(JSON.stringify([{ name: "SAT-3", norad_id: 40003, uplink: "435.250", downlink: "145.960", mode: "FM", extra: 1 }]));
    return new Response("", { status: 404 });
  });
  try {
    const { satellites } = await import("../server/satellites.mjs");
    const r = await satellites("test");
    assert.equal(r.tles.length, 12);
    assert.deepEqual(r.satnogs.active, ["40001"]);
    assert.deepEqual(r.satnogs.byNorad["40002"], [{ mode: "CW", up: null, down: 145900000, desc: "" }]);
    assert.deepEqual(r.amsat, [{ name: "SAT-3", norad: "40003", uplink: "435.250", downlink: "145.960", beacon: null, mode: "FM", callsign: null }]);
    const n = urls.length;
    await satellites("test");
    assert.equal(urls.length, n, "druga odpowiedź z pamięci");
  } finally {
    globalThis.fetch = realFetch;
  }
});
