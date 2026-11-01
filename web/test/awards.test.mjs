// Port AwardSourcesTest.kt + serwerowe /api/awards z podstawionym fetch
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseRadiodyplomIcs, parseAwardPage, repeatLimit, computeAwardProgress } from "../public/js/core/awards.js";

const utc = (s) => Date.parse(`${s.replace(" ", "T")}:00Z`);
const ICS = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//RadioDyplom//SES Calendar//EN\r\n" +
        "BEGIN:VEVENT\r\nUID:ses-239-20250101@radiodyplom.pl\r\nDTSTART;VALUE=DATE:20250101\r\nDTEND;VALUE=DATE:20270102\r\n" +
        "SUMMARY:E7POTA\r\n" +
        "DESCRIPTION:[b][b]Let's celebrate our POTA activities with a special call sign [url=https://e7pota.ba/]E7 POTA[/url]\uFEFF !!![/b]\r\n" +
        "URL:https://radiodyplom.pl/ses/239\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:ses-345-20261015@radiodyplom.pl\r\nDTSTART;VALUE=DATE:20261015\r\nDTEND;VALUE=DATE:20261111\r\n" +
        "SUMMARY:70. rocznica rewolucji 1956 na Węgrzech\r\nDESCRIPTION: Budapeszt\\, 1956 \r\n" +
        "URL:https://radiodyplom.pl/ses/345\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:ses-999-20261101@radiodyplom.pl\r\nDTSTART;VALUE=DATE:20261101\r\nSUMMARY:Bez URL i końca\r\nEND:VEVENT\r\n" +
        "END:VCALENDAR\r\n";

const card = (call, points, type = null) => `
        <div class="column is-narrow">
            <!-- Górny róg: Znak i powiadomienie 1x -->
            <div class="is-flex">
                <a href="https://radiodyplom.pl/sesStation.php?ses_id=345&ses_callsign=${call}"
                   class="has-text-weight-bold is-size-5" style="font-family: var(--font-mono);">
                    ${call}                        </a>
            </div>
            <!-- Dolny róg: Punkty i Typ stacji -->
            <div class="ses-station-meta">
                <span class="tag is-info is-light">
                    <strong class="ses-station-points">${points}</strong>&nbsp;
                    punktów                        </span>
                ${type != null ? `<span class="tag is-link is-light"><span class="material-symbols-outlined mr-1" style="font-size: 14px;">badge</span> ${type}                        </span>` : ""}
            </div>
        </div>
    `;
const rankingRow = (rank, call, points, qsos) => `
        <tr class="ranking-row " style="">
            <td class="has-text-centered">${rank}.</td>
            <td class="has-text-weight-bold" style="font-family: var(--font-mono);"><a href="/sesParticipant.php?ses_id=345&callsign=${call}">${call}</a></td>
            <td class="has-text-right">${points}</td>
            <td class="has-text-right">${qsos}</td>
            <td class="has-text-centered"></td>
        </tr>
    `;
