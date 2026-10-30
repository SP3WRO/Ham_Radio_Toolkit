package io.github.sp3wro.hamradiotoolkit.ft8

import io.github.sp3wro.hamradiotoolkit.dsp.MixedRadixFft
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Dekoder FT8 – przeniesienie biblioteki ft8_lib (Kārlis Goba, licencja MIT, LICENSE-ft8_lib.txt):
 *   common/monitor.c – STFT sygnału do "wodospadu" (moduły w dB, 0,5 dB na krok, uint8),
 *   ft8/decode.c     – wyszukiwanie kandydatów po synchronizacji Costas i wiarygodności bitów,
 *   ft8/ldpc.c       – dekoder LDPC(174,91) bp_decode,
 *   ft8/crc.c        – CRC-14.
 * Parametry jak w demo/decode_ft8.c. Nazwy i kolejność obliczeń trzymane blisko oryginału,
 * żeby dało się je porównywać linia po linii. FT4 – te same pliki ft8_lib (gałęzie FTX_PROTOCOL_FT4).
 */

/** Emisja: FT8 (8-FSK, 15 s) albo FT4 (4-FSK, 7,5 s) – ten sam kod LDPC(174,91) i ta sama 77-bitowa treść. */
enum class FtxMode(val label: String, val symbolPeriod: Float, val slotTime: Float) {
    FT8("FT8", 0.160f, 15.0f),
    FT4("FT4", 0.048f, 7.5f);

    val slotMs: Long get() = (slotTime * 1000).toLong()
    internal val numTones: Int get() = if (this == FT4) 4 else 8
}

internal object Ft4Protocol {
    const val ND = 87          // symbole danych (2 bity na symbol)
    const val NN = 105         // wszystkie symbole: R S D29 S D29 S D29 S R
    const val LENGTH_SYNC = 4
    const val NUM_SYNC = 4
    const val SYNC_OFFSET = 33

    val COSTAS = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(1, 0, 2, 3), intArrayOf(2, 3, 1, 0), intArrayOf(3, 2, 0, 1))
    val GRAY_MAP = intArrayOf(0, 1, 3, 2)
    // "Tylko w FT4 treść jest XOR-owana z ciągiem pseudolosowym, żeby CQ nie było długim ciągiem zer"
    val XOR_SEQUENCE = intArrayOf(0x4A, 0x5E, 0x89, 0xB4, 0xB0, 0x8A, 0x79, 0x55, 0xBE, 0x28)

    /** Pozycja k-tego symbolu danych: pomija rampę i 1, 2 albo 3 grupy synchronizacji. */
    fun dataSymbolIndex(k: Int): Int = k + if (k < 29) 5 else if (k < 58) 9 else 13
}

internal object Ft8Protocol {
    const val SYMBOL_PERIOD = 0.160f
    const val SLOT_TIME = 15.0f

    const val ND = 58          // symbole danych
    const val NN = 79          // wszystkie symbole
    const val LENGTH_SYNC = 7  // długość grupy synchronizacji
    const val NUM_SYNC = 3     // liczba grup synchronizacji
    const val SYNC_OFFSET = 36 // odstęp między grupami

    const val LDPC_N = 174
    const val LDPC_K = 91
    const val LDPC_M = 83
    const val LDPC_K_BYTES = (LDPC_K + 7) / 8

    const val CRC_POLYNOMIAL = 0x2757
    const val CRC_WIDTH = 14

    val COSTAS = intArrayOf(3, 1, 4, 0, 6, 5, 2)
    val GRAY_MAP = intArrayOf(0, 1, 3, 2, 5, 6, 4, 7)
}

/** Moduły FFT z całego slotu: uint8[bloki][time_osr][freq_osr][biny], wartość 0..255 = −120..+7,5 dB. */
internal class Ft8Waterfall(
    val maxBlocks: Int,
    val numBins: Int,
    val timeOsr: Int,
    val freqOsr: Int,
    val mode: FtxMode = FtxMode.FT8
) {
    val blockStride = timeOsr * freqOsr * numBins
    val mag = ByteArray(maxBlocks * blockStride)
    var numBlocks = 0

    fun magInt(index: Int): Int = mag[index].toInt() and 0xFF
    fun magDb(index: Int): Float = magInt(index) * 0.5f - 120.0f
}

