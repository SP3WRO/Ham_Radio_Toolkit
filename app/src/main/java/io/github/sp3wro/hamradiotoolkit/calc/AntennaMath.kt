package io.github.sp3wro.hamradiotoolkit.calc

import io.github.sp3wro.hamradiotoolkit.data.model.bandForKhz
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Wzory antenowe – wszystkie długości w metrach, częstotliwość w MHz. */
object AntennaMath {
    private const val C = 299.792458
    private const val FEET = 0.3048

    fun wavelength(freqMhz: Double): Double = C / freqMhz

    data class Dipole(val total: Double, val leg: Double, val wavelength: Double)

    /** Dipol półfalowy: λ/2·K (K = 0,95 odpowiada popularnemu 142,5/f). */
    fun dipole(freqMhz: Double, k: Double = 0.95): Dipole {
        val lambda = wavelength(freqMhz)
        val total = 0.5 * lambda * k
        return Dipole(total, total / 2, lambda)
    }

    data class Efhw(val radiator: Double, val counterpoise: Double, val harmonics: List<Pair<Double, String>>)

    /** Półfalówka zasilana na końcu: promiennik λ/2·K, przeciwwaga ok. 0,05 λ; pasma harmoniczne ×2…×4. */
    fun efhw(freqMhz: Double, k: Double = 0.95): Efhw {
        val lambda = wavelength(freqMhz)
        val harmonics = (2..4).mapNotNull { n ->
            val f = freqMhz * n
            bandForKhz(f * 1000)?.let { f to it }
        }
        return Efhw(0.5 * lambda * k, 0.05 * lambda, harmonics)
    }

    data class GroundPlane(val radiator: Double, val radials: Double)

    fun groundPlane(freqMhz: Double, k: Double = 0.95): GroundPlane {
        val radiator = 0.25 * wavelength(freqMhz) * k
        // Radiale podniesione – nieco dłuższe od promiennika
        return GroundPlane(radiator, radiator * 1.05)
    }

    data class JPole(val longElement: Double, val stub: Double, val feedPoint: Double, val spacingMin: Double, val spacingMax: Double)

    fun jPole(freqMhz: Double, k: Double = 0.95): JPole {
        val lambda = wavelength(freqMhz)
        return JPole(
            longElement = 0.75 * lambda * k,
            stub = 0.25 * lambda * k,
            feedPoint = 0.025 * lambda,
            spacingMin = 0.01 * lambda,
            spacingMax = 0.02 * lambda
        )
    }

    data class Yagi3(
        val reflector: Double, val driven: Double, val director: Double,
        val spacingReflector: Double, val spacingDirector: Double, val boom: Double
    )

    /** Klasyczne wzory 492/f, 468/f, 447/f (stopy) i odstępy 0,2 λ / 0,15 λ. */
    fun yagi3(freqMhz: Double): Yagi3 {
        val lambda = wavelength(freqMhz)
        val sr = 0.20 * lambda
        val sd = 0.15 * lambda
        return Yagi3(
            reflector = 492 * FEET / freqMhz,
            driven = 468 * FEET / freqMhz,
            director = 447 * FEET / freqMhz,
            spacingReflector = sr,
            spacingDirector = sd,
            boom = sr + sd
        )
    }

    data class Moxon(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double)

    /** Prostokąt Moxona – równania L.B. Cebika (W4RNL) z log10 średnicy przewodu wyrażonej w długościach fali. */
    fun moxon(freqMhz: Double, wireDiameterM: Double): Moxon {
        val lambda = wavelength(freqMhz)
        val d1 = log10(wireDiameterM / lambda)
        val a = -0.0008571428571 * d1.pow(2) - 0.009571428571 * d1 + 0.3398571429
        val b = -0.002142857143 * d1.pow(2) - 0.02035714286 * d1 + 0.008285714286
        val c = 0.001809523381 * d1.pow(2) + 0.01780952381 * d1 + 0.05164285714
        val d = 0.001 * d1 + 0.07178571429
        return Moxon(a * lambda, b * lambda, c * lambda, d * lambda, (b + c + d) * lambda)
    }

    data class Loop(val perimeter: Double, val quadSide: Double, val deltaSide: Double, val reflectorPerimeter: Double)

    /** Pętla pełnofalowa: 1005/f ft, reflektor quada 1030/f ft. */
    fun loop(freqMhz: Double): Loop {
        val perimeter = 1005 * FEET / freqMhz
        return Loop(perimeter, perimeter / 4, perimeter / 3, 1030 * FEET / freqMhz)
    }
}

/** Linie zasilające, dopasowanie, moc i zasilanie. */
object FeedlineMath {
    data class Stubs(val full: Double, val half: Double, val quarter: Double)

    fun stubs(freqMhz: Double, velocityFactor: Double): Stubs {
        val full = AntennaMath.wavelength(freqMhz) * velocityFactor
        return Stubs(full, full / 2, full / 4)
    }

    fun quarterWaveZ0(z1: Double, z2: Double): Double = sqrt(z1 * z2)

    fun gammaFromPower(forwardW: Double, reflectedW: Double): Double = sqrt(reflectedW / forwardW)

    fun gammaFromImpedance(r: Double, x: Double, z0: Double): Double =
        sqrt(((r - z0) * (r - z0) + x * x) / ((r + z0) * (r + z0) + x * x))

    fun swr(gamma: Double): Double = if (gamma >= 1.0) Double.POSITIVE_INFINITY else (1 + gamma) / (1 - gamma)

    fun returnLossDb(gamma: Double): Double = -20 * log10(gamma)

    fun mismatchLossDb(gamma: Double): Double = if (gamma >= 1.0) Double.POSITIVE_INFINITY else -10 * log10(1 - gamma * gamma)

    data class Radiated(val txDbm: Double, val atAntennaW: Double, val eirpW: Double, val erpW: Double)

    fun radiated(powerW: Double, cableLossDb: Double, gainDbi: Double): Radiated {
        val atAntenna = powerW * 10.0.pow(-cableLossDb / 10)
        val eirp = atAntenna * 10.0.pow(gainDbi / 10)
        // Dipol półfalowy ma 2,15 dBi
        return Radiated(10 * log10(powerW * 1000), atAntenna, eirp, eirp / 10.0.pow(2.15 / 10))
    }

    data class Battery(val averageA: Double, val usableAh: Double, val hours: Double)

    fun battery(capacityAh: Double, usablePercent: Double, rxA: Double, txA: Double, txDutyPercent: Double): Battery {
        val duty = txDutyPercent / 100
        val average = rxA * (1 - duty) + txA * duty
        val usable = capacityAh * usablePercent / 100
        return Battery(average, usable, if (average <= 0) Double.POSITIVE_INFINITY else usable / average)
    }
}
