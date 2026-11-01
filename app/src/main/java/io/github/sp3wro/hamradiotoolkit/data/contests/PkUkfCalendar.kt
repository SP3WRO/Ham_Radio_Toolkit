package io.github.sp3wro.hamradiotoolkit.data.contests

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kalendarz zawodów VHF/UHF/SHF PK-UKF / SP1CNV. Na prośbę PK-UKF aplikacja nie czyta ich strony:
 * dane pobiera co 48 godzin workflow w tym repozytorium (tools/pkukf-calendar) i publikuje jako JSON.
 */
object PkUkfCalendar {
    const val JSON_URL = "https://raw.githubusercontent.com/SP3WRO/Ham_Radio_Toolkit/main/tools/pkukf-calendar/data/contests.json"

    private const val DAY_MS = 86_400_000L
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val POLISH_ORGANIZER = Regex("""PK[ -]?UKF|PZK|\bSP\d?\b""", RegexOption.IGNORE_CASE)

    @Serializable
    private class Calendar(val contests: List<Contest> = emptyList())

    @Serializable
    private class Contest(
        val name: String? = null,
        @SerialName("start_utc") val startUtc: String? = null,
        @SerialName("end_utc") val endUtc: String? = null,
        val band: String? = null,
        val organizer: String? = null,
        @SerialName("rules_pl") val rulesPl: String? = null,
        @SerialName("rules_en") val rulesEn: String? = null,
        @SerialName("log_deadline") val logDeadline: String? = null,
        @SerialName("log_deadline_raw") val logDeadlineRaw: String? = null
    )

    /** Wpisy z nieustalonym dniem ("?? Listopad", start_utc = null) są pomijane – nie da się ich umieścić w kalendarzu. */
    fun parse(text: String): List<ContestEntry> =
        json.decodeFromString<Calendar>(text).contests.mapNotNull(::toEntry).sortedBy { it.startMs }

    private fun toEntry(c: Contest): ContestEntry? {
        val name = c.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val start = c.startUtc?.let(ContestText::utcMillis) ?: return null
        var end = c.endUtc?.let(ContestText::utcMillis) ?: return null
        if (end <= start) end += DAY_MS

        val bands = c.band?.trim().orEmpty()
        val organizer = c.organizer?.trim().orEmpty().takeUnless { it == "-" || it == "–" }.orEmpty()
        return ContestEntry(
            name = name,
            startMs = start,
            endMs = ContestText.roundEnd(end),
            source = ContestSource.PK_UKF,
            polish = POLISH_ORGANIZER.containsMatchIn(organizer) || ContestText.looksPolish(name),
            vhf = true,
            modes = ContestText.modesIn("$name $bands") + ContestMode.VHF,
            bands = bands,
            rulesUrl = link(c.rulesEn) ?: link(c.rulesPl),
            rulesPlUrl = link(c.rulesPl),
            organizer = organizer,
            logDeadline = deadline(c)
        )
    }

    // Data z pola log_deadline nie zależy od języka; tekst ze strony tylko gdy daty nie da się ustalić
    private fun deadline(c: Contest): String {
        val date = ISO_DATE.matchEntire(c.logDeadline.orEmpty())?.groupValues
        if (date != null) return "${date[3]}.${date[2]}.${date[1]}"
        return c.logDeadlineRaw?.trim().orEmpty().takeUnless { it == "-" || it == "–" }.orEmpty()
    }

    private fun link(url: String?): String? = url?.trim()?.takeIf { it.startsWith("http") }
}