const PAGE = `
        <html><head><meta property="og:description" content="70. rocznica rewolucji 1956 na Węgrzech" /></head><body>
        <style>.ses-public-rules, .ses-rule-card { min-width: 0; }</style>
        <div class="ses-detail-info"> <span class="ses-detail-label">Organizatorzy:</span> <span class="ses-detail-value"> <b>HA5KDR</b>, <b>SP5ZIM</b> </span> </div>
        <div class="ses-detail-container"><div class="ses-description"><strong>Dyplom</strong><br />
        Punkty przyznają stacje HA1956&amp;co<br /></div></div>
        <!-- Lista stacji -->
        <div class="metryczka-stations">
        ${card("HA1956BA", 10, "Okolicznościowa")}${card("HA2GY", 10, "Klubowa")}${card("3Z45MDY", 2, "Indywidualna")}${card("HA2GY", 10)}
        </div>
        <div class="ses-public-rules mt-5 mb-4"><h2 class="title is-4">Warunki</h2>
        <section class="ses-rule-card"><h3>Czas trwania akcji</h3><div class="ses-rule-dates"><div>Od<time>2026-10-15 00:00:00 UTC</time></div><div>Do<time>2026-11-10 21:59:00 UTC</time></div></div></section>
        <section class="ses-rule-card"><h3>Kiedy kolejna łączność daje punkty?</h3><p>Z każdą stacją organizatora uczestnik ma osobny limit dla każdej pary: pasmo + emisja. Jedna łączność w tym limicie dziennie. Limit odnawia się przy zmianie daty UTC.</p></section>
        <section class="ses-rule-card"><h3>Grupowanie emisji</h3><div class="ses-mode-group"><p>Dopuszczone w tej akcji emisje cyfrowe są rozliczane wspólnie jako DIGI.</p><div class="ses-chip-list"><span class="mode-tag">FT8</span><span class="mode-tag">FT4</span><span class="mode-tag">RTTY</span></div></div></section>
        <div class="ses-public-rules"><h3 class="title is-5">Harmonogram mnożników czasowych</h3></div></div>
        <div class="metryczka-awards">
        <div class="award-card" style="margin-top: 32px;" id="dyplom1"> <h3 class="award-title"> <span class="material-symbols-outlined check-award-icon"> workspace_premium </span> BUDAPEST 1956 - EU </h3>
        <div class="award-requirements"><div class="requirement-section"><p>Należy zdobyć minimum:</p> <div style="display: flex;"> <strong style="font-size: 2em;">35</strong> punktów </div>
        <span class="band-tag">40m</span><span class="band-tag">20m</span><span class="mode-tag">SSB</span><span class="mode-tag">CW</span><span class="mode-tag">DIGI</span></div>
        <div class="requirement-section"><h4>Dozwolone regiony geograficzne</h4><div style="margin-bottom: 10px;"><strong>Kontynenty:</strong> <span class="tag is-info is-light mr-1 mb-1">Europa</span></div></div>
        <div class="requirement-section"><h4>Niedozwolone regiony geograficzne</h4><div><strong>Kraje:</strong> <span class="tag is-danger is-light mr-1 mb-1">HA (Hungary)</span></div></div></div> </div>
        <div class="award-card" style="margin-top: 32px;" id="dyplom2"> <h3 class="award-title"> <span class="material-symbols-outlined"> workspace_premium </span> Dyplom dla SP </h3>
        <div class="award-requirements"><div class="requirement-section"><p>Należy zdobyć minimum:</p> <div> <strong style="font-size: 2em;">70</strong> punktów </div></div>
        <div class="requirement-section"><h4>Dozwolone regiony geograficzne</h4><div><strong>Kraje:</strong> <span class="tag is-success is-light mr-1 mb-1">SP (Poland)</span></div></div></div> </div>
        </div>
        <span>Ranking uczestników całej akcji dyplomowej</span>
        <span class="tag is-light is-info"> Ostatnia aktualizacja rankingu (cache): </span> <span class="tag is-info has-text-weight-bold">2026-10-26 22:26:33 UTC</span>
        <table><tbody>
        ${rankingRow(1, "9A4ZM", 204, 37)}${rankingRow(2, "SP3WRO", 40, 4)}${rankingRow(3, "SP3WRO/P", 10, 1)}
        </tbody></table></body></html>
    `;

test("kalendarz ICS radiodyplom.pl", () => {
  const events = parseRadiodyplomIcs(ICS);
  assert.deepEqual(events.map((e) => e.id), ["239", "345", "999"]);
  const [e7, hu, last] = events;
  assert.equal(e7.name, "E7POTA");
  assert.equal(e7.startMs, utc("2025-01-01 00:00"));
  assert.equal(e7.endMs, utc("2027-01-02 00:00"));
  assert.equal(e7.description, "Let's celebrate our POTA activities with a special call sign E7 POTA !!!");
  assert.equal(hu.description, "Budapeszt, 1956");
  assert.ok(utc("2026-11-10 23:59") < hu.endMs && utc("2026-11-11 00:00") >= hu.endMs);
  assert.equal(last.endMs, utc("2026-11-02 00:00"));
  assert.equal(last.url, "https://radiodyplom.pl/ses/999");
});

test("strona akcji", () => {
  const { details: d, ranking } = parseAwardPage(PAGE, "345", 1);
  assert.equal(d.name, "70. rocznica rewolucji 1956 na Węgrzech");
  assert.equal(d.organizers, "HA5KDR, SP5ZIM");
  assert.equal(d.description, "Dyplom\nPunkty przyznają stacje HA1956&co");
  assert.equal(d.startMs, utc("2026-10-15 00:00"));
  assert.equal(d.endMs, utc("2026-11-10 22:00"));
  assert.equal(d.repeatLimit, "BAND_MODE_DAILY");
  assert.deepEqual(d.digiGroup, ["FT8", "FT4", "RTTY"]);
  assert.ok(d.hasTimeMultipliers);
  assert.deepEqual(d.stations, [{ callsign: "HA1956BA", points: 10, type: "Okolicznościowa" }, { callsign: "HA2GY", points: 10, type: "Klubowa" },
    { callsign: "3Z45MDY", points: 2, type: "Indywidualna" }]);
  assert.equal(d.awards.length, 2);
  const eu = d.awards[0];
  assert.equal(eu.title, "BUDAPEST 1956 - EU");
  assert.equal(eu.minPoints, 35);
  assert.deepEqual(eu.bands, ["40m", "20m"]);
  assert.deepEqual(eu.modes, ["SSB", "CW", "DIGI"]);
  assert.deepEqual(eu.allowedRegions, ["Europa"]);
  assert.deepEqual(eu.excludedRegions, ["HA (Hungary)"]);
  assert.deepEqual(d.awards[1].allowedRegions, ["SP (Poland)"]);
  assert.equal(d.awards[1].minPoints, 70);
  assert.equal(d.participants, 3);
  assert.equal(d.rankingUpdated, "2026-10-26 22:26:33 UTC");
  assert.deepEqual(ranking.SP3WRO, { rank: 2, points: 40, qsos: 4 });
  assert.equal(ranking.SP9XYZ, undefined);
  const empty = parseAwardPage("<html><body>Błąd 404</body></html>", "1").details;
  assert.equal(empty.stations.length + empty.awards.length, 0);
  assert.equal(empty.startMs, null);
});

