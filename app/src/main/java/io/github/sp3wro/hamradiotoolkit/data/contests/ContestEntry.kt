package io.github.sp3wro.hamradiotoolkit.data.contests

/** Skąd pochodzi wpis kalendarza. */
enum class ContestSource(val label: String, val pageUrl: String) {
    SP9CXN("SP9CXN", Sp9cxnCalendar.PAGE_URL),
    PK_UKF("PK-UKF / SP1CNV", "https://pk-ukf.pl/"),
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
    /** Strona tych zawodów w serwisie QRZ.RU; zostaje przy wpisie także po złączeniu ze źródłem o wyższym priorytecie. */
    val sourcePageUrl: String? = null,
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

    private val UTC_TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""")

    /** Czas "2026-03-07T14:00:00Z" albo "2026-03-07 14:00:00" (UTC); sekundy są pomijane. */
    fun utcMillis(s: String): Long? {
        val g = UTC_TIME.find(s.trim())?.groupValues ?: return null
        val y = g[1].toInt()
        val mo = g[2].toInt()
        val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, mo)) return null
        return CivilDate(y, mo, d).epochMillis + (g[4].toInt() * 60 + g[5].toInt()) * 60_000L
    }

    /** Koniec zapisany jako 16:59 / 23:59 traktujemy jak pełną godzinę (17:00 / 24:00). */
    fun roundEnd(endMs: Long): Long = if ((endMs / 60_000L) % 60 == 59L) endMs + 60_000L else endMs
}
