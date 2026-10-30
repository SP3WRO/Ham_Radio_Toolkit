package io.github.sp3wro.hamradiotoolkit.rtty

import io.github.sp3wro.hamradiotoolkit.dsp.MixedRadixFft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Kod Baudot (ITA2, wariant US-TTY używany w krótkofalarskim RTTY). Indeks = 5 bitów, pierwszy bit to LSB. */
object Baudot {
    const val LTRS = 0x1F
    const val FIGS = 0x1B

    private const val L = "\u0000E\nA SIU\rDRJNFCKTZLWHYPQOBG\u0000MXV\u0000"
    private const val F = "\u00003\n- \u000787\r\$4',!:(5\")2#6019?&\u0000./;\u0000"

    fun letter(code: Int): Char = L[code]
    fun figure(code: Int): Char = F[code]

    /** Kod znaku i czy wymaga trybu cyfr; null dla znaków spoza alfabetu. */
    fun encode(c: Char): Pair<Int, Boolean>? {
        val upper = c.uppercaseChar()
        val l = L.indexOf(upper)
        if (l > 0 && upper != '\u0000') return l to false
        val f = F.indexOf(upper)
        if (f > 0 && upper != '\u0000') return f to true
        return null
    }
}

/** Cztery sumy kroczące (I/Q dla mark i space) o długości [n] próbek – filtr dopasowany do bitu. */
private class IqBoxcar(val n: Int) {
    private val bufs = Array(4) { FloatArray(n) }
    /** I, Q tonu wyższego; I, Q tonu niższego. */
    val sums = DoubleArray(4)
    private var pos = 0

    fun push(a: Float, b: Float, c: Float, d: Float) {
        sums[0] += a - bufs[0][pos]; bufs[0][pos] = a
        sums[1] += b - bufs[1][pos]; bufs[1][pos] = b
        sums[2] += c - bufs[2][pos]; bufs[2][pos] = c
        sums[3] += d - bufs[3][pos]; bufs[3][pos] = d
        pos = (pos + 1) % n
    }

    /** Dyskryminator (wyższy − niższy)/(wyższy + niższy), −1..1. */
    fun discriminator(): Float {
        val hi = sqrt(sums[0] * sums[0] + sums[1] * sums[1])
        val lo = sqrt(sums[2] * sums[2] + sums[3] * sums[3])
        return ((hi - lo) / (hi + lo + 1e-9)).toFloat()
    }
}

/**
 * Demodulator RTTY: filtry dopasowane (mieszanie I/Q + średnia krocząca o długości bitu) dla tonu wyższego
 * i niższego, dyskryminator, synchronizacja na zbocze bitu startu.
 *
 * Automatyka (każdą część można wyłączyć i ustawić ręcznie):
 *  - polaryzacja: dwa odbiorniki znaków pracują równolegle na d i −d; wygrywa ten z mniejszą liczbą błędów ramki,
 *  - szybkość: długości odcinków mark/space z szybkiego dyskryminatora → [estimateBaud].
 * Strojenie i przesuw ustawia z zewnątrz [RttyEngine] na podstawie widma.
 */
