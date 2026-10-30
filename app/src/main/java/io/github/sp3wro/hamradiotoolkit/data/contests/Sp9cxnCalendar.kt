package io.github.sp3wro.hamradiotoolkit.data.contests

import io.github.sp3wro.hamradiotoolkit.AppStrings
import io.github.sp3wro.hamradiotoolkit.R

/**
 * Kalendarz zawodów SP9CXN (sp9cxn.pzk.pl): tabele zawodów krajowych i międzynarodowych na 3 miesiące,
 * godziny w UTC, linki do regulaminów (także przetłumaczonych na polski).
 */
object Sp9cxnCalendar {
    const val URL = "https://sp9cxn.pzk.pl/kalendarz.html"
    private const val BASE = "https://sp9cxn.pzk.pl/"
    private const val DAY_MS = 86_400_000L

    private val HEADING = Regex("""KALENDARZ\s+ZAWOD\S*\s+(KRAJOWYCH|MI\S*DZYNARODOWYCH)""", RegexOption.IGNORE_CASE)
    private val TABLE = Regex("""(?s)<table\b.*?</table>""", RegexOption.IGNORE_CASE)
    private val ROW = Regex("""(?s)<tr\b[^>]*>(.*?)</tr>""", RegexOption.IGNORE_CASE)
    private val CELL = Regex("""(?s)<td\b[^>]*>(.*?)</td>""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""(?s)<a\b[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
    private val DATE = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})$""")
    private val TIME = Regex("""^(\d{1,2})[:.](\d{2})$""")

    fun parse(html: String): List<ContestEntry> {
        val headings = HEADING.findAll(html).map { it.range.first to it.groupValues[1].uppercase().startsWith("K") }.toList()
        val result = ArrayList<ContestEntry>()
        for (table in TABLE.findAll(html)) {
            val domestic = headings.lastOrNull { it.first < table.range.first }?.second ?: continue
            for (row in ROW.findAll(table.value)) {
                parseRow(CELL.findAll(row.groupValues[1]).map { it.groupValues[1] }.toList(), domestic)?.let { result += it }
            }
        }
        return result.sortedBy { it.startMs }
    }

    private fun parseRow(cells: List<String>, domestic: Boolean): ContestEntry? {
        if (cells.size < 5) return null
        val start = dateTime(cells[0], cells[1]) ?: return null
        var end = dateTime(cells[cells.size - 2], cells[cells.size - 1]) ?: return null
        // Literówki w dacie końca (np. 2016 zamiast 2026) – bierzemy godzinę końca z dnia startu
        if (end <= start || end - start > 14 * DAY_MS) {
            val day = start - Math.floorMod(start, DAY_MS)
            end = day + Math.floorMod(end, DAY_MS)
            if (end <= start) end += DAY_MS
        }

        val nameCell = cells[2]
        val links = LINK.findAll(nameCell).toList()
        val cellText = ContestText.text(nameCell)
        var name = links.firstOrNull()?.let { ContestText.text(it.groupValues[2]) }?.takeIf { it.isNotBlank() } ?: cellText
        val notes = ArrayList<String>()
        val unconfirmed = "??" in cellText
        if (unconfirmed) notes += AppStrings.get(R.string.ct_note_unconfirmed)
        if (cellText.trimEnd().endsWith("*") || Regex("""\s\*(\s|$)""").containsMatchIn(cellText)) {
            notes += AppStrings.get(R.string.ct_note_old_rules)
        }
        if (Regex("""UA\s*i\s*EU""").containsMatchIn(cellText)) {
            notes += AppStrings.get(R.string.ct_note_ru_by)
        }
        name = name.replace("??", "").replace(Regex("""UA\s*i\s*EU"""), "").trimEnd('*', ' ')
            .replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) return null

        val plRules = if (cells.size >= 6) LINK.find(cells[3])?.groupValues?.get(1)?.let(::absolute) else null
        val all = "$name $cellText"
        val vhf = ContestText.looksVhf(name)
        return ContestEntry(
            name = name,
            startMs = start,
            endMs = ContestText.roundEnd(end),
            source = ContestSource.SP9CXN,
            polish = domestic || ContestText.looksPolish(name),
            vhf = vhf,
            modes = ContestText.modesIn(all) + if (vhf) setOf(ContestMode.VHF) else emptySet(),
            rulesUrl = links.firstOrNull()?.groupValues?.get(1)?.let(::absolute),
            rulesPlUrl = plRules,
            notes = notes
        )
    }

    private fun dateTime(dateCell: String, timeCell: String): Long? {
        val d = DATE.matchEntire(ContestText.text(dateCell)) ?: return null
        val t = TIME.matchEntire(ContestText.text(timeCell)) ?: return null
        val day = d.groupValues[1].toInt()
        val month = d.groupValues[2].toInt()
        val year = d.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..CivilDate.lengthOfMonth(year, month)) return null
        val minutes = t.groupValues[1].toInt() * 60 + t.groupValues[2].toInt()
        return CivilDate(year, month, day).epochMillis + minutes * 60_000L
    }

    private fun absolute(href: String): String? {
        val url = ContestText.decodeEntities(href.trim())
        if (url.isEmpty() || url.startsWith("#") || url.startsWith("mailto:")) return null
        return if (url.startsWith("http://") || url.startsWith("https://")) url else BASE + url.removePrefix("./").removePrefix("/")
    }
}
