package io.github.sp3wro.hamradiotoolkit.utils

import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry

data class EntityStats(val dxcc: Int, val name: String, val qsoCount: Int, val bands: Set<String>, val modes: Set<String>)

data class LogStats(
    val totalQso: Int,
    val uniqueCalls: Int,
    val dxccCount: Int,
    val entities: List<EntityStats>,
    val bandModeCounts: Map<String, Map<String, Int>>, // pasmo -> (grupa emisji -> liczba QSO)
    val dxccPerBand: Map<String, Int>,
    val potaRefs: Set<String>,
    val wwffRefs: Set<String>,
    val withoutDxcc: Int
)

object LogStatistics {
    val BAND_ORDER = listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm", "23cm")
    val MODE_GROUPS = listOf("CW", "PHONE", "DIGI")

    private val WWFF_REF = Regex("^[A-Z0-9]{1,4}FF-\\d{4}$")
    private val POTA_REF = Regex("^[A-Z0-9]{1,4}-\\d{4,5}$")

    // Grupy emisji jak w dyplomach DXCC: CW / fonia / emisje cyfrowe
    fun modeGroup(mode: String): String = when (mode.uppercase()) {
        "CW" -> "CW"
        "SSB", "USB", "LSB", "AM", "FM", "DMR", "C4FM", "DSTAR", "D-STAR" -> "PHONE"
        else -> "DIGI"
    }

    fun compute(logs: List<QsoEntry>): LogStats {
        val withDxcc = logs.filter { it.dxcc != null }
        val entities = withDxcc.groupBy { it.dxcc!! }.map { (dxcc, qsos) ->
            EntityStats(
                dxcc = dxcc,
                name = qsos.firstOrNull { it.country.isNotBlank() }?.country ?: "DXCC $dxcc",
                qsoCount = qsos.size,
                bands = qsos.map { it.band.lowercase() }.toSet(),
                modes = qsos.map { modeGroup(it.mode) }.toSet()
            )
        }.sortedBy { it.name }

        val bandMode = logs.groupBy { it.band.lowercase() }.mapValues { (_, qsos) ->
            qsos.groupingBy { modeGroup(it.mode) }.eachCount()
        }
        val dxccPerBand = withDxcc.groupBy { it.band.lowercase() }.mapValues { (_, qsos) -> qsos.map { it.dxcc }.toSet().size }

        val refs = logs.map { it.huntedRef.uppercase() }.filter { it.isNotBlank() }
        return LogStats(
            totalQso = logs.size,
            uniqueCalls = logs.map { Callsigns.baseCall(it.callsign) }.toSet().size,
            dxccCount = entities.size,
            entities = entities,
            bandModeCounts = bandMode,
            dxccPerBand = dxccPerBand,
            // Wzorzec POTA (XX-1234) pasuje też do WWFF (XXFF-1234), więc WWFF wykluczamy jawnie
            potaRefs = refs.filter { POTA_REF.matches(it) && !WWFF_REF.matches(it) }.toSet(),
            wwffRefs = refs.filter { WWFF_REF.matches(it) }.toSet(),
            withoutDxcc = logs.size - withDxcc.size
        )
    }
}
