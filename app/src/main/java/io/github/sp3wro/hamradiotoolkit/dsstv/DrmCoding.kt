package io.github.sp3wro.hamradiotoolkit.dsstv

/*
 * Kodowanie kanałowe DRM wg ES 201 980 rozdz. 7: rozpraszanie energii (7.2.5), kod splotowy K=7
 * z nakłuwaniem (7.3.3), przeplatanie bitów i komórek (7.3.3, 7.6), CRC (annex D) i odwzorowanie QAM (7.4).
 * Implementacja własna; kolejność bitów i warianty wzorów dobrane do sygnału nadawanego przez HamDRM.
 */
internal object DrmCrc {
    /** CRC-8 (x^8+x^4+x^3+x^2+1) lub CRC-16 (x^16+x^12+x^5+1): rejestr startuje od jedynek, wynik zanegowany. */
    fun compute(data: IntArray, offset: Int, length: Int, degree: Int): Int {
        val poly = when (degree) {
            8 -> 0x1D
            16 -> 0x1021
            else -> throw IllegalArgumentException("CRC-$degree")
        }
        val top = 1 shl (degree - 1)
        val mask = (1 shl degree) - 1
        var reg = mask
        for (i in offset until offset + length) {
            val b = data[i]
            for (bit in 7 downTo 0) {
                val inBit = (b shr bit) and 1
                val fb = ((reg and top) != 0) xor (inBit == 1)
                reg = (reg shl 1) and mask
                if (fb) reg = reg xor poly
            }
        }
        return reg.inv() and mask
    }
}

/** Ciąg PRBS x^9+x^5+1 (rejestr startuje od jedynek) – nakładany XOR na bity danych. */
internal object EnergyDispersal {
    fun apply(bits: ByteArray, count: Int = bits.size) {
        var reg = 0x1FF
        for (i in 0 until count) {
            val prbs = ((reg shr 4) and 1) xor ((reg shr 8) and 1)
            reg = ((reg shl 1) or prbs) and 0x1FF
            bits[i] = (bits[i].toInt() xor prbs).toByte()
        }
    }
}

/** Tablica przeplatacza blokowego (7.3.3): Π(0)=0, Π(i) = (t0·Π(i−1) + q) mod s, z pominięciem ≥ N. */
internal object BlockInterleaver {
    private val cache = HashMap<Long, IntArray>()

    fun table(size: Int, t0: Int): IntArray = synchronized(cache) {
        cache.getOrPut(size.toLong() * 1000 + t0) {
            // s = 2^(floor(log2 N) + 1) – tak liczy nadajnik (dla N będącego potęgą 2 to 2N, nie N)
            var s = 1
            while (s <= size) s = s shl 1
            val q = s / 4 - 1
            val t = IntArray(size)
            for (i in 1 until size) {
                var v = (t0 * t[i - 1] + q) % s
                while (v >= size) v = (t0 * v + q) % s
                t[i] = v
            }
            t
        }
    }
}

/** Kod splotowy K=7, wielomiany 133, 171, 145, 133 (ósemkowo) i wzorce nakłuwania DRM. */
internal object ConvCode {
    // Wielomiany z odwróconą kolejnością bitów – najnowszy bit wejściowy jest na pozycji 0
    private val GEN = intArrayOf(0b1101101, 0b1001111, 0b1010011, 0b1101101)
    private const val STATES = 64

    // Wzorce: kolejne bity wejściowe wysyłają wyjścia: 1 – {0}, 3 – {0,1}, 5 – {0,2}, 7 – {0,1,2}, 15 – {0,1,2,3}
    private const val P1111 = 15
    private const val P0111 = 7
    private const val P0011 = 3
    private const val P0001 = 1
    private const val P0101 = 5

