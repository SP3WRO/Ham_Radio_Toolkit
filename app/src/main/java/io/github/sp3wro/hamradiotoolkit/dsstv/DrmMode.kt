package io.github.sp3wro.hamradiotoolkit.dsstv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * HamDRM (DSSTV) – parametry sygnału. Źródła: norma ETSI ES 201 980 (DRM), rozdz. 8 (struktura ramki,
 * piloty) oraz specyfikacja "DRM mode HAM" HB9TLK (różnice względem normy: tryby A/B/E, pasma 2,3/2,5 kHz,
 * 45 komórek FAC, 15/15/20 symboli w ramce 400 ms). Tabele liczbowe pilotów i komórek FAC opisują sygnał
 * nadawany przez programy HamDRM (EasyPal, QSSTV) – implementacja odbiornika jest własna.
 */

/** Tryb odporności HamDRM. Rozmiary w próbkach przy 48 kHz (jak w specyfikacji). */
enum class DrmRobustness(
    val label: String,
    val fftSize48: Int,
    val guardNum: Int,
    val guardDen: Int,
    val symbolsPerFrame: Int,
    internal val scatFreqInt: Int,
    internal val scatTimeInt: Int
) {
    A("A", 1152, 1, 9, 15, 4, 5),
    B("B", 1024, 1, 4, 15, 2, 3),
    E("E", 640, 1, 2, 20, 1, 4);

    val guard48: Int get() = fftSize48 * guardNum / guardDen
}

/** Zajętość pasma: 2,3 kHz (SO_0), 2,5/2,8 kHz (SO_1). */
enum class DrmOccupancy(val label: String, internal val index: Int) {
    SO_0("2,3 kHz", 0), SO_1("2,5 kHz", 1)
}

/** Rodzaj komórki OFDM. */
internal object CellType {
    const val MSC = 1
    const val FAC = 2
    const val SCAT = 4
    const val TIME = 8
    const val FREQ = 16
    const val BOOSTED = 32
    const val DC = 64

    fun isPilot(t: Int) = t and (SCAT or TIME or FREQ) != 0
}

internal object DrmTables {
    const val FRAMES_PER_SUPERFRAME = 3
    const val NUM_FAC_CELLS = 45
    /** Środek pasma DRM (nośna 0) w sygnale audio. */
    const val DC_FREQ_HZ = 350.0

    // Najniższa i najwyższa nośna [zajętość][tryb A, B, E]
    private val KMIN = arrayOf(intArrayOf(2, 1, 1), intArrayOf(2, 1, 1))
    private val KMAX = arrayOf(intArrayOf(54, 45, 29), intArrayOf(58, 51, 31))

    fun kMin(mode: DrmRobustness, occ: DrmOccupancy) = KMIN[occ.index][mode.ordinal]
    fun kMax(mode: DrmRobustness, occ: DrmOccupancy) = KMAX[occ.index][mode.ordinal]

    // Komórki FAC: {symbol w ramce, nośna}
    private val FAC_A = intArrayOf(
        1, 10, 1, 22, 1, 30, 1, 50, 2, 14, 2, 26, 2, 34, 3, 18, 3, 30, 3, 38, 4, 22, 4, 34, 4, 42,
        5, 18, 5, 26, 5, 38, 5, 46, 6, 22, 6, 30, 6, 42, 6, 50, 7, 26, 7, 34, 7, 46, 8, 10, 8, 30, 8, 38, 8, 50,
        9, 14, 9, 34, 9, 42, 10, 18, 10, 38, 10, 46, 11, 10, 11, 22, 11, 42, 11, 50, 12, 14, 12, 26, 12, 46,
        13, 18, 13, 30, 14, 22, 14, 34
    )
    private val FAC_B = intArrayOf(
        0, 21, 1, 11, 1, 23, 1, 35, 2, 13, 2, 25, 2, 37, 3, 15, 3, 27, 3, 39, 4, 5, 4, 17, 4, 29, 4, 41,
        5, 7, 5, 19, 5, 31, 6, 9, 6, 21, 6, 33, 7, 11, 7, 23, 7, 35, 8, 13, 8, 25, 8, 37, 9, 15, 9, 27, 9, 39,
        10, 5, 10, 17, 10, 29, 10, 41, 11, 7, 11, 19, 11, 31, 12, 9, 12, 21, 12, 33, 13, 11, 13, 23, 13, 35,
        14, 13, 14, 25, 14, 37
    )
    private val FAC_E = intArrayOf(
        1, 7, 1, 23, 2, 8, 2, 16, 2, 24, 3, 9, 3, 17, 4, 10, 4, 18, 5, 11, 5, 19, 6, 4, 6, 12, 7, 13, 7, 21,
        8, 6, 8, 14, 8, 22, 9, 7, 9, 23, 10, 8, 10, 16, 10, 24, 11, 9, 11, 13, 11, 17, 12, 10, 12, 18,
        13, 11, 13, 19, 14, 4, 14, 12, 14, 16, 15, 13, 15, 21, 16, 6, 16, 14, 16, 22, 17, 7, 17, 23,
        18, 8, 18, 16, 18, 24, 19, 9, 19, 17
    )