/** STFT jak monitor.c: okno Hanna o długości nfft = block_size·freq_osr, przesuw o pół symbolu (time_osr = 2). */
internal class Ft8Monitor(
    sampleRate: Int = 12000,
    mode: FtxMode = FtxMode.FT8,
    fMin: Float = 200f,
    fMax: Float = 3000f,
    timeOsr: Int = 2,
    freqOsr: Int = 2
) {
    val blockSize = (sampleRate * mode.symbolPeriod).toInt()
    private val subblockSize = blockSize / timeOsr
    private val nfft = blockSize * freqOsr
    private val window = FloatArray(nfft)
    private val lastFrame = FloatArray(nfft)
    private val fft = MixedRadixFft(nfft)
    private val timeRe = DoubleArray(nfft)
    private val timeIm = DoubleArray(nfft)
    private val freqRe = DoubleArray(nfft)
    private val freqIm = DoubleArray(nfft)

    val minBin = (fMin * mode.symbolPeriod).toInt()
    private val maxBin = (fMax * mode.symbolPeriod).toInt() + 1
    val wf = Ft8Waterfall((mode.slotTime / mode.symbolPeriod).toInt(), maxBin - minBin, timeOsr, freqOsr, mode)

    init {
        val fftNorm = 2.0f / nfft
        for (i in 0 until nfft) {
            val x = sin(PI * i / nfft).toFloat()
            window[i] = fftNorm * x * x
        }
    }

    fun reset() {
        wf.numBlocks = 0
    }

    /** Jeden blok (symbol) próbek: frame[offset until offset + blockSize]. */
    fun process(frame: FloatArray, offset: Int) {
        if (wf.numBlocks >= wf.maxBlocks) return
        var out = wf.numBlocks * wf.blockStride
        var framePos = offset

        for (timeSub in 0 until wf.timeOsr) {
            // Przesunięcie ramki analizy i dopisanie nowych próbek
            System.arraycopy(lastFrame, subblockSize, lastFrame, 0, nfft - subblockSize)
            for (pos in nfft - subblockSize until nfft) {
                lastFrame[pos] = frame[framePos++]
            }
            for (pos in 0 until nfft) {
                timeRe[pos] = (window[pos] * lastFrame[pos]).toDouble()
                timeIm[pos] = 0.0
            }
            fft.transform(timeRe, timeIm, freqRe, freqIm)

            for (freqSub in 0 until wf.freqOsr) {
                for (bin in minBin until maxBin) {
                    val src = bin * wf.freqOsr + freqSub
                    val mag2 = (freqIm[src] * freqIm[src] + freqRe[src] * freqRe[src]).toFloat()
                    val db = 10.0f * log10(1e-12f + mag2)
                    // Zakres 0–240 to −120..0 dB w krokach 0,5 dB
                    val scaled = (2 * db + 240).toInt()
                    wf.mag[out++] = scaled.coerceIn(0, 255).toByte()
                }
            }
        }
        wf.numBlocks++
    }
}

internal class Ft8Candidate(
    val score: Int,
    val timeOffset: Int,
    val freqOffset: Int,
    val timeSub: Int,
    val freqSub: Int
)

/** Wynik udanego dekodowania: 77 bitów treści (10 bajtów) i całe słowo kodowe (do oceny SNR). */
internal class Ft8RawMessage(val payload: IntArray, val hash: Int, val codeword: ByteArray) {
    fun samePayload(other: Ft8RawMessage): Boolean = payload.contentEquals(other.payload)
}

internal object Ft8Decoder {

    /** Indeks symbolu 0 kandydata w tablicy wodospadu (może być ujemny dla time_offset < 0). */
    fun candidateBase(wf: Ft8Waterfall, c: Ft8Candidate): Int =
        candidateBase(wf, c.timeOffset, c.freqOffset, c.timeSub, c.freqSub)

    private fun candidateBase(wf: Ft8Waterfall, timeOffset: Int, freqOffset: Int, timeSub: Int, freqSub: Int): Int {
        var offset = timeOffset
        offset = offset * wf.timeOsr + timeSub
        offset = offset * wf.freqOsr + freqSub
        offset = offset * wf.numBins + freqOffset
        return offset
    }

    private fun syncScore(wf: Ft8Waterfall, timeOffset: Int, freqOffset: Int, timeSub: Int, freqSub: Int): Int =
        if (wf.mode == FtxMode.FT4) ft4SyncScore(wf, timeOffset, freqOffset, timeSub, freqSub)
        else ft8SyncScore(wf, timeOffset, freqOffset, timeSub, freqSub)

