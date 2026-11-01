// Port ContestSourcesTest.kt i ContestCalendarTest.kt + serwerowe /api/contests z podstawionym fetch
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  parseSp9cxn, parsePkUkf, parseQrzRu, mergeContests, cyclicNames, translateRussian, isCyrillic, fullWeekendSaturday,
  builtinOccurrences, contestIcs,
} from "../public/js/core/contests.js";

const KNOWN = JSON.parse(readFileSync(new URL("../public/data/contest_names_ru.json", import.meta.url), "utf8"));
const fmt = (ms) => new Date(ms).toISOString().slice(0, 16).replace("T", " ");
const ms = (s) => Date.parse(`${s.replace(" ", "T")}:00Z`);
const byName = (list) => Object.fromEntries(list.map((e) => [e.name, e]));

const SP9CXN = JSON.stringify({ source: "SP9CXN", months: ["2026-10"], count: 7, contests: [
  { id: "a", name: "OMP ARKI VHF X tura", section: "domestic", domestic: true, start_utc: "2026-10-07T17:00:00Z", end_utc: "2026-10-07T19:00:00Z",
    rules_url: "https://sp9cxn.pzk.pl/arki_ukf_2026.pdf", rules_pl_url: null, modes: [], unconfirmed: false, old_rules: false, no_ru_by_contacts: false, problems: [] },
  { id: "b", name: "SP CW Contest", domestic: true, start_utc: "2026-10-11T15:00:00Z", end_utc: "2026-10-11T15:59:00Z", rules_url: "https://sp9cxn.pzk.pl/sp_cw_2026.pdf" },
  { id: "c", name: "ON Contest 80/40 m SSB", domestic: false, start_utc: "2026-10-04T06:00:00Z", end_utc: "2026-10-04T09:00:00Z",
    rules_url: "https://www.uba.be/en/hf/contest-rules/on-contest", rules_pl_url: "https://www-uba-be.translate.goog/en/?_x_tr_sl=auto&_x_tr_tl=pl",
    rules_pl_machine_translated: true, no_ru_by_contacts: true },
  { id: "d", name: "DARC RTTY Short Contest", domestic: false, start_utc: "2026-10-13T18:00:00Z", end_utc: "2026-10-13T19:29:00Z",
    rules_url: "https://www.darc.de/rtty", rules_pl_url: "https://sp9cxn.pzk.pl/DARC_RTTY.pdf" },
  { id: "e", name: "Sunday Contest CW", domestic: false, start_utc: "2026-10-18T17:30:00Z", end_utc: "2026-10-18T18:00:00Z",
    rules_url: "https://example.org/sunday", rules_pl_url: null, old_rules: true, problems: ["end_date_fixed"] },
  { id: "f", name: "Nocne Marki", domestic: true, start_utc: "2026-12-06T23:00:00Z", end_utc: "2026-12-22T00:00:00Z" },
  { id: "g", name: "Bez końca", domestic: false, start_utc: "2026-10-20T18:00:00Z", end_utc: null, problems: ["end_unparsed"] },
] });

test("SP9CXN – JSON z repozytorium", () => {
  const list = byName(parseSp9cxn(SP9CXN));
  assert.equal(Object.keys(list).length, 6, Object.keys(list).join());
  assert.equal(fmt(list["Nocne Marki"].endMs), "2026-12-07 00:00");
  const arki = list["OMP ARKI VHF X tura"];
  assert.ok(arki.polish && arki.vhf);
  assert.equal(fmt(arki.startMs), "2026-10-07 17:00");
  assert.equal(arki.rulesUrl, "https://sp9cxn.pzk.pl/arki_ukf_2026.pdf");
  assert.equal(fmt(list["SP CW Contest"].endMs), "2026-10-11 16:00");
  const on = list["ON Contest 80/40 m SSB"];
  assert.equal(on.polish, false);
  assert.deepEqual(on.modes, ["SSB"]);
  assert.equal(on.rulesPlUrl, "https://www-uba-be.translate.goog/en/?_x_tr_sl=auto&_x_tr_tl=pl");
  assert.deepEqual(on.notes, ["ct_note_ru_by"]);
  assert.equal(list["DARC RTTY Short Contest"].rulesPlUrl, "https://sp9cxn.pzk.pl/DARC_RTTY.pdf");
  const sunday = list["Sunday Contest CW"];
  assert.equal(fmt(sunday.endMs), "2026-10-18 18:00");
  assert.deepEqual(sunday.notes, ["ct_note_old_rules"]);
  assert.equal(sunday.rulesPlUrl, null);
});

