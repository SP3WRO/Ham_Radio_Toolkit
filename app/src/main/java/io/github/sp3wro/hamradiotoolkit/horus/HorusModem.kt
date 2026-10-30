package io.github.sp3wro.hamradiotoolkit.horus

import kotlin.math.ln
import kotlin.math.log10

/** Ramka po korekcji błędów, z poprawnym CRC: treść (bez UW) i wersja formatu (1, 2 albo 3). */
class HorusRawFrame(val payload: IntArray, val version: Int) {
    val hex: String get() = payload.joinToString("") { "%02X".format(it) }
}

/*
 * Odbiornik Horus Binary – przeniesienie src/horus_api.c z horusdemodlib (David Rowe, licencja LGPL-2.1,
 * LICENSE-horusdemodlib.txt), tylko tryb binarny (HORUS_MODE_BINARY_V1, który obsługuje też v2 i v3).
 * Bity z demodulatora trafiają do bufora mieszczącego najdłuższą ramkę; w nowych bitach szukamy słowa
 * synchronizacji "$$" (dopuszczalny 1 błąd), a każdą znalezioną pozycję próbujemy dekodować przy kolejnych
 * wywołaniach, aż zbierze się cała ramka: najpierw 22 bajty (v1), potem 32…128 bajtów (v2 – CRC na końcu,
 * v3 – CRC na początku).
 */
internal class HorusModem(fs: Int, rs: Int = DEFAULT_BAUD, toneSpacing: Int = -1) {
    val fsk = HorusFsk(
        fs = fs, rs = rs, m = 4,
        // Bez podanego odstępu tonów: wartość zastępcza i estymacja po szczytach widma (jak w oryginale)
        fsTx = if (toneSpacing == -1) DEFAULT_TONE_SPACING else toneSpacing
    ).also { it.freqEstType = if (toneSpacing == -1) 0 else 1 }

    private val rs = rs
    private val maxPacketLen = V2_256BIT_NUM_CODED_BITS * 7
    private val rxBitsLen = maxPacketLen + fsk.nbits
    private val rxBits = ByteArray(rxBitsLen)
    private val uw = UW.map { 2 * it - 1 }.toIntArray()
    private val uwThresh = UW.size - 2
    private val uwLoc = IntArray(MAX_UW_TO_TRACK)
    private var uwCount = 0

    val nin: Int get() = fsk.nin

    /** SNR w paśmie 3 kHz (z Eb/No demodulatora). */
    val snrDb: Float get() = fsk.snrEst + 10 * log10(rs.toFloat() * (ln(4.0) / ln(2.0)).toFloat() / 3000f)

    /** Częstotliwości tonów (Hz) używane przez demodulator. */
    val toneFrequencies: FloatArray get() = if (fsk.freqEstType != 0) fsk.f2Est else fsk.fEst

    fun setFreqEstLimits(lower: Int, upper: Int) {
        require(upper > lower)
        fsk.estMin = lower
        fsk.estMax = upper
    }

    /** horus_rx: przyjmuje dokładnie [nin] próbek; zwraca ramkę, jeśli udało się ją zdekodować. */
    fun rx(samples: FloatArray): HorusRawFrame? {
        val nbits = fsk.nbits
        System.arraycopy(rxBits, nbits, rxBits, 0, rxBitsLen - nbits)
        fsk.demodulate(samples, rxBits, rxBitsLen - nbits)

        // Pozycje słów synchronizacji przesuwają się razem z buforem; te, które z niego wypadły, usuwamy
        if (uwCount > 0) {
            val old = uwCount
            uwCount = 0
            for (idx in 0 until old) {
                if (uwLoc[idx] - nbits >= 0 && uwCount < MAX_UW_TO_TRACK) {
                    uwLoc[uwCount++] = uwLoc[idx] - nbits
                }
            }
        }
        findUw()

        for (idx in 0 until uwCount) {
            val loc = uwLoc[idx]
            var frame = extractBinaryV1(loc)
            if (frame == null) {
                for (size in V3_CHECK_SIZES) {
                    frame = extractBinaryV2(loc, size)
                    if (frame != null) break
                }
            }
            if (frame != null) {
                uwLoc[idx] = -1 // ta pozycja jest już obsłużona
                return frame
            }
        }
        return null
    }

