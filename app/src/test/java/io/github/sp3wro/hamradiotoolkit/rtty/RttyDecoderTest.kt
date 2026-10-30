package io.github.sp3wro.hamradiotoolkit.rtty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RttyDecoderTest {
    private val rate = 12000

    /** Sygnał AFSK: cisza (mark), potem znaki 1 start + 5 danych (LSB pierwszy) + 1,5 stopu. */
    private fun afsk(
        text: String, markHz: Double, spaceHz: Double, noise: Double,
        baud: Double = 45.45, seed: Long = 1
    ): FloatArray {
        val bits = ArrayList<Pair<Boolean, Double>>() // (mark?, długość w bitach)
        bits += true to 20.0
        fun char(code: Int) {
            bits += false to 1.0
            for (i in 0 until 5) bits += ((code shr i) and 1 == 1) to 1.0
            bits += true to 1.5
        }
        var figures = false
        char(Baudot.LTRS)
        for (c in text) {
            val (code, needsFigures) = Baudot.encode(c) ?: continue
            if (c != ' ' && needsFigures != figures) {
                char(if (needsFigures) Baudot.FIGS else Baudot.LTRS)
                figures = needsFigures
            }
            char(code)
            if (c == ' ') figures = false // unshift-on-space po stronie odbiorcy
        }
        bits += true to 20.0

        val bitSamples = rate / baud
        val total = bits.sumOf { it.second } * bitSamples
        val out = FloatArray(total.toInt() + 1)
        val random = Random(seed)
        var phase = 0.0
        var t = 0.0
        var idx = 0
        for ((mark, len) in bits) {
            t += len * bitSamples
            val f = if (mark) markHz else spaceHz
            while (idx < t && idx < out.size) {
                phase += 2 * PI * f / rate
                out[idx] = (0.3 * sin(phase) + noise * random.nextGaussian()).toFloat()
                idx++
            }
        }
        return out
    }

    private fun feed(signal: FloatArray, process: (FloatArray, Int) -> Unit) {
        // Podawanie w blokach jak z mikrofonu (50 ms)
        var pos = 0
        val chunk = FloatArray(600)
        while (pos < signal.size) {
            val n = minOf(chunk.size, signal.size - pos)
            System.arraycopy(signal, pos, chunk, 0, n)
            process(chunk, n)
            pos += n
        }
    }

    /** Dekoder ze wszystkimi parametrami ustawionymi ręcznie. */
    private fun decodeManual(signal: FloatArray, center: Double, reverse: Boolean, baud: Double = 45.45, shift: Double = 170.0): String {
        val sb = StringBuilder()
        val decoder = RttyDecoder(rate) { sb.append(it) }
        decoder.autoBaud = false
        decoder.autoPolarity = false
        decoder.centerHz = center
        decoder.shiftHz = shift
        decoder.baud = baud
        decoder.reverse = reverse
        feed(signal) { c, n -> decoder.process(c, n) }
        return sb.toString()
    }

    @Test
    fun decodesCleanSignalWithFigures() {
        val msg = "CQ CQ DE SP3WRO SP3WRO PSE K 599 5NN 73"
        val signal = afsk(msg, markHz = 1585.0, spaceHz = 1415.0, noise = 0.0)
        assertEquals(msg, decodeManual(signal, 1500.0, reverse = false).trim())
    }

    @Test
    fun decodesNoisySignal() {
        val msg = "RYRYRY THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 1234567890"
        // SNR ok. 3 dB w paśmie 3 kHz – dla RTTY to wciąż czytelny sygnał
        val signal = afsk(msg, markHz = 2125.0, spaceHz = 2295.0, noise = 0.2)
        val decoded = decodeManual(signal, 2210.0, reverse = true).trim()
        val errors = msg.zip(decoded).count { (a, b) -> a != b } + abs(msg.length - decoded.length)
        assertTrue("Za dużo błędów ($errors): '$decoded'", errors <= 2)
    }

    @Test
    fun manualSettingsAreRespected() {
        val msg = "CQ CQ DE SP3WRO SP3WRO K"
        val signal = afsk(msg, markHz = 1585.0, spaceHz = 1415.0, noise = 0.0)
        assertFalse(decodeManual(signal, 1500.0, reverse = true).contains("SP3WRO"))
        // Ręcznie ustawione 75 Bd dla sygnału 45,45 Bd – bez automatyki nie ma szans
        assertFalse(decodeManual(signal, 1500.0, reverse = false, baud = 75.0).contains("SP3WRO"))
    }

    private data class Case(
        val baud: Double, val shift: Double, val center: Double, val inverted: Boolean,
        val noise: Double = 0.05, val driftHzPerS: Double = 0.0
    )

    /** Sygnał z dryfem: każdy znak przesunięty proporcjonalnie do czasu (jak nagrzewający się nadajnik). */
    private fun drifting(text: String, case: Case): FloatArray {
        if (case.driftHzPerS == 0.0) {
            val higher = case.center + case.shift / 2
            val lower = case.center - case.shift / 2
            return afsk(text, if (case.inverted) lower else higher, if (case.inverted) higher else lower,
                case.noise, case.baud, case.center.toLong())
        }
        // Kawałki po 10 znaków, każdy z nieco wyższą częstotliwością
        val parts = text.chunked(10)
        var t = 0.0
        val out = ArrayList<FloatArray>()
        for ((i, part) in parts.withIndex()) {
            val c = case.center + case.driftHzPerS * t
            val higher = c + case.shift / 2
            val lower = c - case.shift / 2
            val piece = afsk(part, if (case.inverted) lower else higher, if (case.inverted) higher else lower,
                case.noise, case.baud, i.toLong())
            out += piece
            t += piece.size.toDouble() / rate
        }
        val all = FloatArray(out.sumOf { it.size })
        var pos = 0
        out.forEach { System.arraycopy(it, 0, all, pos, it.size); pos += it.size }
        return all
    }

    @Test
    fun autoDetectsFrequencyShiftBaudAndPolarity() {
        val msg = "CQ CQ DE SP3WRO SP3WRO PSE K"
        val text = ("RYRYRYRYRY " + "$msg ".repeat(8)).trim()
        for (case in listOf(
            Case(45.45, 170.0, 1000.0, inverted = false),
            Case(50.0, 450.0, 1700.0, inverted = true),
            Case(75.0, 850.0, 1500.0, inverted = false),
            Case(100.0, 170.0, 1300.0, inverted = false),
            Case(45.45, 200.0, 1600.0, inverted = false),
            Case(45.45, 425.0, 1100.0, inverted = true),
            Case(45.45, 170.0, 2210.0, inverted = true),
            Case(45.45, 170.0, 800.0, inverted = false, noise = 0.15),
            Case(45.45, 170.0, 1200.0, inverted = false, driftHzPerS = 1.0)
        )) {
            val signal = drifting(text, case)
            val endCenter = case.center + case.driftHzPerS * signal.size / rate
            val sb = StringBuilder()
            val engine = RttyEngine(rate) { sb.append(it) } // start z domyślnych: 1500 Hz, 170 Hz, 45,45 Bd
            feed(signal) { c, n -> engine.process(c, n) }
            val d = engine.decoder
            val label = "$case → ${"%.1f".format(d.centerHz)} Hz, ${d.shiftHz} Hz, ${d.baud} Bd, odwr.=${d.activeReverse}"
            println(label)
            assertEquals(label, case.shift, d.shiftHz, 0.1)
            assertEquals(label, case.baud, d.baud, 0.01)
            assertTrue(label, abs(d.centerHz - endCenter) < 3)
            assertEquals(label, case.inverted, d.activeReverse)
            assertTrue("$label: '${sb.takeLast(80)}'", sb.takeLast(80).contains(msg))
        }
    }

    /** Zakłócenia jak z mikrofonu przy głośniku: przydźwięk 50 Hz z harmonicznymi do 600 Hz i obca nośna. */
    private fun addInterference(signal: FloatArray, carrierHz: Double = 2600.0, seed: Long = 7): FloatArray {
        val random = Random(seed)
        return FloatArray(signal.size) { i ->
            val t = i.toDouble() / rate
            var hum = 0.0
            for (h in 1..12) hum += 0.05 / h * sin(2 * PI * 50 * h * t)
            (signal[i] + hum + 0.08 * sin(2 * PI * carrierHz * t) + 0.03 * random.nextGaussian()).toFloat()
        }
    }

    private fun mix(a: FloatArray, b: FloatArray): FloatArray = FloatArray(maxOf(a.size, b.size)) {
        (if (it < a.size) a[it] else 0f) + (if (it < b.size) b[it] else 0f)
    }

    @Test
    fun acquiresQuicklyDespiteHumCarrierAndSecondStation() {
        val msg = "CQ CQ DE SP3WRO SP3WRO PSE K"
        val text = ("RYRYRYRYRY " + "$msg ".repeat(6)).trim()
        // Silna stacja 2210 Hz (odwrócona – jak AFSK 2125/2295), słabsza 700 Hz, start AFC z 1500 Hz
        val strong = afsk(text, markHz = 2125.0, spaceHz = 2295.0, noise = 0.0, seed = 3)
        // Słabsza nadaje krócej niż silna – po końcu silnej AFC słusznie przeszłoby na nią
        val weak = afsk("RYRYRY ".repeat(15), markHz = 785.0, spaceHz = 615.0, noise = 0.0, seed = 4)
            .map { it * 0.3f }.toFloatArray()
        val signal = addInterference(mix(strong, weak))

        val sb = StringBuilder()
        val engine = RttyEngine(rate) { sb.append(it) }
        var acquiredAt = -1.0
        var pos = 0
        val chunk = FloatArray(600)
        while (pos < signal.size) {
            val n = minOf(chunk.size, signal.size - pos)
            System.arraycopy(signal, pos, chunk, 0, n)
            engine.process(chunk, n)
            pos += n
            if (acquiredAt < 0 && abs(engine.decoder.centerHz - 2210.0) < 5) acquiredAt = pos.toDouble() / rate
        }
        val d = engine.decoder
        val label = "środek ${"%.1f".format(d.centerHz)} Hz, ${d.shiftHz} Hz, złapane po ${"%.2f".format(acquiredAt)} s"
        println("Zakłócenia: $label")
        assertTrue(label, acquiredAt in 0.0..2.0)
        assertTrue(label, abs(d.centerHz - 2210.0) < 3)
        assertEquals(label, 170.0, d.shiftHz, 0.1)
        assertTrue("$label: '${sb.takeLast(80)}'", sb.takeLast(80).contains(msg))
    }

    @Test
    fun tapSelectsWeakerStation() {
        val strong = afsk("RYRYRY ".repeat(60), markHz = 2125.0, spaceHz = 2295.0, noise = 0.0, seed = 5)
        val msg = "CQ DE SP3WRO K"
        val weak = afsk(("RYRY " + "$msg ".repeat(10)).trim(), markHz = 1085.0, spaceHz = 915.0, noise = 0.0, seed = 6)
            .map { it * 0.4f }.toFloatArray()
        val signal = addInterference(mix(strong, weak))
        val sb = StringBuilder()
        val engine = RttyEngine(rate) { sb.append(it) }
        var pos = 0
        val chunk = FloatArray(600)
        var tapped = false
        var centerAt20s = 0.0
        while (pos < signal.size) {
            val n = minOf(chunk.size, signal.size - pos)
            System.arraycopy(signal, pos, chunk, 0, n)
            engine.process(chunk, n)
            pos += n
            // Po 3 s użytkownik dotyka widma w pobliżu słabszej stacji (z błędem 40 Hz)
            if (!tapped && pos > 3 * rate) {
                engine.setCenter(1040.0)
                tapped = true
            }
            // Słabsza stacja nadaje ok. 26 s; w 20 s (już po 10-sekundowym oknie wskazania) AFC ma ją trzymać
            if (centerAt20s == 0.0 && pos > 20 * rate) centerAt20s = engine.decoder.centerHz
        }
        val label = "środek w 20 s: ${"%.1f".format(centerAt20s)} Hz"
        assertTrue(label, abs(centerAt20s - 1000.0) < 3)
        assertTrue("$label: '$sb'", sb.contains("$msg $msg"))
        // Po końcu słabszej stacji AFC wraca do jedynej nadającej
        assertEquals(2210.0, engine.decoder.centerHz, 3.0)
    }

    @Test
    fun afcDoesNotWanderOnNoiseOrSingleCarrier() {
        // 20 s samego szumu, przydźwięku i jednej nośnej – strojenie nie może się ruszyć
        val signal = addInterference(FloatArray(20 * rate), carrierHz = 1530.0)
        val engine = RttyEngine(rate) { }
        engine.setCenter(1200.0)
        feed(signal) { c, n -> engine.process(c, n) }
        assertEquals(1200.0, engine.decoder.centerHz, 1.0)
        assertEquals(170.0, engine.decoder.shiftHz, 0.1)
    }

    @Test
    fun recordedWavRoundTripDecodesTheSame() {
        val msg = "CQ CQ DE SP3WRO SP3WRO PSE K"
        val signal = addInterference(afsk(("RYRY " + "$msg ".repeat(4)).trim(), 2125.0, 2295.0, 0.0, seed = 9))
        val bytes = java.io.ByteArrayOutputStream()
        io.github.sp3wro.hamradiotoolkit.dsp.WavPcmWriter.write(bytes, signal, rate)
        val audio = io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader.read(bytes.toByteArray().inputStream())
        assertEquals(rate, audio.sampleRate)
        assertEquals(signal.size, audio.samples.size)
        val sb = StringBuilder()
        val engine = RttyEngine(rate) { sb.append(it) }
        feed(audio.samples) { c, n -> engine.process(c, n) }
        assertTrue("'$sb'", sb.contains("$msg $msg"))
    }

    @Test
    fun baudEstimatorPrefersCorrectGrid() {
        // Odcinki 50 Bd (240 próbek): 1, 2, 1, 3, 1,5 itd. bitu
        val bit = rate / 50.0
        val pattern = listOf(1.0, 2.0, 1.0, 3.0, 1.5, 1.0, 2.5, 1.0, 4.0, 1.0)
        val runs = IntArray(60) { (pattern[it % pattern.size] * bit).toInt() + (it % 3) - 1 }
        assertEquals(50.0, RttyDecoder.estimateBaud(runs, rate)!!, 1e-9)
        // Same krótkie zakłócenia – brak decyzji
        assertNull(RttyDecoder.estimateBaud(IntArray(60) { 20 + it % 7 }, rate))
    }

    @Test
    fun shiftSnapping() {
        assertEquals(170.0, TuningSpectrum.snapShift(176.0), 1e-9)
        assertEquals(450.0, TuningSpectrum.snapShift(441.0), 1e-9)
        assertEquals(850.0, TuningSpectrum.snapShift(838.0), 1e-9)
        assertEquals(300.0, TuningSpectrum.snapShift(302.0), 1e-9)
    }

    @Test
    fun spectrumFindsPairAndShift() {
        val signal = afsk("RYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRY", markHz = 1885.0, spaceHz = 1715.0, noise = 0.02)
        val spectrum = TuningSpectrum(rate)
        var pos = 0
        // Bez końcowej ciszy (sam ton mark) – widmo uśrednione z kolejnych bloków jak na żywo
        while (pos + 600 < signal.size - 20 * 264) {
            spectrum.process(signal.copyOfRange(pos, pos + 600), 600)
            pos += 600
        }
        val found = spectrum.findRttySignal()
        assertNotNull(found)
        assertTrue("Środek ${found!!.centerHz}", abs(found.centerHz - 1800.0) <= 6.0)
        assertEquals(170.0, found.shiftHz, 1e-9)
        // Przy wymuszonym przesuwie 850 Hz nie ma pasującej pary
        assertNull(spectrum.findRttySignal(fixedShift = 850.0))
    }
}