    // Piloty czasu (tylko symbol 0 ramki): {nośna, faza/1024}
    private val TIME_A = intArrayOf(
        6, 973, 7, 205, 11, 717, 12, 264, 15, 357, 16, 357, 23, 952, 29, 440, 30, 856, 33, 88, 34, 88,
        38, 68, 39, 836, 41, 836, 45, 836, 46, 1008
    )
    private val TIME_B = intArrayOf(
        6, 304, 10, 331, 11, 108, 14, 620, 17, 192, 18, 704, 27, 44, 28, 432, 30, 588, 33, 844, 34, 651,
        38, 651, 40, 651, 41, 460, 44, 944
    )
    private val TIME_E = intArrayOf(7, 432, 8, 331, 13, 108, 14, 620, 21, 192, 22, 704, 26, 44, 27, 304)

    // Piloty częstotliwości (wszystkie symbole): {nośna, faza/1024}
    private val FREQ_A = intArrayOf(9, 205, 27, 836, 36, 215)
    private val FREQ_B = intArrayOf(8, 331, 24, 651, 32, 555)
    private val FREQ_E = intArrayOf(5, 788, 15, 1014, 20, 332)

    // Piloty rozproszone: stałe {x, y, k0}, macierze W i Z (wiersze y × kolumny), Q
    private val SCAT_CONST = arrayOf(intArrayOf(4, 5, 2), intArrayOf(2, 3, 1), intArrayOf(1, 4, 1))
    private val W_A = arrayOf(
        intArrayOf(228, 341, 455), intArrayOf(455, 569, 683), intArrayOf(683, 796, 910),
        intArrayOf(910, 0, 114), intArrayOf(114, 228, 341)
    )
    private val Z_A = arrayOf(
        intArrayOf(0, 81, 248), intArrayOf(18, 106, 106), intArrayOf(122, 116, 31),
        intArrayOf(129, 129, 39), intArrayOf(33, 32, 111)
    )
    private val W_B = arrayOf(
        intArrayOf(512, 0, 512, 0, 512), intArrayOf(0, 512, 0, 512, 0), intArrayOf(512, 0, 512, 0, 512)
    )
    private val Z_B = arrayOf(
        intArrayOf(0, 57, 164, 64, 12), intArrayOf(168, 255, 161, 106, 118), intArrayOf(25, 232, 132, 233, 38)
    )
    private val W_E = arrayOf(
        intArrayOf(512, 0, 512, 0, 512), intArrayOf(0, 512, 0, 512, 0),
        intArrayOf(512, 0, 512, 0, 512), intArrayOf(0, 512, 0, 512, 0)
    )
    private val Z_E = arrayOf(
        intArrayOf(0, 57, 164, 64, 12), intArrayOf(168, 255, 161, 106, 118),
        intArrayOf(25, 232, 132, 233, 38), intArrayOf(168, 255, 161, 106, 118)
    )
    private val Q = intArrayOf(36, 12, 10)
    // Wzmocnione piloty rozproszone na brzegach pasma [tryb][zajętość]
    private val BOOSTED = arrayOf(
        arrayOf(intArrayOf(2, 4, 50, 54), intArrayOf(2, 6, 54, 58)),
        arrayOf(intArrayOf(1, 3, 43, 45), intArrayOf(1, 3, 49, 51)),
        arrayOf(intArrayOf(1, 29), intArrayOf(1, 31))
    )

    fun facTable(mode: DrmRobustness) = when (mode) { DrmRobustness.A -> FAC_A; DrmRobustness.B -> FAC_B; DrmRobustness.E -> FAC_E }
    fun timePilots(mode: DrmRobustness) = when (mode) { DrmRobustness.A -> TIME_A; DrmRobustness.B -> TIME_B; DrmRobustness.E -> TIME_E }
    fun freqPilots(mode: DrmRobustness) = when (mode) { DrmRobustness.A -> FREQ_A; DrmRobustness.B -> FREQ_B; DrmRobustness.E -> FREQ_E }

    internal fun scatteredPhase(mode: DrmRobustness, sym: Int, car: Int): Int {
        val c = SCAT_CONST[mode.ordinal]
        val (w, z) = when (mode) {
            DrmRobustness.A -> W_A to Z_A
            DrmRobustness.B -> W_B to Z_B
            DrmRobustness.E -> W_E to Z_E
        }
        val n = sym % c[1]
        val m = sym / c[1]
        val p = (car - c[2] - n * c[0]) / (c[0] * c[1])
        return floorMod(4 * z[n][m] + p * w[n][m] + p * p * (1 + sym) * Q[mode.ordinal], 1024)
    }

    internal fun boosted(mode: DrmRobustness, occ: DrmOccupancy): IntArray = BOOSTED[mode.ordinal][occ.index]

    fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b
}

/**
 * Mapa komórek jednej ramki (wszystkie trzy ramki nadramki są jednakowe) i wartości pilotów –
 * zasady z ES 201 980 8.4 z tabelami HamDRM. Indeks nośnej w tablicach: k − kMin.
 */
