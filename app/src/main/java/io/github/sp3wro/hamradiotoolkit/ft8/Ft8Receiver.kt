package io.github.sp3wro.hamradiotoolkit.ft8

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.dsp.MicInput
import io.github.sp3wro.hamradiotoolkit.dsp.WavPcmReader
import io.github.sp3wro.hamradiotoolkit.dsp.resample
import io.github.sp3wro.hamradiotoolkit.utils.polishPlural
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Jedna zdekodowana wiadomość FT8/FT4 w formacie zbliżonym do WSJT-X. */
data class Ft8Decode(
    val slotStartMs: Long,   // początek slotu UTC (dla pliku: 0, 15 000, …)
    val snr: Int,            // dB w paśmie 2500 Hz (szacunek)
    val dt: Float,           // przesunięcie czasu względem nominalnego startu (0,5 s po początku slotu)
    val freqHz: Int,         // częstotliwość audio najniższego tonu
    val message: Ft8Text,
    val fromFile: Boolean = false,
    val mode: FtxMode = FtxMode.FT8
) {
    val text: String get() = message.text
    val isCq: Boolean get() = message.field2 != null && (message.field1 == "CQ" || message.field1.startsWith("CQ "))

    /** Znak nadawcy (drugie pole), bez nawiasów hasza; null dla tekstu dowolnego i nieznanych haszy. */
    val senderCall: String?
        get() = message.field2?.takeIf { it != "<...>" }?.removePrefix("<")?.removeSuffix(">")

    /** Lokator z wiadomości (np. "CQ SP3WRO JO82") albo null. */
    val grid: String?
        get() = message.field3?.removePrefix("R ")?.takeIf { GRID.matches(it) && it != "RR73" }

    private companion object {
        val GRID = Regex("^[A-R]{2}[0-9]{2}$")
    }
}

/** Dekodowanie pełnego slotu (15 s FT8 / 7,5 s FT4) – pętla z demo/decode_ft8.c (ft8_lib). */
class Ft8SlotDecoder(sampleRate: Int = MicInput.TARGET_RATE, val mode: FtxMode = FtxMode.FT8) {
    private val monitor = Ft8Monitor(sampleRate, mode)
    private val hashTable = Ft8CallsignHashTable()
    // Nadawanie zaczyna się 0,5 s po początku slotu; +1 symbol, bo okno STFT (2 symbole) patrzy symbol wstecz.
    // Zgodność z DT z WSJT-X sprawdzona w Ft8DecoderTest.
    private val nominalStartS = 0.5f + mode.symbolPeriod

    fun decode(samples: FloatArray, count: Int, slotStartMs: Long, fromFile: Boolean = false): List<Ft8Decode> {
        monitor.reset()
        var pos = 0
        while (pos + monitor.blockSize <= count) {
            monitor.process(samples, pos)
            pos += monitor.blockSize
        }
        val wf = monitor.wf
        val candidates = Ft8Decoder.findCandidates(wf, MAX_CANDIDATES, MIN_SCORE)

        val decoded = ArrayList<Ft8RawMessage>()
        val result = ArrayList<Ft8Decode>()
        for (cand in candidates) {
            val raw = Ft8Decoder.decodeCandidate(wf, cand, LDPC_ITERATIONS) ?: continue
            if (decoded.any { it.hash == raw.hash && it.samePayload(raw) }) continue
            if (decoded.size >= MAX_DECODED) break
            decoded += raw

            val text = Ft8MessageUnpacker.decode(raw.payload, hashTable) ?: continue
            val freqHz = (monitor.minBin + cand.freqOffset + cand.freqSub.toFloat() / wf.freqOsr) / mode.symbolPeriod
            val timeSec = (cand.timeOffset + cand.timeSub.toFloat() / wf.timeOsr) * mode.symbolPeriod
            result += Ft8Decode(
                slotStartMs = slotStartMs,
                snr = Ft8Decoder.estimateSnr(wf, cand, raw.codeword),
                dt = timeSec - nominalStartS,
                freqHz = Math.round(freqHz),
                message = text,
                fromFile = fromFile,
                mode = mode
            )
        }
        hashTable.cleanup(10)
        return result.sortedBy { it.freqHz }
    }

