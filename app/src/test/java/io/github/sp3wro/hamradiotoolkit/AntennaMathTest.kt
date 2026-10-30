package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.calc.AntennaMath
import io.github.sp3wro.hamradiotoolkit.calc.FeedlineMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaMathTest {

    @Test
    fun dipoleMatchesClassic142Formula() {
        // 142,5/f to dipol z K ≈ 0,95
        val d = AntennaMath.dipole(14.175)
        assertEquals(142.5 / 14.175, d.total, 0.02)
        assertEquals(d.total / 2, d.leg, 1e-12)
    }

    @Test
    fun moxonFor20mMatchesCebikGenerator() {
        // Generator Cebika dla 14,15 MHz, drut 2 mm: A ≈ 7,72 m, B ≈ 1,18 m, C ≈ 0,20 m, D ≈ 1,44 m
        val m = AntennaMath.moxon(14.15, 0.002)
        assertEquals(7.72, m.a, 0.05)
        assertEquals(1.18, m.b, 0.03)
        assertEquals(0.20, m.c, 0.02)
        assertEquals(1.44, m.d, 0.03)
        assertEquals(m.b + m.c + m.d, m.e, 1e-9)
    }

    @Test
    fun efhwFor40mWorksOnHarmonics() {
        val e = AntennaMath.efhw(7.1)
        assertEquals(listOf("20m", "15m", "10m"), e.harmonics.map { it.second })
        assertEquals(0.05 * AntennaMath.wavelength(7.1), e.counterpoise, 1e-9)
    }

    @Test
    fun jPoleFor2m() {
        // Klasyczna J-pole na 146 MHz: ok. 1,46 m i 0,49 m
        val j = AntennaMath.jPole(146.0)
        assertEquals(1.46, j.longElement, 0.02)
        assertEquals(0.49, j.stub, 0.01)
    }

    @Test
    fun loopAndYagiClassicFormulas() {
        assertEquals(1005 * 0.3048 / 14.2, AntennaMath.loop(14.2).perimeter, 1e-9)
        val y = AntennaMath.yagi3(28.5)
        assertTrue(y.reflector > y.driven && y.driven > y.director)
        assertEquals(468 * 0.3048 / 28.5, y.driven, 1e-9)
    }

    @Test
    fun swrAndLosses() {
        // 100 W / 11,1 W odbite → SWR 2
        val g = FeedlineMath.gammaFromPower(100.0, 100.0 / 9)
        assertEquals(2.0, FeedlineMath.swr(g), 1e-9)
        assertEquals(0.51, FeedlineMath.mismatchLossDb(g), 0.01)
        // 100 Ω na linii 50 Ω → SWR 2
        assertEquals(2.0, FeedlineMath.swr(FeedlineMath.gammaFromImpedance(100.0, 0.0, 50.0)), 1e-9)
        assertEquals(1.0, FeedlineMath.swr(FeedlineMath.gammaFromImpedance(50.0, 0.0, 50.0)), 1e-9)
        assertEquals(9.54, FeedlineMath.returnLossDb(1.0 / 3), 0.01)
    }

    @Test
    fun powerAndBattery() {
        val r = FeedlineMath.radiated(100.0, 3.0, 2.15)
        assertEquals(50.0, r.txDbm, 1e-9)
        assertEquals(50.1, r.atAntennaW, 0.1)
        assertEquals(r.atAntennaW, r.erpW, 1e-6) // dipol: ERP = moc na antenie
        val b = FeedlineMath.battery(20.0, 90.0, 1.0, 4.0, 25.0)
        assertEquals(1.75, b.averageA, 1e-9)
        assertEquals(18.0 / 1.75, b.hours, 1e-9)
        assertEquals(70.7, FeedlineMath.quarterWaveZ0(50.0, 100.0), 0.1)
        assertEquals(AntennaMath.wavelength(7.1) * 0.66 / 4, FeedlineMath.stubs(7.1, 0.66).quarter, 1e-9)
    }
}
