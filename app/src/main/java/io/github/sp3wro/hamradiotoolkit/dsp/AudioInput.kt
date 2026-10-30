package io.github.sp3wro.hamradiotoolkit.dsp

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Nasłuch z mikrofonu dla dekoderów cyfrowych (FT8, RTTY): próbki zawsze w [TARGET_RATE] = 12 kHz.
 * Najpierw próbujemy nagrywać od razu w 12 kHz; jeśli urządzenie tego nie obsługuje – 48 kHz z decymacją ×4.
 */
class MicInput(private val chunkMs: Int = 100) {
    @Volatile private var running = false
    private var worker: Thread? = null

    /** [onChunk] wołane w wątku nagrywania z próbkami −1..1 i znacznikiem czasu końca bloku (ms, zegar systemowy). */
    @SuppressLint("MissingPermission")
    fun start(onChunk: (samples: FloatArray, count: Int, endTimeMs: Long) -> Unit, onStopped: () -> Unit = {}): Boolean {
        if (running) return true
        val (record, rate) = open(TARGET_RATE) ?: open(48000) ?: return false
        val decimator = if (rate == TARGET_RATE) null else Decimator(rate / TARGET_RATE, rate)
        record.startRecording()
        running = true
        worker = Thread({
            try {
                val raw = ShortArray(rate * chunkMs / 1000)
                val out = FloatArray(raw.size)
                while (running) {
                    val n = record.read(raw, 0, raw.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) break
                    if (n == 0) continue
                    val now = System.currentTimeMillis()
                    for (i in 0 until n) out[i] = raw[i] / 32768f
                    val count = decimator?.process(out, n) ?: n
                    onChunk(out, count, now)
                }
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
                running = false
                onStopped()
            }
        }, "MicInput").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
    }

    @SuppressLint("MissingPermission")
    private fun open(rate: Int): Pair<AudioRecord, Int>? {
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, rate / 2 * 2)
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) record to rate else {
                record.release()
                null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val TARGET_RATE = 12000
    }
}

/** Filtr dolnoprzepustowy (okno Blackmana, odcięcie 5,5 kHz) + decymacja w strumieniu. */
class Decimator(private val factor: Int, inputRate: Int) {
    private val taps = lowpassTaps(5500.0 / inputRate, 16 * factor + 1)
    private val history = FloatArray(taps.size)
    private var historyPos = 0
    private var phase = 0

    /** Filtruje [count] próbek z [buffer] i zapisuje wynik na jego początek; zwraca liczbę próbek wyjściowych. */
    fun process(buffer: FloatArray, count: Int): Int {
        var outCount = 0
        for (i in 0 until count) {
            history[historyPos] = buffer[i]
            historyPos = (historyPos + 1) % history.size
            phase++
            if (phase == factor) {
                phase = 0
                var acc = 0f
                var idx = historyPos
                for (t in taps.indices) {
                    acc += taps[t] * history[idx]
                    idx = (idx + 1) % history.size
                }
                buffer[outCount++] = acc
            }
        }
        return outCount
    }
}

internal fun lowpassTaps(cutoffRatio: Double, length: Int): FloatArray {
    val m = length - 1
    val taps = DoubleArray(length) { i ->
        val x = i - m / 2.0
        val sinc = if (x == 0.0) 2 * cutoffRatio else sin(2 * PI * cutoffRatio * x) / (PI * x)
        val window = 0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m)
        sinc * window
    }
    val sum = taps.sum()
    return FloatArray(length) { (taps[it] / sum).toFloat() }
}

/**
 * Zmiana częstotliwości próbkowania całego nagrania: w dół – filtr + interpolacja liniowa,
 * w górę – interpolacja sinc z oknem (liniowa zostawiała lustrzane kopie widma powyżej starej Nyquista).
 */
