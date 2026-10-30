package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.utils.CsvParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvParserTest {

    @Test
    fun parseLine_handlesQuotedCommasAndEscapedQuotes() {
        val fields = CsvParser.parseLine("PL-0003,\"Nadbużański, PLA\",\"PL-LU,PL-MZ\",\"say \"\"hi\"\"\",,end")
        assertEquals(listOf("PL-0003", "Nadbużański, PLA", "PL-LU,PL-MZ", "say \"hi\"", "", "end"), fields)
    }

    @Test
    fun parseWithHeader_skipsTitleLineAndFiltersRows() {
        val csv = """
            SOTA Summits List (Date=17/09/2026)
            SummitCode,AssociationName,RegionName,SummitName,Longitude,Latitude
            3B8/MU-001,Mauritius,Mauritius,"Piton, Black River",57.40771,-20.40882
            SP/BI-003,Poland,Bieszczady,"Wielka Rawka",22.57800,49.09880
        """.trimIndent().replace("\n", "\r\n")

        val rows = CsvParser.parseWithHeader(csv, headerMarker = "SummitCode") { it.startsWith("SP/") }

        assertEquals(1, rows.size)
        assertEquals("SP/BI-003", rows[0]["SummitCode"])
        assertEquals("Wielka Rawka", rows[0]["SummitName"])
        assertEquals("49.09880", rows[0]["Latitude"])
        assertEquals("", rows[0]["NoSuchColumn"])
    }

    @Test
    fun parseWithHeader_returnsEmptyWhenHeaderMissing() {
        assertEquals(0, CsvParser.parseWithHeader("a,b\n1,2", headerMarker = "reference").size)
    }
}
