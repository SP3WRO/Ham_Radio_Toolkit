package io.github.sp3wro.hamradiotoolkit.data.awards

import io.github.sp3wro.hamradiotoolkit.data.contests.ContestText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Publiczne pliki radiodyplom.pl. Serwis zezwala na korzystanie wyłącznie z tych dwóch plików – strony
 * akcji (/ses/) ani informacji o postępach aplikacja nie pobiera. Czasy w plikach są podane w UTC.
 */
object RadiodyplomFeed {
    const val EVENTS_URL = "https://radiodyplom.pl/public_events.json"
    const val BANDMAP_URL = "https://radiodyplom.pl/public_bandmap.json"
    const val PAGE_URL = "https://radiodyplom.pl/"
    private const val BANDMAP_WINDOW_MINUTES = 30
    private const val MINUTE_MS = 60_000L

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    private class EventsFile(val events: List<EventDto> = emptyList())

    @Serializable
    private class EventDto(
        val id: Long? = null,
        val title: String? = null,
        @SerialName("title_global") val titleGlobal: String? = null,
        val url: String? = null,
        val status: String? = null,
        @SerialName("start_date") val startDate: String? = null,
        @SerialName("end_date") val endDate: String? = null,
        val organizers: String? = null,
        @SerialName("organizers_global") val organizersGlobal: String? = null
    )

    @Serializable
    private class BandmapFile(val spots: List<SpotDto> = emptyList())

    @Serializable
    private class SpotDto(
        val callsign: String? = null,
        val band: String? = null,
        val mode: String? = null,
        @SerialName("frequency_khz") val frequencyKhz: Double? = null,
        @SerialName("time_on_display") val timeOnDisplay: String? = null,
        @SerialName("minutes_ago") val minutesAgo: Int = 0,
        @SerialName("ses_list") val sesList: List<SpotEventDto> = emptyList()
    )

    @Serializable
    private class SpotEventDto(
        val id: Long? = null,
        val title: String? = null,
        @SerialName("points_info") val pointsInfo: String? = null
    )

    fun parseEvents(text: String): List<AwardEvent> =
        json.decodeFromString<EventsFile>(text).events.mapNotNull(::toEvent)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.startMs }, { it.name }))

    private fun toEvent(e: EventDto): AwardEvent? {
        if (e.status == "ended") return null
        val id = e.id?.toString() ?: return null
        val name = e.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val start = e.startDate?.let(ContestText::utcMillis) ?: return null
        val end = e.endDate?.let(ContestText::utcMillis) ?: return null
        return AwardEvent(
            id = id,
            name = name,
            globalName = e.titleGlobal?.trim()?.takeIf { it.isNotEmpty() },
            startMs = start,
            // Koniec w pliku to ostatnia minuta akcji
            endMs = end + MINUTE_MS,
            organizers = e.organizers?.trim().orEmpty(),
            globalOrganizers = e.organizersGlobal?.trim()?.takeIf { it.isNotEmpty() },
            url = e.url?.trim()?.takeIf { it.startsWith("http") } ?: PAGE_URL
        )
    }

    fun parseBandmap(text: String): List<BandmapSpot> =
        json.decodeFromString<BandmapFile>(text).spots.mapNotNull { s ->
            val call = s.callsign?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            BandmapSpot(
                callsign = call,
                band = s.band?.trim().orEmpty(),
                mode = s.mode?.trim().orEmpty(),
                frequencyKhz = s.frequencyKhz,
                timeUtc = s.timeOnDisplay?.trim().orEmpty(),
                minutesAgo = s.minutesAgo,
                events = s.sesList.mapNotNull { e ->
                    val id = e.id?.toString() ?: return@mapNotNull null
                    BandmapEvent(id, e.title?.trim().orEmpty(), e.pointsInfo?.trim().orEmpty())
                }
            )
        }

    /** Stacje z kopii sprzed [copyAgeMs], które nadal mieszczą się w 30-minutowym oknie mapy pasm. */
    internal fun activeSpots(spots: List<BandmapSpot>, copyAgeMs: Long): List<BandmapSpot> =
        spots.filter { it.minutesAgo + copyAgeMs / MINUTE_MS <= BANDMAP_WINDOW_MINUTES }

    /** Stacje obserwowanych akcji: znak -> akcje. */
    internal fun followedTags(spots: List<BandmapSpot>, followed: Set<String>): Map<String, List<AwardStationTag>> {
        val result = HashMap<String, MutableList<AwardStationTag>>()
        for (spot in spots) {
            for (event in spot.events) {
                if (event.id !in followed) continue
                val tag = AwardStationTag(event.title.ifEmpty { "#${event.id}" }, event.pointsInfo)
                val tags = result.getOrPut(spot.callsign) { ArrayList() }
                if (tag !in tags) tags += tag
            }
        }
        return result
    }
}