class RttyDecoder(
    private val sampleRate: Int = 12000,
    private val onText: (String) -> Unit
) {
    @Volatile var centerHz: Double = 1500.0
    @Volatile var shiftHz: Double = 170.0
    @Volatile var baud: Double = 45.45
    /** Ręczna polaryzacja: false – mark to wyższy ton (USB), true – mark niższy (LSB / tradycyjne AFSK). */
    @Volatile var reverse: Boolean = false
    @Volatile var autoPolarity: Boolean = true
    @Volatile var autoBaud: Boolean = true
    /** Unshift-on-space: po spacji powrót do liter (standard w krótkofalarskim RTTY). */
    @Volatile var unshiftOnSpace: Boolean = true
    /** Minimalna jakość znaku (średnie |d| w punktach próbkowania), poniżej której znak jest odrzucany. */
    @Volatile var squelch: Float = 0.45f

    /** Jakość ostatnich znaków 0..1 (do wskaźnika na ekranie). */
    @Volatile var quality: Float = 0f
        private set
    /** Polaryzacja faktycznie używana (przy automatyce – wybrana przez dekoder). */
    @Volatile var activeReverse: Boolean = false
        private set

    private var configuredBaud = 0.0
    private var configuredShift = 0.0
    private var bitSamples = 0.0
    private lateinit var slow: IqBoxcar
    private lateinit var fast: IqBoxcar

    private var hiPhase = 0.0
    private var loPhase = 0.0
    private var sampleIndex = 0L

    private val normal = Uart(inverted = false)
    private val inverted = Uart(inverted = true)
    private var figures = false

    // Zbieranie długości odcinków do wykrywania szybkości
    private val runs = IntArray(400)
    private var runCount = 0
    private var runPos = 0
    private var runState = 1
    private var runStart = 0L
    private var sinceEstimate = 0
    private var lastEstimate: Double? = null
    private var estimateRepeats = 0

    // Precyzyjne AFC: obrót fazy wyjścia filtra tonu w czasie [afcLag] próbek, gdy ten ton trwa
    private val afcHistory = Array(5) { FloatArray(AFC_RING) } // hiI, hiQ, loI, loQ, d
    private var afcPos = 0
    private var afcLag = 16
    private var hiRe = 0.0
    private var hiIm = 0.0
    private var loRe = 0.0
    private var loIm = 0.0
    private var hiCount = 0
    private var loCount = 0
    // Suma |z(t)|·|z(t − lag)| – do oceny spójności fazy (szum daje przypadkowe kierunki)
    private var hiMag = 0.0
    private var loMag = 0.0

    /** Błąd strojenia zmierzony z fazy: środek i przesuw (Hz, wartość dodatnia = sygnał wyżej / szerzej). */
    data class FrequencyError(val centerHz: Double, val shiftHz: Double)

    /**
     * Pomiar z ostatniego okresu (zeruje akumulatory) albo null, gdy za mało danych z obu tonów
     * albo faza nie jest spójna – wtedy filtry nie stoją na sygnale i pomiar byłby przypadkowy.
     */
    @Synchronized
    fun takeFrequencyError(): FrequencyError? {
        val minCount = (2 * bitSamples).toInt()
        val coherentHi = hiMag > 0 && sqrt(hiRe * hiRe + hiIm * hiIm) / hiMag > MIN_COHERENCE
        val coherentLo = loMag > 0 && sqrt(loRe * loRe + loIm * loIm) / loMag > MIN_COHERENCE
        val result = if (hiCount >= minCount && loCount >= minCount && coherentHi && coherentLo) {
            // Ton o częstotliwości f0 + δ daje na wyjściu I + jQ ≈ e^(−jδt) – stąd minus
            val k = sampleRate / (2 * PI * afcLag)
            val dHi = -kotlin.math.atan2(hiIm, hiRe) * k
            val dLo = -kotlin.math.atan2(loIm, loRe) * k
            FrequencyError((dHi + dLo) / 2, dHi - dLo)
        } else null
        hiRe = 0.0; hiIm = 0.0; loRe = 0.0; loIm = 0.0
        hiMag = 0.0; loMag = 0.0
        hiCount = 0; loCount = 0
        return result
    }

    fun reset() {
        configuredBaud = 0.0
        figures = false
        quality = 0f
        normal.reset()
        inverted.reset()
        clearRuns()
    }

    private fun clearRuns() {
        runCount = 0
        runPos = 0
        lastEstimate = null
        estimateRepeats = 0
    }

    private fun configure() {
        val b = baud
        val s = shiftHz
        if (b == configuredBaud && s == configuredShift) return
        if (b != configuredBaud) {
            bitSamples = sampleRate / b
            slow = IqBoxcar(bitSamples.roundToInt())
            // Ćwierć bitu: jednoznaczność do ±fs/(2·lag) ≈ ±90 Hz przy 45 Bd
            afcLag = (bitSamples / 4).roundToInt().coerceIn(4, AFC_RING - 1)
            normal.reset()
            inverted.reset()
        }
        if (s != configuredShift) {
            // Długość 1/przesuw zeruje odpowiedź filtra na drugim tonie
            fast = IqBoxcar((sampleRate / s).roundToInt().coerceAtLeast(4))
            clearRuns()
        }
        configuredBaud = b
        configuredShift = s
    }

    fun process(samples: FloatArray, count: Int) {
        configure()
        val hiStep = 2 * PI * (centerHz + shiftHz / 2) / sampleRate
        val loStep = 2 * PI * (centerHz - shiftHz / 2) / sampleRate

        for (i in 0 until count) {
            val x = samples[i]
            hiPhase += hiStep
            loPhase += loStep
            if (hiPhase > 2 * PI) hiPhase -= 2 * PI
            if (loPhase > 2 * PI) loPhase -= 2 * PI
            val hi = (x * cos(hiPhase)).toFloat()
            val hq = (x * sin(hiPhase)).toFloat()
            val li = (x * cos(loPhase)).toFloat()
            val lq = (x * sin(loPhase)).toFloat()
            slow.push(hi, hq, li, lq)
            fast.push(hi, hq, li, lq)

            val d = slow.discriminator()
            trackPhase(d)
            val useInverted = if (autoPolarity) activeReverse else reverse
            val codeNormal = normal.step(d)
            val codeInverted = inverted.step(d)
            val code = if (useInverted) codeInverted else codeNormal
            if (code >= 0) emit(code)

            collectRun(fast.discriminator())
            sampleIndex++
        }

        if (autoPolarity) choosePolarity() else activeReverse = reverse

        sinceEstimate += count
        if (autoBaud && sinceEstimate >= sampleRate / 2) {
            sinceEstimate = 0
            updateBaud()
        }
    }

    private fun trackPhase(d: Float) {
        val s = slow.sums
        val past = (afcPos - afcLag + AFC_RING) % AFC_RING
        val pd = afcHistory[4][past]
        // Tylko gdy ten sam ton trwa w obu chwilach (środek długich odcinków), z wagą amplitudy
        if (d > 0.6f && pd > 0.6f) {
            val pi = afcHistory[0][past]
            val pq = afcHistory[1][past]
            synchronized(this) {
                hiRe += s[0] * pi + s[1] * pq
                hiIm += s[1] * pi - s[0] * pq
                hiMag += sqrt(s[0] * s[0] + s[1] * s[1]) * sqrt((pi * pi + pq * pq).toDouble())
                hiCount++
            }
        } else if (d < -0.6f && pd < -0.6f) {
            val pi = afcHistory[2][past]
            val pq = afcHistory[3][past]
            synchronized(this) {
                loRe += s[2] * pi + s[3] * pq
                loIm += s[3] * pi - s[2] * pq
                loMag += sqrt(s[2] * s[2] + s[3] * s[3]) * sqrt((pi * pi + pq * pq).toDouble())
                loCount++
            }
        }
        afcHistory[0][afcPos] = s[0].toFloat()
        afcHistory[1][afcPos] = s[1].toFloat()
        afcHistory[2][afcPos] = s[2].toFloat()
        afcHistory[3][afcPos] = s[3].toFloat()
        afcHistory[4][afcPos] = d
        afcPos = (afcPos + 1) % AFC_RING
    }

    private fun choosePolarity() {
        val active = if (activeReverse) inverted else normal
        val other = if (activeReverse) normal else inverted
        if (other.frames >= 8 && other.goodRate > active.goodRate + 0.25f) {
            activeReverse = !activeReverse
            figures = false
        }
    }

    private fun collectRun(df: Float) {
        // Histereza ±0,3 – krótkie zakłócenia nie dzielą odcinka
        val flip = (runState > 0 && df < -0.3f) || (runState < 0 && df > 0.3f)
        if (!flip) return
        runs[runPos] = (sampleIndex - runStart).toInt()
        runPos = (runPos + 1) % runs.size
        if (runCount < runs.size) runCount++
        runState = -runState
        runStart = sampleIndex
    }

    private fun updateBaud() {
        if (runCount < 40) return
        val estimate = estimateBaud(runs.copyOf(runCount), sampleRate) ?: return
        estimateRepeats = if (estimate == lastEstimate) estimateRepeats + 1 else 1
        lastEstimate = estimate
        // Zmiana dopiero po dwóch zgodnych oszacowaniach z rzędu
        if (estimateRepeats >= 2 && estimate != baud) baud = estimate
    }

    private fun emit(code: Int) {
        when (code) {
            Baudot.LTRS -> { figures = false; return }
            Baudot.FIGS -> { figures = true; return }
        }
        val c = if (figures) Baudot.figure(code) else Baudot.letter(code)
        if (c == ' ' && unshiftOnSpace) figures = false
        when (c) {
            '\u0000', '\u0007', '\r' -> return
            else -> onText(c.toString())
        }
    }

    /** Odbiornik znaków start-stop (1 start, 5 danych od LSB, stop) na wyjściu filtra o długości bitu. */
    private inner class Uart(private val inverted: Boolean) {
        private var prevD = 0f
        private var inChar = false
        private var charStart = 0.0
        private var nextBit = 0
        private var bits = 0
        private var qualitySum = 0f
        var goodRate = 0.5f
            private set
        var frames = 0
            private set

        fun reset() {
            inChar = false
            goodRate = 0.5f
            frames = 0
        }

        // Filtr o długości bitu: odpowiedź na zbocze to rampa przecinająca zero pół bitu po zboczu,
        // a pełny bit i jest "widoczny" na wyjściu w chwili (przecięcie + (i + 0,5) bitu).
        // Zwraca kod znaku albo −1.
        fun step(raw: Float): Int {
            val d = if (inverted) -raw else raw
            val prev = prevD
            prevD = d
            if (!inChar) {
                if (prev >= 0f && d < 0f) {
                    inChar = true
                    charStart = sampleIndex.toDouble()
                    nextBit = 0
                    bits = 0
                    qualitySum = 0f
                }
                return -1
            }
            if (sampleIndex < charStart + (nextBit + 0.5) * bitSamples) return -1

            qualitySum += abs(d)
            when (nextBit) {
                0 -> if (d >= 0f) { // fałszywy start
                    inChar = false
                    return -1
                }
                in 1..5 -> if (d > 0f) bits = bits or (1 shl (nextBit - 1))
                6 -> {
                    inChar = false
                    val stopOk = d > 0f // bit stopu = mark
                    frames++
                    goodRate = goodRate * 0.9f + (if (stopOk) 0.1f else 0f)
                    val q = qualitySum / 7f
                    val usesThis = (if (autoPolarity) activeReverse else reverse) == inverted
                    if (usesThis) quality = quality * 0.8f + q * 0.2f
                    return if (stopOk && q >= squelch) bits else -1
                }
            }
            nextBit++
            return -1
        }
    }

    companion object {
        private const val AFC_RING = 128
        private const val MIN_COHERENCE = 0.7
        val STANDARD_BAUDS = listOf(45.45, 50.0, 75.0, 100.0)

        /**
         * Szybkość z długości odcinków (w próbkach): odcinki RTTY to wielokrotności bitu (i n + 0,5 przy bicie
         * stopu 1,5). Dla każdej standardowej szybkości liczymy, jak dobrze odcinki pasują do jej siatki;
         * wymagamy też odcinków jednobitowych, żeby nie wybrać dwukrotnie wolniejszej podwielokrotności.
         */
        fun estimateBaud(runs: IntArray, sampleRate: Int): Double? {
            var best: Double? = null
            var bestScore = 0.0
            for (b in STANDARD_BAUDS) {
                val bit = sampleRate / b
                var considered = 0
                var score = 0.0
                var ones = 0
                for (r in runs) {
                    val ratio = r / bit
                    if (ratio < 0.6 || ratio > 8.5) continue
                    considered++
                    val nearest = (Math.round(ratio * 2) / 2.0).coerceAtLeast(1.0)
                    val dev = (ratio - nearest) / 0.12
                    score += exp(-dev * dev)
                    if (ratio in 0.8..1.25) ones++
                }
                if (considered < 30) continue
                var s = score / considered
                if (ones < considered * 0.08) s *= 0.5
                if (s > bestScore) {
                    bestScore = s
                    best = b
                }
            }
            return if (bestScore >= 0.6) best else null
        }
    }
}

