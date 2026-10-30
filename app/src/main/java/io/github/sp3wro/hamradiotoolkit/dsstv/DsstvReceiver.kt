package io.github.sp3wro.hamradiotoolkit.dsstv

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.dsp.MicInput
import io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader
import io.github.sp3wro.hamradiotoolkit.dsp.resample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** Odebrany obrazek DSSTV. */
class DsstvPicture(
    val name: String,
    val bitmap: Bitmap?,
    val bytes: ByteArray,
    val extension: String,
    val callsign: String,
    val receivedMs: Long,
    val rsCorrected: Int?
)

/** Odbiór DSSTV (HamDRM) z mikrofonu albo z pliku WAV. */
class DsstvReceiver {
    private val mic = MicInput(chunkMs = 100)
    private val imageExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "DsstvImage") }

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _sync = MutableStateFlow(DrmSyncStatus(false))
    val sync: StateFlow<DrmSyncStatus> = _sync.asStateFlow()
    private val _progress = MutableStateFlow<DrmFileProgress?>(null)
    val progress: StateFlow<DrmFileProgress?> = _progress.asStateFlow()
    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** Podgląd obrazka w trakcie odbioru. */
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()
    private val _pictures = MutableStateFlow<List<DsstvPicture>>(emptyList())
    val pictures: StateFlow<List<DsstvPicture>> = _pictures.asStateFlow()
    private val _callsign = MutableStateFlow("")
    /** Etykieta nadawcy z FAC (do 9 znaków, po 3 w ramce). */
    val callsign: StateFlow<String> = _callsign.asStateFlow()

    /** Wywoływane dla każdego kompletnego obrazka (np. autozapis). */
    @Volatile var onPicture: ((DsstvPicture) -> Unit)? = null

    private val label = CharArray(9) { ' ' }
    private lateinit var receiver: DrmReceiver
    private lateinit var data: DrmDataDecoder

    init {
        reset()
    }

    private fun reset() {
        for (i in label.indices) label[i] = ' '
        data = DrmDataDecoder(
            onProgress = { _progress.value = it },
            onFile = { file -> imageExecutor.execute { deliver(file) } },
            onPartial = { name, prefix -> imageExecutor.execute { decodePreview(name, prefix) } }
        )
        receiver = DrmReceiver(
            MicInput.TARGET_RATE,
            onStatus = { status ->
                _sync.value = status
                status.fac?.let { fac ->
                    for (i in 0 until 3) {
                        val pos = fac.frameId * 3 + i
                        if (pos < label.size) label[pos] = fac.labelPart.getOrElse(i) { ' ' }
                    }
                    _callsign.value = String(label).trim()
                }
            },
            onFrame = { bytes, _ -> data.pushPacket(bytes) }
        )
    }

    fun start(): Boolean {
        if (_listening.value || _busy.value) return _listening.value
        reset()
        val started = mic.start(onChunk = { samples, count, _ ->
            var sum = 0.0
            for (i in 0 until count) sum += samples[i] * samples[i]
            _level.value = (sqrt(sum / count.coerceAtLeast(1)) * 4).toFloat().coerceIn(0f, 1f)
            try {
                receiver.process(samples, count)
            } catch (e: Exception) {
                Log.w(TAG, "Błąd odbiornika DRM", e)
                reset()
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

    fun clear() {
        _pictures.value = emptyList()
        _preview.value = null
        _progress.value = null
    }

    fun release() {
        stop()
        imageExecutor.shutdown()
    }

    /** Dekodowanie nagrania WAV (dowolne próbkowanie) – szybciej niż w czasie rzeczywistym. */
    fun decodeFile(open: () -> InputStream?, onDone: (found: Int, error: String?) -> Unit) {
        if (_listening.value || _busy.value) return
        _busy.value = true
        Thread({
            var error: String? = null
            val before = _pictures.value.size
            try {
                val audio = open()?.use { WavPcmReader.read(it) } ?: throw java.io.IOException(AppStrings.get(R.string.err_cannot_open_file))
                val samples = resample(audio.samples, audio.sampleRate, MicInput.TARGET_RATE)
                reset()
                val chunk = FloatArray(MicInput.TARGET_RATE / 10)
                var pos = 0
                while (pos < samples.size) {
                    val count = minOf(chunk.size, samples.size - pos)
                    System.arraycopy(samples, pos, chunk, 0, count)
                    receiver.process(chunk, count)
                    pos += count
                }
                // Poczekaj na dekodowanie obrazków w tle
                imageExecutor.submit {}.get()
            } catch (e: Exception) {
                Log.w(TAG, "Błąd dekodowania pliku", e)
                error = e.message ?: AppStrings.get(R.string.err_file)
            } finally {
                _busy.value = false
                onDone(_pictures.value.size - before, error)
            }
        }, "DsstvFile").start()
    }

    private fun deliver(file: DrmFile) {
        val bitmap = toBitmap(file.extension, file.bytes)
        val picture = DsstvPicture(
            file.name, bitmap, file.bytes, file.extension, _callsign.value, System.currentTimeMillis(), file.rsCorrected
        )
        _pictures.value = listOf(picture) + _pictures.value.take(MAX_PICTURES - 1)
        _preview.value = null
        _progress.value = null
        onPicture?.invoke(picture)
    }

    private fun decodePreview(name: String, prefix: ByteArray) {
        val ext = name.substringAfterLast('.', "").lowercase()
        // Niepełny JPEG 2000 dekoduje się do obrazu niższej jakości; inne formaty – dopiero w całości
        if (ext != "jp2" && ext != "j2k") return
        toBitmap(ext, prefix)?.let { _preview.value = it }
    }

    companion object {
        private const val TAG = "DsstvReceiver"
        private const val MAX_PICTURES = 30

        fun toBitmap(extension: String, bytes: ByteArray): Bitmap? = try {
            when (extension.lowercase()) {
                "jp2", "j2k", "jpc" -> {
                    val img = Jpeg2000Decoder.decode(bytes)
                    val px = IntArray(img.width * img.height) { i ->
                        val p = i * img.components
                        val r = img.pixels[p].toInt() and 0xFF
                        val g = if (img.components >= 3) img.pixels[p + 1].toInt() and 0xFF else r
                        val b = if (img.components >= 3) img.pixels[p + 2].toInt() and 0xFF else r
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
                }
                // JPEG, PNG, GIF, BMP, WebP; AVIF od Androida 12
                else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się zdekodować obrazka .$extension", e)
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
