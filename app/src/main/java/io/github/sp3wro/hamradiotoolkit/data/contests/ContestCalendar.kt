package io.github.sp3wro.hamradiotoolkit.data.contests

enum class ContestMode(val label: String) { CW("CW"), SSB("SSB"), RTTY("RTTY"), DIGI("Cyfrowe"), VHF("UKF") }

/** Dni tygodnia jak w ISO (poniedziałek = 1); java.time jest dostępne dopiero od API 26. */
enum class Weekday { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

/** Data kalendarzowa (UTC) bez java.time – rok, miesiąc 1–12, dzień 1–31. */
data class CivilDate(val year: Int, val month: Int, val day: Int) {
    /** Dni od 1970-01-01 (algorytm "days from civil", H. Hinnant). */
    val epochDay: Long
        get() {
            val y = if (month <= 2) year - 1 else year
            val era = Math.floorDiv(y, 400)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era.toLong() * 146097 + doe - 719468
        }

    val weekday: Weekday get() = Weekday.values()[Math.floorMod(epochDay + 3, 7L).toInt()]

    fun plusDays(days: Long): CivilDate = fromEpochDay(epochDay + days)

    val epochMillis: Long get() = epochDay * DAY_MS

    companion object {
        const val DAY_MS = 86_400_000L

        fun fromEpochDay(epochDay: Long): CivilDate {
            val z = epochDay + 719468
            val era = Math.floorDiv(z, 146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return CivilDate(y, m, d)
        }

        fun fromMillis(ms: Long): CivilDate = fromEpochDay(Math.floorDiv(ms, DAY_MS))

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

/** Kiedy odbywają się zawody: n-ty pełny weekend miesiąca albo co tydzień. */
sealed class ContestSchedule {
    /**
     * [weekend]: 1–4 = n-ty pełny weekend (sobota i niedziela w tym miesiącu), −1 = ostatni pełny weekend.
     * [startOffsetMinutes]: start względem soboty 00:00 UTC (piątek 22:00 = −120).
     */
    data class FullWeekend(
        val month: Int,
        val weekend: Int,
        val startOffsetMinutes: Int,
        val durationHours: Int
    ) : ContestSchedule()

    data class Weekly(val day: Weekday, val startMinuteUtc: Int, val durationMinutes: Int) : ContestSchedule()
}

data class ContestDefinition(
    val id: String,
    val name: String,
    val modes: Set<ContestMode>,
    val bands: String,
    val schedule: ContestSchedule,
    val exchange: String = "",
    val rulesUrl: String? = null,
    val polish: Boolean = false
)

/** Termin zawodów; czasy w ms od epoki (UTC). */
data class ContestOccurrence(val contest: ContestDefinition, val startMs: Long, val endMs: Long) {
    fun isRunning(nowMs: Long): Boolean = nowMs in startMs until endMs
}

/**
 * Zapasowy kalendarz wyliczany lokalnie ze stałych terminów – używany, gdy nie ma połączenia
 * ani zapisanej kopii kalendarzy z sieci ([ContestSources]); stąd też cotygodniowe CWT.
 * Terminy to zwyczajowe reguły organizatorów – przed startem trzeba sprawdzić aktualny regulamin.
 */
object ContestCalendar {
    private const val SAT = 0
    private const val FRI_22 = -120

    val contests: List<ContestDefinition> = listOf(
        ContestDefinition(
            "arrl-rtty-ru", "ARRL RTTY Roundup", setOf(ContestMode.RTTY, ContestMode.DIGI), "80–10 m",
            ContestSchedule.FullWeekend(1, 1, 18 * 60, 30),
            "RST + numer kolejny (W/VE: RST + stan/prowincja)", "https://www.arrl.org/rtty-roundup"
        ),
        ContestDefinition(
            "ha-dx", "HA DX Contest", setOf(ContestMode.CW, ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(1, 3, 12 * 60, 24),
            "RS(T) + numer kolejny (HA: kod komitatu)"
        ),
        ContestDefinition(
            "cq160-cw", "CQ WW 160-Meter Contest CW", setOf(ContestMode.CW), "160 m",
            ContestSchedule.FullWeekend(1, -1, FRI_22, 48), rulesUrl = "https://cq160.com"
        ),
        ContestDefinition(
            "cqwpx-rtty", "CQ WW WPX RTTY", setOf(ContestMode.RTTY), "80–10 m",
            ContestSchedule.FullWeekend(2, 2, SAT, 48),
            "RST + numer kolejny", "https://www.cqwpxrtty.com"
        ),
        ContestDefinition(
            "arrl-dx-cw", "ARRL International DX CW", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.FullWeekend(2, 3, SAT, 48),
            "DX: RST + moc nadajnika; łączności tylko z W/VE", "https://www.arrl.org/arrl-dx"
        ),
        ContestDefinition(
            "cq160-ssb", "CQ WW 160-Meter Contest SSB", setOf(ContestMode.SSB), "160 m",
            ContestSchedule.FullWeekend(2, -1, FRI_22, 48), rulesUrl = "https://cq160.com"
        ),
        ContestDefinition(
            "arrl-dx-ssb", "ARRL International DX SSB", setOf(ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(3, 1, SAT, 48),
            "DX: RS + moc nadajnika; łączności tylko z W/VE", "https://www.arrl.org/arrl-dx"
        ),
        ContestDefinition(
            "rdxc", "Russian DX Contest", setOf(ContestMode.CW, ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(3, 3, 12 * 60, 24),
            "RS(T) + numer kolejny (UA: kod obwodu)", "https://www.rdxc.org"
        ),
        ContestDefinition(
            "cqwpx-ssb", "CQ WW WPX SSB", setOf(ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(3, -1, SAT, 48),
            "RS + numer kolejny", "https://www.cqwpx.com"
        ),
        ContestDefinition(
            "spdx", "SP DX Contest", setOf(ContestMode.CW, ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(4, 1, 15 * 60, 24),
            "RS(T) + numer kolejny; stacje SP: RS(T) + litera województwa", polish = true
        ),
        ContestDefinition(
            "spdx-rtty", "SP DX RTTY Contest", setOf(ContestMode.RTTY), "80–10 m",
            ContestSchedule.FullWeekend(4, -1, 12 * 60, 24),
            "RST + numer kolejny; stacje SP: RST + litera województwa", polish = true
        ),
        ContestDefinition(
            "cqwpx-cw", "CQ WW WPX CW", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.FullWeekend(5, -1, SAT, 48),
            "RST + numer kolejny", "https://www.cqwpx.com"
        ),
        ContestDefinition(
            "aa-cw", "All Asian DX Contest CW", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.FullWeekend(6, 3, SAT, 48),
            "RST + wiek operatora; łączności tylko z Azją"
        ),
        ContestDefinition(
            "iaru-hf", "IARU HF World Championship", setOf(ContestMode.CW, ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(7, 2, 12 * 60, 24),
            "RS(T) + strefa ITU (stacje HQ: skrót stowarzyszenia, np. PZK)",
            "https://www.arrl.org/iaru-hf-championship"
        ),
        ContestDefinition(
            "cqww-vhf", "CQ WW VHF Contest", setOf(ContestMode.VHF, ContestMode.CW, ContestMode.SSB, ContestMode.DIGI), "6 m, 2 m",
            ContestSchedule.FullWeekend(7, 3, 18 * 60, 27),
            "Lokator (4 znaki)"
        ),
        ContestDefinition(
            "wae-cw", "WAE DX Contest CW", setOf(ContestMode.CW), "80–10 m",
            ContestSchedule.FullWeekend(8, 2, SAT, 48),
            "RST + numer kolejny; stacje europejskie pracują z pozaeuropejskimi (QTC)"
        ),
        ContestDefinition(
            "euhfc", "EU HF Championship", setOf(ContestMode.CW, ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(8, 2, 12 * 60, 12),
            "RS(T) + dwie ostatnie cyfry roku pierwszej licencji"
        ),
        ContestDefinition(
            "aa-ssb", "All Asian DX Contest SSB", setOf(ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(9, 1, SAT, 48),
            "RS + wiek operatora; łączności tylko z Azją"
        ),
        ContestDefinition(
            "iaru-r1-vhf", "IARU Region 1 VHF Contest", setOf(ContestMode.VHF, ContestMode.CW, ContestMode.SSB), "2 m",
            ContestSchedule.FullWeekend(9, 1, 14 * 60, 24),
            "RS(T) + numer kolejny + lokator (6 znaków)"
        ),
        ContestDefinition(
            "wae-ssb", "WAE DX Contest SSB", setOf(ContestMode.SSB), "80–10 m",
            ContestSchedule.FullWeekend(9, 2, SAT, 48),
            "RS + numer kolejny; stacje europejskie pracują z pozaeuropejskimi (QTC)"
        ),
        ContestDefinition(
            "cqww-rtty", "CQ WW RTTY DX Contest", setOf(ContestMode.RTTY), "80–10 m",
            ContestSchedule.FullWeekend(9, -1, SAT, 48),
            "RST + strefa CQ (W/VE także stan/prowincja)", "https://www.cqwwrtty.com"
        ),
        ContestDefinition(
            "iaru-r1-uhf", "IARU Region 1 UHF/Microwave Contest", setOf(ContestMode.VHF, ContestMode.CW, ContestMode.SSB), "70 cm i wyżej",
            ContestSchedule.FullWeekend(10, 1, 14 * 60, 24),
            "RS(T) + numer kolejny + lokator (6 znaków)"
        ),
        ContestDefinition(
            "cqww-ssb", "CQ WW DX Contest SSB", setOf(ContestMode.SSB), "160–10 m",
            ContestSchedule.FullWeekend(10, -1, SAT, 48),
            "RS + strefa CQ (SP = 15)", "https://www.cqww.com"
        ),
        ContestDefinition(
            "wae-rtty", "WAE DX Contest RTTY", setOf(ContestMode.RTTY), "80–10 m",
            ContestSchedule.FullWeekend(11, 2, SAT, 48),
            "RST + numer kolejny (QTC między wszystkimi stacjami)"
        ),
        ContestDefinition(
            "okom-cw", "OK/OM DX Contest CW", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.FullWeekend(11, 2, 12 * 60, 24),
            "RST + numer kolejny (OK/OM: kod powiatu)"
        ),
        ContestDefinition(
            "cqww-cw", "CQ WW DX Contest CW", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.FullWeekend(11, -1, SAT, 48),
            "RST + strefa CQ (SP = 15)", "https://www.cqww.com"
        ),
        ContestDefinition(
            "arrl-160", "ARRL 160-Meter Contest", setOf(ContestMode.CW), "160 m",
            ContestSchedule.FullWeekend(12, 1, FRI_22, 42),
            "DX: RST; łączności tylko z W/VE", "https://www.arrl.org/160-meter"
        ),
        ContestDefinition(
            "arrl-10", "ARRL 10-Meter Contest", setOf(ContestMode.CW, ContestMode.SSB), "10 m",
            ContestSchedule.FullWeekend(12, 2, SAT, 48),
            "RS(T) + numer kolejny (W/VE: stan/prowincja)", "https://www.arrl.org/10-meter"
        ),
        // CWops CWT – 4 godzinne sesje w tygodniu
        ContestDefinition(
            "cwt-wed13", "CWops Test (CWT)", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.Weekly(Weekday.WEDNESDAY, 13 * 60, 60),
            "Imię + numer członkowski (lub kraj)", "https://cwops.org/cwops-tests/"
        ),
        ContestDefinition(
            "cwt-wed19", "CWops Test (CWT)", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.Weekly(Weekday.WEDNESDAY, 19 * 60, 60),
            "Imię + numer członkowski (lub kraj)", "https://cwops.org/cwops-tests/"
        ),
        ContestDefinition(
            "cwt-thu03", "CWops Test (CWT)", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.Weekly(Weekday.THURSDAY, 3 * 60, 60),
            "Imię + numer członkowski (lub kraj)", "https://cwops.org/cwops-tests/"
        ),
        ContestDefinition(
            "cwt-thu07", "CWops Test (CWT)", setOf(ContestMode.CW), "160–10 m",
            ContestSchedule.Weekly(Weekday.THURSDAY, 7 * 60, 60),
            "Imię + numer członkowski (lub kraj)", "https://cwops.org/cwops-tests/"
        )
    )

    /** Sobota n-tego pełnego weekendu (sobota i niedziela w tym miesiącu); weekend = −1 to ostatni. */
    fun fullWeekendSaturday(year: Int, month: Int, weekend: Int): CivilDate {
        val length = CivilDate.lengthOfMonth(year, month)
        return if (weekend > 0) {
            val first = CivilDate(year, month, 1)
            val toSaturday = Math.floorMod(Weekday.SATURDAY.ordinal - first.weekday.ordinal, 7)
            first.plusDays(toSaturday + 7L * (weekend - 1))
        } else {
            val last = CivilDate(year, month, length)
            var sat = last.plusDays(-Math.floorMod(last.weekday.ordinal - Weekday.SATURDAY.ordinal, 7).toLong())
            // Sobota w ostatnim dniu miesiąca – niedziela wypada już w następnym, weekend nie jest pełny
            if (sat == last) sat = sat.plusDays(-7)
            sat
        }
    }

    /** Wszystkie terminy przecinające przedział [fromMs, toMs). */
    fun occurrences(fromMs: Long, toMs: Long, definitions: List<ContestDefinition> = contests): List<ContestOccurrence> {
        val result = ArrayList<ContestOccurrence>()
        val fromDate = CivilDate.fromMillis(fromMs)
        val toDate = CivilDate.fromMillis(toMs)
        for (def in definitions) {
            when (val s = def.schedule) {
                is ContestSchedule.FullWeekend -> {
                    for (year in fromDate.year - 1..toDate.year) {
                        val sat = fullWeekendSaturday(year, s.month, s.weekend)
                        val start = sat.epochMillis + s.startOffsetMinutes * 60_000L
                        val end = start + s.durationHours * 3_600_000L
                        if (end > fromMs && start < toMs) result += ContestOccurrence(def, start, end)
                    }
                }
                is ContestSchedule.Weekly -> {
                    val firstDay = fromDate.plusDays(-1)
                    var date = firstDay.plusDays(Math.floorMod(s.day.ordinal - firstDay.weekday.ordinal, 7).toLong())
                    while (date.epochDay <= toDate.epochDay) {
                        val start = date.epochMillis + s.startMinuteUtc * 60_000L
                        val end = start + s.durationMinutes * 60_000L
                        if (end > fromMs && start < toMs) result += ContestOccurrence(def, start, end)
                        date = date.plusDays(7)
                    }
                }
            }
        }
        return result.sortedBy { it.startMs }
    }
}
