package io.github.sp3wro.hamradiotoolkit.data.awards

import kotlinx.serialization.Serializable

/** Akcja dyplomowa z kalendarza radiodyplom.pl (ICS). [endMs] – koniec dnia ostatniego (wyłącznie). */
data class AwardEvent(
    val id: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val description: String,
    val url: String
) {
    fun isRunning(now: Long) = now in startMs until endMs
}

/** Stacja przyznająca punkty w akcji. [type] – np. "Okolicznościowa", "Klubowa", "Indywidualna". */
@Serializable
data class AwardStation(val callsign: String, val points: Int, val type: String = "")

/** Jeden dyplom akcji: próg punktów, dozwolone pasma/emisje i ograniczenia geograficzne. */
@Serializable
data class AwardRule(
    val title: String,
    val minPoints: Int?,
    val unit: String = "punktów",
    val bands: List<String> = emptyList(),
    val modes: List<String> = emptyList(),
    val allowedRegions: List<String> = emptyList(),
    val excludedRegions: List<String> = emptyList()
)

/** Jak liczą się kolejne łączności z tą samą stacją. */
@Serializable
enum class RepeatLimit { BAND_MODE, BAND_MODE_DAILY, BAND, STATION }

/** Pozycja znaku w rankingu akcji (na podstawie logów wgranych przez organizatorów). */
@Serializable
data class RankingEntry(val rank: Int, val points: Int, val qsos: Int)

/** Szczegóły akcji ze strony radiodyplom.pl/ses/<id>. */
@Serializable
data class AwardDetails(
    val eventId: String,
    val name: String = "",
    val startMs: Long? = null,
    val endMs: Long? = null,
    val organizers: String = "",
    val description: String = "",
    val repeatRule: String = "",
    val repeatLimit: RepeatLimit = RepeatLimit.BAND_MODE,
    /** Emisje liczone wspólnie jako DIGI (puste – bez grupowania). */
    val digiGroup: List<String> = emptyList(),
    val hasTimeMultipliers: Boolean = false,
    val stations: List<AwardStation> = emptyList(),
    val awards: List<AwardRule> = emptyList(),
    val participants: Int = 0,
    val rankingUpdated: String = "",
    /** Znak, dla którego odczytano [myRanking]. */
    val rankingCallsign: String = "",
    val myRanking: RankingEntry? = null,
    val fetchedAtMs: Long = 0
)
