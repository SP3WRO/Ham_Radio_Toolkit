package io.github.sp3wro.hamradiotoolkit.horus

import kotlin.math.floor

/*
 * Warstwa 2 Horus Binary – przeniesienie src/horus_l2.c i src/golay23.c z horusdemodlib
 * (David Rowe, Tomas Härdin; licencja LGPL-2.1, LICENSE-horusdemodlib.txt), wariant zbudowany
 * z -DHORUS_L2_RX -DINTERLEAVER -DSCRAMBLER -DRUN_TIME_TABLES jak horus_demod.
 *
 * Ramka: | UW "$$" | bajty treści | bity parzystości Golay(23,12) |, wszystko po UW przeplecione
 * i zaszyfrowane addytywnie (DVB, 16 bitów). Interleaver i scrambler numerują bity od LSB bajtu.
 */
internal object HorusL2 {
    private const val UW_BYTES = 2
    private const val GOLAY_POLYNOMIAL = 0xC75 // AE3 odwrócone bitowo

    private val decodingTable = IntArray(2048).also { table ->
        // Tablica syndromów dla wszystkich błędów 1-, 2- i 3-bitowych (golay23_init z RUN_TIME_TABLES)
        for (x in 0 until 23) {
            val d = 1 shl x
            table[golay23Syndrome(d)] = d
        }
        for (x in 0 until 22) for (y in x + 1 until 23) {
            val d = (1 shl x) or (1 shl y)
            table[golay23Syndrome(d)] = d
        }
        for (x in 0 until 21) for (y in x + 1 until 22) for (z in y + 1 until 23) {
            val d = (1 shl x) or (1 shl y) or (1 shl z)
            table[golay23Syndrome(d)] = d
        }
    }

    fun golay23Syndrome(cIn: Int): Int {
        var c = cIn
        for (x in 11 downTo 0) {
            if (c and ((1 shl 11) shl x) != 0) c = c xor (GOLAY_POLYNOMIAL shl x)
        }
        return c
    }

    /** Słowo 23-bitowe po korekcji (treść przesunięta o 11 bitów w lewo). */
    fun golay23Decode(c: Int): Int = c xor decodingTable[golay23Syndrome(c)]

    fun golay23Encode(c: Int): Int {
        val shifted = c shl 11
        return golay23Syndrome(shifted) or shifted
    }

    /** Liczba bajtów ramki (z UW) dla [payloadBytes] bajtów treści. */
    fun numTxDataBytes(payloadBytes: Int): Int {
        val payloadBits = payloadBytes * 8
        var codewords = payloadBits / 12
        if (payloadBits % 12 != 0) codewords++
        val txBits = UW_BYTES * 8 + payloadBits + codewords * 11
        var txBytes = txBits / 8
        if (txBits % 8 != 0) txBytes++
        return txBytes
    }

    /**
     * horus_l2_decode_rx_packet: [rx] – odebrana ramka z UW (modyfikowana w miejscu: deszyfracja i rozplot),
     * wynik – [payloadBytes] bajtów treści po korekcji Golay.
     */
    fun decodeRxPacket(rx: IntArray, payloadBytes: Int): IntArray {
        val numTxDataBytes = numTxDataBytes(payloadBytes)
        scramble(rx, UW_BYTES, numTxDataBytes - 2)
        interleave(rx, UW_BYTES, numTxDataBytes - 2, dir = 1)

        val out = IntArray(payloadBytes)
        var pout = 0
        var pin = UW_BYTES + payloadBytes
        fun nextByte(): Int = if (pin < rx.size) rx[pin++] else 0

        val payloadBits = payloadBytes * 8
        var ninbit = 0
        var ingolay = 0
        var ningolay = 0
        var nparitybits = 0
        var paritybyte = nextByte()
        var noutbits = 0
        var outbyte = 0

        while (ninbit < payloadBits) {
            val ninbyte = ninbit / 8 + UW_BYTES
            val shift = 7 - (ninbit % 8)
            val inbit = (rx[ninbyte] shr shift) and 0x1
            ninbit++

            ingolay = ingolay or inbit
            ningolay++
            ingolay = ingolay shl 1

            // Po 12 bitach danych czytamy 11 bitów parzystości i dekodujemy
            if (ningolay % 12 == 0) {
                for (i in 0 until 11) {
                    val pshift = 7 - (nparitybits % 8)
                    val golayparitybit = (paritybyte shr pshift) and 0x1
                    ingolay = ingolay or golayparitybit
                    if (i != 10) ingolay = ingolay shl 1
                    nparitybits++
                    if (nparitybits % 8 == 0) paritybyte = nextByte()
                }
                val outdata = golay23Decode(ingolay) shr 11
                for (i in 0 until 12) {
                    val oshift = 11 - i
                    val outbit = (outdata shr oshift) and 0x1
                    outbyte = outbyte or outbit
                    noutbits++
                    if (noutbits % 8 != 0) {
                        outbyte = outbyte shl 1
                    } else {
                        out[pout++] = outbyte and 0xFF
                        outbyte = 0
                    }
                }
                ingolay = 0
            }
        }

        // Ostatnie, niepełne słowo kodowe
        if (ningolay % 12 != 0) {
            var golayparity = 0
            for (i in 0 until 11) {
                val pshift = 7 - (nparitybits % 8)
                val golayparitybit = (paritybyte shr pshift) and 0x1
                golayparity = golayparity or golayparitybit
                if (i != 10) golayparity = golayparity shl 1
                nparitybits++
                if (nparitybits % 8 == 0) paritybyte = nextByte()
            }
            ingolay = ingolay shr 1
            val codeword = (ingolay shl 12) + golayparity
            val outdata = golay23Decode(codeword) shr 11
            val ntogo = payloadBits - noutbits
            for (i in 0 until ntogo) {
                val oshift = ntogo - i
                val outbit = (outdata shr oshift) and 0x1
                outbyte = outbyte or outbit
                noutbits++
                if (noutbits % 8 != 0) {
                    outbyte = outbyte shl 1
                } else {
                    out[pout++] = outbyte and 0xFF
                    outbyte = 0
                }
            }
        }
        check(pout == payloadBytes)
        return out
    }