    private fun ft8SyncScore(wf: Ft8Waterfall, timeOffset: Int, freqOffset: Int, timeSub: Int, freqSub: Int): Int {
        var score = 0
        var numAverage = 0
        val magCand = candidateBase(wf, timeOffset, freqOffset, timeSub, freqSub)

        // Średnia po symbolach synchronizacji (0–6, 36–42, 72–78)
        for (m in 0 until Ft8Protocol.NUM_SYNC) {
            for (k in 0 until Ft8Protocol.LENGTH_SYNC) {
                val block = Ft8Protocol.SYNC_OFFSET * m + k
                val blockAbs = timeOffset + block
                if (blockAbs < 0) continue
                if (blockAbs >= wf.numBlocks) break

                val p8 = magCand + block * wf.blockStride
                val sm = Ft8Protocol.COSTAS[k]
                val expected = wf.magInt(p8 + sm)
                if (sm > 0) {
                    score += expected - wf.magInt(p8 + sm - 1)
                    numAverage++
                }
                if (sm < 7) {
                    score += expected - wf.magInt(p8 + sm + 1)
                    numAverage++
                }
                if (k > 0 && blockAbs > 0) {
                    score += expected - wf.magInt(p8 + sm - wf.blockStride)
                    numAverage++
                }
                if (k + 1 < Ft8Protocol.LENGTH_SYNC && blockAbs + 1 < wf.numBlocks) {
                    score += expected - wf.magInt(p8 + sm + wf.blockStride)
                    numAverage++
                }
            }
        }
        if (numAverage > 0) score /= numAverage
        return score
    }

    // decode.c: ft4_sync_score – cztery różne wzorce Costas 4×4 (symbole 1–4, 34–37, 67–70, 100–103)
    private fun ft4SyncScore(wf: Ft8Waterfall, timeOffset: Int, freqOffset: Int, timeSub: Int, freqSub: Int): Int {
        var score = 0
        var numAverage = 0
        val magCand = candidateBase(wf, timeOffset, freqOffset, timeSub, freqSub)

        for (m in 0 until Ft4Protocol.NUM_SYNC) {
            for (k in 0 until Ft4Protocol.LENGTH_SYNC) {
                val block = 1 + Ft4Protocol.SYNC_OFFSET * m + k
                val blockAbs = timeOffset + block
                if (blockAbs < 0) continue
                if (blockAbs >= wf.numBlocks) break

                val p4 = magCand + block * wf.blockStride
                val sm = Ft4Protocol.COSTAS[m][k]
                val expected = wf.magInt(p4 + sm)
                if (sm > 0) {
                    score += expected - wf.magInt(p4 + sm - 1)
                    numAverage++
                }
                if (sm < 3) {
                    score += expected - wf.magInt(p4 + sm + 1)
                    numAverage++
                }
                if (k > 0 && blockAbs > 0) {
                    score += expected - wf.magInt(p4 + sm - wf.blockStride)
                    numAverage++
                }
                if (k + 1 < Ft4Protocol.LENGTH_SYNC && blockAbs + 1 < wf.numBlocks) {
                    score += expected - wf.magInt(p4 + sm + wf.blockStride)
                    numAverage++
                }
            }
        }
        if (numAverage > 0) score /= numAverage
        return score
    }

    /** Najlepsze [maxCandidates] kandydatów (malejąco po sile synchronizacji). */
    fun findCandidates(wf: Ft8Waterfall, maxCandidates: Int, minScore: Int): List<Ft8Candidate> {
        val all = ArrayList<Ft8Candidate>()
        val numTones = wf.mode.numTones
        for (timeSub in 0 until wf.timeOsr) {
            for (freqSub in 0 until wf.freqOsr) {
                for (timeOffset in -10 until 20) {
                    var freqOffset = 0
                    while (freqOffset + numTones - 1 < wf.numBins) {
                        val score = syncScore(wf, timeOffset, freqOffset, timeSub, freqSub)
                        if (score >= minScore) {
                            all += Ft8Candidate(score, timeOffset, freqOffset, timeSub, freqSub)
                        }
                        freqOffset++
                    }
                }
            }
        }
        // ft8_lib trzyma kopiec min o pojemności N – wynik to te same N najlepszych, posortowanych malejąco
        return all.sortedByDescending { it.score }.take(maxCandidates)
    }