const PK = JSON.stringify({ years: [2026], count: 4, contests: [
  { id: "a", name: "SPAC Listopad 144MHz", major: false, month: 11, start_utc: "2026-11-03T18:00:00Z", end_utc: "2026-11-03T22:00:00Z",
    band: "144 MHz", organizer: "PK UKF", rules_pl: "https://pk-ukf.pl/pl.pdf", rules_en: "https://pk-ukf.pl/en.pdf",
    log_deadline_raw: "10 Listopad 2026 (7dni)", log_deadline: "2026-11-10", log_deadline_computed: false, is_spac: true, year: 2026 },
  { id: "b", name: "MMC 144 MHz", start_utc: "2026-11-07T14:00:00Z", end_utc: "2026-11-08T14:00:00Z",
    band: "144MHz CW", organizer: "ARI", rules_pl: null, rules_en: "https://example.org/mmc", log_deadline_raw: "30 dni po zawodach", log_deadline: "2026-12-08" },
  { id: "c", name: "EME ARRL I-part", start_utc: "2026-10-31T00:00:00Z", end_utc: "2026-11-01T23:59:00Z",
    band: "50 .. 1296 MHz", organizer: "-", rules_pl: null, rules_en: null, log_deadline_raw: "?? Lipiec 2026 (5 dni)", log_deadline: null },
  { id: "d", name: "YL VHF Contest", start_date: null, end_date: null, start_utc: null, end_utc: null, band: "144 i 432 MHz", organizer: "LRAL" },
] });

test("PK-UKF / SP1CNV – JSON z repozytorium", () => {
  const list = byName(parsePkUkf(PK));
  assert.equal(Object.keys(list).length, 3);
  const spac = list["SPAC Listopad 144MHz"];
  assert.ok(spac.polish && spac.vhf);
  assert.equal(fmt(spac.startMs), "2026-11-03 18:00");
  assert.equal(fmt(spac.endMs), "2026-11-03 22:00");
  assert.equal(spac.rulesUrl, "https://pk-ukf.pl/en.pdf");
  assert.equal(spac.rulesPlUrl, "https://pk-ukf.pl/pl.pdf");
  assert.equal(spac.logDeadline, "10.11.2026");
  const mmc = list["MMC 144 MHz"];
  assert.equal(mmc.polish, false);
  assert.ok(mmc.modes.includes("CW"));
  assert.equal(fmt(mmc.endMs), "2026-11-08 14:00");
  assert.equal(mmc.rulesUrl, "https://example.org/mmc");
  assert.equal(mmc.rulesPlUrl, null);
  assert.equal(mmc.logDeadline, "08.12.2026");
  const eme = list["EME ARRL I-part"];
  assert.equal(fmt(eme.startMs), "2026-10-31 00:00");
  assert.equal(fmt(eme.endMs), "2026-11-02 00:00");
  assert.equal(eme.organizer, "");
  assert.equal(eme.logDeadline, "?? Lipiec 2026 (5 dni)");
});

const ICS = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
  "BEGIN:VEVENT\r\nDTSTART;TZID=Europe/Moscow:20260105T180000\r\nDTEND;TZID=Europe/Moscow:20260105T195900\r\n" +
  "LOCATION: Диапазон(ы): 160 м\\, 80 м\\, 40 м. Модуляция: CW\\, SSB.\r\nUID:54@qrz.ru\r\n" +
  "SUMMARY: КВ Кубок Брянской области\\, проводится с 05.01.2026 18:00 UTC по 05.01.2026 19:59 UTC\r\n" +
  "DESCRIPTION:Соревнование \"КВ Кубок Брянской области\":\\n\r\n проводится с 05.01.2026 18:00 UTC\\n\r\n" +
  " Отчеты принимаются до: 12.01.2026\\n\r\n Homepage: http://r3y.ru\\n\r\n Положение: https://www.qrz.ru/contest/detail/54.html\r\n" +
  "END:VEVENT\r\n" +
  "BEGIN:VEVENT\r\nDTSTART;TZID=Europe/Moscow:20261113T200000\r\nDTEND;TZID=Europe/Moscow:20261113T215900\r\n" +
  "LOCATION: Диапазон(ы): 144 МГц\\, 432 МГц. Модуляция: CW\\, FM\\, SSB.\r\nUID:346@qrz.ru\r\n" +
  "SUMMARY: Зоя\\, проводится с 13.11.2026 20:00 MSK по 13.11.2026 21:59 MSK\r\nEND:VEVENT\r\n" +
  "BEGIN:VEVENT\r\nDTSTART;TZID=Europe/Moscow:20261114T000000\r\nDTEND;TZID=Europe/Moscow:20261115T235900\r\n" +
  "LOCATION: Диапазон(ы): 80 м\\, 40 м\\, 20 м\\, 15 м\\, 10 м. Модуляция: RTTY.\r\nUID:9@qrz.ru\r\n" +
  "SUMMARY: WAEDC-Contest RTTY\\, проводится с 14.11.2026 00:00 UTC по 15.11.2026 24:00 UTC\r\nEND:VEVENT\r\n" +
  "END:VCALENDAR\r\n";