    /** horus_l2_encode_tx_packet – nadajnik, potrzebny do testów (ramki wzorcowe). */
    fun encodeTxPacket(payload: IntArray): IntArray {
        val numTx = numTxDataBytes(payload.size)
        val out = IntArray(numTx)
        out[0] = '$'.code
        out[1] = '$'.code
        for (i in payload.indices) out[UW_BYTES + i] = payload[i] and 0xFF
        var pout = UW_BYTES + payload.size

        val payloadBits = payload.size * 8
        var ninbit = 0
        var ingolay = 0
        var ningolay = 0
        var paritybyte = 0
        var nparitybits = 0
        fun writeParity(golayparity: Int) {
            for (i in 0 until 11) {
                val bit = (golayparity shr (10 - i)) and 0x1
                paritybyte = paritybyte or bit
                nparitybits++
                if (nparitybits % 8 != 0) {
                    paritybyte = paritybyte shl 1
                } else {
                    out[pout++] = paritybyte and 0xFF
                    paritybyte = 0
                }
            }
        }
        while (ninbit < payloadBits) {
            val inbit = (payload[ninbit / 8] shr (7 - ninbit % 8)) and 0x1
            ninbit++
            ingolay = ingolay or inbit
            ningolay++
            if (ningolay % 12 != 0) {
                ingolay = ingolay shl 1
            } else {
                writeParity(golay23Syndrome(ingolay shl 11))
                ingolay = 0
            }
        }
        if (ningolay % 12 != 0) {
            ingolay = ingolay shr 1
            writeParity(golay23Syndrome(ingolay shl 12))
        }
        if (nparitybits % 8 != 0) {
            paritybyte = paritybyte shl (7 - nparitybits % 8)
            out[pout++] = paritybyte and 0xFF
        }
        check(pout == numTx)
        interleave(out, UW_BYTES, numTx - 2, dir = 0)
        scramble(out, UW_BYTES, numTx - 2)
        return out
    }

    private fun isPrime(x: Int): Boolean {
        for (i in 2 until x) if (x % i == 0) return false
        return true
    }

    private fun nextPrime(xIn: Int): Int {
        var x = xIn + 1
        while (!isPrime(x)) x++
        return x
    }

    /** Interleaver algebraiczny (Xie i in.): bit i → bit (b·i) mod n; dir = 1 – odwrotny. */
    fun interleave(data: IntArray, start: Int, nbytes: Int, dir: Int) {
        val nbits = nbytes * 8
        val b: Long = when (nbytes) {
            43 -> 337   // Horus v1
            63 -> 389   // Horus v2
            else -> nextPrime(floor(nbits / 1.62).toInt()).toLong()
        }
        val out = IntArray(nbytes)
        for (n in 0 until nbits) {
            var i = n.toLong()
            var j = (b * i) % nbits
            if (dir != 0) {
                val tmp = j
                j = i
                i = tmp
            }
            val ibit = (data[start + (i / 8).toInt()] shr (i % 8).toInt()) and 0x1
            val jbyte = (j / 8).toInt()
            out[jbyte] = out[jbyte] or (ibit shl (j % 8).toInt())
        }
        System.arraycopy(out, 0, data, start, nbytes)
    }

    /** Addytywny scrambler DVB (16 bitów, start 0x4A80 na początku każdej ramki) – działa w obie strony. */
    fun scramble(data: IntArray, start: Int, nbytes: Int) {
        var scrambler = 0x4a80
        for (i in 0 until nbytes * 8) {
            val scramblerOut = ((scrambler and 0x2) shr 1) xor (scrambler and 0x1)
            val ibyte = start + i / 8
            val ishift = i % 8
            val ibit = (data[ibyte] shr ishift) and 0x1
            val ibits = ibit xor scramblerOut
            data[ibyte] = (data[ibyte] and (1 shl ishift).inv()) or (ibits shl ishift)
            scrambler = (scrambler shr 1) or (scramblerOut shl 14)
        }
    }

    /** CRC16-CCITT (start 0xFFFF) – horus_l2_gen_crc16. */
    fun crc16(data: IntArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (k in 0 until length) {
            var x = ((crc shr 8) xor data[offset + k]) and 0xFF
            x = x xor (x shr 4)
            crc = ((crc shl 8) xor (x shl 12) xor (x shl 5) xor x) and 0xFFFF
        }
        return crc
    }
}