    /** {okres (bity wejściowe), liczba wyjść, wzorce...} – indeks = numer stopy kodu z tabel DRM. */
    private val PUNCTURING = arrayOf(
        intArrayOf(1, 4, P1111),
        intArrayOf(3, 10, P1111, P0111, P0111),
        intArrayOf(1, 3, P0111),
        intArrayOf(4, 11, P0111, P0111, P0111, P0011),
        intArrayOf(1, 2, P0011),
        intArrayOf(4, 7, P0011, P0101, P0011, P0001),
        intArrayOf(3, 5, P0011, P0001, P0011),
        intArrayOf(2, 3, P0011, P0001),
        intArrayOf(8, 11, P0011, P0001, P0001, P0011, P0001, P0001, P0011, P0001),
        intArrayOf(3, 4, P0011, P0001, P0001),
        intArrayOf(4, 5, P0011, P0001, P0001, P0001),
        intArrayOf(7, 8, P0011, P0001, P0001, P0001, P0001, P0001, P0001),
        intArrayOf(8, 9, P0011, P0001, P0001, P0001, P0001, P0001, P0001, P0001)
    )
    private val TAIL = arrayOf(
        intArrayOf(P0011, P0011, P0011, P0011, P0011, P0011),
        intArrayOf(P0111, P0011, P0011, P0011, P0011, P0011),
        intArrayOf(P0111, P0011, P0011, P0111, P0011, P0011),
        intArrayOf(P0111, P0111, P0011, P0111, P0011, P0011),
        intArrayOf(P0111, P0111, P0011, P0111, P0111, P0011),
        intArrayOf(P0111, P0111, P0111, P0111, P0111, P0011),
        intArrayOf(P0111, P0111, P0111, P0111, P0111, P0111),
        intArrayOf(P1111, P0111, P0111, P0111, P0111, P0111),
        intArrayOf(P1111, P0111, P0111, P1111, P0111, P0111),
        intArrayOf(P1111, P1111, P0111, P1111, P0111, P0111),
        intArrayOf(P1111, P1111, P0111, P1111, P0111, P1111),
        intArrayOf(P1111, P1111, P1111, P1111, P0111, P1111)
    )

    fun rateNumerator(rate: Int) = PUNCTURING[rate][0]
    fun rateDenominator(rate: Int) = PUNCTURING[rate][1]

    /** Liczba bitów informacyjnych mieszczących się w [codedBits] bitach (bez ogona) – M = RX·⌊(2N−12)/RY⌋. */
    fun infoBits(codedBits: Int, rate: Int): Int = PUNCTURING[rate][0] * ((codedBits - 12) / PUNCTURING[rate][1])

    /**
     * Wzorzec nakłuwania dla każdego z [infoBits] + 6 kroków kodera.
     * [facTail]: w FAC ogon używa zwykłego wzorca (tak nadaje HamDRM), w MSC – wzorca ogona z normy.
     */
    fun pattern(infoBits: Int, rate: Int, codedBits: Int, facTail: Boolean): IntArray {
        val p = PUNCTURING[rate]
        val period = p[0]
        val tailIndex = (codedBits - 12) - p[1] * ((codedBits - 12) / p[1])
        return IntArray(infoBits + 6) { i ->
            if (i < infoBits || facTail) p[2 + i % period] else TAIL[tailIndex][i - infoBits]
        }
    }

    private fun parity(x: Int): Int = Integer.bitCount(x) and 1

    /** Kodowanie z ogonem 6 zer: wynik to bity po nakłuciu. */
    fun encode(info: ByteArray, pattern: IntArray): ByteArray {
        val out = ArrayList<Byte>(pattern.size * 4)
        var state = 0
        for (i in pattern.indices) {
            state = (state shl 1) and 0x7F
            if (i < info.size && info[i].toInt() != 0) state = state or 1
            val pat = pattern[i]
            for (g in 0 until 4) {
                if (pat and (1 shl g) != 0) out += parity(state and GEN[g]).toByte()
            }
        }
        return out.toByteArray()
    }

