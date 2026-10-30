package io.github.sp3wro.hamradiotoolkit.data.awards

import io.github.sp3wro.hamradiotoolkit.data.contests.CivilDate
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns

/** Szacunek postępu do jednego dyplomu z własnego logu. */
data class AwardRuleProgress(val rule: AwardRule, val points: Int, val countedQsos: Int) {
    val achieved: Boolean get() = rule.minPoints != null && points >= rule.minPoints
}

data class AwardProgress(
    val rules: List<AwardRuleProgress>,
    /** Stacje akcji z logu: znak -> liczba QSO w czasie akcji. */
    val workedStations: Map<String, Int>,
    /** QSO z logu w czasie akcji ze stacjami akcji (przed odrzuceniem powtórzeń). */
    val matchingQsos: Int
)

/**
 * Liczy punkty z własnego logu według zasad akcji: czas trwania, pasma i emisje dyplomu, limit powtórzeń
 * (pasmo + emisja, dziennie, …) i grupowanie emisji cyfrowych jako DIGI. Mnożników czasowych ani ograniczeń
 * geograficznych nie liczymy – to szacunek; wiążący jest ranking radiodyplom.pl z logów organizatorów.
 */
object AwardProgressCalculator {
    private val DATETIME = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""")

    fun compute(details: AwardDetails, logs: List<QsoEntry>, fallbackStartMs: Long? = null, fallbackEndMs: Long? = null): AwardProgress {
        val stations = details.stations.associateBy { it.callsign.uppercase() }
        val start = details.startMs ?: fallbackStartMs ?: Long.MIN_VALUE
        val end = details.endMs ?: fallbackEndMs ?: Long.MAX_VALUE
        val digi = details.digiGroup.toSet()

        class Hit(val station: AwardStation, val timeMs: Long, val band: String, val mode: String, val rawMode: String)

        val hits = logs.mapNotNull { qso ->
            val call = qso.callsign.trim().uppercase()
            val station = stations[call] ?: stations[Callsigns.baseCall(call)] ?: return@mapNotNull null
            val time = parseUtc(qso.datetimeUtc) ?: return@mapNotNull null
            if (time !in start until end) return@mapNotNull null
            val raw = normalizeMode(qso.mode)
            Hit(station, time, qso.band.trim().lowercase(), if (raw in digi) "DIGI" else raw, raw)
        }.sortedBy { it.timeMs }

        val rules = details.awards.map { rule ->
            val bands = rule.bands.map { it.lowercase() }.toSet()
            val modes = rule.modes.toSet()
            val seen = HashSet<String>()
            var points = 0
            var counted = 0
            for (h in hits) {
                if (bands.isNotEmpty() && h.band !in bands) continue
                if (modes.isNotEmpty() && h.rawMode !in modes && h.mode !in modes) continue
                val key = when (details.repeatLimit) {
                    RepeatLimit.BAND_MODE -> "${h.station.callsign}|${h.band}|${h.mode}"
                    RepeatLimit.BAND_MODE_DAILY -> "${h.station.callsign}|${h.band}|${h.mode}|${h.timeMs / 86_400_000L}"
                    RepeatLimit.BAND -> "${h.station.callsign}|${h.band}"
                    RepeatLimit.STATION -> h.station.callsign
                }
                if (seen.add(key)) {
                    points += h.station.points
                    counted++
                }
            }
            AwardRuleProgress(rule, points, counted)
        }
        return AwardProgress(
            rules = rules,
            workedStations = hits.groupingBy { it.station.callsign }.eachCount(),
            matchingQsos = hits.size
        )
    }

    /** Emisja z logu w zapisie serwisu (USB/LSB -> SSB, PSK31 zostaje PSK31). */
    internal fun normalizeMode(mode: String): String = when (val m = mode.trim().uppercase()) {
        "USB", "LSB" -> "SSB"
        "D-STAR" -> "DSTAR"
        else -> m
    }

    private fun parseUtc(s: String): Long? {
        val g = DATETIME.find(s.trim())?.groupValues ?: return null
        val y = g[1].toInt()
        val mo = g[2].toInt()
        val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, mo)) return null
        return CivilDate(y, mo, d).epochMillis + (g[4].toInt() * 60 + g[5].toInt()) * 60_000L
    }
}