    fun decodeCandidate(wf: Ft8Waterfall, cand: Ft8Candidate, maxIterations: Int): Ft8RawMessage? {
        val log174 = FloatArray(Ft8Protocol.LDPC_N)
        if (wf.mode == FtxMode.FT4) ft4ExtractLikelihood(wf, cand, log174) else extractLikelihood(wf, cand, log174)
        normalizeLogl(log174)

        val plain174 = ByteArray(Ft8Protocol.LDPC_N)
        val ldpcErrors = Ft8Ldpc.bpDecode(log174, maxIterations, plain174)
        if (ldpcErrors > 0) return null

        // Treść + CRC (pierwsze 91 bitów) spakowane w bajty
        val a91 = IntArray(Ft8Protocol.LDPC_K_BYTES)
        packBits(plain174, Ft8Protocol.LDPC_K, a91)

        val crcExtracted = Ft8Crc.extract(a91)
        // "CRC liczone z wiadomości rozszerzonej zerami z 77 do 82 bitów"
        a91[9] = a91[9] and 0xF8
        a91[10] = 0
        val crcCalculated = Ft8Crc.compute(a91, 96 - 14)
        if (crcExtracted != crcCalculated) return null

        val payload = a91.copyOf(10)
        if (wf.mode == FtxMode.FT4) {
            for (i in payload.indices) payload[i] = payload[i] xor Ft4Protocol.XOR_SEQUENCE[i]
        }
        return Ft8RawMessage(payload, crcCalculated, plain174)
    }

    private fun ft4ExtractLikelihood(wf: Ft8Waterfall, cand: Ft8Candidate, log174: FloatArray) {
        val mag = candidateBase(wf, cand)
        for (k in 0 until Ft4Protocol.ND) {
            val symIdx = Ft4Protocol.dataSymbolIndex(k)
            val bitIdx = 2 * k
            val block = cand.timeOffset + symIdx
            if (block < 0 || block >= wf.numBlocks) {
                log174[bitIdx] = 0f
                log174[bitIdx + 1] = 0f
            } else {
                val index = mag + symIdx * wf.blockStride
                val s2 = FloatArray(4)
                for (j in 0 until 4) s2[j] = wf.magDb(index + Ft4Protocol.GRAY_MAP[j])
                log174[bitIdx] = maxOf(s2[2], s2[3]) - maxOf(s2[0], s2[1])
                log174[bitIdx + 1] = maxOf(s2[1], s2[3]) - maxOf(s2[0], s2[2])
            }
        }
    }

    private fun extractLikelihood(wf: Ft8Waterfall, cand: Ft8Candidate, log174: FloatArray) {
        val mag = candidateBase(wf, cand)
        // Symbole danych, z pominięciem synchronizacji Costas
        for (k in 0 until Ft8Protocol.ND) {
            val symIdx = k + if (k < 29) 7 else 14
            val bitIdx = 3 * k
            val block = cand.timeOffset + symIdx
            if (block < 0 || block >= wf.numBlocks) {
                log174[bitIdx] = 0f
                log174[bitIdx + 1] = 0f
                log174[bitIdx + 2] = 0f
            } else {
                extractSymbol(wf, mag + symIdx * wf.blockStride, log174, bitIdx)
            }
        }
    }