fun resample(samples: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
    if (inputRate == outputRate) return samples
    if (outputRate > inputRate) return upsampleSinc(samples, inputRate, outputRate)
    val source = if (inputRate > outputRate) {
        val taps = lowpassTaps(0.45 * outputRate / inputRate, 63)
        val half = taps.size / 2
        FloatArray(samples.size) { i ->
            var acc = 0f
            for (t in taps.indices) {
                val j = i + t - half
                if (j in samples.indices) acc += taps[t] * samples[j]
            }
            acc
        }
    } else samples
    val outLength = (samples.size.toLong() * outputRate / inputRate).toInt()
    val step = inputRate.toDouble() / outputRate
    return FloatArray(outLength) { i ->
        val pos = i * step
        val j = pos.toInt()
        val frac = (pos - j).toFloat()
        val a = source[j.coerceAtMost(source.size - 1)]
        val b = source[(j + 1).coerceAtMost(source.size - 1)]
        a + (b - a) * frac
    }
}

// Pasmo przepustowe do 0,45·fs wejścia, okno Blackmana na ±16 próbek wejściowych
private fun upsampleSinc(samples: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
    val half = 16
    val cutoff = 0.9
    val outLength = (samples.size.toLong() * outputRate / inputRate).toInt()
    val step = inputRate.toDouble() / outputRate
    return FloatArray(outLength) { i ->
        val pos = i * step
        val center = floor(pos).toInt()
        var acc = 0.0
        for (k in center - half + 1..center + half) {
            if (k < 0 || k >= samples.size) continue
            val x = pos - k
            val sinc = if (abs(x) < 1e-9) cutoff else sin(PI * cutoff * x) / (PI * x)
            val w = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)
            acc += samples[k] * sinc * w
        }
        acc.toFloat()
    }
}

/** Zapis próbek −1..1 jako WAV PCM 16-bit mono. */
object WavPcmWriter {
    fun write(out: java.io.OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1) // PCM, mono
            putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }
        out.write(header.array())
        val body = java.nio.ByteBuffer.allocate(dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) body.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        out.write(body.array())
    }
}

/** Minimalny czytnik WAV PCM 16-bit (mono lub wiele kanałów – brany jest pierwszy kanał). */
object WavPcmReader {
    class Audio(val samples: FloatArray, val sampleRate: Int)

    fun read(stream: InputStream): Audio {
        val input = DataInputStream(stream.buffered())
        if (readTag(input) != "RIFF") throw IOException(AppStrings.get(R.string.err_not_wav))
        readIntLe(input)
        if (readTag(input) != "WAVE") throw IOException(AppStrings.get(R.string.err_not_wav))
        var channels = 1
        var sampleRate = 0
        var bits = 16
        while (true) {
            val tag = try {
                readTag(input)
            } catch (e: EOFException) {
                throw IOException(AppStrings.get(R.string.err_wav_no_audio))
            }
            val size = readIntLe(input)
            when (tag) {
                "fmt " -> {
                    val format = readShortLe(input)
                    channels = readShortLe(input)
                    sampleRate = readIntLe(input)
                    readIntLe(input)
                    readShortLe(input)
                    bits = readShortLe(input)
                    if (format != 1 && format != 0xFFFE) throw IOException(AppStrings.get(R.string.err_wav_pcm))
                    input.skipBytes(size - 16)
                }
                "data" -> {
                    if (bits != 16) throw IOException(AppStrings.get(R.string.err_wav_16bit))
                    if (sampleRate <= 0) throw IOException(AppStrings.get(R.string.err_wav_fmt))
                    val frameBytes = 2 * channels
                    val frames = if (size > 0) size / frameBytes else Int.MAX_VALUE
                    val out = FloatArrayBuilder()
                    val frame = ByteArray(frameBytes)
                    var read = 0
                    while (read < frames) {
                        try {
                            input.readFully(frame)
                        } catch (e: EOFException) {
                            break
                        }
                        val value = (frame[0].toInt() and 0xFF) or (frame[1].toInt() shl 8)
                        out.add(value.toShort() / 32768f)
                        read++
                    }
                    return Audio(out.toArray(), sampleRate)
                }
                else -> input.skipBytes(size + (size and 1))
            }
        }
    }

    private fun readTag(input: DataInputStream): String {
        val b = ByteArray(4)
        input.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun readIntLe(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(input: DataInputStream): Int {
        val b = ByteArray(2)
        input.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
