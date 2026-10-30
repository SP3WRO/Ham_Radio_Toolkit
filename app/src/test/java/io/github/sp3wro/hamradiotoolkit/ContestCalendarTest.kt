package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.contests.CivilDate
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestCalendar
import io.github.sp3wro.hamradiotoolkit.data.contests.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ContestCalendarTest {
    private val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun ms(text: String): Long = utc.parse(text)!!.time
    private fun fmt(ms: Long): String = utc.format(ms)

    private fun occurrence(id: String, from: String, to: String) =
        ContestCalendar.occurrences(ms(from), ms(to)).single { it.contest.id == id }

    @Test
    fun civilDateRoundTripAndWeekday() {
        assertEquals(Weekday.THURSDAY, CivilDate(1970, 1, 1).weekday)
        assertEquals(Weekday.WEDNESDAY, CivilDate(2026, 10, 21).weekday)
        assertEquals(Weekday.SATURDAY, CivilDate(2026, 10, 31).weekday)
        assertEquals(Weekday.TUESDAY, CivilDate(2000, 2, 29).weekday)
        for (day in -800_000L..800_000L step 997) {
            assertEquals(day, CivilDate.fromEpochDay(day).epochDay)
        }
        assertEquals(ms("2026-10-21 00:00"), CivilDate(2026, 10, 21).epochMillis)
    }

    @Test
    fun lastFullWeekendSkipsSaturdayOnLastDay() {
        // 31.10.2026 to sobota – niedziela jest już w listopadzie, więc ostatni pełny weekend to 24–25.10
        assertEquals(CivilDate(2026, 10, 24), ContestCalendar.fullWeekendSaturday(2026, 10, -1))
        // 1.08.2027 to niedziela – pierwszy pełny weekend to 7–8.08
        assertEquals(CivilDate(2027, 8, 7), ContestCalendar.fullWeekendSaturday(2027, 8, 1))
    }

    @Test
    fun cqwwSsbMatchesWa7bnmCalendar2026() {
        // WA7BNM: "0000Z, Oct 24 to 2359Z, Oct 25" (2026)
        val o = occurrence("cqww-ssb", "2026-10-01 00:00", "2026-11-01 00:00")
        assertEquals("2026-10-24 00:00", fmt(o.startMs))
        assertEquals("2026-10-26 00:00", fmt(o.endMs))
    }

    @Test
    fun cwtWeeklySessionsMatchWa7bnm() {
        // WA7BNM: CWT 1300Z i 1900Z 21.10.2026, 0300Z i 0700Z 22.10.2026
        val starts = ContestCalendar.occurrences(ms("2026-10-21 00:00"), ms("2026-10-23 00:00"))
            .filter { it.contest.id.startsWith("cwt") }
            .map { fmt(it.startMs) }
        assertEquals(listOf("2026-10-21 13:00", "2026-10-21 19:00", "2026-10-22 03:00", "2026-10-22 07:00"), starts)
    }

    @Test
    fun otherKnownDates() {
        assertEquals("2026-04-04 15:00", fmt(occurrence("spdx", "2026-04-01 00:00", "2026-05-01 00:00").startMs))
        assertEquals("2026-11-28 00:00", fmt(occurrence("cqww-cw", "2026-11-01 00:00", "2026-12-01 00:00").startMs))
        assertEquals("2026-12-12 00:00", fmt(occurrence("arrl-10", "2026-12-01 00:00", "2027-01-01 00:00").startMs))
        // CQ 160 CW: ostatni pełny weekend stycznia 2027 to 30–31.01, start w piątek 22:00
        val cq160 = occurrence("cq160-cw", "2027-01-01 00:00", "2027-02-02 00:00")
        assertEquals("2027-01-29 22:00", fmt(cq160.startMs))
        assertEquals("2027-01-31 22:00", fmt(cq160.endMs))
    }

    @Test
    fun runningContestIsIncludedAndSorted() {
        val list = ContestCalendar.occurrences(ms("2026-10-25 12:00"), ms("2027-10-25 12:00"))
        assertTrue(list.first().contest.id == "cqww-ssb" && list.first().isRunning(ms("2026-10-25 12:00")))
        assertEquals(list.sortedBy { it.startMs }, list)
        // Każde zawody "weekendowe" występują w ciągu roku dokładnie raz (+ trwające na początku przedziału)
        val weekend = list.filterNot { it.contest.id.startsWith("cwt") }.groupBy { it.contest.id }
        assertTrue(weekend.all { it.value.size in 1..2 })
    }
}
