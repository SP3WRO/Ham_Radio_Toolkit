package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.data.network.HamQthClient
import io.github.sp3wro.hamradiotoolkit.data.network.LogUploadClient
import io.github.sp3wro.hamradiotoolkit.data.network.UploadOutcome
import io.github.sp3wro.hamradiotoolkit.utils.AdifGenerator
import io.github.sp3wro.hamradiotoolkit.utils.AdifParser
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import io.github.sp3wro.hamradiotoolkit.utils.LogStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookFeaturesTest {

    private val qso = QsoEntry(
        id = "1", callsign = "sp3abc", datetimeUtc = "2026-10-20 18:45", frequency = "14074",
        band = "20m", mode = "FT4", rstSent = "-10", rstRcvd = "-12", comment = "Łódź",
        name = "Jan", qth = "Poznań", gridSquare = "JO82lk", dxcc = 269, country = "Poland",
        huntedRef = "SP-0012"
    )

    @Test
    fun adifExport_usesSubmodesAndProgramReferences() {
        val record = AdifGenerator.record(qso, "SP9XYZ")
        assertTrue(record.contains("<MODE:4>MFSK <SUBMODE:3>FT4 "))
        assertTrue(record.contains("<POTA_REF:7>SP-0012 "))
        assertTrue(record.contains("<SIG:4>POTA <SIG_INFO:7>SP-0012 "))
        assertTrue(record.contains("<QTH:6>Poznan "))
        assertTrue(record.contains("<STATION_CALLSIGN:6>SP9XYZ "))
        assertTrue(AdifGenerator.record(qso.copy(huntedRef = "SPFF-0001")).contains("<WWFF_REF:9>SPFF-0001 "))
        assertTrue(AdifGenerator.record(qso.copy(mode = "SSB")).contains("<MODE:3>SSB "))
    }

    @Test
    fun adifRoundTrip_preservesQsoData() {
        val text = AdifGenerator.generateAdif(listOf(qso, qso.copy(id = "2", callsign = "DL1ABC", mode = "CW", huntedRef = "")))
        val result = AdifParser.parse(text)
        assertEquals(0, result.skipped)
        assertEquals(2, result.entries.size)
        val back = result.entries[0]
        assertEquals("SP3ABC", back.callsign)
        assertEquals("2026-10-20 18:45", back.datetimeUtc)
        assertEquals("14074", back.frequency)
        assertEquals("20m", back.band)
        assertEquals("FT4", back.mode)
        assertEquals("Jan", back.name)
        assertEquals("JO82LK", back.gridSquare)
        assertEquals(269, back.dxcc)
        assertEquals("SP-0012", back.huntedRef)
        assertEquals("CW", result.entries[1].mode)
    }

    @Test
    fun adifImport_fromOtherLoggerWithLowercaseTagsAndTypes() {
        val text = """
            Exported by SomeLogger
            <adif_ver:5>3.1.0 <eoh>
            <call:5>K1ABC <qso_date:8:D>20250102 <time_on:6>235959 <band:3>40M <mode:3>SSB <submode:3>LSB
            <freq:5>7.185 <rst_sent:2>59 <rst_rcvd:2>57 <qrzcom_qso_upload_status:1>Y <eor>
            <call:4>XX1X <qso_date:8>20250102 <mode:2>CW <eor>
        """.trimIndent()
        val result = AdifParser.parse(text)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.skipped) // brak TIME_ON i pasma
        val e = result.entries.single()
        assertEquals("2025-01-02 23:59", e.datetimeUtc)
        assertEquals("7185", e.frequency)
        assertEquals("40m", e.band)
        assertEquals("SSB", e.mode)
        assertTrue(e.qrzUploaded)
    }

    @Test
    fun qrzResponses() {
        assertEquals(UploadOutcome.Uploaded, LogUploadClient.parseQrzResponse("RESULT=OK&LOGID=130877825&COUNT=1"))
        assertEquals(
            UploadOutcome.AlreadyThere,
            LogUploadClient.parseQrzResponse("RESULT=FAIL&REASON=Unable to add QSO to database: duplicate&EXTENDED=")
        )
        assertTrue(LogUploadClient.parseQrzResponse("RESULT=AUTH") is UploadOutcome.Fatal)
        assertTrue(LogUploadClient.parseQrzResponse("RESULT=FAIL&REASON=wrong band") is UploadOutcome.Rejected)
    }

    @Test
    fun eqslResponses() {
        assertEquals(
            UploadOutcome.Uploaded,
            LogUploadClient.parseEqslResponse("<!-- Reply form eQSL.cc ADIF Real-time Interface -->Result: 1 out of 1 records added<BR>")
        )
        assertEquals(
            UploadOutcome.AlreadyThere,
            LogUploadClient.parseEqslResponse("Result: 0 out of 1 records added<BR>Warning: Y=2025 M=01 D=02 Bad record: Duplicate<BR>")
        )
        assertTrue(LogUploadClient.parseEqslResponse("Error: No match on eQSL_User/eQSL_Pswd<BR>") is UploadOutcome.Fatal)
        assertTrue(LogUploadClient.parseEqslResponse("Result: 0 out of 1 records added<BR>Warning: Y=2025 M=01 D=02 Bad Mode: XYZ<BR>") is UploadOutcome.Rejected)
    }

    @Test
    fun hamQthSearchParsing() {
        val xml = """
            <?xml version="1.0"?>
            <HamQTH version="2.8" xmlns="https://www.hamqth.com">
            <search>
              <callsign>ok2cqr</callsign>
              <nick>Petr</nick>
              <qth>Neratovice</qth>
              <country>Czech Republic</country>
              <adif>503</adif>
              <grid>jo70gg</grid>
              <adr_name>Petr Hlozek</adr_name>
            </search>
            </HamQTH>
        """.trimIndent()
        val info = HamQthClient.parseSearch(xml)!!
        assertEquals("OK2CQR", info.callsign)
        assertEquals("Petr Hlozek", info.name)
        assertEquals("Neratovice", info.qth)
        assertEquals("JO70GG", info.gridSquare)
        assertEquals(503, info.dxcc)
        assertNull(HamQthClient.parseSearch("<session><error>Callsign not found</error></session>"))
    }

    @Test
    fun statistics() {
        val logs = listOf(
            qso,
            qso.copy(id = "2", callsign = "SP3ABC/P", band = "40m", mode = "CW", huntedRef = ""),
            qso.copy(id = "3", callsign = "DL1ABC", dxcc = 230, country = "Fed. Rep. of Germany", mode = "SSB", huntedRef = "DAFF-0001"),
            qso.copy(id = "4", callsign = "XX1X", dxcc = null, country = "", huntedRef = "")
        )
        val stats = LogStatistics.compute(logs)
        assertEquals(4, stats.totalQso)
        assertEquals(3, stats.uniqueCalls) // SP3ABC i SP3ABC/P to ten sam operator
        assertEquals(2, stats.dxccCount)
        assertEquals(1, stats.withoutDxcc)
        assertEquals(setOf("SP-0012"), stats.potaRefs)
        assertEquals(setOf("DAFF-0001"), stats.wwffRefs)
        assertEquals(2, stats.bandModeCounts["20m"]!!["DIGI"])
        assertEquals(1, stats.bandModeCounts["20m"]!!["PHONE"])
        assertEquals(1, stats.bandModeCounts["40m"]!!["CW"])
        assertEquals("SP3ABC", Callsigns.baseCall("DL/SP3ABC/P"))
    }
}
