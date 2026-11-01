package io.github.sp3wro.hamradiotoolkit.data.contests

import java.util.Calendar
import java.util.TimeZone

/**
 * Kalendarz zawodów QRZ.RU – plik ICS z najbliższym terminem każdych zawodów.
 * Rosyjskie nazwy tłumaczymy na angielski ([RussianContestNames]), oryginał zostaje w [ContestEntry.originalName].
 *
 * Ustalenia z QRZ.RU: cały plik ma ok. 1,1 MB, więc pobieramy tylko potrzebne miesiące (parametr m, po jednym
 * w zapytaniu, bieżący i dwa kolejne), od razu z m.qrz.ru, bo tam www.qrz.ru przekierowuje Androida.
 * Stron /contest/detail/ aplikacja nie pobiera; otwiera je tylko użytkownik, a link do strony zawodów
 * ([ContestEntry.sourcePageUrl]) musi zostać przy każdym wpisie.
 *
 * Uwaga: DTSTART ma TZID=Europe/Moscow, ale w rzeczywistości zawiera czas z regulaminu (zwykle UTC),
 * dlatego czas bierzemy z SUMMARY ("проводится с 05.01.2026 18:00 UTC по 05.01.2026 19:59 UTC").
 * Parametr m to sam numer miesiąca bez roku, więc w pliku bywają też terminy sprzed roku i za rok.
 */
object QrzRuCalendar {
    private const val ICS_URL = "https://m.qrz.ru/contest/qrz-contest.ics"
    const val PAGE_URL = "https://www.qrz.ru/contest/"
    private const val HOUR_MS = 3_600_000L
    private const val MONTHS_AHEAD = 2

    fun icsUrl(month: Int): String = "$ICS_URL?m=$month"

    /** Numery miesięcy do pobrania (1–12): bieżący według UTC i [MONTHS_AHEAD] kolejnych. */
    fun monthsToFetch(nowMs: Long): List<Int> {
        val month = monthOf(nowMs)
        return (0..MONTHS_AHEAD).map { (month - 1 + it) % 12 + 1 }
    }

    /** Poprzedni miesiąc: nie jest pobierany, ale jego kopia pokazuje zawody trwające na przełomie miesięcy. */
    fun previousMonth(nowMs: Long): Int = (monthOf(nowMs) + 10) % 12 + 1

    private fun monthOf(nowMs: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }.get(Calendar.MONTH) + 1

    private val EVENT = Regex("""(?s)BEGIN:VEVENT(.*?)END:VEVENT""")
    private val PERIOD = Regex(
        """^(.*?),?\s*проводится с (\d{2})\.(\d{2})\.(\d{4}) (\d{1,2}):(\d{2}) (UTC|MSK) по (\d{2})\.(\d{2})\.(\d{4}) (\d{1,2}):(\d{2}) (UTC|MSK)"""
    )
    private val DT = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})""")
    private val LOCATION = Regex("""Диапазон\(ы\):\s*(.*?)\.\s*Модуляция:\s*(.*?)\.?\s*$""")
    private val UID = Regex("""^(\d+)@qrz\.ru$""")

    fun parse(ics: String): List<ContestEntry> {
        val unfolded = ics.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")
        return EVENT.findAll(unfolded).mapNotNull { parseEvent(properties(it.groupValues[1])) }
            .sortedBy { it.startMs }.toList()
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

    private fun parseEvent(p: Map<String, String>): ContestEntry? {
        val summary = p["SUMMARY"]?.trim() ?: return null
        val period = PERIOD.find(summary)
        val original: String
        val start: Long
        var end: Long
        if (period != null) {
            val g = period.groupValues
            original = g[1].trim().trimEnd(',').trim()
            start = time(g[4], g[3], g[2], g[5], g[6], g[7]) ?: return null
            end = time(g[10], g[9], g[8], g[11], g[12], g[13]) ?: return null
        } else {
            original = summary
            start = dt(p["DTSTART"]) ?: return null
            end = dt(p["DTEND"]) ?: return null
        }
        if (original.isEmpty()) return null
        if (end <= start) end = start + HOUR_MS

        val location = LOCATION.find(p["LOCATION"].orEmpty().trim())
        val bands = location?.groupValues?.get(1)?.let(::bandsToLatin).orEmpty()
        val modulation = location?.groupValues?.get(2).orEmpty()
        val description = p["DESCRIPTION"].orEmpty()
        val vhf = Regex("""MHz|GHz""").containsMatchIn(bands) || Regex("""УКВ|VHF|UHF""", RegexOption.IGNORE_CASE).containsMatchIn(original)
        val id = UID.matchEntire(p["UID"].orEmpty().trim())?.groupValues?.get(1)
        val detailUrl = id?.let { "https://www.qrz.ru/contest/detail/$it.html" }
        val russian = RussianContestNames.isCyrillic(original)
        val name = if (russian) RussianContestNames.translate(original) else original
        return ContestEntry(
            name = name,
            startMs = start,
            endMs = ContestText.roundEnd(end),
            source = ContestSource.QRZ_RU,
            polish = ContestText.looksPolish(original),
            vhf = vhf,
            modes = ContestText.modesIn("$modulation $original") + if (vhf) setOf(ContestMode.VHF) else emptySet(),
            bands = bands,
            originalName = if (russian) original else null,
            rulesUrl = field(description, "Положение")?.takeIf { it.startsWith("http") } ?: detailUrl,
            sourcePageUrl = detailUrl,
            homepage = field(description, "Homepage")?.takeIf { it.startsWith("http") },
            logDeadline = field(description, "Отчеты принимаются до").orEmpty()
        )
    }

    /** Czas z SUMMARY; "24:00" to koniec dnia, MSK = UTC+3. */
    private fun time(year: String, month: String, day: String, h: String, m: String, zone: String): Long? {
        val mo = month.toInt()
        val d = day.toInt()
        val y = year.toInt()
        if (mo !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, mo)) return null
        val ms = CivilDate(y, mo, d).epochMillis + (h.toInt() * 60 + m.toInt()) * 60_000L
        return if (zone == "MSK") ms - 3 * HOUR_MS else ms
    }

    private fun dt(value: String?): Long? {
        val m = DT.find(value?.trim() ?: return null) ?: return null
        val g = m.groupValues
        return time(g[1], g[2], g[3], g[4], g[5], "UTC")
    }

    private fun field(description: String, label: String): String? =
        description.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    internal fun bandsToLatin(s: String): String = s
        .replace("ГГц", "GHz").replace("МГц", "MHz").replace("кГц", "kHz")
        .replace(Regex("""(\d)\s*см(?!\p{L})"""), "$1 cm").replace(Regex("""(\d)\s*мм(?!\p{L})"""), "$1 mm")
        .replace(Regex("""(\d)\s*м(?!\p{L})"""), "$1 m")
        .trim()
}
