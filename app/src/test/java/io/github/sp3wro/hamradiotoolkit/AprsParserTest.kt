package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.network.AprsClient
import io.github.sp3wro.hamradiotoolkit.data.network.AprsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AprsParserTest {

    @Test
    fun mobileStation_parsesPositionCourseSpeedAndPhg() {
        val packet = AprsParser.parse("SP3XYZ-9>APRS,TCPIP*:=5224.50N/01654.30E>090/036PHG5130Mobile", 0L)!!

        assertEquals("SP3XYZ-9", packet.callsign)
        assertEquals(52.408333, packet.latitude, 1e-5)
        assertEquals(16.905, packet.longitude, 1e-5)
        assertEquals('/', packet.table)
        assertEquals('>', packet.symbol)
        assertEquals(90, packet.courseDegrees)
        assertEquals(36 * 1.852, packet.speedKmh!!, 1e-6)
        assertEquals(25, packet.powerW)
        assertEquals(20 * 0.3048, packet.antennaHeightM!!, 1e-6)
        assertEquals(3, packet.antennaGainDb)
        assertEquals(0, packet.directivityDeg)
        assertEquals("Mobile", packet.comment)
    }

    @Test
    fun southWestCoordinates_areNegative() {
        val packet = AprsParser.parse("LU1ABC>APRS:!3436.00S/05822.00W-", 0L)!!
        assertEquals(-34.6, packet.latitude, 1e-6)
        assertEquals(-58.366667, packet.longitude, 1e-5)
    }

    @Test
    fun weatherStation_usesMphAndParsesAllFields() {
        val packet = AprsParser.parse(
            "SP3WX>APRS:@201234z5224.50N/01654.30E_180/005g010t050r001p002P003h85b10132Test WX", 0L
        )!!

        assertEquals(180, packet.windDirDeg)
        assertEquals(5 * 1.60934, packet.windSpeedKmh!!, 1e-6)
        assertEquals(10 * 1.60934, packet.windGustKmh!!, 1e-6)
        assertEquals(10.0, packet.tempCelsius!!, 1e-6)
        assertEquals(0.254, packet.rain1hMm!!, 1e-6)
        assertEquals(0.508, packet.rain24hMm!!, 1e-6)
        assertEquals(0.762, packet.rainMidnightMm!!, 1e-6)
        assertEquals(85, packet.humidityPercent)
        assertEquals(1013.2, packet.pressureHpa!!, 1e-6)
        assertEquals("Test WX", packet.comment)
        assertNull(packet.courseDegrees)
    }

    @Test
    fun weatherStation_negativeTemperatureAndMissingValues() {
        val packet = AprsParser.parse("SP3WX>APRS:!5224.50N/01654.30E_.../...g...t-05h00b.....", 0L)!!
        assertNull(packet.windSpeedKmh)
        assertNull(packet.windGustKmh)
        assertEquals((-5 - 32.0) * 5.0 / 9.0, packet.tempCelsius!!, 1e-6)
        assertEquals(100, packet.humidityPercent)
        assertNull(packet.pressureHpa)
        assertEquals("", packet.comment)
    }

    @Test
    fun regularStation_commentIsNotMistakenForWeather() {
        val packet = AprsParser.parse("SP3R>APRS:!5224.50N/01654.30E#QRV r145 s123 t100 h50", 0L)!!
        assertNull(packet.rain1hMm)
        assertNull(packet.windSpeedKmh)
        assertNull(packet.tempCelsius)
        assertNull(packet.humidityPercent)
        assertEquals("QRV r145 s123 t100 h50", packet.comment)
    }

    @Test
    fun overlay_isExtractedFromTableCharacter() {
        val packet = AprsParser.parse("SR3D>APRS:!5224.50ND01654.30E#Digi", 0L)!!
        // Nakładka zawsze dotyczy tabeli alternatywnej (APRS 1.0.1, rozdz. 20)
        assertEquals('\\', packet.table)
        assertEquals('#', packet.symbol)
        assertEquals('D', packet.overlay)
    }

    @Test
    fun compressedPosition_specExample() {
        // APRS 1.0.1, rozdz. 9: "/5L!!<*e7>7P[" = 49.5°N 72.75°W, kurs 88°, 36,2 węzła
        val packet = AprsParser.parse("W1AW>APRS:!/5L!!<*e7>7P[Compressed", 0L)!!
        assertEquals(49.5, packet.latitude, 1e-4)
        assertEquals(-72.75, packet.longitude, 1e-4)
        assertEquals('/', packet.table)
        assertEquals('>', packet.symbol)
        assertEquals(88, packet.courseDegrees)
        assertEquals((Math.pow(1.08, 47.0) - 1) * 1.852, packet.speedKmh!!, 1e-6)
        assertEquals("Compressed", packet.comment)
    }

    @Test
    fun compressedPosition_overlayFromLowercaseTable() {
        val packet = AprsParser.parse("SR3D>APRS:=b5L!!<*e7#  [", 0L)!!
        assertEquals('\\', packet.table)
        assertEquals('1', packet.overlay)
        assertEquals('#', packet.symbol)
        assertNull(packet.speedKmh)
    }

    @Test
    fun micE_decodesPositionSpeedCourseAndComment() {
        // 52°24.50'N 16°54.30'E, 20 węzłów, kurs 251° – zakodowane ręcznie wg rozdz. 10
        val packet = AprsParser.parse("SP3ABC-9>522T50,WIDE1-1:`,R:n\"O>/Hello", 0L)!!
        assertEquals(52.408333, packet.latitude, 1e-5)
        assertEquals(16.905, packet.longitude, 1e-5)
        assertEquals('/', packet.table)
        assertEquals('>', packet.symbol)
        assertEquals(20 * 1.852, packet.speedKmh!!, 1e-6)
        assertEquals(251, packet.courseDegrees)
        assertEquals("Hello", packet.comment)
    }

    @Test
    fun micE_westAndOffsetLongitude() {
        // 33°25.64'S 112°07.74'W: znak 4 = '5' (S), 5 = 'V' (6, +100°), 6 = 'T' (4, W); 112-100=12 -> '('
        val packet = AprsParser.parse("N0CALL>3325VT:`(#f\u001c\u001c\u001c>/", 0L)
        assertNotNull(packet)
        assertEquals(-(33 + 25.64 / 60), packet!!.latitude, 1e-5)
        assertEquals(-(112 + 7.74 / 60), packet.longitude, 1e-5)
        assertNull(packet.speedKmh)
    }

    @Test
    fun altitudeInComment_isParsedAndRemoved() {
        val packet = AprsParser.parse("SP3XYZ>APRS:!5224.50N/01654.30E>Mobile /A=000328 QRV", 0L)!!
        assertEquals(328 * 0.3048, packet.altitudeM!!, 1e-6)
        assertEquals("Mobile  QRV", packet.comment)
    }

    @Test
    fun invalidOrNonPositionPackets_returnNull() {
        assertNull(AprsParser.parse("garbage", 0L))
        assertNull(AprsParser.parse("SP3XYZ>APRS::SP3ABC   :message", 0L))
        assertNull(AprsParser.parse("SP3XYZ>APRS:!5224.50N/01654", 0L))
        assertNull(AprsParser.parse("SP3XYZ>APRS:!52x4.50N/01654.30E>", 0L))
        assertNotNull(AprsParser.parse("SP3XYZ>APRS:!5224.50N/01654.30E>", 0L))
    }

    @Test
    fun beaconCoordinates_roundIntoNextDegree() {
        // 52°59,9976' – minuty zaokrąglają się do 60,00, więc musi wyjść 53°00,00'
        assertEquals("5300.00N", AprsClient.formatLat(52.99996))
        assertEquals("01700.00E", AprsClient.formatLon(16.99996))
        assertEquals("3436.00S", AprsClient.formatLat(-34.6))
        assertEquals("05822.00W", AprsClient.formatLon(-58.366667))
        // Wynik przechodzi przez parser bez zmian
        val packet = AprsParser.parse("SP3XYZ>APRS:=${AprsClient.formatLat(52.99996)}/${AprsClient.formatLon(16.99996)}>", 0L)!!
        assertEquals(53.0, packet.latitude, 1e-6)
        assertEquals(17.0, packet.longitude, 1e-6)
    }

    @Test
    fun passcode_ignoresSsidAndCase() {
        val passcode = AprsParser.passcode("SP3ABC")
        assertEquals(passcode, AprsParser.passcode("sp3abc-7"))
        assert(passcode in 0..0x7fff)
    }

    @Test
    fun symbolCode_validation() {
        listOf("/[", "/b", "\\j", "S#", "1&").forEach { assert(AprsParser.isValidSymbolCode(it)) { it } }
        listOf("", "/", "/[x", "a#", " [", "/ ").forEach { assert(!AprsParser.isValidSymbolCode(it)) { it } }
    }
}
