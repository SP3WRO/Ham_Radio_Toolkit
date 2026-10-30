package io.github.sp3wro.hamradiotoolkit.sstv.robot36

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class Robot36SessionTest {

    private val rate = 44100

    // Syntetyczny sygnał SSTV z ciągłą fazą (jak w DecoderEndOfInputTest z Robot36)
    private class Signal(val rate: Int) {
        val samples = ArrayList<Float>()
        private var phase = 0.0

        fun sweep(from: Double, to: Double, seconds: Double) {
            val count = (seconds * rate).roundToInt()
            for (i in 0 until count) {
                val f = from + (to - from) * i / count
                samples.add((0.5 * sin(phase)).toFloat())
                phase = (phase + 2 * PI * f / rate) % (2 * PI)
            }
        }

        fun tone(frequency: Double, seconds: Double) = sweep(frequency, frequency, seconds)

        fun header(vis: Int) {
            tone(1900.0, .3); tone(1200.0, .01); tone(1900.0, .3); tone(1200.0, .03)
            val code = vis or ((Integer.bitCount(vis) and 1) shl 7)
            for (i in 0 until 8) tone(if (code and (1 shl i) == 0) 1300.0 else 1100.0, .03)
            tone(1200.0, .03)
        }
    }

    // Robot 36: jasność rośnie od lewej do prawej, chrominancja neutralna (1900 Hz)
    private fun robot36Gradient(): Signal {
        val s = Signal(rate)
        s.header(8)
        for (line in 0 until 240) {
            s.tone(1200.0, .009); s.tone(1500.0, .003)
            s.sweep(1500.0, 2300.0, .088)
            s.tone(if (line % 2 == 0) 1500.0 else 2300.0, .0045); s.tone(1900.0, .0015)
            s.tone(1900.0, .044)
        }
        return s
    }

    // Próbki po kwantyzacji do 16 bitów – takie same, jakie da WAV albo AudioRecord
    private fun quantize(signal: Signal): ShortArray =
        ShortArray(signal.samples.size) { (signal.samples[it] * 32767f).roundToInt().toShort() }

    private fun wavBytes(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size * 2)
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    // Referencja: Decoder karmiony dokładnie jak w MainActivity Robot36 (bloki rate/50, float = short/32768)
    private fun decodeLikeRobot36(pcm: ShortArray): IntArray? {
        val image = PixelBuffer(800, 616)
        val decoder = Decoder(PixelBuffer(640, 2 * 1280), image, "Raw", rate)
        decoder.setMode("Auto")
        var result: IntArray? = null
        fun processImage() {
            if (image.line < image.height) return
            image.line = -1
            result = image.pixels.copyOf(image.width * image.height)
        }
        val block = rate / 50
        var start = 0
        while (start < pcm.size) {
            val frames = minOf(block, pcm.size - start)
            val buffer = FloatArray(frames) { pcm[start + it] / 32768.0f }
            if (decoder.process(buffer, 0)) processImage()
            start += frames
        }
        decoder.finish(0)
        processImage()
        return result
    }

    @Test
    fun wavFilePath_producesPixelIdenticalImageToRobot36() {
        val pcm = quantize(robot36Gradient())
        val reference = decodeLikeRobot36(pcm)
        assertNotNull("Robot36 musi zdekodować obraz", reference)

        // Ścieżka produkcyjna: WAV -> WavFileReader -> Robot36Session (jak Robot36Receiver.decodeFile)
        val images = mutableListOf<CompletedImage>()
        WavFileReader.open(ByteArrayInputStream(wavBytes(pcm))).use { reader ->
            val format = reader.format
            val buffer = FloatArray(maxOf(1, format.sampleRate / 50) * format.channels)
            val session = Robot36Session(
                format.sampleRate, format.channels, PixelBuffer(800, 616), PixelBuffer(640, 2 * 1280),
                Robot36Session.AUTO_MODE
            )
            while (true) {
                val frames = reader.read(buffer)
                if (frames == 0) break
                if (session.process(buffer.copyOf(frames * format.channels))) {
                    session.takeCompletedImage()?.let { images.add(it) }
                }
            }
            session.finish()
            session.takeCompletedImage()?.let { images.add(it) }
            assertEquals("Robot 36 Color", session.currentMode.name)
        }

        assertEquals(1, images.size)
        val image = images.single()
        assertEquals(320, image.width)
        assertEquals(240, image.height)
        assertArrayEquals(reference, image.pixels)

        // Sanity: gradient jasności od lewej do prawej, szarość (R≈G≈B)
        val row = 120 * image.width
        val left = image.pixels[row + 10] and 0xff
        val right = image.pixels[row + 310] and 0xff
        assertTrue("lewa krawędź ciemna ($left)", left < 60)
        assertTrue("prawa krawędź jasna ($right)", right > 190)
        val mid = image.pixels[row + 160]
        val r = mid shr 16 and 0xff
        val g = mid shr 8 and 0xff
        val b = mid and 0xff
        assertTrue("szarość: $r/$g/$b", maxOf(r, g, b) - minOf(r, g, b) < 20)
    }

    @Test
    fun everyModeName_matchesRobot36Decoder() {
        for (name in Robot36Session.MODE_NAMES) {
            val session = Robot36Session(rate, 1, PixelBuffer(800, 616), PixelBuffer(640, 2 * 1280), name)
            assertEquals(name, session.currentMode.name)
        }
    }
}
