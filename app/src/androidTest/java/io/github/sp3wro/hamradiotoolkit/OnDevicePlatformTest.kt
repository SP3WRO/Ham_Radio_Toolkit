package io.github.sp3wro.hamradiotoolkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sp3wro.hamradiotoolkit.background.SpaceWeather
import io.github.sp3wro.hamradiotoolkit.cw.CwDecoder
import io.github.sp3wro.hamradiotoolkit.cw.CwGenerator
import io.github.sp3wro.hamradiotoolkit.cw.CwTiming
import io.github.sp3wro.hamradiotoolkit.cw.CwTrainerScoring
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyDatabase
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.data.network.AprsParser
import io.github.sp3wro.hamradiotoolkit.data.network.HamQthClient
import io.github.sp3wro.hamradiotoolkit.data.network.LiveSpotsClient
import io.github.sp3wro.hamradiotoolkit.data.network.LogUploadClient
import io.github.sp3wro.hamradiotoolkit.data.network.UploadOutcome
import io.github.sp3wro.hamradiotoolkit.ui.components.WorldOutlines
import io.github.sp3wro.hamradiotoolkit.utils.AdifGenerator
import io.github.sp3wro.hamradiotoolkit.utils.AdifParser
import io.github.sp3wro.hamradiotoolkit.utils.CsvParser
import io.github.sp3wro.hamradiotoolkit.utils.LogStatistics
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ten sam kod co w testach jednostkowych, ale uruchomiony na Androidzie: wyłapuje różnice
 * między Javą na PC a środowiskiem telefonu (np. silnik wyrażeń regularnych ICU, który
 * odrzuca niezabezpieczone "]" i "}" – przez to aplikacja wywracała się w DX Clusterze).
 */
@RunWith(AndroidJUnit4::class)
class OnDevicePlatformTest {

    @Test
    fun ctyDatabase_parsesOnAndroid() {
        val cty = CtyDatabase.parse(
            """
            SP,Poland,269,EU,15,28,52.28,-18.67,-1.0,3Z HF SN SO SP SQ SR;
            K,United States,291,NA,5,8,37.60,91.87,5.0,AA AB K N W =4U1WB(5)[8] =AH0AB(3)[6];
            VK,Australia,150,OC,30,59,-23.70,-132.33,-10.0,VK VK4[55] =VK6TX[55] <-31.0/-115.9>VK6{OC}~-8.0~;
            """.trimIndent()
        )
        assertEquals("Poland", cty.resolve("SP3ABC")!!.name)
        assertEquals(5, cty.resolve("4U1WB")!!.cqZone)
        assertEquals(55, cty.resolve("VK4ABC")!!.ituZone)
        assertEquals(115.9, cty.resolve("VK6ABC")!!.lon, 1e-9)
    }

    @Test
    fun realCtyFile_ifAlreadyDownloaded() {
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "cty.csv")
        if (!file.exists()) return
        val cty = CtyDatabase.parse(file.readText())
        assertTrue(cty.entities.size > 300)
        assertEquals("Poland", cty.resolve("SP3ABC")!!.name)
    }

    @Test
    fun adifAprsLocatorAndParsers_onAndroid() {
        val qso = QsoEntry(callsign = "SP3ABC", datetimeUtc = "2026-10-20 18:45", frequency = "14074", band = "20m",
            mode = "FT4", rstSent = "-10", rstRcvd = "-12", comment = "Łódź", huntedRef = "SPFF-0001")
        val parsed = AdifParser.parse(AdifGenerator.generateAdif(listOf(qso)))
        assertEquals("FT4", parsed.entries.single().mode)
        assertEquals("Lodz", parsed.entries.single().comment)

        assertEquals(52.408333, AprsParser.parse("SP3XYZ>APRS:=5224.50N/01654.30E>090/036PHG5130Mobile", 0L)!!.latitude, 1e-5)
        assertEquals("KO02mf", Maidenhead.fromLatLon(52.2297, 21.0122, 6))
        assertEquals("Wielka Rawka", CsvParser.parseLine("SP/BI-003,\"Wielka Rawka\",1").let { it[1] })
        assertEquals("JO70GG", HamQthClient.parseSearch("<search><callsign>ok2cqr</callsign><grid>jo70gg</grid></search>")!!.gridSquare)
        assertEquals(UploadOutcome.Uploaded, LogUploadClient.parseQrzResponse("RESULT=OK&LOGID=1&COUNT=1"))
        assertEquals(UploadOutcome.Uploaded, LogUploadClient.parseEqslResponse("Result: 1 out of 1 records added<BR>"))
        assertEquals(1, LogStatistics.compute(listOf(qso)).wwffRefs.size)
        assertTrue(WorldOutlines.parse("M500,250 L510,250 L510,260 Z").isNotEmpty())
        assertEquals(1.0, CwTrainerScoring.accuracy("KM KM", "km  km"), 1e-9)
        assertEquals(5.67, SpaceWeather.latestKp("""[{"time_tag":"2026-10-20T18:00:00","Kp":5.67}]""")!!.kp, 1e-9)
        assertEquals(1, LiveSpotsClient().parseWwff(
            """[{"activator":"W7B","frequency_khz":21063,"mode":"CW","reference":"KFF-1572","spot_time":1}]"""
        ).size)
    }

    @Test
    fun worldMapResource_loadsOnAndroid() {
        val outlines = WorldOutlines.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(outlines.size > 200)
    }

    @Test
    fun cwDecoder_onAndroid() {
        val text = "CQ DE SP3ABC"
        val pcm = CwGenerator(toneHz = 650.0).render(text, CwTiming(20))
        val out = StringBuilder()
        CwDecoder(44100, 650.0, 20) { out.append(it) }.apply { autoTone = false }.process(pcm)
        assertEquals(text, out.toString().trim())
    }
}
