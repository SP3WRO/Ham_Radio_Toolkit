package io.github.sp3wro.hamradiotoolkit.data.contests

import io.github.sp3wro.hamradiotoolkit.AppStrings
import io.github.sp3wro.hamradiotoolkit.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kalendarz zawodów SP9CXN: zawody krajowe i międzynarodowe na 3 miesiące, linki do regulaminów (także
 * przetłumaczonych na polski). Aplikacja nie obciąża strony sp9cxn.pzk.pl: dane pobiera co 48 godzin
 * workflow w tym repozytorium (tools/sp9cxn-calendar) i publikuje jako JSON.
 */
object Sp9cxnCalendar {
    const val JSON_URL = "https://raw.githubusercontent.com/SP3WRO/Ham_Radio_Toolkit/main/tools/sp9cxn-calendar/data/contests.json"
    const val PAGE_URL = "https://sp9cxn.pzk.pl/kalendarz.html"
    private const val DAY_MS = 86_400_000L

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    private class Calendar(val contests: List<Contest> = emptyList())

    @Serializable
    private class Contest(
        val name: String? = null,
        val domestic: Boolean = false,
        @SerialName("start_utc") val startUtc: String? = null,
        @SerialName("end_utc") val endUtc: String? = null,
        @SerialName("rules_url") val rulesUrl: String? = null,
        @SerialName("rules_pl_url") val rulesPlUrl: String? = null,
        val unconfirmed: Boolean = false,
        @SerialName("old_rules") val oldRules: Boolean = false,
        @SerialName("no_ru_by_contacts") val noRuByContacts: Boolean = false
    )

    fun parse(text: String): List<ContestEntry> =
        json.decodeFromString<Calendar>(text).contests.mapNotNull(::toEntry).sortedBy { it.startMs }

    private fun toEntry(c: Contest): ContestEntry? {
        val name = c.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val start = c.startUtc?.let(ContestText::utcMillis) ?: return null
        var end = c.endUtc?.let(ContestText::utcMillis) ?: return null
        // Literówki w dacie końca (np. 2016 zamiast 2026) – bierzemy godzinę końca z dnia startu
        if (end <= start || end - start > 14 * DAY_MS) {
            val day = start - Math.floorMod(start, DAY_MS)
            end = day + Math.floorMod(end, DAY_MS)
            if (end <= start) end += DAY_MS
        }

        val notes = ArrayList<String>()
        if (c.unconfirmed) notes += AppStrings.get(R.string.ct_note_unconfirmed)
        if (c.oldRules) notes += AppStrings.get(R.string.ct_note_old_rules)
        if (c.noRuByContacts) notes += AppStrings.get(R.string.ct_note_ru_by)

        val vhf = ContestText.looksVhf(name)
        return ContestEntry(
            name = name,
            startMs = start,
            endMs = ContestText.roundEnd(end),
            source = ContestSource.SP9CXN,
            polish = c.domestic || ContestText.looksPolish(name),
            vhf = vhf,
            modes = ContestText.modesIn(name) + if (vhf) setOf(ContestMode.VHF) else emptySet(),
            rulesUrl = link(c.rulesUrl),
            rulesPlUrl = link(c.rulesPlUrl),
            notes = notes
        )
    }

    private fun link(url: String?): String? = url?.trim()?.takeIf { it.startsWith("http") }
}