internal class DrmCellMap(val mode: DrmRobustness, val occupancy: DrmOccupancy) {
    val kMin = DrmTables.kMin(mode, occupancy)
    val kMax = DrmTables.kMax(mode, occupancy)
    val numCarriers = kMax - kMin + 1
    val symbols = mode.symbolsPerFrame
    val type = Array(symbols) { IntArray(numCarriers) }
    val pilotRe = Array(symbols) { FloatArray(numCarriers) }
    val pilotIm = Array(symbols) { FloatArray(numCarriers) }
    /** Liczba komórek MSC w symbolu (ostatni symbol nadramki zawiera jeszcze komórki "dummy"). */
    val mscPerSymbol = IntArray(symbols)
    val facPerSymbol = IntArray(symbols)
    /** Użyteczne komórki MSC na ramkę logiczną (N_MUX). */
    val mscCellsPerFrame: Int
    /** Komórki dummy na końcu nadramki. */
    val dummyCells: Int

    init {
        val fac = DrmTables.facTable(mode)
        val timeP = DrmTables.timePilots(mode)
        val freqP = DrmTables.freqPilots(mode)
        val boosted = DrmTables.boosted(mode, occupancy)
        val x = mode.scatFreqInt
        val y = mode.scatTimeInt
        val k0 = (x + 1) / 2

        for (s in 0 until symbols) {
            var facIndex = 0
            while (facIndex < DrmTables.NUM_FAC_CELLS && fac[facIndex * 2] < s) facIndex++
            for (k in kMin..kMax) {
                val i = k - kMin
                var t = CellType.MSC
                // FAC
                for (f in 0 until DrmTables.NUM_FAC_CELLS) {
                    if (fac[f * 2] == s && fac[f * 2 + 1] == k) t = CellType.FAC
                }
                // Piloty rozproszone (mają pierwszeństwo przed FAC; czasu i częstotliwości – przed nimi)
                if (floorModSafe(k - k0 - x * (s % y), x * y) == 0 && k >= k0 + x * (s % y)) {
                    t = CellType.SCAT
                    val phase = DrmTables.scatteredPhase(mode, s, k)
                    val gain = if (k in boosted) 2.0 else sqrt(2.0)
                    if (k in boosted) t = t or CellType.BOOSTED
                    setPilot(s, i, gain, phase)
                }
                if (s == 0) {
                    for (p in 0 until timeP.size / 2) {
                        if (timeP[p * 2] == k) {
                            t = if (t and CellType.SCAT != 0) t or CellType.TIME else CellType.TIME
                            setPilot(s, i, sqrt(2.0), timeP[p * 2 + 1])
                        }
                    }
                }
                for (p in 0 until freqP.size / 2) {
                    if (freqP[p * 2] == k) {
                        t = if (t and (CellType.TIME or CellType.SCAT) != 0) t or CellType.FREQ else CellType.FREQ
                        // W trybie E dwa pierwsze piloty częstotliwości odwracają fazę w symbolach nieparzystych
                        val special = mode == DrmRobustness.E && p != freqP.size / 2 - 1 && s % 2 == 1
                        val phase = if (special) (freqP[p * 2 + 1] + 512) % 1024 else freqP[p * 2 + 1]
                        setPilot(s, i, sqrt(2.0), phase)
                    }
                }
                if (k == 0 || (mode == DrmRobustness.A && (k == 1 || k == -1))) t = CellType.DC
                type[s][i] = t
            }
        }
        var msc = 0
        for (s in 0 until symbols) {
            var m = 0
            var f = 0
            for (i in 0 until numCarriers) {
                if (type[s][i] == CellType.MSC) m++
                if (type[s][i] == CellType.FAC) f++
            }
            mscPerSymbol[s] = m
            facPerSymbol[s] = f
            msc += m
        }
        check(facPerSymbol.sum() == DrmTables.NUM_FAC_CELLS) { "FAC: ${facPerSymbol.sum()} komórek" }
        val perSuperframe = msc * DrmTables.FRAMES_PER_SUPERFRAME
        mscCellsPerFrame = perSuperframe / DrmTables.FRAMES_PER_SUPERFRAME
        dummyCells = perSuperframe - mscCellsPerFrame * DrmTables.FRAMES_PER_SUPERFRAME
    }

    private fun floorModSafe(a: Int, b: Int) = DrmTables.floorMod(a, b)

    private fun setPilot(s: Int, i: Int, gain: Double, phase1024: Int) {
        val a = 2 * PI * phase1024 / 1024
        pilotRe[s][i] = (gain * cos(a)).toFloat()
        pilotIm[s][i] = (gain * sin(a)).toFloat()
    }

    fun isScatteredCarrier(s: Int, k: Int): Boolean = type[s][k - kMin] and CellType.SCAT != 0

    companion object {
        private val cache = HashMap<Pair<DrmRobustness, DrmOccupancy>, DrmCellMap>()
        fun of(mode: DrmRobustness, occ: DrmOccupancy): DrmCellMap =
            synchronized(cache) { cache.getOrPut(mode to occ) { DrmCellMap(mode, occ) } }
    }
}
