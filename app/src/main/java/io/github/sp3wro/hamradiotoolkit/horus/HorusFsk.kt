package io.github.sp3wro.hamradiotoolkit.horus

import io.github.sp3wro.hamradiotoolkit.dsp.MixedRadixFft
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Demodulator 2/4-FSK – przeniesienie src/fsk.c z horusdemodlib (Brady O'Brien, David Rowe,
 * licencja LGPL-2.1, LICENSE-horusdemodlib.txt). Tylko część odbiorcza: estymacja tonów (FFT),
 * przemiana do zera, całkowanie po symbolu z P przesunięciami, synchronizacja symbolowa
 * na "magicznej linii" widma i decyzja max-z-M. Bez modulatora, diagramu oka i sond modemu.
 * Nazwy i kolejność obliczeń jak w oryginale, żeby dało się je porównywać linia po linii.
 */
internal class HorusFsk(
    val fs: Int,
    val rs: Int,
    val m: Int,
    private val p: Int = DEFAULT_P,
    val nsym: Int = DEFAULT_NSYM,
    private val fsTx: Int
) {
    val ts = fs / rs
    val n = ts * nsym
    val nbits = if (m == 2) nsym else nsym * 2
    val ndft: Int
    private val nmem = n + 2 * ts
    private val tc = 0.1f

    /** Liczba próbek do podania w następnym wywołaniu [demodulate] (zmienia się o ±Ts/2 przy korekcie taktu). */
    var nin = n
        private set
    var estMin = 0
    var estMax = fs
    private val estSpace = (0.75 * rs).toInt()
    /** 0 – szczyty widma (domyślnie), 1 – maska o znanym odstępie tonów. */
    var freqEstType = 0

    private val hannTable: FloatArray
    /** Uśrednione widmo amplitudowe (po przesunięciu: bin ndft/2 = 0 Hz). */
    val sf: FloatArray
    private val phiRe = FloatArray(m) { 1f }
    private val phiIm = FloatArray(m)
    private val fDcRe = FloatArray(m * nmem)
    private val fDcIm = FloatArray(m * nmem)
    private val fft: MixedRadixFft
    private val fftRe: DoubleArray
    private val fftIm: DoubleArray
    private val outRe: DoubleArray
    private val outIm: DoubleArray
    private val fIntRe = Array(m) { FloatArray((nsym + 1) * p) }
    private val fIntIm = Array(m) { FloatArray((nsym + 1) * p) }

    val fEst = FloatArray(m)
    val f2Est = FloatArray(m)
    var normRxTiming = 0f
        private set
    var ppm = 0f
        private set
    var ebNoDb = 0f
        private set
    /** stats->snr_est: wygładzone Eb/No w dB. */
    var snrEst = 0f
        private set

    init {
        require(fs % rs == 0 && (fs / rs) % p == 0 && (m == 2 || m == 4))
        // Biny co najwyżej 10% szybkości symbolowej
        val binWidthHz = 0.1f * rs
        ndft = 2.0.pow(ceil(ln((fs / binWidthHz).toDouble()) / ln(2.0))).toInt()
        hannTable = FloatArray(ndft) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (ndft - 1))).toFloat() }
        sf = FloatArray(ndft)
        fft = MixedRadixFft(ndft)
        fftRe = DoubleArray(ndft)
        fftIm = DoubleArray(ndft)
        outRe = DoubleArray(ndft)
        outIm = DoubleArray(ndft)
    }

    /** Estymacja częstotliwości tonów: uśrednione widmo FFT, M szczytów (fEst) i korelacja z maską (f2Est). */
    private fun freqEst(fskIn: FloatArray) {
        var st = (estMin * ndft) / fs + ndft / 2
        if (st < 0) st = 0
        var en = (estMax * ndft) / fs + ndft / 2
        if (en > ndft) en = ndft
        val fZero = (estSpace * ndft) / fs
        val work = FloatArray(ndft)

        val numffts = floor(nin.toFloat() / (ndft / 2)).toInt() - 1
        for (j in 0 until numffts) {
            val a = j * ndft / 2
            for (i in 0 until ndft) {
                fftRe[i] = (hannTable[i] * fskIn[i + a]).toDouble()
                fftIm[i] = 0.0
            }
            fft.transform(fftRe, fftIm, outRe, outIm)
            // Przesunięcie widma: bin 0 Hz na środek
            for (i in 0 until ndft) {
                val src = (i + ndft / 2) % ndft
                val mag2 = (outRe[src] * outRe[src] + outIm[src] * outIm[src]).toFloat()
                sf[i] = sf[i] * (1 - tc) + sqrt(mag2) * tc
                work[i] = sf[i]
            }
        }

        // M najsilniejszych szczytów; wokół każdego wygaszamy ±0,75·Rs
        val freqi = IntArray(m)
        for (i in 0 until m) {
            var imax = 0
            var max = 0f
            for (j in st until en) {
                if (work[j] > max) {
                    max = work[j]
                    imax = j
                }
            }
            val fMin = (imax - fZero).coerceAtLeast(0)
            val fMax = (imax + fZero).coerceAtMost(ndft)
            for (j in fMin until fMax) work[j] = 0f
            freqi[i] = imax - ndft / 2
        }
        freqi.sort()
        for (i in 0 until m) fEst[i] = freqi[i].toFloat() * (fs.toFloat() / ndft.toFloat())

        // Metoda 2: maska z jedynkami w odstępach tonów przeciągana po widmie
        val mask = FloatArray(ndft)
        for (i in 0 until 3) mask[i] = 1f
        var bin = 0
        for (k in 1 until m) {
            bin = (k.toFloat() * fsTx * ndft / fs).roundToInt() - 1
            for (i in bin..bin + 2) mask[i] = 1f
        }
        val lenMask = bin + 2 + 1
        var bMax = st
        var corrMax = 0f
        for (b in st until en - lenMask) {
            var corr = 0f
            for (i in 0 until lenMask) corr += mask[i] * sf[b + i]
            if (corr > corrMax) {
                corrMax = corr
                bMax = b
            }
        }
        val foff = (bMax - ndft / 2) * fs / ndft
        for (k in 0 until m) f2Est[k] = (foff + k * fsTx).toFloat()
    }

    /**
     * fsk_demod_core: [fskIn] musi mieć co najmniej [nin] próbek (sygnał rzeczywisty).
     * Zapisuje [nbits] bitów do [rxBits] od pozycji [offset].
     */
    fun demodulate(fskIn: FloatArray, rxBits: ByteArray, offset: Int) {
        val nold = nmem - nin
        freqEst(fskIn)
        val fEstUsed = if (freqEstType != 0) f2Est else fEst

        // Przesunięcie pamięci integratorów o nin próbek
        for (k in 0 until m) {
            System.arraycopy(fDcRe, k * nmem + nmem - nold, fDcRe, k * nmem, nold)
            System.arraycopy(fDcIm, k * nmem + nmem - nold, fDcIm, k * nmem, nold)
        }

        // Przemiana każdego tonu do zera z ciągłą fazą lokalnego oscylatora
        for (k in 0 until m) {
            val w = 2 * PI * (fEstUsed[k] / fs.toFloat())
            val dRe = cos(w).toFloat()
            val dIm = sin(w).toFloat()
            var pr = phiRe[k]
            var pi = phiIm[k]
            var j = 0
            for (i in nold until nmem) {
                val nr = pr * dRe - pi * dIm
                val ni = pr * dIm + pi * dRe
                pr = nr
                pi = ni
                // fsk_in · conj(phi); wejście rzeczywiste
                fDcRe[k * nmem + i] = fskIn[j] * pr
                fDcIm[k * nmem + i] = -fskIn[j] * pi
                j++
            }
            val mag = sqrt(pr * pr + pi * pi)
            phiRe[k] = pr / mag
            phiIm[k] = pi / mag
        }

        // Całkowanie po okresie symbolu przy P przesunięciach na symbol
        for (i in 0 until (nsym + 1) * p) {
            val st = i * ts / p
            val en = st + ts - 1
            for (k in 0 until m) {
                var re = 0f
                var im = 0f
                for (j in st..en) {
                    re += fDcRe[k * nmem + j]
                    im += fDcIm[k * nmem + j]
                }
                fIntRe[k][i] = re
                fIntIm[k][i] = im
            }
        }

        // Synchronizacja symbolowa: |f_int|² ma składową o częstotliwości Rs – jej faza to przesunięcie taktu
        val dphiftW = 2 * PI * (rs.toFloat() / (p * rs).toFloat())
        val dftRe = cos(dphiftW).toFloat()
        val dftIm = sin(dphiftW).toFloat()
        var phiFtRe = 1f
        var phiFtIm = 0f
        var tcRe = 0f
        var tcIm = 0f
        for (i in 0 until (nsym + 1) * p) {
            var ft1 = 0f
            for (k in 0 until m) ft1 += fIntRe[k][i] * fIntRe[k][i] + fIntIm[k][i] * fIntIm[k][i]
            tcRe += ft1 * phiFtRe
            tcIm += ft1 * phiFtIm
            val nr = phiFtRe * dftRe - phiFtIm * dftIm
            val ni = phiFtRe * dftIm + phiFtIm * dftRe
            phiFtRe = nr
            phiFtIm = ni
        }
        if (tcRe.isNaN() || tcIm.isNaN()) return

        val norm = (atan2(tcIm, tcRe) / (2 * PI)).toFloat()
        val rxTiming = norm * p
        val oldNorm = normRxTiming
        normRxTiming = norm
        val dNorm = norm - oldNorm
        if (kotlin.math.abs(dNorm) < .2f) {
            val appm = 1e6f * dNorm / nsym
            ppm = .9f * ppm + .1f * appm
        }
        nin = when {
            norm > 0.25f -> n + ts / 2
            norm < -0.25f -> n - ts / 2
            else -> n
        }

        // Interpolacja liniowa integratorów w chwili optymalnej i decyzja
        val lowSample = floor(rxTiming).toInt()
        val fract = rxTiming - lowSample
        val highSample = ceil(rxTiming).toInt()
        var meanEbNo = 0f
        var stdEbNo = 0f
        val tmax = FloatArray(m)
        for (i in 0 until nsym) {
            val st = (i + 1) * p
            for (k in 0 until m) {
                val re = (1 - fract) * fIntRe[k][st + lowSample] + fract * fIntRe[k][st + highSample]
                val im = (1 - fract) * fIntIm[k][st + lowSample] + fract * fIntIm[k][st + highSample]
                tmax[k] = re * re + im * im
            }
            var max = tmax[0]
            var sym = 0
            for (k in 0 until m) {
                if (tmax[k] > max) {
                    max = tmax[k]
                    sym = k
                }
            }
            if (m == 2) {
                rxBits[offset + i] = (if (sym == 1) 1 else 0).toByte()
            } else {
                rxBits[offset + i * 2 + 1] = (sym and 0x1).toByte()
                rxBits[offset + i * 2] = ((sym and 0x2) shr 1).toByte()
            }
            stdEbNo += max
            meanEbNo += sqrt(max)
        }
        meanEbNo /= nsym
        stdEbNo = stdEbNo / nsym - meanEbNo * meanEbNo
        stdEbNo = if (stdEbNo > 0f) sqrt(stdEbNo) else 0f
        ebNoDb = -6 + 20 * log10((1e-6f + meanEbNo) / (1e-6f + stdEbNo))
        snrEst = .5f * snrEst + .5f * ebNoDb
    }

    companion object {
        const val DEFAULT_P = 8
        const val DEFAULT_NSYM = 50
    }
}
