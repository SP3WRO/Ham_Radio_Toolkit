package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.contests.ContestMode
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestSource
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestSources
import io.github.sp3wro.hamradiotoolkit.data.contests.PkUkfCalendar
import io.github.sp3wro.hamradiotoolkit.data.contests.QrzRuCalendar
import io.github.sp3wro.hamradiotoolkit.data.contests.RussianContestNames
import io.github.sp3wro.hamradiotoolkit.data.contests.Sp9cxnCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ContestSourcesTest {
    private val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun fmt(ms: Long): String = utc.format(ms)

    // ------------------------------------------------------------------ SP9CXN

    private val sp9cxnJson = """
        {"source":"SP9CXN","months":["2026-10"],"count":7,"contests":[
        {"id":"a","name":"OMP ARKI VHF X tura","section":"domestic","domestic":true,"start_utc":"2026-10-07T17:00:00Z","end_utc":"2026-10-07T19:00:00Z",
         "rules_url":"https://sp9cxn.pzk.pl/arki_ukf_2026.pdf","rules_pl_url":null,"modes":[],"unconfirmed":false,"old_rules":false,"no_ru_by_contacts":false,"problems":[]},
        {"id":"b","name":"SP CW Contest","domestic":true,"start_utc":"2026-10-11T15:00:00Z","end_utc":"2026-10-11T15:59:00Z","rules_url":"https://sp9cxn.pzk.pl/sp_cw_2026.pdf"},
        {"id":"c","name":"ON Contest 80/40 m SSB","domestic":false,"start_utc":"2026-10-04T06:00:00Z","end_utc":"2026-10-04T09:00:00Z",
         "rules_url":"https://www.uba.be/en/hf/contest-rules/on-contest","rules_pl_url":"https://www-uba-be.translate.goog/en/?_x_tr_sl=auto&_x_tr_tl=pl",
         "rules_pl_machine_translated":true,"no_ru_by_contacts":true},
        {"id":"d","name":"DARC RTTY Short Contest","domestic":false,"start_utc":"2026-10-13T18:00:00Z","end_utc":"2026-10-13T19:29:00Z",
         "rules_url":"https://www.darc.de/rtty","rules_pl_url":"https://sp9cxn.pzk.pl/DARC_RTTY.pdf"},
        {"id":"e","name":"Sunday Contest CW","domestic":false,"start_utc":"2026-10-18T17:30:00Z","end_utc":"2026-10-18T18:00:00Z",
         "rules_url":"https://example.org/sunday","rules_pl_url":null,"old_rules":true,"problems":["end_date_fixed"]},
        {"id":"f","name":"Nocne Marki","domestic":true,"start_utc":"2026-12-06T23:00:00Z","end_utc":"2026-12-22T00:00:00Z"},
        {"id":"g","name":"Bez końca","domestic":false,"start_utc":"2026-10-20T18:00:00Z","end_utc":null,"problems":["end_unparsed"]}
        ]}
    """.trimIndent()

    @Test
    fun sp9cxnParsesPublishedJson() {
        val list = Sp9cxnCalendar.parse(sp9cxnJson).associateBy { it.name }
        assertEquals(list.keys.toString(), 6, list.size) // bez daty końca pominięty

        // Koniec ponad 14 dni po starcie to literówka: ta sama godzina w dniu po starcie
        assertEquals("2026-12-07 00:00", fmt(list.getValue("Nocne Marki").endMs))

        val arki = list.getValue("OMP ARKI VHF X tura")
        assertTrue(arki.polish)
        assertTrue(arki.vhf)
        assertEquals("2026-10-07 17:00", fmt(arki.startMs))
        assertEquals("https://sp9cxn.pzk.pl/arki_ukf_2026.pdf", arki.rulesUrl)

        // 15:59 = koniec o pełnej godzinie
        assertEquals("2026-10-11 16:00", fmt(list.getValue("SP CW Contest").endMs))

        val on = list.getValue("ON Contest 80/40 m SSB")
        assertFalse(on.polish)
        assertEquals(setOf(ContestMode.SSB), on.modes)
        assertEquals("https://www-uba-be.translate.goog/en/?_x_tr_sl=auto&_x_tr_tl=pl", on.rulesPlUrl)
        assertEquals(AppStrings.get(R.string.ct_note_ru_by), on.notes.single())

        assertEquals("https://sp9cxn.pzk.pl/DARC_RTTY.pdf", list.getValue("DARC RTTY Short Contest").rulesPlUrl)

        // Gwiazdka "regulamin poprzedniej edycji"
        val sunday = list.getValue("Sunday Contest CW")
        assertEquals("2026-10-18 18:00", fmt(sunday.endMs))
        assertEquals(1, sunday.notes.size)
        assertNull(sunday.rulesPlUrl)
    }

    // ------------------------------------------------------------------ PK-UKF

    private val pkJson = """
        {"years":[2026],"count":4,"contests":[
        {"id":"a","name":"SPAC Listopad 144MHz","major":false,"month":11,"start_utc":"2026-11-03T18:00:00Z","end_utc":"2026-11-03T22:00:00Z",
         "band":"144 MHz","organizer":"PK UKF","rules_pl":"https://pk-ukf.pl/pl.pdf","rules_en":"https://pk-ukf.pl/en.pdf",
         "log_deadline_raw":"10 Listopad 2026 (7dni)","log_deadline":"2026-11-10","log_deadline_computed":false,"is_spac":true,"year":2026},
        {"id":"b","name":"MMC 144 MHz","start_utc":"2026-11-07T14:00:00Z","end_utc":"2026-11-08T14:00:00Z",
         "band":"144MHz CW","organizer":"ARI","rules_pl":null,"rules_en":"https://example.org/mmc","log_deadline_raw":"30 dni po zawodach","log_deadline":"2026-12-08"},
        {"id":"c","name":"EME ARRL I-part","start_utc":"2026-10-31T00:00:00Z","end_utc":"2026-11-01T23:59:00Z",
         "band":"50 .. 1296 MHz","organizer":"-","rules_pl":null,"rules_en":null,"log_deadline_raw":"?? Lipiec 2026 (5 dni)","log_deadline":null},
        {"id":"d","name":"YL VHF Contest","start_date":null,"end_date":null,"start_utc":null,"end_utc":null,"band":"144 i 432 MHz","organizer":"LRAL"}
        ]}
    """.trimIndent()

    @Test
    fun pkUkfParsesPublishedJson() {
        val list = PkUkfCalendar.parse(pkJson).associateBy { it.name }
        assertEquals(list.keys.toString(), 3, list.size) // data "??" pominięta

        val spac = list.getValue("SPAC Listopad 144MHz")
        assertEquals("PK-UKF / SP1CNV", spac.source.label)
        assertTrue(spac.polish)
        assertTrue(spac.vhf)
        assertEquals("2026-11-03 18:00", fmt(spac.startMs))
        assertEquals("2026-11-03 22:00", fmt(spac.endMs))
        assertEquals("https://pk-ukf.pl/en.pdf", spac.rulesUrl)
        assertEquals("https://pk-ukf.pl/pl.pdf", spac.rulesPlUrl)
        assertEquals("10.11.2026", spac.logDeadline)

        val mmc = list.getValue("MMC 144 MHz")
        assertFalse(mmc.polish)
        assertTrue(ContestMode.CW in mmc.modes)
        assertEquals("2026-11-08 14:00", fmt(mmc.endMs))
        assertEquals("https://example.org/mmc", mmc.rulesUrl)
        assertNull(mmc.rulesPlUrl)
        // "30 dni po zawodach" jest już przeliczone na datę
        assertEquals("08.12.2026", mmc.logDeadline)

        val eme = list.getValue("EME ARRL I-part")
        assertEquals("2026-10-31 00:00", fmt(eme.startMs))
        assertEquals("2026-11-02 00:00", fmt(eme.endMs))
        assertEquals("", eme.organizer)
        assertEquals("?? Lipiec 2026 (5 dni)", eme.logDeadline)
    }

    // ------------------------------------------------------------------ QRZ.RU

    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
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
        "END:VCALENDAR\r\n"

    @Test
    fun qrzRuParsesIcsAndTranslates() {
        val list = QrzRuCalendar.parse(ics)
        assertEquals(3, list.size)

        val bryansk = list[0]
        assertEquals("Bryansk Oblast HF Cup", bryansk.name)
        assertEquals("КВ Кубок Брянской области", bryansk.originalName)
        assertEquals("2026-01-05 18:00", fmt(bryansk.startMs))
        assertEquals("2026-01-05 20:00", fmt(bryansk.endMs))
        assertEquals("160 m, 80 m, 40 m", bryansk.bands)
        assertEquals(setOf(ContestMode.CW, ContestMode.SSB), bryansk.modes)
        assertEquals("12.01.2026", bryansk.logDeadline)
        assertEquals("http://r3y.ru", bryansk.homepage)
        assertEquals("https://www.qrz.ru/contest/detail/54.html", bryansk.rulesUrl)
        assertFalse(bryansk.vhf)

        // Czas moskiewski -> UTC
        val zoya = list[1]
        assertEquals("Zoya", zoya.name)
        assertEquals("2026-11-13 17:00", fmt(zoya.startMs))
        assertTrue(zoya.vhf)
        assertEquals("144 MHz, 432 MHz", zoya.bands)
        assertEquals("https://www.qrz.ru/contest/detail/346.html", zoya.rulesUrl)

        val waedc = list[2]
        assertNull(waedc.originalName)
        assertEquals("2026-11-16 00:00", fmt(waedc.endMs))
        assertTrue(ContestMode.RTTY in waedc.modes)
    }

    @Test
    fun russianNamesTranslation() {
        // Łacińskie "C" na początku słowa pisanego cyrylicą
        assertEquals("SRR Radiosport Contest", RussianContestNames.translate("Cоревнования СРР по радиоспорту"))
        assertEquals("Youth Contest \"Snowflake\"", RussianContestNames.translate("Молодежные соревнования «Снежинка»"))
        assertEquals("Oceania DX Contest", RussianContestNames.translate("Oceania DX Contest"))
        // Nazwy spoza słownika: tłumaczenie przybliżone bez cyrylicy
        val unknown = RussianContestNames.translate("КВ Чемпионат Ивановской области - телеграф")
        assertFalse(unknown, RussianContestNames.isCyrillic(unknown))
        assertEquals("HF Championship Ivanovsk Oblast – CW", unknown)
    }

    // ------------------------------------------------------------------ łączenie

    @Test
    fun mergeJoinsSameContestFromDifferentSources() {
        val sp = Sp9cxnCalendar.parse(sp9cxnJson)
        val qrz = QrzRuCalendar.parse(
            "BEGIN:VEVENT\nUID:77@qrz.ru\nLOCATION: Диапазон(ы): 80 м\\, 40 м. Модуляция: SSB.\n" +
                "SUMMARY: ON Contest\\, проводится с 04.10.2026 06:00 UTC по 04.10.2026 09:00 UTC\nEND:VEVENT\n" +
                "BEGIN:VEVENT\nUID:78@qrz.ru\n" +
                "SUMMARY: Oceania DX Contest\\, проводится с 04.10.2026 06:00 UTC по 05.10.2026 06:00 UTC\nEND:VEVENT\n"
        )
        val merged = ContestSources.merge(listOf(sp, emptyList(), qrz))
        assertEquals(sp.size + 1, merged.size)
        val on = merged.single { it.name.startsWith("ON Contest") }
        assertEquals(setOf(ContestSource.SP9CXN, ContestSource.QRZ_RU), on.sources)
        assertEquals("80 m, 40 m", on.bands)
        assertEquals("https://www.uba.be/en/hf/contest-rules/on-contest", on.rulesUrl)
        // Link do strony QRZ.RU zostaje, choć regulamin pochodzi z SP9CXN
        assertEquals("https://www.qrz.ru/contest/detail/77.html", on.sourcePageUrl)
    }

    @Test
    fun qrzRuRequestsOnlyNeededMonths() {
        fun at(y: Int, m: Int, d: Int) = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis
        assertEquals(listOf(11, 12, 1), QrzRuCalendar.monthsToFetch(at(2026, 11, 1)))
        assertEquals(listOf(12, 1, 2), QrzRuCalendar.monthsToFetch(at(2026, 12, 31)))
        assertEquals(10, QrzRuCalendar.previousMonth(at(2026, 11, 1)))
        assertEquals(12, QrzRuCalendar.previousMonth(at(2027, 1, 15)))
        assertEquals("https://m.qrz.ru/contest/qrz-contest.ics?m=11", QrzRuCalendar.icsUrl(11))
    }

    @Test
    fun cyclicContestsDetected() {
        val week = 7 * 86_400_000L
        val base = Sp9cxnCalendar.parse(sp9cxnJson).first()
        val entries = (0 until 4).map { base.copy(name = "MWC Contest CW", startMs = base.startMs + it * week, endMs = base.endMs + it * week) } +
            base.copy(name = "SP CW Contest")
        assertEquals(setOf("mwc contest cw"), ContestSources.cyclicNames(entries))
    }

    // ------------------------------------------------------------------ pełne pobrane pliki

    @Test
    fun parsesDownloadedFiles() {
        // Opcjonalnie: -Pcontests.dir=<katalog z sp9cxn.json, pkukf.json, qrzru.ics>
        val dir = System.getProperty("contests.dir")?.let(::File) ?: return
        val sp = File(dir, "sp9cxn.json").takeIf { it.exists() }?.readText()?.let(Sp9cxnCalendar::parse).orEmpty()
        val pk = File(dir, "pkukf.json").takeIf { it.exists() }?.readText()?.let(PkUkfCalendar::parse).orEmpty()
        val qrz = File(dir, "qrzru.ics").takeIf { it.exists() }?.readText()?.let(QrzRuCalendar::parse).orEmpty()
        val merged = ContestSources.merge(listOf(sp, pk, qrz))
        println("SP9CXN=${sp.size} (krajowe ${sp.count { it.polish }}), PK-UKF=${pk.size}, QRZ.RU=${qrz.size}, po złączeniu=${merged.size}")
        println("Cykliczne: ${ContestSources.cyclicNames(merged)}")
        merged.filter { it.sources.size > 1 }.forEach { println("  złączone: ${it.name} ${fmt(it.startMs)} ${it.sources}") }
        qrz.filter { it.originalName != null }.forEach { println("  RU: ${it.originalName} -> ${it.name}") }
        // Możliwe niezłączone duplikaty: ten sam start, różne źródła
        merged.groupBy { it.startMs }.values.filter { g -> g.map { it.source }.distinct().size > 1 }.forEach { g ->
            println("  ten sam start ${fmt(g.first().startMs)}: ${g.map { "${it.name} [${it.source}]" }}")
        }
        assertTrue(qrz.none { RussianContestNames.isCyrillic(it.name) })
    }
}
