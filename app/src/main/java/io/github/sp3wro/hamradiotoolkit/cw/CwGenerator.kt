package io.github.sp3wro.hamradiotoolkit.cw

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Generator sygnału CW (16-bit PCM mono) z łagodnymi zboczami, bez trzasków przy kluczowaniu. */
class CwGenerator(
    private val sampleRate: Int = 44100,
    private val toneHz: Double = 600.0,
    private val amplitude: Double = 0.6,
    private val riseMs: Double = 5.0
) {
    // Rosnący bufor bez opakowywania każdej próbki w obiekt (minuta audio to ~2,6 mln próbek)
    private class PcmBuffer {
        private var data = ShortArray(44100)
        var size = 0
            private set

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toShortArray(): ShortArray = data.copyOf(size)
    }

    fun render(text: String, timing: CwTiming, leadInMs: Double = 300.0): ShortArray {
        val out = PcmBuffer()
        var phase = 0.0
        val phaseStep = 2 * PI * toneHz / sampleRate

        fun silence(ms: Double) {
            repeat(samples(ms)) { out.add(0) }
        }

        fun tone(ms: Double) {
            val n = samples(ms)
            val rise = samples(riseMs).coerceAtMost(n / 2).coerceAtLeast(1)
            for (i in 0 until n) {
                // Zbocze w kształcie podniesionego kosinusa
                val envelope = when {
                    i < rise -> 0.5 - 0.5 * cos(PI * i / rise)
                    i >= n - rise -> 0.5 - 0.5 * cos(PI * (n - 1 - i) / rise)
                    else -> 1.0
                }
                out.add((sin(phase) * envelope * amplitude * Short.MAX_VALUE).roundToInt().toShort())
                phase = (phase + phaseStep) % (2 * PI)
            }
        }

        silence(leadInMs)
        val words = text.uppercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        words.forEachIndexed { wordIndex, word ->
            val chars = word.filter { Morse.CODES.containsKey(it) }
            chars.forEachIndexed { charIndex, ch ->
                val code = Morse.CODES.getValue(ch)
                code.forEachIndexed { elementIndex, element ->
                    tone(if (element == '.') timing.ditMs else timing.dahMs)
                    if (elementIndex < code.lastIndex) silence(timing.elementGapMs)
                }
                if (charIndex < chars.lastIndex) silence(timing.charGapMs)
            }
            if (wordIndex < words.lastIndex) silence(timing.wordGapMs)
        }
        silence(timing.wordGapMs)
        return out.toShortArray()
    }

    private fun samples(ms: Double) = (ms * sampleRate / 1000.0).roundToInt()
}
