package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyDatabase
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorAndDxccTest {

    @Test
    fun bundledCtyDatabase_parsesAndResolvesCommonCalls() {
        // Zapasowa baza DXCC w assets – używana, gdy nie udało się jeszcze pobrać aktualnej
        val db = CtyDatabase.parse(java.io.File("src/main/assets/cty.csv").readText())
        assertTrue(db.entities.size > 300)
        assertEquals(269, db.resolve("SP3WRO")?.dxcc)
        assertEquals(230, db.resolve("DL1ABC")?.dxcc)
        assertEquals(291, db.resolve("W1AW")?.dxcc)
    }

    private val warsaw = LatLon(52.2297, 21.0122)
    private val newYork = LatLon(40.7128, -74.0060)

    @Test
    fun maidenhead_fromLatLon() {
        assertEquals("KO02mf", Maidenhead.fromLatLon(warsaw.lat, warsaw.lon, 6))
        assertEquals("KO02", Maidenhead.fromLatLon(warsaw.lat, warsaw.lon, 4))
        assertEquals("FN20xr", Maidenhead.fromLatLon(newYork.lat, newYork.lon, 6))
        // Krawędzie mapy nie mogą wyjść poza AA–RR
        assertEquals("RR99xx", Maidenhead.fromLatLon(90.0, 180.0, 6))
        assertEquals("AA00aa", Maidenhead.fromLatLon(-90.0, -180.0, 6))
    }

    @Test
    fun maidenhead_toLatLon_returnsCenterAndRoundTrips() {
        val jo82 = Maidenhead.toLatLon("JO82")!!
        assertEquals(52.5, jo82.lat, 1e-9)
        assertEquals(17.0, jo82.lon, 1e-9)
        for (loc in listOf("KO02mf", "FN20xr", "JO82lk", "QF56od", "KO02mf45")) {
            val center = Maidenhead.toLatLon(loc)!!
            assertEquals(loc.uppercase(), Maidenhead.fromLatLon(center.lat, center.lon, loc.length).uppercase())
        }
        assertFalse(Maidenhead.isValid("SP3ABC"))
        assertFalse(Maidenhead.isValid("JO8"))
        assertTrue(Maidenhead.isValid("jo82lk"))
        assertNull(Maidenhead.toLatLon("ZZ00"))
    }

    @Test
    fun greatCircle_distanceAndBearing() {
        val path = GreatCircle.path(warsaw, newYork)
        assertEquals(6854.2, path.shortPathKm, 1.0)
        assertEquals(300.9, path.shortPathBearing, 0.1)
        assertEquals(2 * Math.PI * GreatCircle.EARTH_RADIUS_KM - 6854.2, path.longPathKm, 1.0)
        assertEquals(120.9, path.longPathBearing, 0.1)
        assertEquals("WNW", GreatCircle.compassPoint(path.shortPathBearing))
        assertEquals("N", GreatCircle.compassPoint(359.0))
    }

    @Test
    fun greatCircle_destinationAndAzimuthalProjection() {
        val north = GreatCircle.destination(warsaw, 0.0, 1000.0)
        assertEquals(1000.0, GreatCircle.distanceKm(warsaw, north), 0.5)

        // W rzucie azymutalnym odległość od środka = odległość po ortodromie, kąt = azymut
        val (x, y) = GreatCircle.azimuthalProject(warsaw, newYork)!!
        assertEquals(6854.2, Math.hypot(x, y), 1.0)
        val angle = (Math.toDegrees(Math.atan2(x, y)) + 360) % 360
        assertEquals(300.9, angle, 0.1)
        assertNull(GreatCircle.azimuthalProject(warsaw, LatLon(-warsaw.lat, warsaw.lon - 180)))
    }

    // Wycinek prawdziwego cty.csv (country-files.com)
    private val cty = CtyDatabase.parse(
        """
        DL,Fed. Rep. of Germany,230,EU,14,28,51.00,-10.00,-1.0,DA DB DC DD DF DG DH DJ DK DL DM DN DO DP DQ DR;
        *IG9,African Italy,248,AF,33,37,35.67,-12.67,-1.0,IG9 IH9;
        K,United States,291,NA,5,8,37.60,91.87,5.0,AA AB AC AD AE AF AG AI AJ AK K N W =4U1WB(5)[8] =AH0AB(3)[6];
        KH6,Hawaii,110,OC,31,61,21.12,157.48,10.0,AH6 AH7 KH6 KH7 NH6 NH7 WH6 WH7 =K2GT =WB7N;
        SP,Poland,269,EU,15,28,52.28,-18.67,-1.0,3Z HF SN SO SP SQ SR;
        VK,Australia,150,OC,30,59,-23.70,-132.33,-10.0,AX VI VJ VK VL AX4[55] VI4[55] VJ4[55] VK4[55] VL4[55] =VI9POL =VK6TX[55];
        """.trimIndent()
    )

    @Test
    fun cty_parsesEntitiesAndFlipsLongitude() {
        val poland = cty.resolve("SP3ABC")!!
        assertEquals("Poland", poland.name)
        assertEquals(269, poland.dxcc)
        assertEquals(52.28, poland.lat, 1e-9)
        assertEquals(18.67, poland.lon, 1e-9) // w cty dodatnia długość = zachodnia
        assertEquals(-91.87, cty.resolve("W1AW")!!.lon, 1e-9)
        // Strefa czasowa w cty też ma odwrócony znak: SP = UTC+1, W = UTC−5, VK = UTC+10
        assertEquals(1.0, poland.utcOffsetHours, 1e-9)
        assertEquals(-5.0, cty.resolve("W1AW")!!.utcOffsetHours, 1e-9)
        assertEquals(10.0, cty.resolve("VK2ABC")!!.utcOffsetHours, 1e-9)
        // Wpis "*" (WAE) nie jest osobnym krajem DXCC
        assertEquals(5, cty.entities.size)
    }

    @Test
    fun cty_longestPrefixExactCallsAndOverrides() {
        assertEquals("Hawaii", cty.resolve("KH6XYZ")!!.name)
        assertEquals("United States", cty.resolve("K2ABC")!!.name)
        assertEquals("Hawaii", cty.resolve("K2GT")!!.name) // dokładny znak "="
        assertEquals("Poland", cty.resolve("HF0POL")!!.name)
        assertEquals(55, cty.resolve("VK4ABC")!!.ituZone) // nadpisanie strefy ITU [55]
        assertEquals(59, cty.resolve("VK2ABC")!!.ituZone)
        assertEquals(5, cty.resolve("4U1WB")!!.cqZone)
    }

    @Test
    fun cty_handlesPortableAndSlashedCallsigns() {
        assertEquals("Poland", cty.resolve("SP/DL1ABC")!!.name)
        assertEquals("Poland", cty.resolve("DL1ABC/SP")!!.name)
        assertEquals("Fed. Rep. of Germany", cty.resolve("DL1ABC/P")!!.name)
        assertEquals("Hawaii", cty.resolve("KH6/W1AW/P")!!.name)
        assertEquals("United States", cty.resolve("W1AW/4")!!.name)
        assertNull(cty.resolve("SP3ABC/MM")) // morski mobilny – poza DXCC
        assertNull(cty.resolve(""))
        assertNull(cty.resolve("XX9ZZZ"))
    }
}
