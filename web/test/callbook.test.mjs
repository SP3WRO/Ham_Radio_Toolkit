// Port CallbookTest.kt + łączenie wyników i serwerowe wyszukiwanie z podstawionym fetch
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  parseUkeIndividuals, parseUkeClubs, toTitleCase, parseRadioIdUser, parseHamDb, parseQrzcq, xmlTag, parsePskReporter,
  cbBaseCall, isCompleteCall, combineCallbook,
} from "../public/js/core/callbook.js";

const t = (key, ...args) => [key, ...args].join("|");

test("rejestr UKE – osoby i kluby", () => {
  const csv = "number;valid_to;call_sign;category;transmitter_power;station_location\n" +
    "16/I/C/2016;2026-12-20;SP4QK;Kategoria1;500;Białystok\r\n" +
    "2/I/C/2017;2027-03-06;sp4hkj;Kategoria1;500;Supraśl\n";
  const list = parseUkeIndividuals(csv);
  assert.equal(list.length, 2);
  assert.equal(list[0].callsign, "SP4QK");
  assert.equal(list[0].stationCity, "Białystok");
  assert.equal(list[0].powerW, 500);
  assert.equal(list[1].callsign, "SP4HKJ");
  assert.equal(list[0].isClub, false);

  const clubs = "number;valid_to;call_sign;category;transmitter_power;applicant_name;applicant_city;applicant_street;applicant_house;applicant_number;" +
    "enduser_name;enduser_city;enduser_street;enduser_house;enduser_number;station_city;station_street;station_house;station_number;lat;lng\n" +
    "0004/K/E/2017;2027-01-28;SP2KFD;Kategoria1;500;Liga Obrony Kraju;Warszawa;ul. Chocimska;14;\"\";" +
    "\"Klub \"\"Łączność\"\"; Starogard\";Warszawa;ul. Chocimska;14;\"\";STAROGARD GDAŃSKI;Zielona;18;\"\";;\n";
  const [club, ...rest] = parseUkeClubs(clubs);
  assert.equal(rest.length, 0);
  assert.equal(club.callsign, "SP2KFD");
  assert.equal(club.clubName, "Klub \"Łączność\"; Starogard");
  assert.equal(club.stationCity, "Starogard Gdański");
  assert.equal(club.stationStreet, "Zielona 18");
  assert.equal(club.position ?? null, null);
  assert.equal(club.isClub, true);
});

test("toTitleCase zachowuje separatory", () => {
  assert.equal(toTitleCase("KĘDZIERZYN-KOŹLE"), "Kędzierzyn-Koźle");
  assert.equal(toTitleCase("NOWA WIEŚ"), "Nowa Wieś");
  assert.equal(toTitleCase("Wronki"), "Wronki");
});

test("RadioID, HamDB, QRZCQ", () => {
  const rid = parseRadioIdUser(JSON.stringify({ count: 1, results: [{ callsign: "SP3WRO", city: "Wronki", country: "Poland", fname: "Mateusz",
    id: 2600956, name: "Mateusz", radio_id: 2600956, state: "wielkopolskie", surname: "" }] }), "SP3WRO");
  assert.equal(rid?.name, "Mateusz");
  assert.equal(rid?.city, "Wronki");
  assert.equal(rid?.state, "wielkopolskie");
  assert.equal(parseRadioIdUser('{"count":0,"results":[]}', "SP3WRO"), null);

  const hd = parseHamDb(`{ "hamdb":{ "version":"1", "callsign":{ "call":"VE3ABC", "class":"", "expires":"", "status":"",
    "grid":"FN25ci", "lat":"45.3606600", "lon":"-75.7667900", "fname":"Lawrence Carson", "mi":"", "name":"Kearns",
    "suffix":"", "addr1":"2205 BEL-AIR DRIVE", "addr2":"OTTAWA", "state":"ON", "zip":"K2C0X2", "country":"Canada" },
    "messages":{ "status":"OK" } } }`);
  assert.equal(hd?.callsign, "VE3ABC");
  assert.equal(hd?.gridSquare, "FN25CI");
  assert.equal(hd?.name, "Lawrence Carson Kearns");
  assert.equal(hd?.qth, "OTTAWA, ON");
  assert.equal(parseHamDb('{ "hamdb":{ "version":"1", "callsign":{ "call":"NOT_FOUND", "grid":"NOT_FOUND" }, "messages":{ "status":"NOT_FOUND" } } }'), null);

  const q = parseQrzcq(`<?xml version="1.0" encoding="utf-8" ?>
    <QRZCQDatabase version="1.00" xmlns="http://qrzcq.com">
      <Callsign><call>DO5SSB</call><dxcc>230</dxcc><name>Grzegorz Surmann</name>
        <qth>Buchholz, near Hamburg</qth><city>Buchholz</city><locator>JO43WH</locator>
        <latitude>53.3199308</latitude><longitude>9.8597973</longitude></Callsign>
      <Session><Key>abc</Key><Count>18232</Count></Session>
    </QRZCQDatabase>`);
  assert.equal(q?.callsign, "DO5SSB");
  assert.equal(q?.gridSquare, "JO43WH");
  assert.equal(q?.name, "Grzegorz Surmann");
  assert.equal(q?.qth, "Buchholz, near Hamburg");
  assert.equal(xmlTag("<Session><Error>Not found: N0CALL</Error></Session>", "Error"), "Not found: N0CALL");
  assert.equal(parseQrzcq("<QRZCQDatabase><Session><Error>Session Timeout</Error></Session></QRZCQDatabase>"), null);
});

