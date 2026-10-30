package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.awards.AwardDetails
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardProgressCalculator
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardRule
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardStation
import io.github.sp3wro.hamradiotoolkit.data.awards.RadiodyplomCalendar
import io.github.sp3wro.hamradiotoolkit.data.awards.RadiodyplomEventPage
import io.github.sp3wro.hamradiotoolkit.data.awards.RankingEntry
import io.github.sp3wro.hamradiotoolkit.data.awards.RepeatLimit
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AwardSourcesTest {
    private fun utc(s: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time

    // ------------------------------------------------------------------ kalendarz ICS

    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//RadioDyplom//SES Calendar//EN\r\n" +
        "BEGIN:VEVENT\r\nUID:ses-239-20250101@radiodyplom.pl\r\nDTSTART;VALUE=DATE:20250101\r\nDTEND;VALUE=DATE:20270102\r\n" +
        "SUMMARY:E7POTA\r\n" +
        "DESCRIPTION:[b][b]Let's celebrate our POTA activities with a special call sign [url=https://e7pota.ba/]E7 POTA[/url]\uFEFF !!![/b]\r\n" +
        "URL:https://radiodyplom.pl/ses/239\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:ses-345-20261015@radiodyplom.pl\r\nDTSTART;VALUE=DATE:20261015\r\nDTEND;VALUE=DATE:20261111\r\n" +
        "SUMMARY:70. rocznica rewolucji 1956 na Węgrzech\r\nDESCRIPTION: Budapeszt\\, 1956 \r\n" +
        "URL:https://radiodyplom.pl/ses/345\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:ses-999-20261101@radiodyplom.pl\r\nDTSTART;VALUE=DATE:20261101\r\nSUMMARY:Bez URL i końca\r\nEND:VEVENT\r\n" +
        "END:VCALENDAR\r\n"

    @Test
    fun parsesIcsCalendar() {
        val events = RadiodyplomCalendar.parse(ics)
        assertEquals(listOf("239", "345", "999"), events.map { it.id })
        val e7 = events[0]
        assertEquals("E7POTA", e7.name)
        assertEquals(utc("2025-01-01 00:00"), e7.startMs)
        assertEquals(utc("2027-01-02 00:00"), e7.endMs)
        assertEquals("Let's celebrate our POTA activities with a special call sign E7 POTA !!!", e7.description)
        val hu = events[1]
        assertEquals("Budapeszt, 1956", hu.description)
        assertTrue(hu.isRunning(utc("2026-11-10 23:59")))
        assertFalse(hu.isRunning(utc("2026-11-11 00:00")))
        // Bez DTEND – jeden dzień, adres akcji z numeru w UID
        assertEquals(utc("2026-11-02 00:00"), events[2].endMs)
        assertEquals("https://radiodyplom.pl/ses/999", events[2].url)
    }

    // ------------------------------------------------------------------ strona akcji (fragmenty prawdziwej strony)

    private fun card(call: String, points: Int, type: String? = null) = """
        <div class="column is-narrow">
            <!-- Górny róg: Znak i powiadomienie 1x -->
            <div class="is-flex">
                <a href="https://radiodyplom.pl/sesStation.php?ses_id=345&ses_callsign=$call"
                   class="has-text-weight-bold is-size-5" style="font-family: var(--font-mono);">
                    $call                        </a>
            </div>
            <!-- Dolny róg: Punkty i Typ stacji -->
            <div class="ses-station-meta">
                <span class="tag is-info is-light">
                    <strong class="ses-station-points">$points</strong>&nbsp;
                    punktów                        </span>
                ${if (type != null) """<span class="tag is-link is-light"><span class="material-symbols-outlined mr-1" style="font-size: 14px;">badge</span> $type                        </span>""" else ""}
            </div>
        </div>
    """

    private fun rankingRow(rank: Int, call: String, points: Int, qsos: Int) = """
        <tr class="ranking-row " style="">
            <td class="has-text-centered">$rank.</td>
            <td class="has-text-weight-bold" style="font-family: var(--font-mono);"><a href="/sesParticipant.php?ses_id=345&callsign=$call">$call</a></td>
            <td class="has-text-right">$points</td>
            <td class="has-text-right">$qsos</td>
            <td class="has-text-centered"></td>
        </tr>
    """

    private val page = """
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
    """

    @Test
    fun parsesEventPage() {
        val d = RadiodyplomEventPage.parse(page, "345", "sp3wro", nowMs = 1L)
        assertEquals("70. rocznica rewolucji 1956 na Węgrzech", d.name)
        assertEquals("HA5KDR, SP5ZIM", d.organizers)
        assertEquals("Dyplom\nPunkty przyznają stacje HA1956&co", d.description)
        assertEquals(utc("2026-10-15 00:00"), d.startMs)
        assertEquals(utc("2026-11-10 22:00"), d.endMs)
        assertEquals(RepeatLimit.BAND_MODE_DAILY, d.repeatLimit)
        assertEquals(listOf("FT8", "FT4", "RTTY"), d.digiGroup)
        assertTrue(d.hasTimeMultipliers)
        // Powtórzona karta HA2GY pominięta
        assertEquals(
            listOf(AwardStation("HA1956BA", 10, "Okolicznościowa"), AwardStation("HA2GY", 10, "Klubowa"), AwardStation("3Z45MDY", 2, "Indywidualna")),
            d.stations
        )
        assertEquals(2, d.awards.size)
        val eu = d.awards[0]
        assertEquals("BUDAPEST 1956 - EU", eu.title)
        assertEquals(35, eu.minPoints)
        assertEquals(listOf("40m", "20m"), eu.bands)
        assertEquals(listOf("SSB", "CW", "DIGI"), eu.modes)
        assertEquals(listOf("Europa"), eu.allowedRegions)
        assertEquals(listOf("HA (Hungary)"), eu.excludedRegions)
        assertEquals(listOf("SP (Poland)"), d.awards[1].allowedRegions)
        assertEquals(70, d.awards[1].minPoints)
        assertEquals(3, d.participants)
        assertEquals("2026-10-26 22:26:33 UTC", d.rankingUpdated)
        assertEquals("SP3WRO", d.rankingCallsign)
        // Dokładnie ten znak – nie SP3WRO/P
        assertEquals(RankingEntry(2, 40, 4), d.myRanking)
        assertNull(RadiodyplomEventPage.parse(page, "345", "SP9XYZ").myRanking)
    }

    @Test
    fun unknownPageGivesEmptyDetails() {
        val d = RadiodyplomEventPage.parse("<html><body>Błąd 404</body></html>", "1", "")
        assertTrue(d.stations.isEmpty())
        assertTrue(d.awards.isEmpty())
        assertNull(d.startMs)
        assertNull(d.myRanking)
    }

    @Test
    fun repeatLimitFromRuleText() {
        assertEquals(RepeatLimit.BAND_MODE, RadiodyplomEventPage.repeatLimit("Z każdą stacją organizatora uczestnik ma osobny limit dla każdej pary: pasmo + emisja. Tylko jedno zaliczenie w całej akcji."))
        assertEquals(RepeatLimit.BAND, RadiodyplomEventPage.repeatLimit("Osobny limit dla każdego pasma."))
        assertEquals(RepeatLimit.STATION, RadiodyplomEventPage.repeatLimit("Z każdą stacją tylko jedno zaliczenie w całej akcji."))
        assertEquals(RepeatLimit.BAND_MODE, RadiodyplomEventPage.repeatLimit(""))
    }

    // ------------------------------------------------------------------ postęp z własnego logu

    private fun qso(call: String, time: String, band: String, mode: String) =
        QsoEntry(callsign = call, datetimeUtc = time, frequency = "", band = band, mode = mode, rstSent = "59", rstRcvd = "59")

    private val details = AwardDetails(
        eventId = "345",
        startMs = utc("2026-10-15 00:00"),
        endMs = utc("2026-11-10 22:00"),
        repeatLimit = RepeatLimit.BAND_MODE,
        digiGroup = listOf("FT8", "FT4", "RTTY"),
        stations = listOf(AwardStation("HA1956BA", 10), AwardStation("HA2GY", 5)),
        awards = listOf(
            AwardRule("EU", 20, bands = listOf("40m", "20m"), modes = listOf("SSB", "CW", "DIGI")),
            AwardRule("Tylko CW", 15, modes = listOf("CW"))
        )
    )

    private val logs = listOf(
        qso("HA1956BA", "2026-10-16 10:00", "40m", "SSB"),
        qso("HA1956BA", "2026-10-16 10:05", "40m", "USB"),   // powtórka: to samo pasmo i emisja
        qso("HA1956BA", "2026-10-17 10:00", "40m", "FT8"),
        qso("HA1956BA", "2026-10-17 11:00", "40m", "FT4"),   // FT4 = FT8 w grupie DIGI
        qso("HA2GY/P", "2026-10-18 10:00", "20m", "CW"),
        qso("HA2GY", "2026-10-18 10:00", "80m", "CW"),       // 80 m poza pasmami dyplomu EU
        qso("HA1956BA", "2026-10-14 23:59", "20m", "CW"),    // przed startem akcji
        qso("SP3ABC", "2026-10-18 10:00", "20m", "CW")       // nie jest stacją akcji
    )

    @Test
    fun countsPointsFromLog() {
        val p = AwardProgressCalculator.compute(details, logs)
        val eu = p.rules[0]
        assertEquals(25, eu.points) // SSB 10 + DIGI 10 + HA2GY/P CW 5
        assertEquals(3, eu.countedQsos)
        assertTrue(eu.achieved)
        val cw = p.rules[1]
        assertEquals(10, cw.points) // HA2GY: CW na 20 m i 80 m
        assertFalse(cw.achieved)
        assertEquals(mapOf("HA1956BA" to 4, "HA2GY" to 2), p.workedStations)
        assertEquals(6, p.matchingQsos)
    }

    @Test
    fun dailyLimitCountsAgainNextUtcDay() {
        val daily = details.copy(repeatLimit = RepeatLimit.BAND_MODE_DAILY)
        val log = listOf(
            qso("HA1956BA", "2026-10-16 10:00", "40m", "SSB"),
            qso("HA1956BA", "2026-10-16 23:59", "40m", "SSB"),
            qso("HA1956BA", "2026-10-17 00:00", "40m", "SSB")
        )
        assertEquals(20, AwardProgressCalculator.compute(daily, log).rules[0].points)
        assertEquals(10, AwardProgressCalculator.compute(details.copy(repeatLimit = RepeatLimit.STATION), log).rules[0].points)
    }

    // ------------------------------------------------------------------ pełne pobrane pliki

    @Test
    fun parsesDownloadedFiles() {
        // Opcjonalnie: -Pawards.dir=<katalog z radiodyplom.ics i stronami ses_<id>.html>
        val dir = System.getProperty("awards.dir")?.let(::File) ?: return
        File(dir, "radiodyplom.ics").takeIf { it.exists() }?.let { f ->
            val events = RadiodyplomCalendar.parse(f.readText())
            println("Akcje w kalendarzu: ${events.size}")
            events.forEach { println("  ${it.id}: ${it.name}") }
            assertTrue(events.isNotEmpty())
        }
        dir.listFiles { f -> f.name.matches(Regex("""ses_\d+\.html""")) }?.forEach { f ->
            val id = f.name.removePrefix("ses_").removeSuffix(".html")
            val started = System.nanoTime()
            val d = RadiodyplomEventPage.parse(f.readText(), id, System.getProperty("awards.call") ?: "SP3WRO")
            println("ses $id '${d.name}' (${(System.nanoTime() - started) / 1_000_000} ms): stacji ${d.stations.size}, dyplomów ${d.awards.size}, " +
                "uczestników ${d.participants}, limit ${d.repeatLimit}, DIGI ${d.digiGroup}, mnożniki ${d.hasTimeMultipliers}, ranking ${d.myRanking}")
            d.awards.forEach { println("    ${it.title}: ${it.minPoints} ${it.unit} dla ${it.allowedRegions} bez ${it.excludedRegions}") }
            assertTrue(d.stations.isNotEmpty())
            assertTrue(d.awards.isNotEmpty())
        }
    }
}