/** Para tonów RTTY znaleziona w widmie; [score] – siła słabszego tonu nad szumem (dB) z premią za typowy przesuw. */
data class RttySignal(val centerHz: Double, val shiftHz: Double, val score: Double = 0.0)

/**
 * Uśrednione widmo 0–3 kHz do strojenia (FFT 2048 punktów, okno Hanna, ok. 5,9 Hz na bin).
 * Wołane z wątku audio; [spectrum] zwraca kopię wartości w dB.
 */
class TuningSpectrum(private val sampleRate: Int = 12000, private val size: Int = 2048) {
    private val fft = MixedRadixFft(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    private val ring = FloatArray(size)
    private var ringPos = 0
    private var sinceLast = 0
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val outRe = DoubleArray(size)
    private val outIm = DoubleArray(size)
    val binHz = sampleRate.toDouble() / size
    val bins = (MAX_HZ / binHz).toInt()
    private val avgDb = FloatArray(bins) { -100f }

    @Synchronized
    fun spectrum(): FloatArray = avgDb.copyOf()

    fun process(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            ring[ringPos] = samples[i]
            ringPos = (ringPos + 1) % size
        }
        sinceLast += count
        if (sinceLast < size / 2) return
        sinceLast = 0
        for (i in 0 until size) {
            re[i] = ring[(ringPos + i) % size] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im, outRe, outIm)
        synchronized(this) {
            for (b in 0 until bins) {
                val p = outRe[b] * outRe[b] + outIm[b] * outIm[b]
                val db = (10 * log10(p + 1e-12)).toFloat()
                // Szybkie uśrednianie (stała ok. 0,15 s) – strojenie ma reagować w ułamku sekundy
                avgDb[b] = if (avgDb[b] <= -99f) db else avgDb[b] * 0.5f + db * 0.5f
            }
        }
    }

