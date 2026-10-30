package io.github.sp3wro.hamradiotoolkit.sstv.robot36

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Odbiornik SSTV: mikrofon lub plik WAV -> dekoder Robot36. Parametry jak w Robot36:
 * 44100 Hz, 16 bit, mono, bloki po 20 ms, bufor AudioRecord >= 200 ms.
 * Dekodowanie odbywa się w osobnym wątku; dostęp do dekodera i buforów chroni [lock].
 */
class Robot36Receiver {

    enum class State { IDLE, LISTENING, DECODING_FILE }

    private val lock = Any()

    // Te same rozmiary co w MainActivity Robot36
    private val scopeBuffer = PixelBuffer(640, 2 * 1280)
    private val imageBuffer = PixelBuffer(800, 616)

    private var session: Robot36Session? = null
    private var modeName = Robot36Session.AUTO_MODE

    @Volatile private var running = false
    private var worker: Thread? = null

    // Wysokość widocznego fragmentu podglądu; Robot36 dobiera ją do proporcji ekranu (496..1280)
    @Volatile var scopeHeight: Int = MIN_SCOPE_HEIGHT
        set(value) {
            field = value.coerceIn(MIN_SCOPE_HEIGHT, scopeBuffer.height / 2)
            synchronized(lock) { publishScope() }
        }

    private val _scope = MutableStateFlow<Bitmap?>(null)
    val scope: StateFlow<Bitmap?> = _scope.asStateFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _status = MutableStateFlow(AppStrings.get(R.string.dec_ready))
    val status: StateFlow<String> = _status.asStateFlow()

    private val _currentModeName = MutableStateFlow<String?>(null)
    val currentModeName: StateFlow<String?> = _currentModeName.asStateFlow()

    // 0..1 – miernik szczytowy jak w Robot36 (16 segmentów, skala logarytmiczna)
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    // Wywoływane w wątku dekodera dla każdego ukończonego obrazu (autozapis w Robot36)
    @Volatile var onImageDecoded: ((Bitmap) -> Unit)? = null

    fun setMode(name: String) {
        synchronized(lock) {
            modeName = name
            session?.setMode(name)
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (running) return _state.value == State.LISTENING
        val sampleRate = 44100
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            _status.value = AppStrings.get(R.string.r36_unsupported_audio)
            return false
        }
        val bytesPerFrame = 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, channelMask, encoding,
            max(minBuffer, sampleRate / 5 * bytesPerFrame)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _status.value = AppStrings.get(R.string.r36_mic_failed)
            return false
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            _status.value = AppStrings.get(R.string.r36_mic_failed)
            return false
        }

