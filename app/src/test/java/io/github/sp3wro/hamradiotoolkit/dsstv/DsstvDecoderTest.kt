package io.github.sp3wro.hamradiotoolkit.dsstv

import io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader
import io.github.sp3wro.hamradiotoolkit.dsp.resample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DsstvDecoderTest {

    @Test
    fun cellMapsHaveExpectedCapacity() {
        // Liczby komórek MSC na ramkę ze specyfikacji HB9TLK ("tot MSC cells")
        val expected = mapOf(
            // A/2,5 kHz: specyfikacja HB9TLK podaje 707, sygnał QSSTV (z poprawioną tablicą pilotów) ma 704
            (DrmRobustness.A to DrmOccupancy.SO_1) to 704, (DrmRobustness.B to DrmOccupancy.SO_1) to 530,
            (DrmRobustness.E to DrmOccupancy.SO_1) to 369, (DrmRobustness.A to DrmOccupancy.SO_0) to 647,
            (DrmRobustness.B to DrmOccupancy.SO_0) to 455, (DrmRobustness.E to DrmOccupancy.SO_0) to 339
        )
        for ((key, cells) in expected) {
            val map = DrmCellMap.of(key.first, key.second)
            println("$key: ${map.mscCellsPerFrame} (spec $cells), FAC/symbol ${map.facPerSymbol.toList()}")
        }
        for ((key, cells) in expected) {
            val map = DrmCellMap.of(key.first, key.second)
            assertEquals("$key", cells, map.mscCellsPerFrame)
            assertEquals(0, map.dummyCells)
        }
        // Przepływność: mode B 2,5 kHz, 16-QAM, ochrona 0 → 1047 bitów; 64-QAM → 1572
        assertEquals(1047, DrmChannelDecoder.mscInfoBits(530, MscCoding(2, true)))
        assertEquals(1572, DrmChannelDecoder.mscInfoBits(530, MscCoding(3, true)))
        assertEquals(627, DrmChannelDecoder.mscInfoBits(530, MscCoding(1, false)))
    }

    @Test
    fun viterbiRoundTrip() {
        val rng = java.util.Random(3)
        for (rate in 0 until 13) {
            val coded = 400
            val info = ConvCode.infoBits(coded, rate)
            val bits = ByteArray(info) { rng.nextInt(2).toByte() }
            val pattern = ConvCode.pattern(info, rate, coded, facTail = false)
            val enc = ConvCode.encode(bits, pattern)
            assertEquals("stopa $rate", coded, enc.size)
            val llr = FloatArray(coded) { if (enc[it].toInt() == 0) 1f else -1f }
            assertTrue(ConvCode.decode(llr, pattern, info).contentEquals(bits))
        }
    }

    private fun wavDir(): File? = System.getProperty("dsstv.wavDir")?.let(::File)?.takeIf { it.isDirectory }

    private fun decodeWav(file: File): Pair<List<IntArray>, List<DrmSyncStatus>> {
        val audio = file.inputStream().use { WavPcmReader.read(it) }
        val samples = resample(audio.samples, audio.sampleRate, 12000)
        val frames = ArrayList<IntArray>()
        val status = ArrayList<DrmSyncStatus>()
        val rx = DrmReceiver(12000, onStatus = { status += it }, onFrame = { b, _ -> frames += b })
        var logs = 0
        rx.debugLog = { if (logs++ < 40) println("  $it") }
        println("${file.name}: ${audio.sampleRate} Hz, ${audio.samples.size} próbek, max ${audio.samples.maxOrNull()}")
        var pos = 0
        while (pos < samples.size) {
            val n = minOf(1200, samples.size - pos)
            rx.process(samples.copyOfRange(pos, pos + n), n)
            pos += n
        }
        return frames to status
    }

    private fun packetCrcOk(p: IntArray): Boolean {
        val crc = DrmCrc.compute(p, 0, p.size - 2, 16)
        return crc == ((p[p.size - 2] shl 8) or p[p.size - 1])
    }

    /**
     * Wektor w repozytorium: własny obrazek 48×32 nadany przez QSSTV-Experimental 10.0
     * (tryb B, 16-QAM, 2,3 kHz), przepróbkowany do 12 kHz mono; wzorzec – plik zapisany przez odbiornik QSSTV.
     */
    @Test
    fun decodesBundledTransmission() {
        val wav = File(javaClass.classLoader!!.getResource("dsstv/hamdrm_b16_12k.wav")!!.toURI())
        val expected = File(javaClass.classLoader!!.getResource("dsstv/hamdrm_b16_expected.jp2")!!.toURI()).readBytes()
        val (files, status, frames) = decodeFiles(wav)
        val fac = status.lastOrNull { it.fac != null }?.fac
        println("Wektor: ramek $frames, pliki ${files.map { "${it.name}:${it.bytes.size}" }}, FAC $fac")
        assertEquals(DrmRobustness.B, status.last { it.fac != null }.mode)
        assertEquals(2, fac!!.qamLevels)
        assertEquals(1, files.size)
        assertTrue(files[0].bytes.contentEquals(expected))
        val img = Jpeg2000Decoder.decode(files[0].bytes)
        assertEquals(48, img.width)
        assertEquals(32, img.height)
        // Gradient: lewy górny róg ciemnoczerwony/czarny, prawy – czerwony
        val right = img.pixels[(16 * 48 + 47) * img.components].toInt() and 0xFF
        val left = img.pixels[(16 * 48) * img.components].toInt() and 0xFF
        assertTrue("R: lewy $left, prawy $right", right - left > 150)
        // Niepełny plik (podgląd w trakcie odbioru): przed danymi kafla – kontrolowany wyjątek, potem – obraz
        try {
            Jpeg2000Decoder.decode(expected.copyOf(60))
        } catch (e: Jpeg2000Exception) {
            // oczekiwane: brak nagłówków kafla
        }
        for (cut in listOf(300, 450, 600)) {
            val part = Jpeg2000Decoder.decode(expected.copyOf(cut))
            assertEquals(48, part.width)
        }
    }

    @Test
    fun reedSolomonCorrectsErasuresAndErrors() {
        val rng = java.util.Random(9)
        for (k in listOf(224, 192, 160, 128)) {
            val rs = ReedSolomon(k)
            val data = IntArray(k) { rng.nextInt(256) }
            val code = rs.encode(data)
            val nroots = 255 - k
            // Wymazania + błędy w granicy 2e + w ≤ n−k
            val erasures = (0 until 255).shuffled(rng).take(nroots / 2).toIntArray()
            val bad = code.copyOf()
            for (e in erasures) bad[e] = rng.nextInt(256)
            val errPos = (0 until 255).filter { it !in erasures }.shuffled(rng).take(nroots / 4 - 1)
            for (e in errPos) bad[e] = bad[e] xor (1 + rng.nextInt(255))
            assertTrue("k=$k", rs.decode(bad, erasures) >= 0)
            assertTrue("k=$k", bad.contentEquals(code))
        }
        // Plik RS2 z brakującymi segmentami
        val file = ByteArray(5410) { (it * 7 + 3).toByte() }
        val enc = RsFileCodec.encode(file, "jp2".let { "rs2" }.let { "rs2" })
        val missing = BooleanArray(enc.size)
        for (i in 1000 until 1000 + 109 * 3) missing[i] = true
        for (i in 4000 until 4109) missing[i] = true
        val damaged = enc.copyOf().also { for (i in it.indices) if (missing[i]) it[i] = 0 }
        val res = RsFileCodec.decode(damaged, "rs2", missing)!!
        assertTrue(res.bytes.contentEquals(file))
    }

    private fun decodeFiles(file: File): Triple<List<DrmFile>, List<DrmSyncStatus>, Int> {
        val audio = file.inputStream().use { WavPcmReader.read(it) }
        val samples = resample(audio.samples, audio.sampleRate, 12000)
        val files = ArrayList<DrmFile>()
        val status = ArrayList<DrmSyncStatus>()
        val data = DrmDataDecoder(onFile = { files += it })
        var frames = 0
        val rx = DrmReceiver(12000, onStatus = { status += it }, onFrame = { b, _ -> frames++; data.pushPacket(b) })
        var pos = 0
        while (pos < samples.size) {
            val n = minOf(1200, samples.size - pos)
            rx.process(samples.copyOfRange(pos, pos + n), n)
            pos += n
        }
        return Triple(files, status, frames)
    }

    @Test
    fun decodesJpeg2000LikeOriginalPicture() {
        val dir = wavDir()
        assumeTrue("Brak -Pdsstv.wavDir", dir != null)
        val jp2 = File(dir!!.parentFile, "out").listFiles()!!.first { it.extension == "jp2" }
        val img = Jpeg2000Decoder.decode(jp2.readBytes())
        val png = javax.imageio.ImageIO.read(File(dir.parentFile, "card160.png"))
        println("JPEG2000: ${img.width}x${img.height}, ${img.components} składowe; oryginał ${png.width}x${png.height}")
        assertEquals(png.width, img.width)
        assertEquals(png.height, img.height)
        var se = 0.0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val rgb = png.getRGB(x, y)
            for (c in 0 until 3) {
                val orig = (rgb shr (16 - 8 * c)) and 0xFF
                val got = img.pixels[(y * img.width + x) * img.components + minOf(c, img.components - 1)].toInt() and 0xFF
                se += (orig - got) * (orig - got).toDouble()
            }
        }
        val psnr = 10 * kotlin.math.log10(255.0 * 255.0 / (se / (img.width * img.height * 3)))
        println("PSNR względem oryginału: %.1f dB".format(psnr))
        assertTrue("PSNR $psnr", psnr > 25)
    }

    /** Kanał: przesunięcie częstotliwości (SSB), różnica zegarów (ppm), szum o zadanym SNR w paśmie 2,5 kHz. */
    private fun impair(samples: FloatArray, rate: Int, offsetHz: Double, ppm: Double, snrDb: Double, seed: Long): FloatArray {
        // Przesunięcie widma: sygnał analityczny przez filtr Hilberta byłby dokładniejszy; tu mnożymy przez cos i
        // usuwamy obraz – wystarczy przemiana jednowstęgowa przez FFT całego nagrania
        val n = Integer.highestOneBit(samples.size - 1) shl 1
        val re = DoubleArray(n) { if (it < samples.size) samples[it].toDouble() else 0.0 }
        val im = DoubleArray(n)
        val fft = io.github.sp3wro.hamradiotoolkit.dsp.MixedRadixFft(n)
        val fr = DoubleArray(n); val fi = DoubleArray(n)
        fft.transform(re, im, fr, fi)
        val shift = Math.round(offsetHz * n / rate).toInt()
        val sr = DoubleArray(n); val si = DoubleArray(n)
        for (k in 1 until n / 2) {
            val t = k + shift
            if (t in 1 until n / 2) { sr[t] = fr[k]; si[t] = fi[k] }
        }
        // Odwrotna FFT przez sprzężenie; część rzeczywista ×2 (tylko dodatnie częstotliwości)
        for (k in 0 until n) si[k] = -si[k]
        fft.transform(sr, si, fr, fi)
        val shifted = FloatArray(samples.size) { (2 * fr[it] / n).toFloat() }
        // Zegar: interpolacja liniowa z krokiem 1 + ppm·1e-6
        val step = 1 + ppm * 1e-6
        val out = FloatArray(((shifted.size - 2) / step).toInt()) { i ->
            val p = i * step
            val j = p.toInt()
            val f = (p - j).toFloat()
            shifted[j] + (shifted[j + 1] - shifted[j]) * f
        }
        // Szum biały: moc sygnału w 2,5 kHz → wariancja szumu w paśmie fs/2
        var power = 0.0
        var cnt = 0
        for (v in out) if (v != 0f) { power += v * v; cnt++ }
        power /= cnt.coerceAtLeast(1)
        val noiseVar = power / Math.pow(10.0, snrDb / 10) * (rate / 2.0) / 2500.0
        val rng = java.util.Random(seed)
        val sigma = kotlin.math.sqrt(noiseVar)
        for (i in out.indices) out[i] += (rng.nextGaussian() * sigma).toFloat()
        return out
    }

    @Test
    fun survivesRealisticChannel() {
        val dir = wavDir()
        assumeTrue("Brak -Pdsstv.wavDir", dir != null)
        val cases = listOf(
            Triple("B4", 0.0, 0.0) to 12.0, Triple("B4", 55.0, 0.0) to 40.0, Triple("B4", 0.0, -100.0) to 40.0,
            Triple("B4", 0.0, -100.0) to 12.0, Triple("E4", 0.0, 0.0) to 10.0, Triple("E4", 0.0, 150.0) to 40.0,
            Triple("B16", 37.0, 0.0) to 25.0, Triple("B16", -120.0, 80.0) to 20.0, Triple("B4", 55.0, -100.0) to 12.0,
            Triple("E4", 180.0, 150.0) to 10.0, Triple("A16", -63.0, 50.0) to 22.0, Triple("B64", 12.0, 30.0) to 20.0
        )
        val failures = ArrayList<String>()
        for ((p, snr) in cases) {
            val (name, offset, ppm) = p
            val audio = File(dir, "$name.wav").inputStream().use { WavPcmReader.read(it) }
            val samples = impair(resample(audio.samples, audio.sampleRate, 12000), 12000, offset, ppm, snr, 7)
            val files = ArrayList<DrmFile>()
            val status = ArrayList<DrmSyncStatus>()
            val data = DrmDataDecoder(onFile = { files += it })
            var crcBad = 0
            var frames = 0
            val trace = StringBuilder()
            val rx = DrmReceiver(12000, onStatus = { status += it }, onFrame = { b, _ ->
                frames++
                if (!data.pushPacket(b)) { crcBad++; trace.append("X") } else trace.append(".")
            })
            val mers = ArrayList<String>()
            rx.debugLog = {
                if (it.startsWith("frame:") && mers.size < 400) mers += it.substringAfter("noise=").substringBefore(" ")
                if (it.startsWith("drift")) trace.append("|")
                if (it.startsWith("frame:")) trace.append("f")
            }
            var pos = 0
            while (pos < samples.size) {
                val n = minOf(1200, samples.size - pos)
                rx.process(samples.copyOfRange(pos, pos + n), n)
                pos += n
            }
            val last = status.lastOrNull { it.fac != null }
            val ok = files.isNotEmpty()
            val merList = mers.mapNotNull { it.toDoubleOrNull() }.map { 10 * kotlin.math.log10(1 / it) }
            println(
                "$name +${offset}Hz ${ppm}ppm SNR $snr dB: plik=${ok} ramki=$frames złe CRC=$crcBad " +
                    "MER mediana=${"%.1f".format(merList.sorted().getOrElse(merList.size / 2) { 0.0 })} fac=${last?.facOkPercent}% " +
                    "odchyłka=${"%.1f".format(last?.frequencyOffsetHz ?: 0.0)} Hz MER=${"%.1f".format(last?.snrDb ?: 0.0)} dB"
            )
            if (!ok) failures += "$name/$offset/$ppm/$snr"
            if (crcBad > 0 || !ok) println("  przebieg (f – ramka FAC, | – przesunięcie okna, . / X – CRC MSC): $trace")
        }
        assertTrue("Nie zdekodowano: $failures", failures.isEmpty())
    }

    @Test
    fun decodesQsstvRecordings() {
        val dir = wavDir()
        assumeTrue("Brak -Pdsstv.wavDir", dir != null)
        val names = dir!!.listFiles { f -> f.extension == "wav" && f.length() > 0 }!!.map { it.nameWithoutExtension }.sorted()
        val failures = ArrayList<String>()
        for (name in names) {
            val start = System.nanoTime()
            val (files, status, frames) = decodeFiles(File(dir, "$name.wav"))
            val last = status.lastOrNull { it.fac != null }
            val ref = File(dir.parentFile, "out_$name").listFiles()?.firstOrNull()
                ?: if (name == "B16") File(dir.parentFile, "out").listFiles()?.firstOrNull() else null
            val got = files.firstOrNull()
            // QSSTV dopisuje do plików RS zera z dopełnienia bloku – porównujemy początek
            val match = ref != null && got != null && ref.readBytes().copyOf(got.bytes.size).contentEquals(got.bytes)
            println(
                "$name: ${last?.mode} ${last?.occupancy} ${last?.fac?.qamLabel} long=${last?.fac?.longInterleave} high=${last?.fac?.highProtection} " +
                    "fac=${last?.facOkPercent}% ramek=$frames pliki=${files.map { "${it.name}:${it.bytes.size}" + (it.rsCorrected?.let { c -> " RS$c" } ?: "") }} " +
                    "wzorzec=${ref?.length()} zgodny=$match czas=${(System.nanoTime() - start) / 1_000_000} ms"
            )
            if (!match) failures += name
        }
        assertTrue("Nie zdekodowano: $failures", failures.isEmpty())
    }
}