    private fun findUw() {
        val n = fsk.nbits + uw.size
        for (i in 0 until n - uw.size) {
            var corr = 0
            for (j in uw.indices) corr += (2 * rxBits[rxBitsLen - n + i + j] - 1) * uw[j]
            if (corr >= uwThresh && uwCount < MAX_UW_TO_TRACK) {
                uwLoc[uwCount++] = rxBitsLen - n + i
            }
        }
    }

    private fun bytesAt(start: Int, count: Int): IntArray = IntArray(count) { b ->
        var byte = 0
        for (j in 0 until 8) byte = (byte shl 1) or rxBits[start + b * 8 + j].toInt()
        byte
    }

    private fun extractBinaryV1(loc: Int): HorusRawFrame? {
        if (loc < 0 || loc + V1_NUM_CODED_BITS > rxBitsLen) return null
        val packet = bytesAt(loc, HorusL2.numTxDataBytes(V1_PAYLOAD_BYTES))
        val payload = HorusL2.decodeRxPacket(packet, V1_PAYLOAD_BYTES)
        val crcRx = HorusL2.crc16(payload, 0, V1_PAYLOAD_BYTES - 2)
        val crcTx = payload[V1_PAYLOAD_BYTES - 2] + (payload[V1_PAYLOAD_BYTES - 1] shl 8)
        return if (crcRx == crcTx) HorusRawFrame(payload, 1) else null
    }

    private fun extractBinaryV2(loc: Int, size: Int): HorusRawFrame? {
        val txBytes = HorusL2.numTxDataBytes(size)
        if (loc < 0 || loc + txBytes * 8 > rxBitsLen) return null
        val payload = HorusL2.decodeRxPacket(bytesAt(loc, txBytes), size)
        // v2: 32 bajty z CRC na końcu
        if (size == V2_PAYLOAD_BYTES) {
            val crcRx = HorusL2.crc16(payload, 0, size - 2)
            val crcTx = payload[size - 2] + (payload[size - 1] shl 8)
            if (crcRx == crcTx) return HorusRawFrame(payload, 2)
        }
        // v3: CRC na początku
        val crcRx = HorusL2.crc16(payload, 2, size - 2)
        val crcTx = payload[0] + (payload[1] shl 8)
        return if (crcRx == crcTx) HorusRawFrame(payload, 3) else null
    }

    companion object {
        const val DEFAULT_BAUD = 100
        const val DEFAULT_TONE_SPACING = 270
        private const val V1_NUM_CODED_BITS = 360
        private const val V1_PAYLOAD_BYTES = 22
        private const val V2_PAYLOAD_BYTES = 32
        private const val V2_256BIT_NUM_CODED_BITS = 520
        private const val MAX_UW_TO_TRACK = 32
        private val V3_CHECK_SIZES = intArrayOf(32, 48, 64, 96, 128)
        // "$$" – słowo synchronizacji v1/v2/v3
        private val UW = intArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0)
    }
}

/** Strumień próbek dowolnej długości → wywołania [HorusModem.rx] po [HorusModem.nin] próbek. */
internal class HorusStream(private val modem: HorusModem) {
    private var buffer = FloatArray(modem.fsk.n * 2)
    private var fill = 0

    /** [onBlock] – po każdym bloku demodulatora (co ok. 0,5 s sygnału), np. do odświeżenia widma. */
    fun process(samples: FloatArray, count: Int, onBlock: () -> Unit = {}, onFrame: (HorusRawFrame) -> Unit) {
        if (fill + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, fill + count))
        System.arraycopy(samples, 0, buffer, fill, count)
        fill += count
        var pos = 0
        while (fill - pos >= modem.nin) {
            val nin = modem.nin
            val block = buffer.copyOfRange(pos, pos + nin)
            pos += nin
            modem.rx(block)?.let(onFrame)
            onBlock()
        }
        System.arraycopy(buffer, pos, buffer, 0, fill - pos)
        fill -= pos
    }
}