test("PSK Reporter – lokator nadawcy", () => {
  const xml = `<?xml version="1.0"?>
    <receptionReports>
      <receptionReport receiverCallsign="DL1XYZ" receiverLocator="JO62qm" senderCallsign="SP3WRO" senderLocator="JO82" frequency="14075000" flowStartSeconds="1792950000" mode="FT8"/>
      <receptionReport receiverCallsign="G4ABC" receiverLocator="IO91" senderCallsign="SP3WRO" senderLocator="JO82kr" frequency="14075000" flowStartSeconds="1792940000" mode="FT8"/>
      <receptionReport receiverCallsign="SP3WRO" receiverLocator="JO82kr" senderCallsign="DL1XYZ" senderLocator="JO62qm" flowStartSeconds="1792960000" mode="FT8"/>
    </receptionReports>`;
  const spot = parsePskReporter(xml, "SP3WRO");
  assert.equal(spot?.locator, "JO82KR");
  assert.equal(spot?.timeSeconds, 1792950000);
  assert.equal(parsePskReporter('<receptionReports><lastSequenceNumber value="1"/></receptionReports>', "SP3WRO"), null);
  assert.equal(parsePskReporter('<receptionReport senderCallsign="SP3WRO" senderLocator="JO" flowStartSeconds="1"/>', "SP3WRO"), null);
});

test("znak bazowy i kompletność", () => {
  assert.equal(cbBaseCall("SP3WRO/P"), "SP3WRO");
  assert.equal(cbBaseCall("SP/DL1ABC"), "DL1ABC");
  assert.equal(cbBaseCall("W1AW/4"), "W1AW");
  for (const c of ["SP3WRO", "SN100PZK", "3Z0X"]) assert.ok(isCompleteCall(c), c);
  for (const c of ["SP3", "SP"]) assert.ok(!isCompleteCall(c), c);
});

test("łączenie wyników – kolejność lokatorów", () => {
  const poland = { dxcc: 269, name: "Poland" };
  const germany = { dxcc: 230, name: "Germany" };
  const data = {
    hamQth: { name: "Jan", qth: "Wronki", gridSquare: "JO72AA" },
    qrzcq: { name: "Jan K", qth: "", gridSquare: "JO72BB" },
    hamDb: null, psk: { status: "notHeard" }, notes: [],
  };
  const base = { call: "SP3WRO", entity: poland, homeEntity: poland, hamQthConfigured: true, lastLogged: null };
  // QRZCQ przed HamQTH
  let r = combineCallbook({ ...base, data }, t);
  assert.equal(r.grid?.locator, "JO72BB");
  assert.equal(r.name, "Jan");
  assert.deepEqual(r.sources, ["QRZCQ", "HamQTH"]);
  // Log przed bazami
  r = combineCallbook({ ...base, data, lastLogged: { gridSquare: "jo82kr", datetimeUtc: "2026-01-01 10:00" } }, t);
  assert.equal(r.grid?.locator, "JO82KR");
  // PSK Reporter przed wszystkim
  r = combineCallbook({ ...base, data: { ...data, psk: { status: "found", spot: { locator: "JO82LS", timeSeconds: 1000 } } }, now: 1000_000 }, t);
  assert.equal(r.grid?.locator, "JO82LS");
  // Za granicą domowy lokator z baz się nie liczy
  r = combineCallbook({ ...base, call: "DL/SP3WRO", entity: germany, data }, t);
  assert.equal(r.grid, null);
  assert.ok(r.notes.includes("cb_abroad"));
  // Limit PSK Reporter trafia do notatek, gdy brak lokatora
  r = combineCallbook({ ...base, data: { psk: { status: "rate", waitMinutes: 3 }, notes: [] } }, t);
  assert.ok(r.notes.includes("cb_psk_wait|3"));
});

test("serwer – wyszukiwanie z podstawionym fetch", async () => {
  const realFetch = globalThis.fetch;
  /** @type {string[]} */
  const urls = [];
  globalThis.fetch = /** @type {any} */ (async (url) => {
    urls.push(String(url));
    const u = String(url);
    const body = u.includes("hamdb") ? '{"hamdb":{"callsign":{"call":"K1ABC","grid":"FN42aa","fname":"John","name":"Doe","addr2":"BOSTON","state":"MA"},"messages":{"status":"OK"}}}'
      : u.includes("radioid") ? '{"count":0,"results":[]}'
      : u.includes("pskreporter") ? '<receptionReports><receptionReport senderCallsign="K1ABC" senderLocator="FN42cd" flowStartSeconds="5"/></receptionReports>'
      : "";
    return new Response(body, { status: 200 });
  });
  try {
    const { lookup } = await import("../server/callbook.mjs");
    const r = await lookup({ call: "K1ABC", base: "K1ABC", online: true, uke: false, hamDb: true, psk: true }, "test");
    assert.equal(r.hamDb?.gridSquare, "FN42AA");
    assert.equal(r.radioId, null);
    assert.equal(r.psk?.status, "found");
    assert.equal(r.psk?.spot.locator, "FN42CD");
    // Drugie zapytanie PSK w ciągu 5 minut – z pamięci dla tego samego znaku, limit dla innego
    const again = await lookup({ call: "K1ABC", base: "K1ABC", online: true, uke: false, hamDb: false, psk: true }, "test");
    assert.equal(again.psk?.status, "found");
    const other = await lookup({ call: "K2XYZ", base: "K2XYZ", online: true, uke: false, hamDb: false, psk: true }, "test");
    assert.equal(other.psk?.status, "rate");
    assert.equal(urls.filter((u) => u.includes("pskreporter")).length, 1);
    await assert.rejects(() => lookup({ call: "<x>", base: "x", online: true, uke: false, hamDb: false, psk: false }, "test"));
  } finally {
    globalThis.fetch = realFetch;
  }
});
