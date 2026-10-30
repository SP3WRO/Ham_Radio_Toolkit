package io.github.sp3wro.hamradiotoolkit.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zespolone FFT dowolnej długości (algorytm Cooleya-Tukeya z mieszanymi podstawami, jak kiss_fft):
 * długość rozkładana na czynniki 4, 2, 3, 5, 7…, każdy etap liczony ogólnym motylkiem.
 * FT8 potrzebuje N = 3840 (2^8·3·5), czego zwykłe FFT radix-2 nie obsłuży.
 * Instancja nie jest bezpieczna wątkowo (wspólny bufor roboczy).
 */
class MixedRadixFft(val n: Int) {
    private val twRe = DoubleArray(n) { cos(-2.0 * PI * it / n) }
    private val twIm = DoubleArray(n) { sin(-2.0 * PI * it / n) }
    // Pary (p, m): podstawa etapu i iloczyn pozostałych czynników
    private val factors: IntArray
    private val scratchRe: DoubleArray
    private val scratchIm: DoubleArray

    private lateinit var inRe: DoubleArray
    private lateinit var inIm: DoubleArray
    private lateinit var outRe: DoubleArray
    private lateinit var outIm: DoubleArray

    init {
        require(n >= 1) { "Długość FFT musi być dodatnia" }
        val list = ArrayList<Int>()
        var rest = n
        var p = 4
        while (rest > 1) {
            while (rest % p != 0) {
                p = when (p) {
                    4 -> 2
                    2 -> 3
                    else -> p + 2
                }
                if (p.toLong() * p > rest) p = rest
            }
            rest /= p
            list += p
            list += rest
        }
        if (list.isEmpty()) {
            list += 1
            list += 1
        }
        factors = list.toIntArray()
        val maxP = factors.filterIndexed { i, _ -> i % 2 == 0 }.maxOrNull() ?: 1
        scratchRe = DoubleArray(maxP)
        scratchIm = DoubleArray(maxP)
    }

    /** Transformata w przód (bez normalizacji): out[k] = Σ in[j]·e^(−2πijk/N). */
    fun transform(inputRe: DoubleArray, inputIm: DoubleArray, resultRe: DoubleArray, resultIm: DoubleArray) {
        inRe = inputRe
        inIm = inputIm
        outRe = resultRe
        outIm = resultIm
        if (n == 1) {
            resultRe[0] = inputRe[0]
            resultIm[0] = inputIm[0]
            return
        }
        work(0, 0, 1, 0)
    }

    private fun work(outOffset: Int, inOffset: Int, fstride: Int, factorIndex: Int) {
        val p = factors[factorIndex]
        val m = factors[factorIndex + 1]
        if (m == 1) {
            for (q in 0 until p) {
                val src = inOffset + q * fstride
                outRe[outOffset + q] = inRe[src]
                outIm[outOffset + q] = inIm[src]
            }
        } else {
            for (q in 0 until p) {
                work(outOffset + q * m, inOffset + q * fstride, fstride * p, factorIndex + 2)
            }
        }
        butterfly(outOffset, fstride, m, p)
    }

    private fun butterfly(offset: Int, fstride: Int, m: Int, p: Int) {
        for (u in 0 until m) {
            var k = u
            for (q1 in 0 until p) {
                scratchRe[q1] = outRe[offset + k]
                scratchIm[q1] = outIm[offset + k]
                k += m
            }
            k = u
            for (q1 in 0 until p) {
                var tw = 0
                var accRe = scratchRe[0]
                var accIm = scratchIm[0]
                for (q in 1 until p) {
                    tw += fstride * k
                    if (tw >= n) tw -= n
                    val wr = twRe[tw]
                    val wi = twIm[tw]
                    accRe += scratchRe[q] * wr - scratchIm[q] * wi
                    accIm += scratchRe[q] * wi + scratchIm[q] * wr
                }
                outRe[offset + k] = accRe
                outIm[offset + k] = accIm
                k += m
            }
        }
    }
}