    /**
     * Dekoder Viterbiego (miękkie decyzje): [llr] > 0 oznacza bit 0, 0 – brak informacji.
     * Zwraca [infoBits] bitów; ostatni stan wymuszony na 0 (ogon zer).
     */
    fun decode(llr: FloatArray, pattern: IntArray, infoBits: Int): ByteArray {
        val steps = pattern.size
        // Przewidywane wyjścia dla każdego stanu i bitu: rejestr 7 bitów = (stan << 1 | bit)
        val metric = FloatArray(STATES) { if (it == 0) 0f else -1e30f }
        val newMetric = FloatArray(STATES)
        val decisions = Array(steps) { ByteArray(STATES) }
        val outBits = Array(4) { IntArray(128) }
        for (reg in 0 until 128) for (g in 0 until 4) outBits[g][reg] = parity(reg and GEN[g])

        var pos = 0
        val local = FloatArray(4)
        for (i in 0 until steps) {
            val pat = pattern[i]
            var n = 0
            for (g in 0 until 4) {
                if (pat and (1 shl g) != 0) {
                    local[g] = if (pos < llr.size) llr[pos] else 0f
                    pos++
                    n++
                } else local[g] = 0f
            }
            java.util.Arrays.fill(newMetric, -1e30f)
            val forceZero = i >= infoBits
            for (s in 0 until STATES) {
                val m = metric[s]
                if (m <= -1e29f) continue
                for (bit in 0..(if (forceZero) 0 else 1)) {
                    val reg = (s shl 1) or bit
                    var bm = 0f
                    for (g in 0 until 4) {
                        if (pat and (1 shl g) != 0) bm += if (outBits[g][reg] == 0) local[g] else -local[g]
                    }
                    val ns = reg and 0x3F
                    val cand = m + bm
                    if (cand > newMetric[ns]) {
                        newMetric[ns] = cand
                        // Zapamiętujemy najstarszy bit poprzedniego stanu, żeby móc się cofnąć
                        decisions[i][ns] = (s shr 5).toByte()
                    }
                }
            }
            System.arraycopy(newMetric, 0, metric, 0, STATES)
        }
        // Śledzenie wstecz od stanu 0
        val bits = ByteArray(steps)
        var s = 0
        for (i in steps - 1 downTo 0) {
            bits[i] = (s and 1).toByte()
            val oldest = decisions[i][s].toInt()
            s = (s shr 1) or (oldest shl 5)
        }
        return bits.copyOf(infoBits)
    }
}

/** Konstelacje QAM (wartości na jednej osi; indeks = bity poziomów od najstarszego). */
internal object DrmQam {
    val QAM4 = floatArrayOf(0.7071067811f, -0.7071067811f)
    val QAM16 = floatArrayOf(0.9486832980f, -0.3162277660f, 0.3162277660f, -0.9486832980f)
    val QAM64 = floatArrayOf(
        1.0801234497f, -0.1543033499f, 0.4629100498f, -0.7715167498f,
        0.7715167498f, -0.4629100498f, 0.1543033499f, -1.0801234497f
    )

    fun axis(levels: Int) = when (levels) { 1 -> QAM4; 2 -> QAM16; else -> QAM64 }
}

/** Schemat kodowania MSC z FAC: liczba poziomów i stopy kodu na poziomach. */
internal class MscCoding(val qamLevels: Int, highProtection: Boolean) {
    /** Indeksy stóp kodu (tabela nakłuwania) dla kolejnych poziomów. */
    val rates: IntArray = when (qamLevels) {
        1 -> intArrayOf(6)
        2 -> if (highProtection) intArrayOf(2, 7) else intArrayOf(4, 9)
        else -> if (highProtection) intArrayOf(0, 4, 9) else intArrayOf(2, 7, 10)
    }
    /** Przeplatacz bitów na poziomie: t0 = 13, 21 albo brak (−1). */
    val interleaverT0: IntArray = when (qamLevels) {
        1 -> intArrayOf(21)
        2 -> intArrayOf(13, 21)
        else -> intArrayOf(-1, 13, 21)
    }
}