    // Nienormalizowane log(p(1)/p(0)) trzech bitów jednego symbolu 8-FSK
    private fun extractSymbol(wf: Ft8Waterfall, index: Int, logl: FloatArray, at: Int) {
        val s2 = FloatArray(8)
        for (j in 0 until 8) s2[j] = wf.magDb(index + Ft8Protocol.GRAY_MAP[j])
        logl[at] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3])
        logl[at + 1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5])
        logl[at + 2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6])
    }

    private fun normalizeLogl(log174: FloatArray) {
        var sum = 0f
        var sum2 = 0f
        for (v in log174) {
            sum += v
            sum2 += v * v
        }
        val invN = 1.0f / Ft8Protocol.LDPC_N
        val variance = (sum2 - sum * sum * invN) * invN
        // Normalizacja rozkładu i skalowanie współczynnikiem dobranym doświadczalnie
        val normFactor = sqrt(24.0f / variance)
        for (i in log174.indices) log174[i] *= normFactor
    }

    private fun packBits(bits: ByteArray, numBits: Int, packed: IntArray) {
        packed.fill(0)
        var mask = 0x80
        var byteIdx = 0
        for (i in 0 until numBits) {
            if (bits[i].toInt() != 0) packed[byteIdx] = packed[byteIdx] or mask
            mask = mask shr 1
            if (mask == 0) {
                mask = 0x80
                byteIdx++
            }
        }
    }

    /** Tony 79 symboli nadanych przez stację – odtworzone ze zdekodowanego słowa kodowego. */
    fun tonesFromCodeword(codeword: ByteArray): IntArray {
        val tones = IntArray(Ft8Protocol.NN)
        for (i in 0 until 7) {
            tones[i] = Ft8Protocol.COSTAS[i]
            tones[36 + i] = Ft8Protocol.COSTAS[i]
            tones[72 + i] = Ft8Protocol.COSTAS[i]
        }
        for (k in 0 until Ft8Protocol.ND) {
            val bits = (codeword[3 * k].toInt() shl 2) or (codeword[3 * k + 1].toInt() shl 1) or codeword[3 * k + 2].toInt()
            val symIdx = k + if (k < 29) 7 else 14
            tones[symIdx] = Ft8Protocol.GRAY_MAP[bits]
        }
        return tones
    }

    /** Tony 105 symboli FT4 (ft4_encode): rampy na tonie 0, cztery grupy Costas, dane po 2 bity. */
    fun ft4TonesFromCodeword(codeword: ByteArray): IntArray {
        val tones = IntArray(Ft4Protocol.NN)
        for (m in 0 until Ft4Protocol.NUM_SYNC) {
            for (k in 0 until Ft4Protocol.LENGTH_SYNC) {
                tones[1 + Ft4Protocol.SYNC_OFFSET * m + k] = Ft4Protocol.COSTAS[m][k]
            }
        }
        for (k in 0 until Ft4Protocol.ND) {
            val bits = (codeword[2 * k].toInt() shl 1) or codeword[2 * k + 1].toInt()
            tones[Ft4Protocol.dataSymbolIndex(k)] = Ft4Protocol.GRAY_MAP[bits]
        }
        return tones
    }

    /**
     * Szacunkowy SNR w paśmie 2500 Hz (jak w WSJT-X): średnia moc w binach nadawanych tonów
     * względem mediany szumu całego pasma w tych samych blokach. ft8_lib podaje tu tylko score·0,5.
     * Sąsiednie tony nie nadają się na odniesienie – przy oknie 2 symboli ton przecieka do nich.
     */
    fun estimateSnr(wf: Ft8Waterfall, cand: Ft8Candidate, codeword: ByteArray): Int {
        val ft4 = wf.mode == FtxMode.FT4
        val tones = if (ft4) ft4TonesFromCodeword(codeword) else tonesFromCodeword(codeword)
        val base = candidateBase(wf, cand)
        val row = IntArray(wf.numBins)
        var signal = 0.0
        var noise = 0.0
        var count = 0
        // W FT4 pomijamy symbole rampy (pierwszy i ostatni) – mają obniżoną amplitudę
        val symbols = if (ft4) 1 until Ft4Protocol.NN - 1 else 0 until Ft8Protocol.NN
        for (s in symbols) {
            val block = cand.timeOffset + s
            if (block < 0 || block >= wf.numBlocks) continue
            val idx = base + s * wf.blockStride
            signal += 10.0.pow(wf.magDb(idx + tones[s]) / 10.0)

            val rowStart = idx - cand.freqOffset
            for (b in 0 until wf.numBins) row[b] = wf.magInt(rowStart + b)
            row.sort()
            noise += 10.0.pow((row[row.size / 2] * 0.5 - 120.0) / 10.0)
            count++
        }
        if (count == 0 || noise <= 0.0) return -24
        val ratio = (signal / noise - 1.0).coerceAtLeast(1e-3)
        val offset = if (ft4) SNR_OFFSET_DB_FT4 else SNR_OFFSET_DB
        return Math.round(SNR_SLOPE * 10.0 * log10(ratio) + offset).toInt().coerceIn(-30, 40)
    }

    // Przeliczenie z binu FFT na 2500 Hz – regresja liniowa względem raportów WSJT-X
    // na 933 wiadomościach z nagrań testowych ft8_lib (Ft8DecoderTest)
    internal const val SNR_SLOPE = 0.81
    internal const val SNR_OFFSET_DB = -29.4
    // FT4 ma bin 3,33× szerszy (teoretycznie +5,2 dB); wyrównane do FT8 na tym samym sygnale syntetycznym
    internal const val SNR_OFFSET_DB_FT4 = -25.2

    private fun max4(a: Float, b: Float, c: Float, d: Float): Float = maxOf(maxOf(a, b), maxOf(c, d))
}