test("limit powtórzeń z opisu", () => {
  assert.equal(repeatLimit("Z każdą stacją organizatora uczestnik ma osobny limit dla każdej pary: pasmo + emisja. Tylko jedno zaliczenie w całej akcji."), "BAND_MODE");
  assert.equal(repeatLimit("Osobny limit dla każdego pasma."), "BAND");
  assert.equal(repeatLimit("Z każdą stacją tylko jedno zaliczenie w całej akcji."), "STATION");
  assert.equal(repeatLimit(""), "BAND_MODE");
});

const qso = (callsign, datetimeUtc, band, mode) => ({ callsign, datetimeUtc, band, mode });
const DETAILS = {
  eventId: "345", name: "", startMs: utc("2026-10-15 00:00"), endMs: utc("2026-11-10 22:00"), organizers: "", description: "", repeatRule: "",
  repeatLimit: /** @type {const} */ ("BAND_MODE"), digiGroup: ["FT8", "FT4", "RTTY"], hasTimeMultipliers: false,
  stations: [{ callsign: "HA1956BA", points: 10, type: "" }, { callsign: "HA2GY", points: 5, type: "" }],
  awards: [{ title: "EU", minPoints: 20, unit: "punktów", bands: ["40m", "20m"], modes: ["SSB", "CW", "DIGI"], allowedRegions: [], excludedRegions: [] },
    { title: "Tylko CW", minPoints: 15, unit: "punktów", bands: [], modes: ["CW"], allowedRegions: [], excludedRegions: [] }],
  participants: 0, rankingUpdated: "", rankingCallsign: "", myRanking: null, fetchedAtMs: 0,
};

test("punkty z własnego logu", () => {
  const logs = [
    qso("HA1956BA", "2026-10-16 10:00", "40m", "SSB"), qso("HA1956BA", "2026-10-16 10:05", "40m", "USB"),
    qso("HA1956BA", "2026-10-17 10:00", "40m", "FT8"), qso("HA1956BA", "2026-10-17 11:00", "40m", "FT4"),
    qso("HA2GY/P", "2026-10-18 10:00", "20m", "CW"), qso("HA2GY", "2026-10-18 10:00", "80m", "CW"),
    qso("HA1956BA", "2026-10-14 23:59", "20m", "CW"), qso("SP3ABC", "2026-10-18 10:00", "20m", "CW"),
  ];
  const p = computeAwardProgress(DETAILS, logs);
  assert.equal(p.rules[0].points, 25);
  assert.equal(p.rules[0].countedQsos, 3);
  assert.ok(p.rules[0].achieved);
  assert.equal(p.rules[1].points, 10);
  assert.ok(!p.rules[1].achieved);
  assert.deepEqual(p.workedStations, { HA1956BA: 4, HA2GY: 2 });
  assert.equal(p.matchingQsos, 6);
  const log = [qso("HA1956BA", "2026-10-16 10:00", "40m", "SSB"), qso("HA1956BA", "2026-10-16 23:59", "40m", "SSB"), qso("HA1956BA", "2026-10-17 00:00", "40m", "SSB")];
  assert.equal(computeAwardProgress({ ...DETAILS, repeatLimit: "BAND_MODE_DAILY" }, log).rules[0].points, 20);
  assert.equal(computeAwardProgress({ ...DETAILS, repeatLimit: "STATION" }, log).rules[0].points, 10);
});

test("serwer – lista i szczegóły akcji", async () => {
  const realFetch = globalThis.fetch;
  let pageFetches = 0;
  globalThis.fetch = /** @type {any} */ (async (url) => {
    const u = String(url);
    if (u.includes("ics_download")) return new Response(ICS);
    if (u.endsWith("/ses/345")) { pageFetches++; return new Response(PAGE); }
    return new Response("nie ma", { status: 404 });
  });
  try {
    const { awardEvents, awardDetails } = await import("../server/awards.mjs");
    const list = await awardEvents("test");
    assert.equal(list.events.length, 3);
    const mine = await awardDetails("345", "sp3wro", "test");
    assert.deepEqual(mine.myRanking, { rank: 2, points: 40, qsos: 4 });
    assert.equal(mine.rankingCallsign, "SP3WRO");
    const other = await awardDetails("345", "SP9XYZ", "test");
    assert.equal(other.myRanking, null);
    assert.equal(pageFetches, 1);
    await assert.rejects(() => awardDetails("1", "", "test"));
    await assert.rejects(() => awardDetails("../x", "", "test"));
  } finally {
    globalThis.fetch = realFetch;
  }
});
