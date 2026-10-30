package io.github.sp3wro.hamradiotoolkit.data.contests

/**
 * Kalendarz zawodów VHF/UHF/SHF Stowarzyszenia Polski Klub UKF (pk-ukf.pl) – jedna strona na rok,
 * tabele miesięczne: data, czas UTC, nazwa, pasmo, organizator, regulamin PL/EN, termin wysyłki logów.
 */
object PkUkfCalendar {
    fun url(year: Int) = "https://pk-ukf.pl/kalendarz-zawodow-$year/"

    private const val DAY_MS = 86_400_000L
    private val ROW = Regex("""(?s)<tr\b[^>]*>(.*?)</tr>""", RegexOption.IGNORE_CASE)
    private val CELL = Regex("""(?s)<td\b[^>]*>(.*?)</td>""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""<a\b[^>]*href="([^"]*)"""", RegexOption.IGNORE_CASE)
    // "3 Listopad 2026", "7-8 Listopad 2026", "31 Październik - 1 Listopad 2026"
    private val DATE = Regex("""^(\d{1,2})(?:\s*-\s*(\d{1,2}))?\s+(\p{L}+)(?:\s*-\s*(\d{1,2})\s+(\p{L}+))?\s+(\d{4})$""")
    // "18:00 - 22:00", "14.00 - 18.00", "18:0 - 22:00"
    private val TIME = Regex("""^(\d{1,2})[:.](\d{1,2})\s*-\s*(\d{1,2})[:.](\d{1,2})$""")
    private val MONTHS = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")
    private val POLISH_ORGANIZER = Regex("""PK[ -]?UKF|PZK|\bSP\d?\b""", RegexOption.IGNORE_CASE)

    /** Wiersze z nieustalonym dniem ("?? Listopad") są pomijane – nie da się ich umieścić w kalendarzu. */
    fun parse(html: String): List<ContestEntry> =
        ROW.findAll(html).mapNotNull { row ->
            parseRow(CELL.findAll(row.groupValues[1]).map { it.groupValues[1] }.toList())
        }.sortedBy { it.startMs }.toList()

    private fun parseRow(cells: List<String>): ContestEntry? {
        if (cells.size < 9) return null
        val d = DATE.matchEntire(ContestText.dashes(ContestText.text(cells[1]))) ?: return null
        val t = TIME.matchEntire(ContestText.dashes(ContestText.text(cells[2]))) ?: return null
        val startMonth = month(d.groupValues[3]) ?: return null
        val endMonth = if (d.groupValues[5].isNotEmpty()) month(d.groupValues[5]) ?: return null else startMonth
        val year = d.groupValues[6].toInt()
        // "31 Grudzień - 1 Styczeń 2027": rok podany przy dacie końca
        val startYear = if (endMonth < startMonth) year - 1 else year
        val startDay = d.groupValues[1].toInt()
        val endDay = (d.groupValues[4].ifEmpty { d.groupValues[2] }.ifEmpty { d.groupValues[1] }).toInt()
        if (startDay > CivilDate.lengthOfMonth(startYear, startMonth) || endDay > CivilDate.lengthOfMonth(year, endMonth)) return null
        val start = CivilDate(startYear, startMonth, startDay).epochMillis + minutes(t.groupValues[1], t.groupValues[2])
        var end = CivilDate(year, endMonth, endDay).epochMillis + minutes(t.groupValues[3], t.groupValues[4])
        if (end <= start) end += DAY_MS

        val name = ContestText.text(cells[3]).takeIf { it.isNotBlank() } ?: return null
        val bands = ContestText.text(cells[4])
        val organizer = ContestText.text(cells[5]).takeUnless { it == "-" || it == "–" }.orEmpty()
        return ContestEntry(
            name = name,
            startMs = start,
            endMs = ContestText.roundEnd(end),
            source = ContestSource.PK_UKF,
            polish = POLISH_ORGANIZER.containsMatchIn(organizer) || ContestText.looksPolish(name),
            vhf = true,
            modes = ContestText.modesIn("$name $bands") + ContestMode.VHF,
            bands = bands,
            rulesUrl = link(cells[7]) ?: link(cells[6]),
            rulesPlUrl = link(cells[6]),
            organizer = organizer,
            logDeadline = ContestText.text(cells[8]).takeUnless { it == "-" || it == "–" }.orEmpty()
        )
    }

    private fun month(name: String): Int? {
        val prefix = name.lowercase().take(3)
        return MONTHS.indexOf(prefix).takeIf { it >= 0 }?.plus(1)
    }

    private fun minutes(h: String, m: String): Long = (h.toInt() * 60 + m.toInt()) * 60_000L

    private fun link(cell: String): String? =
        LINK.find(cell)?.groupValues?.get(1)?.let { ContestText.decodeEntities(it).trim() }?.takeIf { it.startsWith("http") }
}
