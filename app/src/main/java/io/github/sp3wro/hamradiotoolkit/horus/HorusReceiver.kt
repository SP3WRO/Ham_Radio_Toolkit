package io.github.sp3wro.hamradiotoolkit.horus

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.dsp.MicInput
import io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader
import io.github.sp3wro.hamradiotoolkit.dsp.resample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import kotlin.math.log10
import kotlin.math.sqrt

/** Odebrana ramka: telemetria (null, gdy treści nie dało się rozpakować) i parametry odbioru. */
data class HorusFrameEntry(
    val receivedMs: Long,
    val raw: HorusRawFrame,
    val telemetry: HorusTelemetry?,
    val snrDb: Float,
    val fromFile: Boolean,
    /** Prędkość wznoszenia policzona z poprzedniej ramki tego samego balonu (m/s). */
    val computedAscentMs: Double? = null
) {
    val ascentRateMs: Double? get() = telemetry?.ascentRateMs ?: computedAscentMs
}

/** Nasłuch Horus Binary (4FSK 100 Bd) z mikrofonu albo z pliku WAV. */
class HorusReceiver {
    private val mic = MicInput(chunkMs = 100)
    private var modem = newModem()
    private var stream = HorusStream(modem)

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _busy = MutableStateFlow(false)
    /** Trwa dekodowanie pliku WAV. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _snrDb = MutableStateFlow<Float?>(null)
    val snrDb: StateFlow<Float?> = _snrDb.asStateFlow()
    private val _tones = MutableStateFlow(FloatArray(0))
    /** Częstotliwości czterech tonów znalezione przez demodulator (Hz). */
    val tones: StateFlow<FloatArray> = _tones.asStateFlow()
    private val _spectrum = MutableStateFlow(FloatArray(0))
    /** Widmo 0…[SPECTRUM_MAX_HZ] Hz w dB względem maksimum (do wykresu strojenia). */
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()
    private val _frames = MutableStateFlow<List<HorusFrameEntry>>(emptyList())
    /** Odebrane ramki, najnowsza pierwsza. */
    val frames: StateFlow<List<HorusFrameEntry>> = _frames.asStateFlow()

    @Volatile var lists: HorusPayloadLists.Data? = null