    companion object {
        const val MIN_SCORE = 10
        const val MAX_CANDIDATES = 140
        const val LDPC_ITERATIONS = 25
        const val MAX_DECODED = 50
    }
}

/**
 * Nasłuch FT8/FT4 z mikrofonu zsynchronizowany z zegarem telefonu: sloty FT8 zaczynają się o :00, :15, :30, :45 UTC,
 * sloty FT4 co 7,5 s. Zegar telefonu (NTP od operatora) musi być dokładny do ok. ±1 s – widać to w kolumnie DT.
 */
class Ft8Receiver {
    enum class State { IDLE, LISTENING, DECODING_FILE }

    private val mic = MicInput()
    private val decodeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Ft8Decode") }
    // Osobny dekoder na emisję (każdy ma własny wodospad i tablicę haszy znaków); używane tylko w decodeExecutor
    private val slotDecoders = FtxMode.values().associateWith { Ft8SlotDecoder(MicInput.TARGET_RATE, it) }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _mode = MutableStateFlow(FtxMode.FT8)
    val mode: StateFlow<FtxMode> = _mode.asStateFlow()
    private val _decodes = MutableStateFlow<List<Ft8Decode>>(emptyList())
    val decodes: StateFlow<List<Ft8Decode>> = _decodes.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _slotSecond = MutableStateFlow(0f)
    val slotSecond: StateFlow<Float> = _slotSecond.asStateFlow()
    private val _status = MutableStateFlow(AppStrings.get(R.string.dec_ready))
    val status: StateFlow<String> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // Stan bieżącego slotu (wyłącznie w wątku nagrywania)
    private val buffer = FloatArray(slotSamples(FtxMode.FT8))
    private var fill = 0
    private var slotIndex = Long.MIN_VALUE
    private var slotMode = FtxMode.FT8
    private var collecting = false
    private var submitted = false

    fun startListening(): Boolean {
        if (_state.value != State.IDLE) return _state.value == State.LISTENING
        slotIndex = Long.MIN_VALUE
        collecting = false
        val started = mic.start(onChunk = ::onAudio, onStopped = {
            if (_state.value == State.LISTENING) _state.value = State.IDLE
        })
        if (started) {
            _state.value = State.LISTENING
            _status.value = AppStrings.get(R.string.ft8_wait_slot_start)
        }
        return started
    }

    fun stopListening() {
        mic.stop()
        if (_state.value == State.LISTENING) _state.value = State.IDLE
        _level.value = 0f
        _status.value = "Zatrzymano"
    }

    /** Zmiana emisji; w trakcie nasłuchu obowiązuje od następnego slotu. */
    fun setMode(mode: FtxMode) {
        if (_state.value == State.DECODING_FILE) return
        _mode.value = mode
    }

    fun clear() {
        _decodes.value = emptyList()
    }

    /** Dekodowanie nagrania WAV (dowolna częstotliwość próbkowania) w kolejnych odcinkach długości slotu. */
    fun decodeFile(open: () -> InputStream?) {
        if (_state.value != State.IDLE) return
        val mode = _mode.value
        _state.value = State.DECODING_FILE
        _status.value = AppStrings.get(R.string.dec_decoding_file_mode, mode.label)
        decodeExecutor.execute {
            try {
                val audio = open()?.use { WavPcmReader.read(it) } ?: throw IllegalStateException(AppStrings.get(R.string.err_cannot_open_file))
                val samples = resample(audio.samples, audio.sampleRate, MicInput.TARGET_RATE)
                val slotSamples = slotSamples(mode)
                val maxSlots = (MAX_FILE_MS / mode.slotMs).toInt()
                var total = 0
                var start = 0
                var slot = 0
                while (start < samples.size && slot < maxSlots) {
                    val count = minOf(slotSamples, samples.size - start)
                    if (count < slotSamples / 2 && slot > 0) break
                    val chunk = FloatArray(slotSamples)
                    System.arraycopy(samples, start, chunk, 0, count)
                    val result = slotDecoders.getValue(mode).decode(chunk, slotSamples, slot * mode.slotMs, fromFile = true)
                    publish(result)
                    total += result.size
                    start += slotSamples
                    slot++
                }
                _status.value = AppStrings.get(R.string.ft8_file_decoded, mode.label, messagesWord(total))
            } catch (e: Exception) {
                Log.w(TAG, "Błąd dekodowania pliku", e)
                _status.value = AppStrings.get(R.string.dec_file_error, e.message.orEmpty())
            } finally {
                _state.value = State.IDLE
            }
        }
    }

