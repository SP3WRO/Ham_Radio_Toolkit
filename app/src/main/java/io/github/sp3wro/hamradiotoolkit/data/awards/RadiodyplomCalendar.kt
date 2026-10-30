package io.github.sp3wro.hamradiotoolkit.data.awards

import io.github.sp3wro.hamradiotoolkit.data.contests.CivilDate

/**
 * Kalendarz akcji dyplomowych radiodyplom.pl – publiczny eksport ICS (trwające i nadchodzące akcje).
 * Wydarzenia są całodniowe (DTEND wyłącznie); dokładne godziny UTC podaje dopiero strona akcji.
 */
object RadiodyplomCalendar {
    const val ICS_URL = "https://radiodyplom.pl/ics_download.php?days=365"
    const val PAGE_URL = "https://radiodyplom.pl/"
    private const val DAY_MS = 86_400_000L

    private val EVENT = Regex("""(?s)BEGIN:VEVENT(.*?)END:VEVENT""")
    private val DATE = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2}))?""")
    private val SES_URL = Regex("""/ses/(\d+)""")
    private val UID = Regex("""^ses-(\d+)-""")
    private val BBCODE = Regex("""\[/?[a-zA-Z*]+(?:=[^\]]*)?]""")

    fun parse(ics: String): List<AwardEvent> {
        val unfolded = ics.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")
        return EVENT.findAll(unfolded).mapNotNull { parseEvent(properties(it.groupValues[1])) }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.startMs }, { it.name }))
            .toList()
    }

    private fun properties(block: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in block.split('\n')) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).substringBefore(';').trim().uppercase()
            if (key !in map) map[key] = unescape(line.substring(colon + 1))
        }
        return map
    }

    private fun unescape(v: String): String =
        v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseEvent(p: Map<String, String>): AwardEvent? {
        val name = p["SUMMARY"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = p["URL"]?.trim().orEmpty()
        val id = SES_URL.find(url)?.groupValues?.get(1) ?: UID.find(p["UID"].orEmpty().trim())?.groupValues?.get(1) ?: return null
        val start = date(p["DTSTART"]) ?: return null
        var end = date(p["DTEND"]) ?: (start + DAY_MS)
        if (end <= start) end = start + DAY_MS
        return AwardEvent(
            id = id,
            name = name,
            startMs = start,
            endMs = end,
            description = cleanText(p["DESCRIPTION"].orEmpty()),
            url = url.ifEmpty { eventUrl(id) }
        )
    }

    fun eventUrl(id: String) = "https://radiodyplom.pl/ses/$id"

    private fun date(value: String?): Long? {
        val g = DATE.find(value?.trim() ?: return null)?.groupValues ?: return null
        val y = g[1].toInt()
        val mo = g[2].toInt()
        val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, mo)) return null
        val minutes = if (g[4].isNotEmpty()) g[4].toInt() * 60 + g[5].toInt() else 0
        return CivilDate(y, mo, d).epochMillis + minutes * 60_000L
    }

    /** Opis bez znaczników BBCode i niewidocznych znaków. */
    internal fun cleanText(s: String): String = s
        .replace(BBCODE, " ")
        .replace("\uFEFF", "")
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex(""" *\n *"""), "\n")
        .trim()
}
