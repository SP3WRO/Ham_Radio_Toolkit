package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.cw.CwDecoder
import io.github.sp3wro.hamradiotoolkit.cw.CwGenerator
import io.github.sp3wro.hamradiotoolkit.cw.CwTiming
import io.github.sp3wro.hamradiotoolkit.cw.CwTrainerScoring
import io.github.sp3wro.hamradiotoolkit.cw.Morse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CwTest {

    private fun decode(pcm: ShortArray, toneHz: Double, initialWpm: Int, autoTone: Boolean = false, noise: Double = 0.0): String {
        val out = StringBuilder()
        val decoder = CwDecoder(44100, toneHz, initialWpm) { out.append(it) }
        decoder.autoTone = autoTone
        val random = Random(1)
        val floats = FloatArray(pcm.size) { (pcm[it] / 32768.0 + noise * random.nextGaussian()).toFloat() }
        // Bloki po 20 ms, jak z mikrofonu
        var i = 0
        while (i < floats.size) {
            val n = minOf(882, floats.size - i)
            decoder.process(floats.copyOfRange(i, i + n))
            i += n
        }
        return out.toString().trim()
    }

    @Test
    fun timing_parisAndFarnsworth() {
        val t = CwTiming(20)
        assertEquals(60.0, t.ditMs, 1e-9)
        assertEquals(180.0, t.charGapMs, 1e-9)
        assertEquals(420.0, t.wordGapMs, 1e-9)
        // Farnsworth 20/10: znaki szybkie, odstępy wydłużone (wzór ARRL)
        val f = CwTiming(20, 10)
        assertEquals(60.0, f.ditMs, 1e-9)
        assertTrue(f.charGapMs > 3 * f.ditMs)
        assertEquals(f.wordGapMs / 7, f.charGapMs / 3, 1e-9)
    }

    @Test
    fun morseTablesAreConsistent() {
        assertEquals(Morse.CODES.size, Morse.DECODE.size) // brak dwóch znaków z tym samym kodem
        assertEquals(41, Morse.KOCH_ORDER.length)
        assertEquals(Morse.KOCH_ORDER.length, Morse.KOCH_ORDER.toSet().size)
        assertTrue(Morse.KOCH_ORDER.all { it in Morse.CODES })
        assertEquals("KM", Morse.kochCharacters(1))
        assertEquals("KMURES", Morse.kochCharacters(5))
        assertEquals(Morse.KOCH_ORDER, Morse.kochCharacters(40))
    }

    @Test
    fun decoder_readsGeneratedCw_atSeveralSpeeds() {
        val text = "CQ CQ DE SP3ABC K"
        for (wpm in listOf(12, 20, 30)) {
            val pcm = CwGenerator(toneHz = 650.0).render(text, CwTiming(wpm))
            assertEquals("$wpm WPM", text, decode(pcm, 650.0, initialWpm = 18))
        }
    }

    @Test
    fun decoder_learnsSpeedAndHandlesNoise() {
        // Start z błędną prędkością (15 zamiast 25 WPM) i w szumie: pierwszy znak może przepaść
        // zanim dekoder się dostroi, dalej odbiór musi być bezbłędny
        val text = "TEST 5NN 73 = TU"
        val pcm = CwGenerator(toneHz = 700.0).render(text, CwTiming(25))
        var wpm = 0
        val out = StringBuilder()
        val decoder = CwDecoder(44100, 700.0, 15) { out.append(it) }.apply { autoTone = false }
        val random = Random(1)
        decoder.process(FloatArray(pcm.size) { (pcm[it] / 32768.0 + 0.05 * random.nextGaussian()).toFloat() })
        wpm = decoder.wpm
        assertTrue(out.toString(), out.toString().trim().endsWith("EST 5NN 73 = TU"))
        assertTrue("WPM $wpm", wpm in 23..27)
    }

    @Test
    fun decoder_findsToneAutomatically() {
        val text = "PARIS PARIS PARIS PARIS"
        val pcm = CwGenerator(toneHz = 820.0).render(text, CwTiming(20), leadInMs = 1500.0)
        val decoded = decode(pcm, 500.0, initialWpm = 20, autoTone = true)
        // Pierwsze znaki mogą przepaść zanim dekoder znajdzie ton – reszta musi być poprawna
        assertTrue(decoded, decoded.endsWith("PARIS PARIS"))
    }

    @Test
    fun trainerScoring() {
        assertEquals(1.0, CwTrainerScoring.accuracy("KMKMR RKMUM", "KMKMR RKMUM"), 1e-9)
        assertEquals(1.0, CwTrainerScoring.accuracy("KMKMR RKMUM", "kmkmr  rkmum "), 1e-9)
        assertEquals(1 - 1.0 / 11, CwTrainerScoring.accuracy("KMKMR RKMUM", "KMKMR RKMU"), 1e-9)
        assertEquals(0.0, CwTrainerScoring.accuracy("KMKMR", ""), 1e-9)
        val groups = CwTrainerScoring.randomGroups("KM", groups = 3, groupLength = 5, random = Random(3))
        assertEquals(3, groups.split(' ').size)
        assertTrue(groups.replace(" ", "").all { it in "KM" })
    }
}