    fun release() {
        mic.stop()
        decodeExecutor.shutdown()
    }

    private fun onAudio(samples: FloatArray, count: Int, endTimeMs: Long) {
        var sum = 0.0
        for (i in 0 until count) sum += samples[i] * samples[i]
        _level.value = (sqrt(sum / count.coerceAtLeast(1)) * 4).toFloat().coerceIn(0f, 1f)

        val mode = _mode.value
        val slotMs = mode.slotMs
        val decodeSamples = decodeSamples(mode)
        // Czas pierwszej próbki bloku (opóźnienie wejścia audio pomijamy – widać je jako stałe DT)
        val startMs = endTimeMs - count * 1000L / MicInput.TARGET_RATE
        val index = Math.floorDiv(startMs, slotMs)
        val inSlotMs = startMs - index * slotMs
        _slotSecond.value = (endTimeMs - index * slotMs) / 1000f

        if (index != slotIndex || mode != slotMode) {
            slotIndex = index
            slotMode = mode
            submitted = false
            fill = 0
            // Jak w ft8_lib: start w pierwszej ćwiartce slotu jeszcze się opłaca, brakujący początek to cisza
            collecting = inSlotMs <= slotMs / 4
            if (collecting) {
                fill = (inSlotMs * MicInput.TARGET_RATE / 1000).toInt()
                buffer.fill(0f, 0, fill)
                _status.value = AppStrings.get(R.string.ft8_receiving, mode.label)
            } else {
                _status.value = AppStrings.get(R.string.ft8_wait_slot_mode, mode.label)
            }
        }
        if (!collecting || submitted) return

        val n = minOf(count, decodeSamples - fill)
        if (n > 0) {
            System.arraycopy(samples, 0, buffer, fill, n)
            fill += n
        }
        if (fill >= decodeSamples) {
            submitted = true
            val copy = buffer.copyOf(decodeSamples)
            val slotStart = slotIndex * slotMs
            _busy.value = true
            decodeExecutor.execute {
                try {
                    val result = slotDecoders.getValue(mode).decode(copy, copy.size, slotStart)
                    publish(result)
                    _status.value = AppStrings.get(R.string.ft8_last_slot, mode.label, messagesWord(result.size))
                } catch (e: Exception) {
                    Log.w(TAG, "Błąd dekodowania slotu", e)
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    private fun publish(result: List<Ft8Decode>) {
        if (result.isEmpty()) return
        // Najnowszy slot na górze; ograniczenie historii
        _decodes.value = (result + _decodes.value).take(MAX_HISTORY)
    }

    private fun messagesWord(n: Int): String = AppStrings.plural(R.plurals.ft8_messages, n, n)

    companion object {
        private const val TAG = "Ft8Receiver"
        private const val MAX_HISTORY = 600
        private const val MAX_FILE_MS = 10 * 60_000L

        private fun slotSamples(mode: FtxMode): Int = (mode.slotTime * MicInput.TARGET_RATE).toInt()

        // Jak w ft8_lib dla odbioru na żywo: slot bez ostatnich 0,4 s
        private fun decodeSamples(mode: FtxMode): Int = ((mode.slotTime - 0.4f) * MicInput.TARGET_RATE).toInt()

        fun dtLabel(dt: Float): String = String.format(java.util.Locale.US, "%+.1f", if (abs(dt) < 0.05f) 0f else dt)
    }
}
