// Port AwardSourcesTest.kt (public_events.json, public_bandmap.json) + serwerowe /api/awards z podstawionym fetch
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseAwardEvents, parseBandmap, activeSpots, followedTags, awardTitle, awardOrganizers } from "../public/js/core/awards.js";

const utc = (s) => Date.parse(`${s.replace(" ", "T")}:00Z`);
const EVENTS = JSON.stringify({ generated_at: "2026-10-31T12:00:01+00:00", total_events: 4, events: [
  { id: 285, title: "YL Power! 2027", title_global: null, url: "https://radiodyplom.pl/ses/yl-power-2027", status: "upcoming",
    status_localized: "nadchodząca", start_date: "2027-03-01 00:00:00", end_date: "2027-03-08 23:59:00", organizers: "SP0PYL", organizers_global: null },
  { id: 183, title: "HF10SQ8W - 10 lat aktywności SQ8W", title_global: "HF10SQ8W - 10 Years of SQ8W Activity", url: "https://radiodyplom.pl/ses/hf10sq8w",
    status: "ongoing", start_date: "2026-03-16 00:00:00", end_date: "2026-12-30 00:00:00", organizers: "Wojciech SQ8W", organizers_global: "W. SQ8W" },
  { id: 100, title: "Zakończona", url: "https://radiodyplom.pl/ses/x", status: "ended", start_date: "2026-01-01 00:00:00", end_date: "2026-01-02 00:00:00" },
  { id: 101, title: "Bez końca", status: "ongoing", start_date: "2026-01-01 00:00:00", end_date: null },
] });

test("public_events.json", () => {
  const events = parseAwardEvents(EVENTS);
  assert.deepEqual(events.map((e) => e.id), ["183", "285"]);
  const [hf, yl] = events;
  assert.equal(hf.startMs, utc("2026-03-16 00:00"));
  assert.equal(hf.endMs, utc("2026-12-30 00:01"));
  assert.equal(awardTitle(hf, true), "HF10SQ8W - 10 lat aktywności SQ8W");
  assert.equal(awardTitle(hf, false), "HF10SQ8W - 10 Years of SQ8W Activity");
  assert.equal(awardOrganizers(hf, false), "W. SQ8W");
  assert.equal(awardTitle(yl, false), "YL Power! 2027");
  assert.equal(awardOrganizers(yl, false), "SP0PYL");
  assert.equal(yl.url, "https://radiodyplom.pl/ses/yl-power-2027");
  assert.ok(utc("2027-03-08 23:59") < yl.endMs && utc("2027-03-09 00:00") >= yl.endMs);
});

const BANDMAP = JSON.stringify({ spots: [
  { callsign: "sp5rzl", band: "70cm", mode: "C4FM", frequency_khz: 432200, time_on_display: "14:49", minutes_ago: 0, primary_ses_id: 346,
    ses_list: [{ id: 346, title: "Szalone Lata 90-00", points_info: "(+1 p.)" }, { id: 183, title: "HF10SQ8W", points_info: "" }] },
  { callsign: "SP9ARX", band: "40m", mode: "SSB", frequency_khz: 7177.5, time_on_display: "14:43", minutes_ago: 6, primary_ses_id: 346,
    ses_list: [{ id: 346, title: "Szalone Lata 90-00", points_info: "(+1 p.)" }] },
  { callsign: "SP3OLD", band: "20m", mode: "CW", frequency_khz: null, time_on_display: "14:21", minutes_ago: 28, ses_list: [] },
], window_minutes: 30, generated_utc: "14:50:01", count: 3 });

test("public_bandmap.json, okno 30 minut i stacje obserwowanych akcji", () => {
  const spots = parseBandmap(BANDMAP);
  assert.deepEqual(spots.map((s) => s.callsign), ["SP5RZL", "SP9ARX", "SP3OLD"]);
  assert.equal(spots[0].frequencyKhz, 432200);
  assert.equal(spots[1].frequencyKhz, 7177.5);
  assert.equal(spots[2].frequencyKhz, null);
  assert.equal(spots[0].timeUtc, "14:49");
  assert.deepEqual(spots[0].events.map((e) => e.id), ["346", "183"]);
  assert.equal(activeSpots(spots, 2 * 60_000).length, 3);
  assert.deepEqual(activeSpots(spots, 3 * 60_000).map((s) => s.callsign), ["SP5RZL", "SP9ARX"]);
  assert.equal(activeSpots(spots, 31 * 60_000).length, 0);
  const tags = followedTags(spots, new Set(["346"]));
  assert.deepEqual([...tags.keys()], ["SP5RZL", "SP9ARX"]);
  assert.deepEqual(tags.get("SP5RZL"), ["Szalone Lata 90-00 (+1 p.)"]);
  assert.deepEqual(followedTags(spots, new Set(["183"])).get("SP5RZL"), ["HF10SQ8W"]);
  assert.equal(followedTags(spots, new Set()).size, 0);
});

test("serwer – tylko pliki publiczne radiodyplom.pl", async () => {
  const realFetch = globalThis.fetch;
  /** @type {string[]} */
  const urls = [];
  globalThis.fetch = /** @type {any} */ (async (url) => {
    const u = String(url);
    urls.push(u);
    const body = u.endsWith("/public_events.json") ? EVENTS : u.endsWith("/public_bandmap.json") ? BANDMAP : "";
    return new Response(body, { status: body ? 200 : 404 });
  });
  try {
    const { awardEvents, awardBandmap } = await import("../server/awards.mjs");
    const r = await awardEvents("test");
    assert.deepEqual(r.events.map((e) => e.id), ["183", "285"]);
    assert.ok(r.fetchedAt && !r.error);
    const b = await awardBandmap("test");
    assert.equal(b?.spots.length, 3);
    // Druga prośba w ciągu 5 minut – z kopii
    await awardBandmap("test");
    assert.deepEqual(urls, ["https://radiodyplom.pl/public_events.json", "https://radiodyplom.pl/public_bandmap.json"]);
  } finally {
    globalThis.fetch = realFetch;
  }
});