    /** Najsilniejsza para tonów RTTY (opcjonalnie tylko w promieniu 150 Hz od [nearHz]) albo null. */
    fun findRttySignal(nearHz: Double? = null, fixedShift: Double? = null): RttySignal? =
        findPairs(fixedShift).filter { nearHz == null || abs(it.centerHz - nearHz) <= 150 }.maxByOrNull { it.score }

    /**
     * Wszystkie pary szczytów widma wyglądające jak RTTY:
     *  - oba tony ≥ 9 dB nad szumem (mediana widma), w paśmie 250–2950 Hz (poniżej siedzi przydźwięk sieci),
     *  - odstęp 80–1000 Hz albo równy przesuwowi ustawionemu ręcznie ([fixedShift]),
     *  - między tonami brak prążka tej samej siły – to odrzuca grzebień harmonicznych 50 Hz.
     * Wynik: siła słabszego tonu nad szumem, kara za nierówne tony, premia za typowy przesuw.
     */
    fun findPairs(fixedShift: Double? = null): List<RttySignal> {
        val spec = spectrum()
        if (spec.all { it <= -99f }) return emptyList()
        val sorted = spec.sorted()
        val noiseDb = sorted[sorted.size / 2]

        // Lokalne maksima (±2 biny) z interpolacją paraboliczną
        val peaks = ArrayList<Pair<Double, Float>>() // (Hz, dB)
        val first = (250 / binHz).toInt().coerceAtLeast(2)
        val last = minOf(spec.size - 3, (2950 / binHz).toInt())
        for (b in first..last) {
            val v = spec[b]
            if (v < noiseDb + 9f) continue
            if (v < spec[b - 1] || v < spec[b + 1] || v < spec[b - 2] || v < spec[b + 2]) continue
            val denom = spec[b - 1] - 2 * v + spec[b + 1]
            val offset = if (denom != 0f) (0.5f * (spec[b - 1] - spec[b + 1]) / denom).coerceIn(-0.5f, 0.5f) else 0f
            peaks += (b + offset) * binHz to v
        }

        val result = ArrayList<RttySignal>()
        for (i in peaks.indices) {
            for (j in i + 1 until peaks.size) {
                val (fa, da) = peaks[i]
                val (fb, db) = peaks[j]
                val sep = fb - fa
                if (fixedShift != null) {
                    if (abs(sep - fixedShift) > maxOf(15.0, 0.08 * fixedShift)) continue
                } else if (sep < 80 || sep > 1000) continue
                val weaker = minOf(da, db)
                // Tony RTTY mają podobną siłę – nośna sklejona z harmoniczną przydźwięku to nie para
                if (abs(da - db) > 12f) continue
                // Prążek między tonami prawie tak silny jak one – to grzebień, nie para RTTY
                if ((i + 1 until j).any { peaks[it].second > weaker - 6f }) continue
                val shift = fixedShift ?: snapShift(sep)
                val standard = STANDARD_SHIFTS.any { abs(sep - it) <= it * 0.2 }
                val score = (weaker - noiseDb) - abs(da - db) * 0.5 + if (standard) 3.0 else 0.0
                result += RttySignal((fa + fb) / 2, shift, score)
            }
        }
        return result
    }