test("QRZ.RU – ICS i tłumaczenie nazw", () => {
  const list = parseQrzRu(ICS, KNOWN);
  assert.equal(list.length, 3);
  const [bryansk, zoya, waedc] = list;
  assert.equal(bryansk.name, "Bryansk Oblast HF Cup");
  assert.equal(bryansk.originalName, "КВ Кубок Брянской области");
  assert.equal(fmt(bryansk.startMs), "2026-01-05 18:00");
  assert.equal(fmt(bryansk.endMs), "2026-01-05 20:00");
  assert.equal(bryansk.bands, "160 m, 80 m, 40 m");
  assert.deepEqual(bryansk.modes, ["CW", "SSB"]);
  assert.equal(bryansk.logDeadline, "12.01.2026");
  assert.equal(bryansk.homepage, "http://r3y.ru");
  assert.equal(bryansk.rulesUrl, "https://www.qrz.ru/contest/detail/54.html");
  assert.equal(bryansk.sourcePageUrl, "https://www.qrz.ru/contest/detail/54.html");
  assert.equal(bryansk.vhf, false);
  assert.equal(zoya.name, "Zoya");
  assert.equal(fmt(zoya.startMs), "2026-11-13 17:00");
  assert.ok(zoya.vhf);
  assert.equal(zoya.bands, "144 MHz, 432 MHz");
  assert.equal(zoya.rulesUrl, "https://www.qrz.ru/contest/detail/346.html");
  assert.equal(waedc.originalName, null);
  assert.equal(fmt(waedc.endMs), "2026-11-16 00:00");
  assert.ok(waedc.modes.includes("RTTY"));
});

test("tłumaczenie rosyjskich nazw", () => {
  assert.equal(translateRussian("Cоревнования СРР по радиоспорту", KNOWN), "SRR Radiosport Contest");
  assert.equal(translateRussian("Молодежные соревнования «Снежинка»", KNOWN), "Youth Contest \"Snowflake\"");
  assert.equal(translateRussian("Oceania DX Contest", KNOWN), "Oceania DX Contest");
  const unknown = translateRussian("КВ Чемпионат Ивановской области - телеграф", KNOWN);
  assert.ok(!isCyrillic(unknown), unknown);
  assert.equal(unknown, "HF Championship Ivanovsk Oblast – CW");
});

test("łączenie tych samych zawodów z różnych źródeł i zawody cykliczne", () => {
  const sp = parseSp9cxn(SP9CXN);
  const qrz = parseQrzRu("BEGIN:VEVENT\nUID:77@qrz.ru\nLOCATION: Диапазон(ы): 80 м\\, 40 м. Модуляция: SSB.\n" +
    "SUMMARY: ON Contest\\, проводится с 04.10.2026 06:00 UTC по 04.10.2026 09:00 UTC\nEND:VEVENT\n" +
    "BEGIN:VEVENT\nUID:78@qrz.ru\nSUMMARY: Oceania DX Contest\\, проводится с 04.10.2026 06:00 UTC по 05.10.2026 06:00 UTC\nEND:VEVENT\n");
  const merged = mergeContests([sp, [], qrz]);
  assert.equal(merged.length, sp.length + 1);
  const on = merged.find((e) => e.name.startsWith("ON Contest"));
  assert.deepEqual(on.sources, ["SP9CXN", "QRZ_RU"]);
  assert.equal(on.bands, "80 m, 40 m");
  assert.equal(on.rulesUrl, "https://www.uba.be/en/hf/contest-rules/on-contest");
  // Link do strony QRZ.RU zostaje po złączeniu ze źródłem o wyższym priorytecie
  assert.equal(on.sourcePageUrl, "https://www.qrz.ru/contest/detail/77.html");

  const week = 7 * 86_400_000;
  const base = sp[0];
  const entries = [0, 1, 2, 3].map((i) => ({ ...base, name: "MWC Contest CW", startMs: base.startMs + i * week, endMs: base.endMs + i * week }))
    .concat([{ ...base, name: "SP CW Contest" }]);
  assert.deepEqual([...cyclicNames(entries)], ["mwc contest cw"]);
});

