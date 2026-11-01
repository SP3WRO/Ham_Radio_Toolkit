package io.github.sp3wro.hamradiotoolkit.data.awards

/** Akcja dyplomowa z public_events.json. [endMs] – koniec akcji (wyłącznie). Wersje "global" podaje tylko część akcji. */
data class AwardEvent(
    val id: String,
    val name: String,
    val globalName: String?,
    val startMs: Long,
    val endMs: Long,
    val organizers: String,
    val globalOrganizers: String?,
    val url: String
) {
    fun isRunning(now: Long) = now in startMs until endMs
    fun title(polish: Boolean) = if (polish) name else globalName ?: name
    fun organizersText(polish: Boolean) = if (polish) organizers else globalOrganizers ?: organizers
}

/** Akcja, do której liczy się stacja z mapy pasm. [pointsInfo] – tekst serwisu, np. "(+1 p.)". */
data class BandmapEvent(val id: String, val title: String, val pointsInfo: String)

/** Stacja z public_bandmap.json. [timeUtc] – czas zgłoszenia "HH:mm", [minutesAgo] – względem utworzenia pliku. */
data class BandmapSpot(
    val callsign: String,
    val band: String,
    val mode: String,
    val frequencyKhz: Double?,
    val timeUtc: String,
    val minutesAgo: Int,
    val events: List<BandmapEvent>
)

/** Stacja obserwowanej akcji – do oznaczania spotów w DX Clusterze. */
data class AwardStationTag(val eventName: String, val pointsInfo: String) {
    val text: String get() = "$eventName $pointsInfo".trim()
}
