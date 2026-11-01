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

const spCell = (text) => `<td class="TC-1"><p class="Normal P-5"><span class="C-16">${text}</span></p></td>`;
const SP9CXN = `
<p><span class="C-15">KALENDARZ ZAWODÓW KRAJOWYCH PAŹDZIERNIK 2026</span></p>
<table id="table_261" border="1"><tr><td colspan="2">Początek</td><td>Nazwa zawodów</td><td colspan="2">Koniec</td></tr>
<tr>${spCell("Data")}${spCell("Godz. UTC")}${spCell("Data")}${spCell("Godz. UTC")}</tr>
<tr>${spCell("07.10.2026")}${spCell("17:00")}<td class="TC-2"><p class="Normal"><a href="arki_ukf_2026.pdf" target="_blank" class="C-17">O<span class="C-17">MP ARKI VHF<span class="C-17"> X tura</span></span></a></p></td>${spCell("07.10.2026")}${spCell("19:00")}</tr>
<tr>${spCell("11.10.2026")}${spCell("15:00")}<td class="TC-2"><p class="Normal"><a href="sp_cw_2026.pdf" class="C-17">SP CW Contest</a></p></td>${spCell("11.10.2026")}${spCell("15:59")}</tr>
</table>
<p><span class="C-15">KALENDARZ ZAWODÓW MIĘDZYNARODOWYCH PAŹDZIERNIK 2026</span></p>
<table id="table_262" border="1">
<tr>${spCell("04.10.2026")}${spCell("06:00")}<td class="TC-1"><p class="Normal"><a href="https://www.uba.be/en/hf/contest-rules/on-contest" class="C-17">ON Contest 80/40 m SSB</a><span class="C-16"> &nbsp;</span><span class="C-20">UA i EU</span></p></td><td class="TC-1"><p class="Normal P-6"><a href="https://www-uba-be.translate.goog/en/?_x_tr_sl=auto&amp;_x_tr_tl=pl" class="C-18">PL</a></p></td>${spCell("04.10.2026")}${spCell("09:00")}</tr>
<tr>${spCell("13.10.2026")}${spCell("18:00")}<td class="TC-1"><p class="Normal"><a href="https://www.darc.de/rtty" class="C-17">DARC RTTY <span class="C-17">Short C<span class="C-17">ontest</span></span></a></p></td><td class="TC-1"><p class="Normal P-6"><a href="DARC_RTTY.pdf" class="C-18">PL</a></p></td>${spCell("13.10.2026")}${spCell("19:29")}</tr>
<tr>${spCell("18.10.2026")}${spCell("17:30")}<td class="TC-1"><p class="Normal"><a href="https://example.org/sunday" class="C-17">Sunday Contest<span class="C-17"> CW</span></a><span> *</span></p></td><td class="TC-1">-</td>${spCell("18.10.2016")}${spCell("18:00")}</tr>
<tr>${spCell("??.10.2026")}${spCell("18:00")}<td class="TC-1">Nieznane</td><td>-</td>${spCell("??.10.2026")}${spCell("19:00")}</tr>
</table>`;

test("SP9CXN – tabele krajowe i międzynarodowe", () => {
  const list = byName(parseSp9cxn(SP9CXN));
  assert.equal(Object.keys(list).length, 5, Object.keys(list).join());
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
  assert.equal(sunday.notes.length, 1);
  assert.equal(sunday.rulesPlUrl, null);
});

const pkRow = (date, time, name, band, org, deadline) => `<tr><td>1</td><td>${date}</td><td>${time}</td><td><strong>${name}</strong></td><td>${band}</td><td>${org}</td>
  <td><a href="https://pk-ukf.pl/pl.pdf">Regulamin</a></td><td><a href="https://pk-ukf.pl/en.pdf">Regulamin</a></td><td>${deadline}</td></tr>`;
const PK = `<table><tr><td>Lp.</td><td>Data</td><td>Czas trwania UTC</td><td>Nazwa zawodów</td><td>Pasmo</td><td>Organizator</td><td>Regulamin polski</td><td>Regulamin angielski</td><td>Termin wysyłki logów</td></tr>
${pkRow("3 Listopad 2026", "18:00 &#8211; 22:00", "SPAC Listopad 144MHz", "144 MHz", "PK UKF", "10 Listopad 2026 (7dni)")}
${pkRow("7-8 Listopad 2026", "14:00 &#8211; 14:00", "MMC 144 MHz", "144MHz CW", "ARI", "11 Listopad 2026 (3dni)")}
${pkRow("31 Październik &#8211; 1 Listopad 2026", "00:00 &#8211; 23:59", "EME ARRL I-part", "50 .. 1296 MHz", "ARRL", "30 dni po zawodach")}
${pkRow("6 Styczeń 2026", "18:0 – 22:00", "SPAC Styczeń 144MHz", "144 MHz", "PK UKF", "13 Styczeń 2026")}
${pkRow("1 Styczeń 2026", "14.00 - 18.00", "AGCW VHF/UHF Contest", "144 i 432MHz", "AGCW", "2 Styczeń 2026")}
${pkRow("?? Listopad 2026", "18:00 &#8211; 21:59", "YL VHF Contest", "144 i 432 MHz", "LRAL", "28 Listopad 2026")}
</table>`;

test("PK-UKF – formaty dat", () => {
  const list = byName(parsePkUkf(PK));
  assert.equal(Object.keys(list).length, 5);
  const spac = list["SPAC Listopad 144MHz"];
  assert.ok(spac.polish && spac.vhf);
  assert.equal(fmt(spac.startMs), "2026-11-03 18:00");
  assert.equal(fmt(spac.endMs), "2026-11-03 22:00");
  assert.equal(spac.rulesPlUrl, "https://pk-ukf.pl/pl.pdf");
  assert.equal(spac.logDeadline, "10 Listopad 2026 (7dni)");
  const mmc = list["MMC 144 MHz"];
  assert.equal(mmc.polish, false);
  assert.ok(mmc.modes.includes("CW"));
  assert.equal(fmt(mmc.startMs), "2026-11-07 14:00");
  assert.equal(fmt(mmc.endMs), "2026-11-08 14:00");
  const eme = list["EME ARRL I-part"];
  assert.equal(fmt(eme.startMs), "2026-10-31 00:00");
  assert.equal(fmt(eme.endMs), "2026-11-02 00:00");
  assert.equal(fmt(list["SPAC Styczeń 144MHz"].startMs), "2026-01-06 18:00");
  assert.equal(fmt(list["AGCW VHF/UHF Contest"].endMs), "2026-01-01 18:00");
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
  globalThis.fetch = /** @type {any} */ (async (url) => {
    const u = String(url);
    const body = u.includes("sp9cxn") ? SP9CXN : u.includes("pk-ukf") ? PK : u.includes("qrz.ru") ? ICS : "";
    return new Response(body, { status: body ? 200 : 404 });
  });
  try {
    const { contests } = await import("../server/contests.mjs");
    const r = await contests("test");
    assert.ok(r.entries.length >= 13, String(r.entries.length));
    assert.deepEqual(r.statuses.map((s) => s.source), ["SP9CXN", "PK_UKF", "QRZ_RU"]);
    assert.ok(r.statuses.every((s) => s.fetchedAt && !s.error));
    assert.ok(r.entries.some((e) => e.name === "Bryansk Oblast HF Cup"));
  } finally {
    globalThis.fetch = realFetch;
  }
});