test("kalendarz wbudowany – terminy", () => {
  const day = (d) => fmt(d).slice(0, 10);
  assert.equal(day(fullWeekendSaturday(2026, 10, -1)), "2026-10-24");
  assert.equal(day(fullWeekendSaturday(2027, 8, 1)), "2027-08-07");
  const one = (id, from, to) => {
    const l = builtinOccurrences(ms(from), ms(to)).filter((o) => o.id === id);
    assert.equal(l.length, 1, id);
    return l[0];
  };
  const cqww = one("cqww-ssb", "2026-10-01 00:00", "2026-11-01 00:00");
  assert.equal(fmt(cqww.startMs), "2026-10-24 00:00");
  assert.equal(fmt(cqww.endMs), "2026-10-26 00:00");
  const cwt = builtinOccurrences(ms("2026-10-21 00:00"), ms("2026-10-23 00:00")).filter((o) => o.id.startsWith("cwt")).map((o) => fmt(o.startMs));
  assert.deepEqual(cwt, ["2026-10-21 13:00", "2026-10-21 19:00", "2026-10-22 03:00", "2026-10-22 07:00"]);
  assert.equal(fmt(one("spdx", "2026-04-01 00:00", "2026-05-01 00:00").startMs), "2026-04-04 15:00");
  assert.equal(fmt(one("cqww-cw", "2026-11-01 00:00", "2026-12-01 00:00").startMs), "2026-11-28 00:00");
  assert.equal(fmt(one("arrl-10", "2026-12-01 00:00", "2027-01-01 00:00").startMs), "2026-12-12 00:00");
  const cq160 = one("cq160-cw", "2027-01-01 00:00", "2027-02-02 00:00");
  assert.equal(fmt(cq160.startMs), "2027-01-29 22:00");
  assert.equal(fmt(cq160.endMs), "2027-01-31 22:00");
  const year = builtinOccurrences(ms("2026-10-25 12:00"), ms("2027-10-25 12:00"));
  assert.equal(year[0].id, "cqww-ssb");
  const ics = contestIcs(cqww, "a, b\nc");
  assert.match(ics, /DTSTART:20261024T000000Z/);
  assert.match(ics, /DESCRIPTION:a\\, b\\nc/);
});

test("serwer – /api/contests łączy źródła", async () => {
  const realFetch = globalThis.fetch;
  /** @type {string[]} */
  const urls = [];
  globalThis.fetch = /** @type {any} */ (async (url) => {
    const u = String(url);
    urls.push(u);
    const body = u.includes("sp9cxn-calendar") ? SP9CXN : u.includes("pkukf-calendar") ? PK : u.startsWith("https://m.qrz.ru/contest/qrz-contest.ics?m=") ? ICS : "";
    return new Response(body, { status: body ? 200 : 404 });
  });
  try {
    const { contests } = await import("../server/contests.mjs");
    const r = await contests("test");
    assert.equal(r.entries.length, 12);
    assert.deepEqual(r.statuses.map((s) => s.source), ["SP9CXN", "PK_UKF", "QRZ_RU"]);
    assert.ok(r.statuses.every((s) => s.fetchedAt && !s.error));
    assert.ok(r.entries.some((e) => e.name === "Bryansk Oblast HF Cup"));
    // Bez stron sp9cxn.pzk.pl i pk-ukf.pl; QRZ.RU – trzy miesiące
    assert.ok(!urls.some((u) => u.includes("pk-ukf.pl") || u.includes("sp9cxn.pzk.pl")));
    assert.equal(urls.filter((u) => u.includes("qrz.ru")).length, 3);
  } finally {
    globalThis.fetch = realFetch;
  }
});