    companion object {
        const val MAX_HZ = 3000.0
        val STANDARD_SHIFTS = listOf(170.0, 200.0, 425.0, 450.0, 850.0)

        /**
         * Najbliższy standardowy przesuw (do 20 % odchyłki – szczyty widma FSK potrafią być przesunięte
         * do środka) albo zaokrąglenie do 5 Hz.
         */
        fun snapShift(measured: Double): Double =
            STANDARD_SHIFTS.filter { abs(measured - it) <= it * 0.2 }.minByOrNull { abs(measured - it) }
                ?: (measured / 5).roundToInt() * 5.0
    }
}

/**
 * Kompletny tor odbioru RTTY bez zależności od Androida: widmo + automatyczne strojenie (AFC) i przesuw
 * + dekoder z automatyczną szybkością i polaryzacją. Wywoływany z wątku audio.
 */
class RttyEngine(private val sampleRate: Int = 12000, onText: (String) -> Unit) {
    val decoder = RttyDecoder(sampleRate, onText)
    val spectrum = TuningSpectrum(sampleRate)

    @Volatile var autoFrequency = true
    @Volatile var autoShift = true

    /** Czy linie stoją na parze tonów widocznej w widmie. */
    @Volatile var locked = false
        private set

    private var sinceTune = 0
    private var pendingShift: Double? = null
    private var pendingCenter: Double? = null
    // Stacja wskazana dotknięciem widma – przez [TARGET_CYCLES] cykli wybieramy tylko pary w jej pobliżu
    @Volatile private var userTargetHz: Double? = null
    @Volatile private var userTargetCycles = 0
    // Stacja wybrana przez użytkownika – nie przeskakujemy z niej na silniejszą, dopóki nadaje
    private var userChosen = false
    private var cyclesWithoutPair = 0
    private var sinceFine = 1000 // cykle strojenia (0,25 s) od ostatniego spójnego pomiaru fazy

