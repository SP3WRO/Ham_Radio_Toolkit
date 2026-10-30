package io.github.sp3wro.hamradiotoolkit.data.contests

/** Skąd pochodzi wpis kalendarza. */
enum class ContestSource(val label: String, val pageUrl: String) {
    SP9CXN("SP9CXN", Sp9cxnCalendar.URL),
    PK_UKF("PK-UKF", "https://pk-ukf.pl/"),
    QRZ_RU("QRZ.RU", QrzRuCalendar.PAGE_URL),
    BUILTIN("kalendarz wbudowany", "")
}

/** Zawody z dowolnego źródła; czasy w ms od epoki (UTC). */
data class ContestEntry(
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val source: ContestSource,
    /** Zawody krajowe albo organizowane w Polsce. */
    val polish: Boolean = false,
    val vhf: Boolean = false,
    val modes: Set<ContestMode> = emptySet(),
    val bands: String = "",
    /** Nazwa w języku organizatora, gdy [name] jest tłumaczeniem. */
    val originalName: String? = null,
    val rulesUrl: String? = null,
    /** Regulamin po polsku (tłumaczenie SP9CXN albo polska wersja PK-UKF). */
    val rulesPlUrl: String? = null,
    val homepage: String? = null,
    val organizer: String = "",
    val exchange: String = "",
    val logDeadline: String = "",
    val notes: List<String> = emptyList(),
    val sources: Set<ContestSource> = setOf(source)
) {
    val key: String get() = "${source.name}_${startMs}_${name.hashCode()}"
    fun isRunning(nowMs: Long): Boolean = nowMs in startMs until endMs
}

/** Wspólne narzędzia parserów kalendarzy. */
internal object ContestText {
    private val TAG = Regex("<[^>]+>")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")
    private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")
    private val NAMED = mapOf(
        "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'",
        "&ndash;" to "–", "&mdash;" to "—", "&laquo;" to "«", "&raquo;" to "»", "&oacute;" to "ó", "&Oacute;" to "Ó"
    )

    fun decodeEntities(s: String): String {
        var out = NUMERIC.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code == null || !Character.isValidCodePoint(code)) m.value else String(Character.toChars(code))
        }
        for ((entity, value) in NAMED) out = out.replace(entity, value)
        return out.replace("&amp;", "&")
    }

    /** Tekst komórki HTML: bez znaczników, z rozwiniętymi encjami i pojedynczymi spacjami. */
    fun text(html: String): String =
        decodeEntities(TAG.replace(BR.replace(html, " "), "")).replace(SPACES, " ").trim()

    /** Myślniki typograficzne zamienione na zwykły. */
    fun dashes(s: String): String = s.replace('–', '-').replace('—', '-').replace('−', '-')

    private val CW = Regex("""\bCW\b|telegraf|Telegraphy|телеграф""", RegexOption.IGNORE_CASE)
    private val SSB = Regex("""\bSSB\b|\bFM\b|\bAM\b|fonia|\bphone\b|телефон""", RegexOption.IGNORE_CASE)
    private val RTTY = Regex("""RTTY""", RegexOption.IGNORE_CASE)
    private val DIGI = Regex("""DIGI|\bFT[48]\b|FT8|FT4|\bPSK|\bMFSK|\bJT\d|Hell|\bOLIVIA\b""", RegexOption.IGNORE_CASE)

    fun modesIn(text: String): Set<ContestMode> = buildSet {
        if (CW.containsMatchIn(text)) add(ContestMode.CW)
        if (SSB.containsMatchIn(text)) add(ContestMode.SSB)
        if (RTTY.containsMatchIn(text)) add(ContestMode.RTTY)
        if (DIGI.containsMatchIn(text)) add(ContestMode.DIGI)
    }

    private val POLISH = Regex("""\bSP ?DX\b|\bSP\d\b|\bSP[ -]?CW\b|\bSPAC\b|Poland|Polish|\bPZK\b|PK[ -]?UKF""", RegexOption.IGNORE_CASE)

    fun looksPolish(text: String): Boolean = POLISH.containsMatchIn(text)

    private val VHF = Regex("""\b(VHF|UHF|SHF|UKF|EME)\b|\bSPAC\b|\d\s*(MHz|GHz)""", RegexOption.IGNORE_CASE)

    fun looksVhf(text: String): Boolean = VHF.containsMatchIn(text)

    /** Koniec zapisany jako 16:59 / 23:59 traktujemy jak pełną godzinę (17:00 / 24:00). */
    fun roundEnd(endMs: Long): Long = if ((endMs / 60_000L) % 60 == 59L) endMs + 60_000L else endMs
}
