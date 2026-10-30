package io.github.sp3wro.hamradiotoolkit.dsstv

/** Zawartość kanału FAC HamDRM (40 bitów + CRC-8) – parametry nadawania jednej ramki. */
data class DrmFacInfo(
    val frameId: Int,
    val occupancy: DrmOccupancy,
    val longInterleave: Boolean,
    /** Liczba poziomów QAM: 1 – 4-QAM, 2 – 16-QAM, 3 – 64-QAM. */
    val qamLevels: Int,
    val highProtection: Boolean,
    val isData: Boolean,
    val packetId: Int,
    /** 3 znaki etykiety (znak nadawcy) przesyłane w tej ramce. */
    val labelPart: String,
    val wide: Boolean
) {
    val qamLabel: String get() = when (qamLevels) { 1 -> "4-QAM"; 2 -> "16-QAM"; else -> "64-QAM" }
}

/** Zrównoważone komórki: wartości po korekcji kanału i wagi (|H|², wiarygodność). */
internal class DrmCells(val re: FloatArray, val im: FloatArray, val weight: FloatArray) {
    val size: Int get() = re.size
}

internal object DrmChannelDecoder {
    private const val FAC_BITS = 48
    private const val FAC_CODED = 90
    private const val FAC_RATE = 6
    private const val WIDE_CRC_XOR = 0x5A

    fun decodeFac(cells: DrmCells): DrmFacInfo? {
        require(cells.size == DrmTables.NUM_FAC_CELLS)
        val llr = FloatArray(FAC_CODED)
        for (i in 0 until cells.size) {
            llr[2 * i] = 2f * cells.re[i] * cells.weight[i]
            llr[2 * i + 1] = 2f * cells.im[i] * cells.weight[i]
        }
        val table = BlockInterleaver.table(FAC_CODED, 21)
        val orig = FloatArray(FAC_CODED)
        for (p in 0 until FAC_CODED) orig[table[p]] = llr[p]
        val pattern = ConvCode.pattern(FAC_BITS, FAC_RATE, FAC_CODED, facTail = true)
        val bits = ConvCode.decode(orig, pattern, FAC_BITS)
        EnergyDispersal.apply(bits)
        val bytes = IntArray(6) { b -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or bits[b * 8 + k].toInt() } }
        val crc = DrmCrc.compute(bytes, 0, 5, 8)
        val wide = when (bytes[5]) {
            crc -> false
            crc xor WIDE_CRC_XOR -> true
            else -> return null
        }
        fun field(from: Int, len: Int): Int = (from until from + len).fold(0) { acc, i -> (acc shl 1) or bits[i].toInt() }
        val frameId = when (field(0, 2)) { 3 -> 0; 1 -> 1; 2 -> 2; else -> 0 }
        val isData = field(6, 1) == 1
        val mscMode = field(4, 1)
        val extendedQam4 = isData && field(9, 1) == 1
        val qam = when {
            mscMode == 0 -> 3
            extendedQam4 -> 1
            else -> 2
        }
        val label = StringBuilder()
        for (c in 0 until 3) {
            val ch = field(10 + c * 7, 7)
            if (ch in 32..126) label.append(ch.toChar())
        }
        return DrmFacInfo(
            frameId = frameId,
            occupancy = if (field(2, 1) == 1) DrmOccupancy.SO_1 else DrmOccupancy.SO_0,
            longInterleave = field(3, 1) == 0,
            qamLevels = qam,
            highProtection = field(5, 1) == 0,
            isData = isData,
            packetId = if (isData) field(7, 2) else 0,
            labelPart = label.toString(),
            wide = wide
        )
    }

    /** Liczba bitów danych w ramce MSC dla [cells] komórek. */
    fun mscInfoBits(cells: Int, coding: MscCoding): Int =
        coding.rates.sumOf { ConvCode.infoBits(2 * cells, it) }

    /**
     * Dekodowanie wielopoziomowe ramki MSC (kolejno poziomy 0, 1, 2; zdekodowane niższe poziomy
     * są ponownie kodowane i zawężają konstelację dla wyższych). Wynik: bajty ramki po zdjęciu PRBS.
     */
    fun decodeMsc(cells: DrmCells, coding: MscCoding): IntArray {
        val n = cells.size
        val levels = coding.qamLevels
        val axis = DrmQam.axis(levels)
        val coded = 2 * n
        val known = Array(levels) { ByteArray(coded) }
        val info = ArrayList<ByteArray>()
        val llr = FloatArray(coded)
        val orig = FloatArray(coded)
        for (j in 0 until levels) {
            val bitOfLevel = levels - 1 - j
            for (p in 0 until coded) {
                val v = if (p % 2 == 0) cells.re[p / 2] else cells.im[p / 2]
                var d0 = Float.MAX_VALUE
                var d1 = Float.MAX_VALUE
                for (idx in axis.indices) {
                    var ok = true
                    for (lower in 0 until j) {
                        val b = (idx shr (levels - 1 - lower)) and 1
                        if (b != known[lower][p].toInt()) {
                            ok = false
                            break
                        }
                    }
                    if (!ok) continue
                    val d = (v - axis[idx]) * (v - axis[idx])
                    if ((idx shr bitOfLevel) and 1 == 0) {
                        if (d < d0) d0 = d
                    } else if (d < d1) d1 = d
                }
                llr[p] = cells.weight[p / 2] * (d1 - d0)
            }
            val t0 = coding.interleaverT0[j]
            val table = if (t0 >= 0) BlockInterleaver.table(coded, t0) else null
            if (table != null) {
                for (p in 0 until coded) orig[table[p]] = llr[p]
            } else {
                System.arraycopy(llr, 0, orig, 0, coded)
            }
            val rate = coding.rates[j]
            val infoBits = ConvCode.infoBits(coded, rate)
            val pattern = ConvCode.pattern(infoBits, rate, coded, facTail = false)
            val bits = ConvCode.decode(orig, pattern, infoBits)
            info += bits
            if (j < levels - 1) {
                // Ponowne kodowanie: znane bity tego poziomu w kolejności nadawania
                val enc = ConvCode.encode(bits, pattern)
                for (p in 0 until coded) known[j][p] = if (table != null) enc[table[p]] else enc[p]
            }
        }
        val total = info.sumOf { it.size }
        val all = ByteArray(total)
        var pos = 0
        for (b in info) {
            System.arraycopy(b, 0, all, pos, b.size)
            pos += b.size
        }
        EnergyDispersal.apply(all)
        return IntArray(total / 8) { b -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or all[b * 8 + k].toInt() } }
    }
}

internal val MscCoding.qamLevels: Int get() = rates.size