internal object Ft8Ldpc {
    /** Dekoder "belief propagation" (ldpc.c: bp_decode). Zwraca liczbę niespełnionych równań parzystości. */
    fun bpDecode(codeword: FloatArray, maxIters: Int, plain: ByteArray): Int {
        val n = Ft8Protocol.LDPC_N
        val mCount = Ft8Protocol.LDPC_M
        val nm = Ft8LdpcTables.NM
        val mn = Ft8LdpcTables.MN
        val numRows = Ft8LdpcTables.NUM_ROWS
        val tov = Array(n) { FloatArray(3) }
        val toc = Array(mCount) { FloatArray(7) }

        var minErrors = mCount

        for (iter in 0 until maxIters) {
            // Twarda decyzja (w iteracji 0 tov = 0)
            var plainSum = 0
            for (i in 0 until n) {
                val bit = if (codeword[i] + tov[i][0] + tov[i][1] + tov[i][2] > 0) 1 else 0
                plain[i] = bit.toByte()
                plainSum += bit
            }
            // Same zera są niedozwolone
            if (plainSum == 0) break

            val errors = check(plain)
            if (errors < minErrors) {
                minErrors = errors
                if (errors == 0) break
            }

            // Wiadomości od bitów do równań
            for (m in 0 until mCount) {
                for (nIdx in 0 until numRows[m]) {
                    val bit = nm[m][nIdx] - 1
                    var tnm = codeword[bit]
                    for (mIdx in 0 until 3) {
                        if (mn[bit][mIdx] - 1 != m) tnm += tov[bit][mIdx]
                    }
                    toc[m][nIdx] = fastTanh(-tnm / 2)
                }
            }

            // Wiadomości od równań do bitów
            for (bit in 0 until n) {
                for (mIdx in 0 until 3) {
                    val m = mn[bit][mIdx] - 1
                    var tmn = 1.0f
                    for (nIdx in 0 until numRows[m]) {
                        if (nm[m][nIdx] - 1 != bit) tmn *= toc[m][nIdx]
                    }
                    tov[bit][mIdx] = -2 * fastAtanh(tmn)
                }
            }
        }
        return minErrors
    }

    private fun check(codeword: ByteArray): Int {
        var errors = 0
        for (m in 0 until Ft8Protocol.LDPC_M) {
            var x = 0
            for (i in 0 until Ft8LdpcTables.NUM_ROWS[m]) {
                x = x xor codeword[Ft8LdpcTables.NM[m][i] - 1].toInt()
            }
            if (x != 0) errors++
        }
        return errors
    }

    private fun fastTanh(x: Float): Float {
        if (x < -4.97f) return -1.0f
        if (x > 4.97f) return 1.0f
        val x2 = x * x
        val a = x * (945.0f + x2 * (105.0f + x2))
        val b = 945.0f + x2 * (420.0f + x2 * 15.0f)
        return a / b
    }

    private fun fastAtanh(x: Float): Float {
        val x2 = x * x
        val a = x * (945.0f + x2 * (-735.0f + x2 * 64.0f))
        val b = 945.0f + x2 * (-1050.0f + x2 * 225.0f)
        return a / b
    }
}

internal object Ft8Crc {
    private const val TOPBIT = 1 shl (Ft8Protocol.CRC_WIDTH - 1)

    /** CRC-14 ciągu bitów (MSB pierwszy) – crc.c: ftx_compute_crc. */
    fun compute(message: IntArray, numBits: Int): Int {
        var remainder = 0
        var idxByte = 0
        for (idxBit in 0 until numBits) {
            if (idxBit % 8 == 0) {
                remainder = remainder xor (message[idxByte] shl (Ft8Protocol.CRC_WIDTH - 8))
                idxByte++
            }
            remainder = if (remainder and TOPBIT != 0) {
                (remainder shl 1) xor Ft8Protocol.CRC_POLYNOMIAL
            } else {
                remainder shl 1
            }
            remainder = remainder and 0xFFFF
        }
        return remainder and ((TOPBIT shl 1) - 1)
    }

    fun extract(a91: IntArray): Int =
        ((a91[9] and 0x07) shl 11) or (a91[10] shl 3) or (a91[11] shr 5)
}
