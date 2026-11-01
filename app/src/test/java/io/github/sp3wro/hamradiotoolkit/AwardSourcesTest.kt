package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.awards.AwardStationTag
import io.github.sp3wro.hamradiotoolkit.data.awards.RadiodyplomFeed
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

    // ------------------------------------------------------------------ public_events.json

    private val eventsJson = """
        {"generated_at":"2026-10-31T12:00:01+00:00","total_events":4,"events":[
        {"id":285,"title":"YL Power! 2027","title_global":null,"url":"https://radiodyplom.pl/ses/yl-power-2027","status":"upcoming",
         "status_localized":"nadchodząca","start_date":"2027-03-01 00:00:00","end_date":"2027-03-08 23:59:00","organizers":"SP0PYL","organizers_global":null},
        {"id":183,"title":"HF10SQ8W - 10 lat aktywności SQ8W","title_global":"HF10SQ8W - 10 Years of SQ8W Activity","url":"https://radiodyplom.pl/ses/hf10sq8w",
         "status":"ongoing","start_date":"2026-03-16 00:00:00","end_date":"2026-12-30 00:00:00","organizers":"Wojciech SQ8W","organizers_global":"W. SQ8W"},
        {"id":100,"title":"Zakończona","url":"https://radiodyplom.pl/ses/x","status":"ended","start_date":"2026-01-01 00:00:00","end_date":"2026-01-02 00:00:00"},
        {"id":101,"title":"Bez końca","status":"ongoing","start_date":"2026-01-01 00:00:00","end_date":null}
        ]}
    """.trimIndent()

    @Test
    fun parsesPublicEvents() {
        val events = RadiodyplomFeed.parseEvents(eventsJson)
        // Zakończone i bez daty końca pominięte; identyfikatory takie jak w zapisanych obserwowanych akcjach
        assertEquals(listOf("183", "285"), events.map { it.id })
        val hf = events[0]
        assertEquals(utc("2026-03-16 00:00"), hf.startMs)
        assertEquals(utc("2026-12-30 00:01"), hf.endMs)
        assertEquals("HF10SQ8W - 10 lat aktywności SQ8W", hf.title(polish = true))
        assertEquals("HF10SQ8W - 10 Years of SQ8W Activity", hf.title(polish = false))
        assertEquals("W. SQ8W", hf.organizersText(polish = false))
        val yl = events[1]
        // Bez wersji angielskiej zostaje tytuł oryginalny; ostatnia minuta akcji jeszcze się liczy
        assertEquals("YL Power! 2027", yl.title(polish = false))
        assertEquals("SP0PYL", yl.organizersText(polish = false))
        assertEquals("https://radiodyplom.pl/ses/yl-power-2027", yl.url)
        assertTrue(yl.isRunning(utc("2027-03-08 23:59")))
        assertFalse(yl.isRunning(utc("2027-03-09 00:00")))
    }

    // ------------------------------------------------------------------ public_bandmap.json

    private val bandmapJson = """
        {"spots":[
        {"callsign":"sp5rzl","band":"70cm","mode":"C4FM","frequency_khz":432200,"time_on_display":"14:49","minutes_ago":0,"primary_ses_id":346,
         "ses_list":[{"id":346,"title":"Szalone Lata 90-00","points_info":"(+1 p.)"},{"id":183,"title":"HF10SQ8W","points_info":""}]},
        {"callsign":"SP9ARX","band":"40m","mode":"SSB","frequency_khz":7177.5,"time_on_display":"14:43","minutes_ago":6,"primary_ses_id":346,
         "ses_list":[{"id":346,"title":"Szalone Lata 90-00","points_info":"(+1 p.)"}]},
        {"callsign":"SP3OLD","band":"20m","mode":"CW","frequency_khz":null,"time_on_display":"14:21","minutes_ago":28,"ses_list":[]}
        ],"window_minutes":30,"generated_utc":"14:50:01","count":3}
    """.trimIndent()

    @Test
    fun parsesPublicBandmap() {
        val spots = RadiodyplomFeed.parseBandmap(bandmapJson)
        assertEquals(listOf("SP5RZL", "SP9ARX", "SP3OLD"), spots.map { it.callsign })
        assertEquals(432200.0, spots[0].frequencyKhz!!, 0.0)
        assertEquals(7177.5, spots[1].frequencyKhz!!, 0.0)
        assertNull(spots[2].frequencyKhz)
        assertEquals("14:49", spots[0].timeUtc)
        assertEquals(listOf("346", "183"), spots[0].events.map { it.id })
    }

    @Test
    fun oldBandmapCopyDropsSpotsOutsideWindow() {
        val spots = RadiodyplomFeed.parseBandmap(bandmapJson)
        assertEquals(3, RadiodyplomFeed.activeSpots(spots, 2 * 60_000L).size)
        assertEquals(listOf("SP5RZL", "SP9ARX"), RadiodyplomFeed.activeSpots(spots, 3 * 60_000L).map { it.callsign })
        assertTrue(RadiodyplomFeed.activeSpots(spots, 31 * 60_000L).isEmpty())
    }

    @Test
    fun tagsOnlyStationsOfFollowedEvents() {
        val spots = RadiodyplomFeed.parseBandmap(bandmapJson)
        val tags = RadiodyplomFeed.followedTags(spots, setOf("346"))
        assertEquals(setOf("SP5RZL", "SP9ARX"), tags.keys)
        assertEquals(listOf(AwardStationTag("Szalone Lata 90-00", "(+1 p.)")), tags["SP5RZL"])
        assertEquals("Szalone Lata 90-00 (+1 p.)", tags.getValue("SP5RZL").single().text)
        assertEquals("HF10SQ8W", RadiodyplomFeed.followedTags(spots, setOf("183")).getValue("SP5RZL").single().text)
        assertTrue(RadiodyplomFeed.followedTags(spots, emptySet()).isEmpty())
    }

    // ------------------------------------------------------------------ pełne pobrane pliki

    @Test
    fun parsesDownloadedFiles() {
        // Opcjonalnie: -Pawards.dir=<katalog z public_events.json i public_bandmap.json>
        val dir = System.getProperty("awards.dir")?.let(::File) ?: return
        val events = RadiodyplomFeed.parseEvents(File(dir, "public_events.json").readText())
        println("Akcje: ${events.size}")
        events.forEach { println("  ${it.id}: ${it.name}") }
        assertTrue(events.isNotEmpty())
        val spots = RadiodyplomFeed.parseBandmap(File(dir, "public_bandmap.json").readText())
        println("Mapa pasm: ${spots.size}")
        spots.forEach { println("  ${it.callsign} ${it.frequencyKhz} ${it.band} ${it.mode} ${it.events}") }
    }
}
