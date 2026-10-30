package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion
import io.github.sp3wro.hamradiotoolkit.data.model.BandplanProvider
import io.github.sp3wro.hamradiotoolkit.data.model.BandplanText
import io.github.sp3wro.hamradiotoolkit.data.model.RegionalInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Słownik zwrotów musi obejmować wszystkie polskie opisy bandplanów – inaczej w innych językach zostałby polski tekst. */
class BandplanTextTest {
    private lateinit var saved: Locale
    private val polishWord = Regex("""[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]|(?<!\p{L})(na|dla|oraz|lub|tylko|bez|przy|jest)(?!\p{L})""")

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.ENGLISH) }
    @After fun tearDown() { Locale.setDefault(saved) }

    @Test
    fun everyBandplanTextIsCovered() {
        val texts = IaruRegion.values().flatMap { region ->
            val info = RegionalInfo.forRegion(region, null)
            val caribbean = RegionalInfo.forRegion(region, "JM")
            BandplanProvider.bandsFor(region).flatMap { band -> band.segments.flatMap { listOf(it.mode, it.description) } } +
                listOf(info.aprs, info.fmCalling, info.emergency, caribbean.aprs, caribbean.fmCalling, caribbean.emergency)
        }.distinct()
        val leftovers = texts.map { it to BandplanText.localize(it) }.filter { (_, out) -> polishWord.containsMatchIn(out) }
        assertTrue("Nieprzetłumaczone: " + leftovers.joinToString("\n") { "${it.first} -> ${it.second}" }, leftovers.isEmpty())
    }

    @Test
    fun everyBuiltinContestTextIsCovered() {
        val now = System.currentTimeMillis()
        val texts = io.github.sp3wro.hamradiotoolkit.data.contests.ContestCalendar.occurrences(now, now + 400L * 86_400_000L)
            .flatMap { listOf(it.contest.exchange, it.contest.bands) }.distinct()
        val leftovers = texts.map { it to io.github.sp3wro.hamradiotoolkit.data.model.ContestText.localize(it) }.filter { (_, out) -> polishWord.containsMatchIn(out) }
        assertTrue("Nieprzetłumaczone: " + leftovers.joinToString("\n") { "${it.first} -> ${it.second}" }, leftovers.isEmpty())
    }

    @Test
    fun keepsFrequenciesAndModes() {
        val out = BandplanText.localize("CW; QRS 14.055; QRP 14.060")
        assertEquals("CW; QRS 14.055; QRP 14.060", out)
        Locale.setDefault(Locale.forLanguageTag("pl"))
        assertEquals("Tylko CW", BandplanText.localize("Tylko CW"))
    }
}
