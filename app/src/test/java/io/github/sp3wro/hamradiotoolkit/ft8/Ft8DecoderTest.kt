package io.github.sp3wro.hamradiotoolkit.ft8

import io.github.sp3wro.hamradiotoolkit.dsp.MixedRadixFft
import io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader
import io.github.sp3wro.hamradiotoolkit.dsp.resample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Nagrania wzorcowe z ft8_lib (test/wav) z wynikami WSJT-X w plikach .txt.
 * Domyślnie kilka plików z zasobów testowych; cały zestaw: -Dft8.testDir=<katalog z wav i txt>.
 */
class Ft8DecoderTest {

    private data class Expected(val key: String, val snr: Int, val dt: Float, val freq: Int)

    private fun parseExpected(line: String): Expected? {
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 6 || parts[4] != "~") return null
        // Za wiadomością WSJT-X dopisuje kontynent i kraj po kilku spacjach
        val message = line.substringAfter("~").trim().split(Regex("\\s{2,}")).first()
        return Expected(key(message), parts[1].toInt(), parts[2].toFloat(), parts[3].toInt())
    }

    // Jak utils/run_tests.py: trzy pierwsze pola, znaki z hasza jako <...>
    private fun key(message: String): String =
        message.split(' ').filter { it.isNotEmpty() }.take(3)
            .joinToString(" ") { if (it.startsWith("<") && it.endsWith(">")) "<...>" else it }

    private fun testFiles(): List<Pair<File, File>> {
        val dir = System.getProperty("ft8.testDir")?.let(::File)
            ?: File(javaClass.classLoader!!.getResource("ft8")!!.toURI())
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "wav" }
            .mapNotNull { wav -> File(wav.parentFile, wav.nameWithoutExtension + ".txt").takeIf { it.exists() }?.let { wav to it } }
            .sortedBy { it.first.name }
            .toList()
    }

    private fun stdDev(values: List<Double>): Double {
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun fftMatchesDirectDft() {
        val n = 60
        val fft = MixedRadixFft(n)
        val re = DoubleArray(n) { sin(it * 0.37) + 0.5 * cos(it * 1.3) }
        val im = DoubleArray(n) { 0.1 * it }
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        fft.transform(re, im, outRe, outIm)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (j in 0 until n) {
                val a = -2 * PI * j * k / n
                sr += re[j] * cos(a) - im[j] * sin(a)
                si += re[j] * sin(a) + im[j] * cos(a)
            }
            assertEquals(sr, outRe[k], 1e-9)
            assertEquals(si, outIm[k], 1e-9)
        }
    }

    @Test
    fun crcOfKnownPayload() {
        // Wiadomość zerowa daje CRC 0; pojedynczy bit musi dać niezerowe CRC mieszczące się w 14 bitach
        assertEquals(0, Ft8Crc.compute(IntArray(12), 82))
        val crc = Ft8Crc.compute(IntArray(12).also { it[0] = 0x80 }, 82)
        assertTrue(crc in 1..0x3FFF)
    }

    /** Słowo kodowe LDPC(174,91): 91 bitów wiadomości + 83 bity parzystości z eliminacji Gaussa na macierzy H. */
    private fun ldpcEncode(message: IntArray): ByteArray {
        val k = Ft8Protocol.LDPC_K
        val m = Ft8Protocol.LDPC_M
        // Wiersz i: [A | b], A – kolumny parzystości (83), b – suma bitów wiadomości w równaniu
        val rows = Array(m) { IntArray(m + 1) }
        for (i in 0 until m) {
            for (j in 0 until Ft8LdpcTables.NUM_ROWS[i]) {
                val bit = Ft8LdpcTables.NM[i][j] - 1
                if (bit >= k) rows[i][bit - k] = rows[i][bit - k] xor 1 else rows[i][m] = rows[i][m] xor message[bit]
            }
        }
        for (col in 0 until m) {
            val pivot = (col until m).first { rows[it][col] == 1 }
            rows[col] = rows[pivot].also { rows[pivot] = rows[col] }
            for (r in 0 until m) {
                if (r != col && rows[r][col] == 1) for (c in 0..m) rows[r][c] = rows[r][c] xor rows[col][c]
            }
        }
        return ByteArray(Ft8Protocol.LDPC_N) { if (it < k) message[it].toByte() else rows[it - k][m].toByte() }
    }

    /** Sygnał FT4 (4-FSK z ciągłą fazą) dla 77-bitowej treści – jak ft4_encode z ft8_lib. */
    private fun ft4Signal(payload: IntArray, out: FloatArray, f0: Double, startSample: Int, amplitude: Float) {
        val a = IntArray(12)
        for (i in 0 until 10) a[i] = payload[i] xor Ft4Protocol.XOR_SEQUENCE[i]
        a[9] = a[9] and 0xF8
        val crc = Ft8Crc.compute(a, 96 - 14)
        val bits = IntArray(Ft8Protocol.LDPC_K) { i ->
            if (i < 77) (a[i / 8] shr (7 - i % 8)) and 1 else (crc shr (90 - i)) and 1
        }
        val tones = Ft8Decoder.ft4TonesFromCodeword(ldpcEncode(bits))
        val symbolSamples = 576
        var phase = 0.0
        for (s in tones.indices) {
            val dphi = 2 * PI * (f0 + tones[s] * 12000.0 / symbolSamples) / 12000.0
            for (i in 0 until symbolSamples) {
                val idx = startSample + s * symbolSamples + i
                if (idx in out.indices) out[idx] += amplitude * sin(phase).toFloat()
                phase += dphi
            }
        }
    }

    @Test
    fun decodesSyntheticFt4() {
        // Prawdziwe treści 77-bitowe z nagrania FT8, nadane ponownie jako FT4
        val (wav, _) = testFiles().first()
        val audio = wav.inputStream().use { WavPcmReader.read(it) }
        val samples = resample(audio.samples, audio.sampleRate, 12000)
        val monitor = Ft8Monitor(12000)
        var pos = 0
        while (pos + monitor.blockSize <= minOf(samples.size, 15 * 12000)) {
            monitor.process(samples, pos)
            pos += monitor.blockSize
        }
        val hashTable = Ft8CallsignHashTable()
        val messages = Ft8Decoder.findCandidates(monitor.wf, 140, 10)
            .mapNotNull { Ft8Decoder.decodeCandidate(monitor.wf, it, 25) }
            .distinctBy { it.payload.toList() }
            .mapNotNull { raw -> Ft8MessageUnpacker.decode(raw.payload, hashTable)?.let { raw.payload to it.text } }
            .filter { !it.second.contains("<") }
            .take(4)
        assertTrue("Brak wiadomości wzorcowych", messages.size >= 2)

        val rng = java.util.Random(7)
        val signal = FloatArray(90_000) { (rng.nextGaussian() * 0.05).toFloat() }
        val freqs = messages.indices.map { 600.0 + it * 450.0 }
        messages.forEachIndexed { i, (payload, _) -> ft4Signal(payload, signal, freqs[i], 6000, 0.1f) }

        val result = Ft8SlotDecoder(12000, FtxMode.FT4).decode(signal, signal.size, 0L)
        println("FT4: " + result.joinToString { "${it.snr} ${it.dt} ${it.freqHz} ${it.text}" })
        messages.forEachIndexed { i, (_, text) ->
            val d = result.firstOrNull { it.text == text }
            assertTrue("Nie zdekodowano FT4: $text", d != null)
            assertTrue("Częstotliwość ${d!!.freqHz} zamiast ${freqs[i]}", abs(d.freqHz - freqs[i]) <= 11)
            assertTrue("DT ${d.dt}", abs(d.dt) <= 0.1f)
            assertEquals(FtxMode.FT4, d.mode)
        }
        assertEquals("Fałszywe dekody", messages.size, result.size)
    }

    @Test
    fun decodesReferenceRecordings() {
        val files = testFiles()
        assertTrue("Brak nagrań testowych", files.isNotEmpty())

        var total = 0
        var missed = 0
        var extra = 0
        var matched = 0
        var freqMismatch = 0
        val snrDiff = ArrayList<Int>()
        val snrPairs = ArrayList<Pair<Double, Double>>()
        val dtDiff = ArrayList<Float>()
        val start = System.nanoTime()

        for ((wav, txt) in files) {
            val expected = txt.readLines().mapNotNull(::parseExpected)
            val audio = wav.inputStream().use { WavPcmReader.read(it) }
            val samples = resample(audio.samples, audio.sampleRate, 12000)
            val decoder = Ft8SlotDecoder(12000)
            val result = decoder.decode(samples, minOf(samples.size, 15 * 12000), 0L)

            val got = result.groupBy { key(it.text) }
            val want = expected.groupBy { it.key }
            total += want.size
            missed += (want.keys - got.keys).size
            extra += (got.keys - want.keys).size
            for ((k, list) in want) {
                val decodes = got[k] ?: continue
                for (e in list) {
                    // Ta sama treść bywa nadawana na dwóch częstotliwościach – bierzemy najbliższy dekod
                    val d = decodes.minByOrNull { abs(it.freqHz - e.freq) }!!
                    if (abs(d.freqHz - e.freq) > 4) {
                        freqMismatch++
                        if (System.getProperty("ft8.verbose") != null) println("  częstotliwość ${d.freqHz} vs ${e.freq}: $k")
                        continue
                    }
                    matched++
                    snrPairs += e.snr.toDouble() to d.snr.toDouble()
                    snrDiff += e.snr - d.snr
                    dtDiff += e.dt - d.dt
                }
            }
            if (System.getProperty("ft8.verbose") != null) {
                println("${wav.name}: ${got.keys.intersect(want.keys).size}/${want.size}, extra=${got.keys - want.keys}")
            }
        }

        val ms = (System.nanoTime() - start) / 1_000_000
        val recall = (total - missed).toDouble() / total
        println(
            "FT8: pliki=${files.size}, wzorzec=$total, brak=$missed, nadmiar=$extra, recall=${"%.1f".format(recall * 100)}%, " +
                "śr. różnica SNR (WSJT-X − my)=${"%.2f".format(snrDiff.average())} dB, " +
                "śr. różnica DT=${"%.3f".format(dtDiff.average())} s, " +
                "odch. SNR=${"%.2f".format(stdDev(snrDiff.map { it.toDouble() }))} dB, " +
                "inna częstotliwość=$freqMismatch/$matched, czas=${ms} ms"
        )
        if (System.getProperty("ft8.verbose") != null) {
            // Regresja WSJT-X = a·nasz + b
            val mx = snrPairs.map { it.second }.average()
            val my = snrPairs.map { it.first }.average()
            val a = snrPairs.sumOf { (it.second - mx) * (it.first - my) } / snrPairs.sumOf { (it.second - mx) * (it.second - mx) }
            println("SNR: WSJT-X ≈ ${"%.3f".format(a)}·nasz + ${"%.2f".format(my - a * mx)}")
            for (bucket in listOf(-30..-20, -19..-15, -14..-10, -9..-5, -4..0, 1..10, 11..40)) {
                val sel = snrPairs.filter { it.first.toInt() in bucket }
                if (sel.isNotEmpty()) println("  WSJT-X $bucket: n=${sel.size}, śr. nasz=${"%.1f".format(sel.map { it.second }.average())}")
            }
        }
        assertTrue("Częstotliwości niezgodne: $freqMismatch z $matched", freqMismatch <= matched / 50 + 1)
        assertTrue("Za mało zdekodowanych wiadomości: recall=$recall", recall >= 0.5)
        assertTrue("Za dużo fałszywych dekodów: $extra", extra <= total / 20 + 1)
        assertTrue("Średni błąd SNR ${snrDiff.average()}", abs(snrDiff.average()) <= 2.0)
        assertTrue("Rozrzut SNR", stdDev(snrDiff.map { it.toDouble() }) <= 6.0)
        assertTrue("Średni błąd DT ${dtDiff.average()}", abs(dtDiff.average()) <= 0.05)
    }
}
