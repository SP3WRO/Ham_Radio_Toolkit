package io.github.sp3wro.hamradiotoolkit.rtty

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import io.github.sp3wro.hamradiotoolkit.dsp.MicInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/** Bieżące parametry odbioru (wykryte automatycznie albo ustawione ręcznie). */
data class RttyTuning(
    val centerHz: Double = 1500.0,
    val shiftHz: Double = 170.0,
    val baud: Double = 45.45,
    val reverse: Boolean = false,
    val locked: Boolean = false
)

/** Nasłuch RTTY z mikrofonu: [RttyEngine] (AFC, przesuw, szybkość, polaryzacja) + widmo do strojenia. */
class RttyReceiver {
    private val mic = MicInput(chunkMs = 50)

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _quality = MutableStateFlow(0f)
    val quality: StateFlow<Float> = _quality.asStateFlow()
    private val _spectrum = MutableStateFlow(FloatArray(0))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()
    private val _tuning = MutableStateFlow(RttyTuning())
    val tuning: StateFlow<RttyTuning> = _tuning.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** Trwa dekodowanie pliku WAV. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _recordingSeconds = MutableStateFlow<Int?>(null)
    /** Długość trwającego nagrania próbki (s) albo null, gdy nie nagrywamy. */
    val recordingSeconds: StateFlow<Int?> = _recordingSeconds.asStateFlow()

    // Nagranie próbki – dopisywane w wątku audio, odbierane w wątku UI
    private val recordLock = Any()
    private var recording: ArrayList<FloatArray>? = null
    private var recordedSamples = 0

    private val engine = RttyEngine(MicInput.TARGET_RATE) { piece ->
        // Ostatnie ~3000 znaków wystarczy na ekranie
        _text.value = (_text.value + piece).takeLast(3000)
    }
    private val decoder get() = engine.decoder

    val binHz: Double get() = engine.spectrum.binHz

    fun setCenter(hz: Double) {
        engine.setCenter(hz)
        publishTuning()
    }

    fun setAutoFrequency(auto: Boolean) { engine.autoFrequency = auto }
    fun setAutoShift(auto: Boolean) { engine.autoShift = auto }
    fun setAutoBaud(auto: Boolean) { decoder.autoBaud = auto }

    fun setAutoPolarity(auto: Boolean) {
        // Przejście na ręczne – zostaje polaryzacja wykryta automatycznie
        if (!auto) decoder.reverse = decoder.activeReverse
        decoder.autoPolarity = auto
        publishTuning()
    }

    fun setShift(hz: Double) {
        decoder.shiftHz = hz
        publishTuning()
    }

    fun setBaud(baud: Double) {
        decoder.baud = baud
        publishTuning()
    }

    fun setReverse(reverse: Boolean) {
        decoder.reverse = reverse
        publishTuning()
    }

    fun setUnshiftOnSpace(enabled: Boolean) { decoder.unshiftOnSpace = enabled }
    fun setSquelch(value: Float) { decoder.squelch = value }

    fun clear() {
        _text.value = ""
    }

    fun start(): Boolean {
        if (_listening.value) return true
        decoder.reset()
        var chunks = 0
        val started = mic.start(onChunk = { samples, count, _ ->
            var sum = 0.0
            for (i in 0 until count) sum += samples[i] * samples[i]
            _level.value = (sqrt(sum / count.coerceAtLeast(1)) * 4).toFloat().coerceIn(0f, 1f)
            synchronized(recordLock) {
                recording?.let { list ->
                    if (recordedSamples < MAX_RECORD_S * MicInput.TARGET_RATE) {
                        list += samples.copyOf(count)
                        recordedSamples += count
                        _recordingSeconds.value = recordedSamples / MicInput.TARGET_RATE
                    }
                }
            }
            engine.process(samples, count)
            _quality.value = decoder.quality
            // Widmo i parametry na ekran ok. 5 razy na sekundę
            if (++chunks % 4 == 0) {
                _spectrum.value = engine.spectrum.spectrum()
                publishTuning()
            }
        }, onStopped = { _listening.value = false })
        _listening.value = started
        return started
    }

    fun stop() {
        mic.stop()
        _listening.value = false
        _level.value = 0f
    }

    /** Początek nagrywania próbki audio (12 kHz, maks. [MAX_RECORD_S] s) – działa w trakcie nasłuchu. */
    fun startRecording() {
        synchronized(recordLock) {
            recording = ArrayList()
            recordedSamples = 0
        }
        _recordingSeconds.value = 0
    }

    /** Koniec nagrywania – zwraca próbki do zapisu jako WAV (12 kHz). */
    fun stopRecording(): FloatArray {
        val chunks = synchronized(recordLock) {
            val list = recording ?: ArrayList()
            recording = null
            list
        }
        _recordingSeconds.value = null
        val out = FloatArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        return out
    }

    /** Dekodowanie nagrania WAV z tą samą automatyką co na żywo (szybciej niż w czasie rzeczywistym). */
    fun decodeFile(open: () -> java.io.InputStream?, onDone: (String?) -> Unit) {
        if (_listening.value || _busy.value) return
        _busy.value = true
        Thread({
            var error: String? = null
            try {
                val audio = open()?.use { io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader.read(it) }
                    ?: throw java.io.IOException(AppStrings.get(R.string.err_cannot_open_file))
                val samples = io.github.sp3wro.hamradiotoolkit.dsp.resample(audio.samples, audio.sampleRate, MicInput.TARGET_RATE)
                decoder.reset()
                val chunk = FloatArray(600)
                var pos = 0
                var n = 0
                while (pos < samples.size) {
                    val count = minOf(chunk.size, samples.size - pos)
                    System.arraycopy(samples, pos, chunk, 0, count)
                    engine.process(chunk, count)
                    pos += count
                    if (++n % 4 == 0) {
                        _spectrum.value = engine.spectrum.spectrum()
                        _quality.value = decoder.quality
                        publishTuning()
                    }
                }
                publishTuning()
            } catch (e: Exception) {
                error = e.message ?: AppStrings.get(R.string.err_file)
            } finally {
                _busy.value = false
                onDone(error)
            }
        }, "RttyFile").start()
    }

    companion object {
        const val MAX_RECORD_S = 120
    }

    private fun publishTuning() {
        _tuning.value = RttyTuning(
            centerHz = decoder.centerHz,
            shiftHz = decoder.shiftHz,
            baud = decoder.baud,
            reverse = if (decoder.autoPolarity) decoder.activeReverse else decoder.reverse,
            locked = engine.locked
        )
    }
}