        running = true
        _state.value = State.LISTENING
        _status.value = AppStrings.get(R.string.r36_listening)
        // AudioRecord należy wyłącznie do wątku dekodera – to on go zwalnia po wyjściu z pętli
        worker = Thread({
            try {
                microphoneLoop(record)
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
            }
        }, "Robot36Microphone").also { it.start() }
        return true
    }

    private fun microphoneLoop(record: AudioRecord) {
        val rate = record.sampleRate
        val count = max(1, rate / 50)
        val shorts = ShortArray(count)
        startSession(rate, 1)
        while (running) {
            val n = record.read(shorts, 0, count, AudioRecord.READ_BLOCKING)
            if (!running) break
            if (n < 0) {
                Log.e(TAG, "AudioRecord read failed: $n")
                _status.value = AppStrings.get(R.string.r36_mic_read_error)
                break
            }
            if (n == 0) continue
            consume(FloatArray(n) { shorts[it] / 32768.0f }, preview = true, refreshScopeWithoutNewLines = false)
        }
    }

    /** Dekodowanie pliku WAV (PCM lub float) tak szybko, jak się da – tryb "Turbo" z Robot36. */
    fun decodeFile(openStream: () -> InputStream?) {
        stopListening()
        running = true
        _state.value = State.DECODING_FILE
        _status.value = AppStrings.get(R.string.dec_decoding_file)
        worker = Thread({
            try {
                WavFileReader.open(openStream()).use { reader ->
                    val format = reader.format
                    val channels = format.channels
                    val pcm = FloatArray(max(1, format.sampleRate / 50) * channels)
                    startSession(format.sampleRate, channels)
                    var lastPreviewNanos = 0L
                    while (running) {
                        val frames = reader.read(pcm)
                        if (frames == 0) break
                        val now = System.nanoTime()
                        // Robot36 w trybie Turbo odświeża podgląd najwyżej co 33 ms
                        val preview = lastPreviewNanos == 0L || now - lastPreviewNanos >= 33_000_000L
                        if (preview) lastPreviewNanos = now
                        consume(pcm.copyOf(frames * channels), preview, refreshScopeWithoutNewLines = true)
                    }
                    if (running) {
                        synchronized(lock) {
                            session?.finish()
                            // Zawsze pokazujemy wynik końcowy, nawet jeśli Turbo pominęło podgląd
                            publishScope()
                            publishCompletedImage()
                        }
                        _status.value = AppStrings.get(R.string.r36_file_done)
                    }
                }
            } catch (e: Exception) {
                // IOException z czytnika WAV, ale też SecurityException z dostawcy pliku itp.
                Log.w(TAG, "Błąd dekodowania pliku", e)
                _status.value = AppStrings.get(R.string.dec_file_error, e.message.orEmpty())
            } finally {
                running = false
                _state.value = State.IDLE
            }
        }, "Robot36File").also { it.start() }
    }

    fun stopListening() {
        running = false
        // Czekamy aż wątek skończy bieżący blok (≤ 20 ms audio) i zwolni mikrofon
        worker?.join(1000)
        worker = null
        if (_state.value != State.IDLE) {
            _state.value = State.IDLE
            _status.value = "Zatrzymano"
        }
    }

    /** Zapis całego podglądu (1280 linii) – jak "Store scope" w Robot36. */
    fun captureScope(): Bitmap = synchronized(lock) {
        val width = scopeBuffer.width
        val height = scopeBuffer.height / 2
        val stride = scopeBuffer.width
        val offset = stride * scopeBuffer.line
        val bmp = Bitmap.createBitmap(scopeBuffer.pixels, offset, stride, width, height, Bitmap.Config.ARGB_8888)
        session?.currentMode?.postProcessScopeImage(bmp) ?: bmp
    }

    fun clearScope() {
        synchronized(lock) {
            scopeBuffer.pixels.fill(0)
            publishScope()
        }
    }

    private fun startSession(sampleRate: Int, channels: Int) {
        synchronized(lock) {
            val newSession = Robot36Session(sampleRate, channels, imageBuffer, scopeBuffer, modeName)
            session = newSession
            _currentModeName.value = newSession.currentMode.name
        }
    }

    private fun consume(pcm: FloatArray, preview: Boolean, refreshScopeWithoutNewLines: Boolean) {
        // Miernik liczony przed process(), bo Decoder nadpisuje bufor wartościami częstotliwości
        if (preview) updatePeakMeter(pcm)
        synchronized(lock) {
            val current = session ?: return
            val newLines = current.process(pcm)
            // Ukończenie obrazu sprawdzamy dla każdego bloku, niezależnie od rysowania
            if (newLines) publishCompletedImage()
            if (preview && (newLines || refreshScopeWithoutNewLines)) {
                publishScope()
                _currentModeName.value = current.currentMode.name
            }
        }
    }

    private fun updatePeakMeter(pcm: FloatArray) {
        var peakValue = 0f
        for (v in pcm) peakValue = max(peakValue, abs(v))
        val pixels = PEAK_METER_PIXELS
        var peak = pixels
        if (peakValue > 0) peak = min(max(-Math.PI * ln(peakValue.toDouble()), 0.0), pixels.toDouble()).roundToInt()
        _level.value = (pixels - peak).toFloat() / pixels
    }

    // Wywoływać pod [lock]
    private fun publishScope() {
        val width = scopeBuffer.width
        val height = scopeHeight
        val stride = scopeBuffer.width
        val offset = stride * (scopeBuffer.line + scopeBuffer.height / 2 - height)
        _scope.value = Bitmap.createBitmap(scopeBuffer.pixels, offset, stride, width, height, Bitmap.Config.ARGB_8888)
    }

    // Wywoływać pod [lock]
    private fun publishCompletedImage() {
        val image = session?.takeCompletedImage() ?: return
        val bmp = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        _status.value = "Odebrano obraz ${session?.currentMode?.name}"
        onImageDecoded?.invoke(bmp)
    }

    private companion object {
        const val TAG = "Robot36Receiver"
        const val MIN_SCOPE_HEIGHT = 496
        const val PEAK_METER_PIXELS = 16
    }
}
