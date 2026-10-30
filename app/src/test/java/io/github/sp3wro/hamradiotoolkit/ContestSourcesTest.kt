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

    private fun spCell(text: String) = """<td class="TC-1"><p class="Normal P-5"><span class="C-16">$text</span></p></td>"""

    private val sp9cxnHtml = """
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
        </table>
    """.trimIndent()

    @Test
    fun sp9cxnParsesDomesticAndInternationalTables() {
        val list = Sp9cxnCalendar.parse(sp9cxnHtml).associateBy { it.name }
        assertEquals(list.keys.toString(), 5, list.size)

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

        // Literówka w roku końca (2016) i gwiazdka "regulamin poprzedniej edycji"
        val sunday = list.getValue("Sunday Contest CW")
        assertEquals("2026-10-18 18:00", fmt(sunday.endMs))
        assertEquals(1, sunday.notes.size)
        assertNull(sunday.rulesPlUrl)
    }

    // ------------------------------------------------------------------ PK-UKF

    private fun pkRow(date: String, time: String, name: String, band: String, org: String, deadline: String) =
        """<tr><td>1</td><td>$date</td><td>$time</td><td><strong>$name</strong></td><td>$band</td><td>$org</td>
           <td><a href="https://pk-ukf.pl/pl.pdf">Regulamin</a></td><td><a href="https://pk-ukf.pl/en.pdf">Regulamin</a></td><td>$deadline</td></tr>"""

    private val pkHtml = """
        <table><tr><td>Lp.</td><td>Data</td><td>Czas trwania UTC</td><td>Nazwa zawodów</td><td>Pasmo</td><td>Organizator</td><td>Regulamin polski</td><td>Regulamin angielski</td><td>Termin wysyłki logów</td></tr>
        ${pkRow("3 Listopad 2026", "18:00 &#8211; 22:00", "SPAC Listopad 144MHz", "144 MHz", "PK UKF", "10 Listopad 2026 (7dni)")}
        ${pkRow("7-8 Listopad 2026", "14:00 &#8211; 14:00", "MMC 144 MHz", "144MHz CW", "ARI", "11 Listopad 2026 (3dni)")}
        ${pkRow("31 Październik &#8211; 1 Listopad 2026", "00:00 &#8211; 23:59", "EME ARRL I-part", "50 .. 1296 MHz", "ARRL", "30 dni po zawodach")}
        ${pkRow("6 Styczeń 2026", "18:0 – 22:00", "SPAC Styczeń 144MHz", "144 MHz", "PK UKF", "13 Styczeń 2026")}
        ${pkRow("1 Styczeń 2026", "14.00 - 18.00", "AGCW VHF/UHF Contest", "144 i 432MHz", "AGCW", "2 Styczeń 2026")}
        ${pkRow("?? Listopad 2026", "18:00 &#8211; 21:59", "YL VHF Contest", "144 i 432 MHz", "LRAL", "28 Listopad 2026")}
        </table>
    """.trimIndent()

    @Test
    fun pkUkfParsesDateFormats() {
        val list = PkUkfCalendar.parse(pkHtml).associateBy { it.name }
        assertEquals(list.keys.toString(), 5, list.size) // "?? Listopad" pominięty

        val spac = list.getValue("SPAC Listopad 144MHz")
        assertTrue(spac.polish)
        assertTrue(spac.vhf)
        assertEquals("2026-11-03 18:00", fmt(spac.startMs))
        assertEquals("2026-11-03 22:00", fmt(spac.endMs))
        assertEquals("https://pk-ukf.pl/pl.pdf", spac.rulesPlUrl)
        assertEquals("10 Listopad 2026 (7dni)", spac.logDeadline)

        val mmc = list.getValue("MMC 144 MHz")
        assertFalse(mmc.polish)
        assertTrue(ContestMode.CW in mmc.modes)
        assertEquals("2026-11-07 14:00", fmt(mmc.startMs))
        assertEquals("2026-11-08 14:00", fmt(mmc.endMs))

        val eme = list.getValue("EME ARRL I-part")
        assertEquals("2026-10-31 00:00", fmt(eme.startMs))
        assertEquals("2026-11-02 00:00", fmt(eme.endMs))

        assertEquals("2026-01-06 18:00", fmt(list.getValue("SPAC Styczeń 144MHz").startMs))
        assertEquals("2026-01-01 18:00", fmt(list.getValue("AGCW VHF/UHF Contest").endMs))
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
        val sp = Sp9cxnCalendar.parse(sp9cxnHtml)
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
    }

    @Test
    fun cyclicContestsDetected() {
        val week = 7 * 86_400_000L
        val base = Sp9cxnCalendar.parse(sp9cxnHtml).first()
        val entries = (0 until 4).map { base.copy(name = "MWC Contest CW", startMs = base.startMs + it * week, endMs = base.endMs + it * week) } +
            base.copy(name = "SP CW Contest")
        assertEquals(setOf("mwc contest cw"), ContestSources.cyclicNames(entries))
    }

    // ------------------------------------------------------------------ pełne pobrane pliki

    @Test
    fun parsesDownloadedFiles() {
        // Opcjonalnie: -Pcontests.dir=<katalog z sp9cxn.html, pkukf.html, qrzru.ics>
        val dir = System.getProperty("contests.dir")?.let(::File) ?: return
        val sp = File(dir, "sp9cxn.html").takeIf { it.exists() }?.readText()?.let(Sp9cxnCalendar::parse).orEmpty()
        val pk = File(dir, "pkukf.html").takeIf { it.exists() }?.readText()?.let(PkUkfCalendar::parse).orEmpty()
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
