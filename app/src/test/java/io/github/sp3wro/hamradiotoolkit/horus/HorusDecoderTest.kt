package io.github.sp3wro.hamradiotoolkit.horus

import io.github.sp3wro.hamradiotoolkit.AppStrings
import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader
import io.github.sp3wro.hamradiotoolkit.dsp.resample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Nagrania wzorcowe z horusdemodlib (samples/) i oczekiwane wyniki z jego testów ctest (CMakeLists.txt).
 * Oryginał przepróbkowuje je do 48 kHz – my dekodujemy w 12 kHz, jak z mikrofonu.
 */
class HorusDecoderTest {

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource("horus/$name")!!.toURI())

    private fun rawS16(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.capacity() / 2) { bytes.getShort(it * 2) / 32768f }
    }

    private fun decodeAll(samples: FloatArray, rate: Int): List<HorusRawFrame> {
        val audio = resample(samples, rate, 12000)
        val modem = HorusModem(12000)
        val frames = ArrayList<HorusRawFrame>()
        HorusStream(modem).process(audio, audio.size) { frames += it }
        return frames
    }

    @Test
    fun golayCorrectsUpToThreeErrors() {
        for (c in listOf(0, 1, 0x555, 0xABC, 0xFFF)) {
            val code = HorusL2.golay23Encode(c)
            assertEquals(c, HorusL2.golay23Decode(code) shr 11)
            assertEquals(c, HorusL2.golay23Decode(code xor (1 shl 3) xor (1 shl 17) xor (1 shl 22)) shr 11)
        }
    }

    @Test
    fun l2RoundTripWithBitErrors() {
        for (size in listOf(22, 32, 48, 128)) {
            val payload = IntArray(size) { (it * 37 + 11) and 0xFF }
            val tx = HorusL2.encodeTxPacket(payload)
            assertEquals(HorusL2.numTxDataBytes(size), tx.size)
            // Seria 8 błędów – interleaver rozkłada ją na różne słowa kodowe
            tx[5] = tx[5] xor 0xFF
            val rx = HorusL2.decodeRxPacket(tx.copyOf(), size)
            assertTrue("rozmiar $size", payload.contentEquals(rx))
        }
    }

    @Test
    fun decodesV1LowSnrRecording() {
        val wav = resource("horus_binary_ebno_4.5db.wav").inputStream().use { WavPcmReader.read(it) }
        val frames = decodeAll(wav.samples, wav.sampleRate)
        println("v1: ${frames.size} ramek: " + frames.joinToString { it.hex })
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.version == 1 && it.hex.endsWith("1C9A9545") })
    }

    @Test
    fun decodesV2Recording() {
        val frames = decodeAll(rawS16(resource("horus_v2_100bd_8000_s16.raw")), 8000)
        println("v2: ${frames.size} ramek: " + frames.joinToString { it.hex })
        assertTrue(frames.any { it.version == 2 && it.hex.endsWith("0102030405060708091DBB") })
    }

    @Test
    fun decodesV3Recording() {
        val frames = decodeAll(rawS16(resource("horus_v3_100bd_8000_s16.raw")), 8000)
        println("v3: ${frames.size} ramek, rozmiary ${frames.map { it.payload.size }}")
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.version == 3 && it.payload.size == 128 })
    }

    private fun bytes(vararg b: Int) = b

    private val customFormats by lazy {
        HorusPayloadLists.parseCustomFields(File("src/main/assets/horus/custom_field_list.json").readText())
    }
    private val payloadIds by lazy {
        HorusPayloadLists.parsePayloadIds(File("src/main/assets/horus/payload_id_list.txt").readText())
    }

    @Test
    fun parsesV1PacketLikeDecoderPy() {
        // Wektor z HorusDecoderTests.test_binary_decoder
        val p = bytes(0x01, 0x12, 0x00, 0x00, 0x00, 0x23, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1C, 0x9A, 0x95, 0x45)
        val t = HorusPacketDecoder.decode(HorusRawFrame(p, 1), payloadIds, customFormats)!!
        assertEquals("HORUSBINARY", t.callsign)
        assertEquals(18, t.sequence)
        assertEquals("00:00:35", t.timeUtc)
        assertEquals(28, t.temperatureC!!.toInt())
        assertEquals(5.0 * 0x9A / 255, t.batteryV!!, 1e-9)
        // Ten sam pakiet z godziną 24 – odrzucony (test_binary_tests_break_fields)
        val bad = p.copyOf().also { it[3] = 0x18 }
        assertEquals(null, HorusPacketDecoder.decode(HorusRawFrame(bad, 1), payloadIds, customFormats))
    }

    @Test
    fun parsesV2PacketWithCustomFields() {
        val p = bytes(0x00, 0x01, 0x02, 0x00, 0x0C, 0x22, 0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0xB4, 0xC6)
        val t = HorusPacketDecoder.decode(HorusRawFrame(p, 2), payloadIds, customFormats)!!
        assertEquals(256, t.payloadId)
        assertEquals("4FSKTEST-V2", t.callsign)
        assertEquals(2, t.sequence)
        assertEquals("12:34:56", t.timeUtc)
        // <hhBHxx: ascent 0x0201/100, ext_temperature 0x0403/10, humidity 5, pressure 0x0706/10
        assertEquals(5.13, t.ascentRateMs!!, 1e-9)
        assertEquals("102.7 °C", t.extra.first { it.first == AppStrings.get(R.string.hp_temp_ext) }.second)
        assertEquals("5 %", t.extra.first { it.first == AppStrings.get(R.string.hp_humidity) }.second)
        assertEquals("179.8 hPa", t.extra.first { it.first == AppStrings.get(R.string.hp_pressure) }.second)
    }

    @Test
    fun parsesV3RecordingWithUper() {
        val frames = decodeAll(rawS16(resource("horus_v3_100bd_8000_s16.raw")), 8000)
        val telemetry = frames.map { HorusPacketDecoder.decode(it, payloadIds, customFormats) }
        assertTrue(telemetry.all { it != null })
        val t = telemetry.first()!!
        // Wartości z horus_gen_test_bits (tryb 4)
        assertEquals("4FSKTEST-V2", t.callsign)
        assertEquals("00:00:03", t.timeUtc)
        assertEquals(0.00023, t.latitude, 1e-9)
        assertEquals(0.00034, t.longitude, 1e-9)
        assertEquals(56, t.altitudeM)
        assertEquals(4, t.extra.count { it == ("meowmeow" to "123") })
        assertEquals("574F4F46".repeat(8), t.extra.first { it.first == "Dane" }.second)
        // Kolejne ramki – kolejne numery
        val seqs = telemetry.map { it!!.sequence }
        assertEquals(seqs.sorted(), seqs.reversed().sorted())
        assertTrue(seqs.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun noFalseFramesOnNoise() {
        val rng = java.util.Random(1)
        val noise = FloatArray(12000 * 60) { (rng.nextGaussian() * 0.1).toFloat() }
        val modem = HorusModem(12000)
        var frames = 0
        HorusStream(modem).process(noise, noise.size) { frames++ }
        assertEquals(0, frames)
    }

    @Test
    fun decodesSyntheticSignalWithFrequencyOffset() {
        // 4FSK 100 Bd, odstęp 270 Hz, najniższy ton 1137 Hz, kilka ramek v2 jak z RS41ng
        val payload = IntArray(32) { 0 }.also {
            it[0] = 0x01; it[1] = 0x01 // ID 257
            it[2] = 42
        }
        val crc = HorusL2.crc16(payload, 0, 30)
        payload[30] = crc and 0xFF
        payload[31] = crc shr 8
        val tx = HorusL2.encodeTxPacket(payload)
        val bits = ArrayList<Int>()
        repeat(4) {
            repeat(16) { bits += listOf(0, 1) } // preambuła
            for (b in tx) for (k in 7 downTo 0) bits += (b shr k) and 1
        }
        val rate = 12000
        val sps = rate / 100
        val out = FloatArray(rate + bits.size / 2 * sps + rate)
        var phase = 0.0
        var pos = rate
        for (s in 0 until bits.size / 2) {
            val sym = bits[2 * s] * 2 + bits[2 * s + 1]
            val f = 1137.0 + 270.0 * sym
            repeat(sps) {
                phase += 2 * PI * f / rate
                out[pos++] = 0.3f * sin(phase).toFloat()
            }
        }
        val rng = java.util.Random(5)
        for (i in out.indices) out[i] += (rng.nextGaussian() * 0.05).toFloat()
        val frames = decodeAll(out, rate)
        assertTrue("zdekodowano ${frames.size}", frames.size >= 3)
        assertTrue(frames.all { it.payload.contentEquals(payload) && it.version == 2 })
    }
}