    fun process(samples: FloatArray, count: Int) {
        spectrum.process(samples, count)
        decoder.process(samples, count)
        sinceTune += count
        if (sinceTune >= sampleRate / 4) {
            sinceTune = 0
            autoTune()
        }
    }

    /**
     * Ręczne ustawienie środka (dotknięcie widma, ±5 Hz). Przy włączonym AFC to wskazanie stacji:
     * przez ok. 10 s AFC wybiera tylko pary w pobliżu tego miejsca (także słabsze od innych w paśmie).
     */
    fun setCenter(hz: Double) {
        val clamped = hz.coerceIn(200.0, 2800.0)
        decoder.centerHz = clamped
        userTargetHz = clamped
        userTargetCycles = TARGET_CYCLES
        userChosen = true
        pendingCenter = null
    }

    private fun autoTune() {
        // Dokładny pomiar z fazy (null, gdy filtry nie stoją na sygnale)
        val fine = decoder.takeFrequencyError()
        sinceFine = if (fine != null) 0 else sinceFine + 1
        if (!autoFrequency && !autoShift) {
            locked = false
            return
        }

        val pairs = spectrum.findPairs(if (autoShift) null else decoder.shiftHz)
        val center = decoder.centerHz
        // Para, na której stoimy teraz
        val current = pairs.filter { abs(it.centerHz - center) <= 30 }.maxByOrNull { it.score }
        locked = current != null
        // Wybrana stacja zniknęła na ponad 3 s – znowu wolno wybierać najsilniejszą w paśmie
        cyclesWithoutPair = if (current != null) 0 else cyclesWithoutPair + 1
        if (cyclesWithoutPair > 12 && userTargetCycles == 0) userChosen = false

        if (autoFrequency) {
            val target = userTargetHz?.takeIf { userTargetCycles > 0 }
            if (userTargetCycles > 0) userTargetCycles--
            val candidates = if (target != null) pairs.filter { abs(it.centerHz - target) <= 150 } else pairs
            val best = candidates.maxByOrNull { it.score }
            // Skok, gdy nie stoimy na żadnej parze, gdy użytkownik wskazał stację, albo gdy gdzie indziej
            // jest wyraźnie (o 6 dB) silniejsza – chyba że obecną wybrał użytkownik
            val shouldJump = best != null && abs(best.centerHz - center) > 30 &&
                (current == null || target != null || (!userChosen && best.score > current.score + 6))
            if (shouldJump) {
                val previous = pendingCenter
                if (previous != null && abs(previous - best!!.centerHz) < 20) {
                    decoder.centerHz = best.centerHz
                    if (autoShift) decoder.shiftHz = best.shiftHz
                    pendingCenter = null
                    pendingShift = null
                    return
                }
                pendingCenter = best!!.centerHz
            } else {
                pendingCenter = null
            }

            if (current != null) {
                val diff = current.centerHz - center
                if (fine != null) {
                    // Precyzyjnie z fazy (spójny pomiar = filtry stoją na sygnale), ograniczony krok
                    decoder.centerHz += (fine.centerHz * 0.7).coerceIn(-8.0, 8.0)
                } else if (abs(diff) >= 20 || (sinceFine > 8 && abs(diff) >= 3)) {
                    // Szczyty widma bywają przesunięte o kilka Hz – widmo tylko dociąga z daleka
                    // albo gdy faza milczy od ~2 s; drobne poprawki zostawiamy fazie
                    decoder.centerHz += diff * 0.5
                }
            }
        }

        if (autoShift && current != null) {
            if (abs(current.shiftHz - decoder.shiftHz) > decoder.shiftHz * 0.2) {
                // Widmo pokazuje zupełnie inny przesuw (np. 450 zamiast 170)
                proposeShift(current.shiftHz)
            } else if (fine != null) {
                proposeShift(TuningSpectrum.snapShift(decoder.shiftHz + fine.shiftHz))
            }
        }
    }

    // Zmiana przesuwu dopiero po dwóch zgodnych propozycjach z rzędu
    private fun proposeShift(shift: Double) {
        if (abs(shift - decoder.shiftHz) <= 3) {
            pendingShift = null
            return
        }
        if (pendingShift == shift) {
            decoder.shiftHz = shift
            pendingShift = null
        } else pendingShift = shift
    }

    private companion object {
        const val TARGET_CYCLES = 40 // 10 s po 0,25 s
    }
}
