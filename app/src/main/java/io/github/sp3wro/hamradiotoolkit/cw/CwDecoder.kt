package io.github.sp3wro.hamradiotoolkit.cw

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dekoder CW: filtr Goertzla na częstotliwości tonu (bloki ~8 ms) -> obwiednia -> adaptacyjny próg
 * z histerezą -> czasy znaczników i przerw -> kropki/kreski z samouczącą się długością kropki -> tekst.
 * Bez zależności od Androida (testowany na sygnale z [CwGenerator]).
 */
class CwDecoder(
    private val sampleRate: Int = 44100,
    toneHz: Double = 600.0,
    initialWpm: Int = 20,
    private val onText: (String) -> Unit = {}
) {
    private val blockSize = (sampleRate * 0.008).roundToInt()
    private val blockMs = blockSize * 1000.0 / sampleRate
    private var coeff = 0.0

    @Volatile var toneHz: Double = toneHz
        set(value) {
            field = value
            coeff = 2 * cos(2 * PI * value / sampleRate)
        }

    var autoTone: Boolean = true

    // Dwie grupy długości znaczników (kropki i kreski) uczone z odbieranego sygnału;
    // próg między nimi to średnia geometryczna, więc dekoder dostraja się do każdej prędkości
    private var shortMs: Double = 1200.0 / initialWpm
    private var longMs: Double = 3 * shortMs
    private var marksSeen = 0

    val ditMs: Double get() = (shortMs + longMs / 3) / 2
    val wpm: Int get() = (1200.0 / ditMs).roundToInt()

    /** 0..1 – siła sygnału względem progu, do wskaźnika na ekranie. */
    var signalLevel: Float = 0f
        private set

    private val block = FloatArray(blockSize)
    private var blockFill = 0

    private var noise = 1e-6
    private var peak = 1e-6
    private var keyDown = false
    private var stateMs = 0.0
    private var symbol = StringBuilder()
    private var charFlushed = true
    private var wordFlushed = true

    // Okno do wyszukiwania tonu (autoTone)
    private val searchWindow = FloatArray(4096)
    private var searchFill = 0
    private var searchCountdownMs = 0.0

    init {
        this.toneHz = toneHz
    }

    fun process(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            val s = samples[i]
            block[blockFill++] = s
            if (autoTone) {
                searchWindow[searchFill] = s
                searchFill = (searchFill + 1) % searchWindow.size
            }
            if (blockFill == blockSize) {
                processBlock()
                blockFill = 0
            }
        }
    }

    fun process(samples: ShortArray, count: Int = samples.size) {
        val floats = FloatArray(count) { samples[it] / 32768f }
        process(floats, count)
    }

    private fun processBlock() {
        val magnitude = goertzel(block, blockSize, coeff)

        // Szum: wolno rosnie, szybko opada; szczyt: szybko rośnie, wolno opada
        noise = if (magnitude < noise) noise * 0.7 + magnitude * 0.3 else noise * 0.995 + magnitude * 0.005
        peak = if (magnitude > peak) peak * 0.5 + magnitude * 0.5 else peak * 0.998 + noise * 0.002
        val snrOk = peak > noise * 4
        val high = noise + (peak - noise) * 0.55
        val low = noise + (peak - noise) * 0.35
        signalLevel = if (peak > noise) ((magnitude - noise) / (peak - noise)).toFloat().coerceIn(0f, 1f) else 0f

        val nowDown = if (keyDown) snrOk && magnitude > low else snrOk && magnitude > high
        if (nowDown != keyDown) {
            if (keyDown) endMark(stateMs) else checkSilence(stateMs)
            keyDown = nowDown
            stateMs = 0.0
        }
        stateMs += blockMs
        if (!keyDown) checkSilence(stateMs)

        if (autoTone) {
            searchCountdownMs -= blockMs
            if (searchCountdownMs <= 0) {
                searchCountdownMs = 1000.0
                detectTone()
            }
        }
    }

    private fun endMark(durationMs: Double) {
        if (durationMs < blockMs * 1.5) return // zakłócenie krótsze niż dwa bloki
        // Na początku uczymy się szybko, potem stabilnie
        val rate = if (marksSeen < 8) 0.5 else 0.2
        marksSeen++
        if (durationMs < sqrt(shortMs * longMs)) {
            symbol.append('.')
            shortMs += (durationMs - shortMs) * rate
            // Kreska powinna trwać ~3 kropki – podciągamy ją, jeśli dawno nie było kreski
            longMs += (3 * shortMs - longMs) * rate * 0.3
        } else {
            symbol.append('-')
            longMs += (durationMs - longMs) * rate
            shortMs += (longMs / 3 - shortMs) * rate * 0.3
        }
        shortMs = shortMs.coerceIn(1200.0 / 60, 1200.0 / 5) // 5–60 WPM
        longMs = longMs.coerceIn(2 * shortMs, 4.5 * shortMs)
        charFlushed = false
        wordFlushed = false
    }

    // Przerwa > 2 kropek kończy znak, > 5 kropek – słowo (wysyłane od razu, bez czekania na kolejny znacznik)
    private fun checkSilence(durationMs: Double) {
        if (!charFlushed && durationMs > 2 * ditMs) {
            val code = symbol.toString()
            symbol = StringBuilder()
            charFlushed = true
            if (code.isNotEmpty()) onText((Morse.DECODE[code] ?: '*').toString())
        }
        if (charFlushed && !wordFlushed && durationMs > 5 * ditMs) {
            wordFlushed = true
            onText(" ")
        }
    }

    private fun detectTone() {
        var bestHz = toneHz
        var best = 0.0
        var sum = 0.0
        var count = 0
        var hz = 300.0
        while (hz <= 1200.0) {
            val c = 2 * cos(2 * PI * hz / sampleRate)
            val m = goertzel(searchWindow, searchWindow.size, c)
            sum += m
            count++
            if (m > best) {
                best = m
                bestHz = hz
            }
            hz += 10.0
        }
        // Zmiana tonu tylko przy wyraźnym prążku (inaczej to szum)
        if (count > 0 && best > 6 * (sum / count) && kotlin.math.abs(bestHz - toneHz) > 15) {
            toneHz = bestHz
        }
    }

    fun reset() {
        symbol = StringBuilder()
        keyDown = false
        stateMs = 0.0
        charFlushed = true
        wordFlushed = true
        noise = 1e-6
        peak = 1e-6
    }

    private fun goertzel(data: FloatArray, n: Int, c: Double): Double {
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = data[i] + c * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - c * s1 * s2
        return sqrt(max(power, 0.0)) / n
    }
}