    fun start(): Boolean {
        if (_listening.value || _busy.value) return _listening.value
        reset()
        val started = mic.start(onChunk = { samples, count, _ ->
            var sum = 0.0
            for (i in 0 until count) sum += samples[i] * samples[i]
            _level.value = (sqrt(sum / count.coerceAtLeast(1)) * 4).toFloat().coerceIn(0f, 1f)
            stream.process(samples, count, onBlock = ::publishModemState) { onFrame(it, fromFile = false) }
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
        _frames.value = emptyList()
    }

    /** Dekodowanie nagrania WAV (dowolne próbkowanie) – szybciej niż w czasie rzeczywistym. */
    fun decodeFile(open: () -> InputStream?, onDone: (found: Int, error: String?) -> Unit) {
        if (_listening.value || _busy.value) return
        _busy.value = true
        Thread({
            var error: String? = null
            var found = 0
            try {
                val audio = open()?.use { WavPcmReader.read(it) } ?: throw java.io.IOException(AppStrings.get(R.string.err_cannot_open_file))
                val samples = resample(audio.samples, audio.sampleRate, MicInput.TARGET_RATE)
                reset()
                val chunk = FloatArray(MicInput.TARGET_RATE / 10)
                var pos = 0
                while (pos < samples.size) {
                    val count = minOf(chunk.size, samples.size - pos)
                    System.arraycopy(samples, pos, chunk, 0, count)
                    stream.process(chunk, count, onBlock = ::publishModemState) {
                        onFrame(it, fromFile = true)
                        found++
                    }
                    pos += count
                }
            } catch (e: Exception) {
                Log.w(TAG, "Błąd dekodowania pliku", e)
                error = e.message ?: AppStrings.get(R.string.err_file)
            } finally {
                _busy.value = false
                onDone(found, error)
            }
        }, "HorusFile").start()
    }

    private fun reset() {
        modem = newModem()
        stream = HorusStream(modem)
        _snrDb.value = null
        _tones.value = FloatArray(0)
        _spectrum.value = FloatArray(0)
    }

    private fun onFrame(frame: HorusRawFrame, fromFile: Boolean) {
        val l = lists
        val telemetry = HorusPacketDecoder.decode(frame, l?.payloadIds.orEmpty(), l?.customFormats.orEmpty())
        val now = System.currentTimeMillis()
        val history = _frames.value
        val entry = HorusFrameEntry(
            receivedMs = now,
            raw = frame,
            telemetry = telemetry,
            snrDb = modem.snrDb,
            fromFile = fromFile,
            computedAscentMs = telemetry?.let { computeAscent(it, now, fromFile, history) }
        )
        _frames.value = (listOf(entry) + history).take(MAX_HISTORY)
    }

    // Z poprzedniej ramki tego samego balonu: najpierw czas z ładunku, a bez niego czas odbioru
    private fun computeAscent(t: HorusTelemetry, now: Long, fromFile: Boolean, history: List<HorusFrameEntry>): Double? {
        val alt = t.altitudeM ?: return null
        val prev = history.firstOrNull { it.telemetry?.callsign == t.callsign && it.telemetry?.altitudeM != null } ?: return null
        val prevT = prev.telemetry!!
        val dt = secondsBetween(prevT.timeUtc, t.timeUtc)
            ?: if (!fromFile && !prev.fromFile) (now - prev.receivedMs) / 1000.0 else return null
        if (dt < 1 || dt > 600) return null
        return (alt - prevT.altitudeM!!) / dt
    }

    private fun secondsBetween(a: String?, b: String?): Double? {
        fun parse(s: String?): Int? = s?.split(':')?.takeIf { it.size == 3 }?.let { p ->
            val (h, m, sec) = p.map { it.toIntOrNull() ?: return null }
            h * 3600 + m * 60 + sec
        }
        val ta = parse(a) ?: return null
        val tb = parse(b) ?: return null
        var d = tb - ta
        if (d < -43200) d += 86400 // przejście przez północ
        return d.toDouble()
    }

    private fun publishModemState() {
        _snrDb.value = modem.snrDb
        _tones.value = modem.toneFrequencies.copyOf()
        val fsk = modem.fsk
        val binHz = fsk.fs.toFloat() / fsk.ndft
        val bins = (SPECTRUM_MAX_HZ / binHz).toInt()
        val points = SPECTRUM_POINTS
        val out = FloatArray(points)
        var peak = 1e-12f
        for (i in 0 until points) {
            var v = 0f
            val from = fsk.ndft / 2 + i * bins / points
            val to = fsk.ndft / 2 + (i + 1) * bins / points
            for (b in from until maxOf(to, from + 1)) v = maxOf(v, fsk.sf[b])
            out[i] = v
            peak = maxOf(peak, v)
        }
        for (i in 0 until points) out[i] = (20 * log10(out[i] / peak + 1e-6f)).coerceAtLeast(-60f)
        _spectrum.value = out
    }

    companion object {
        private const val TAG = "HorusReceiver"
        private const val MAX_HISTORY = 500
        const val SPECTRUM_MAX_HZ = 4000f
        private const val SPECTRUM_POINTS = 200
        /** Zakres szukania tonów – głos i przydźwięk poniżej 100 Hz oraz szum powyżej 4 kHz nie przeszkadzają. */
        const val EST_MIN_HZ = 100
        const val EST_MAX_HZ = 4000

        private fun newModem() = HorusModem(MicInput.TARGET_RATE).apply { setFreqEstLimits(EST_MIN_HZ, EST_MAX_HZ) }
    }
}
