package io.github.sp3wro.hamradiotoolkit.cw

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/** Odtwarzanie wygenerowanego CW (trener). AudioTrack należy wyłącznie do wątku odtwarzania. */
class CwPlayer(private val sampleRate: Int = 44100) {
    @Volatile private var playing = false
    private var worker: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun play(pcm: ShortArray, onFinished: () -> Unit = {}) {
        stop()
        playing = true
        worker = Thread({
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minBuffer, sampleRate / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                var offset = 0
                while (playing && offset < pcm.size) {
                    val written = track.write(pcm, offset, min(4096, pcm.size - offset))
                    if (written < 0) break
                    offset += written
                }
                // W trybie strumieniowym stop() dogrywa to, co już jest w buforze
                if (playing) track.stop() else {
                    track.pause()
                    track.flush()
                }
            } catch (e: Exception) {
                Log.w("CwPlayer", "Błąd odtwarzania", e)
            } finally {
                track?.release()
                val finishedNormally = playing
                playing = false
                if (finishedNormally) mainHandler.post(onFinished)
            }
        }, "CwPlayer").also { it.start() }
    }

    fun stop() {
        playing = false
        worker?.join(500)
        worker = null
    }
}

/** Nasłuch CW z mikrofonu -> [CwDecoder]. Parametry audio jak w dekoderze SSTV (44,1 kHz, bloki 20 ms). */
class CwReceiver {
    private val sampleRate = 44100
    @Volatile private var running = false
    private var worker: Thread? = null

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _wpm = MutableStateFlow(0)
    val wpm: StateFlow<Int> = _wpm.asStateFlow()
    private val _toneHz = MutableStateFlow(600.0)
    val toneHz: StateFlow<Double> = _toneHz.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val decoder = CwDecoder(sampleRate, 600.0, 20) { piece ->
        // Ostatnie ~2000 znaków wystarczy na ekranie
        _text.value = (_text.value + piece).takeLast(2000)
    }

    fun setAutoTone(auto: Boolean) {
        decoder.autoTone = auto
    }

    fun setTone(hz: Double) {
        decoder.toneHz = hz
        _toneHz.value = hz
    }

    fun clear() {
        _text.value = ""
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, sampleRate / 5 * 2)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        record.startRecording()
        running = true
        _listening.value = true
        decoder.reset()
        worker = Thread({
            try {
                val buffer = ShortArray(sampleRate / 50)
                while (running) {
                    val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) break
                    if (n == 0) continue
                    decoder.process(buffer, n)
                    _level.value = decoder.signalLevel
                    _wpm.value = decoder.wpm
                    _toneHz.value = decoder.toneHz
                }
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
                _listening.value = false
            }
        }, "CwReceiver").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        worker?.join(1000)
        worker = null
        _listening.value = false
    }
}
