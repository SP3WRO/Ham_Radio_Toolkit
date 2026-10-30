package io.github.sp3wro.hamradiotoolkit.ft8

/*
 * Rozpakowanie 77-bitowych wiadomości FT8 – przeniesienie ft8_lib/ft8/message.c i text.c (MIT).
 * Obsługiwane typy jak w ft8_lib: standardowa (i3 = 1, 2), z niestandardowym znakiem (i3 = 4),
 * tekst dowolny (0.0) i telemetria (0.5). Pozostałe (DXpedycja, Field Day, RTTY RU…) są pomijane.
 */

/** Pola zdekodowanej wiadomości: "do", "od", dodatek (lokator/raport/RR73) albo cały tekst dowolny. */
data class Ft8Text(val field1: String, val field2: String? = null, val field3: String? = null) {
    val text: String
        get() = buildString {
            append(field1)
            if (field2 != null) {
                append(' ').append(field2)
                if (!field3.isNullOrEmpty()) append(' ').append(field3)
            }
        }
}

internal enum class CharTable { FULL, ALPHANUM_SPACE_SLASH, ALPHANUM_SPACE, LETTERS_SPACE, ALPHANUM, NUMERIC }

internal enum class HashType { BITS_22, BITS_12, BITS_10 }

/** Tablica znaków poznanych w poprzednich wiadomościach – do odtwarzania znaków przesłanych jako hasz (<...>). */
internal class Ft8CallsignHashTable {
    private val callsigns = arrayOfNulls<String>(SIZE)
    // 8 najstarszych bitów: wiek wpisu, 22 najmłodsze: hasz
    private val hashes = IntArray(SIZE)

    fun add(callsign: String, hash: Int) {
        val hash10 = (hash ushr 12) and 0x3FF
        var idx = (hash10 * 23) % SIZE
        repeat(SIZE) {
            val existing = callsigns[idx] ?: run {
                callsigns[idx] = callsign.take(11)
                hashes[idx] = hash
                return
            }
            if ((hashes[idx] and 0x3FFFFF) == hash && existing == callsign) {
                hashes[idx] = hashes[idx] and 0x3FFFFF // wyzerowanie wieku
                return
            }
            idx = (idx + 1) % SIZE
        }
    }

    fun lookup(type: HashType, hash: Int): String? {
        val shift = when (type) {
            HashType.BITS_10 -> 12
            HashType.BITS_12 -> 10
            HashType.BITS_22 -> 0
        }
        val hash10 = (hash ushr (12 - shift)) and 0x3FF
        var idx = (hash10 * 23) % SIZE
        repeat(SIZE) {
            val existing = callsigns[idx] ?: return null
            if (((hashes[idx] and 0x3FFFFF) ushr shift) == hash) return existing
            idx = (idx + 1) % SIZE
        }
        return null
    }

    /** Wywoływane po każdym slocie: wpisy starsze niż [maxAge] slotów są usuwane. */
    fun cleanup(maxAge: Int) {
        for (i in 0 until SIZE) {
            if (callsigns[i] == null) continue
            val age = hashes[i] ushr 24
            if (age > maxAge) {
                callsigns[i] = null
                hashes[i] = 0
            } else {
                hashes[i] = ((age + 1) shl 24) or (hashes[i] and 0x3FFFFF)
            }
        }
    }

    private companion object {
        const val SIZE = 256
    }
}

internal object Ft8MessageUnpacker {
    private const val MAX22 = 4194304
    private const val NTOKENS = 2063592
    private const val MAXGRID4 = 32400

    /** null dla typów, których nie obsługujemy (tak jak ft8_lib). */
    fun decode(payload: IntArray, hashTable: Ft8CallsignHashTable): Ft8Text? {
        val i3 = (payload[9] shr 3) and 0x07
        return when (i3) {
            0 -> {
                val n3 = ((payload[8] shl 2) and 0x04) or ((payload[9] shr 6) and 0x03)
                when (n3) {
                    0 -> Ft8Text(decodeFree(payload))
                    5 -> Ft8Text(decodeTelemetryHex(payload))
                    else -> null
                }
            }
            1, 2 -> decodeStd(payload, hashTable)
            4 -> decodeNonstd(payload, hashTable)
            else -> null
        }
    }

    private fun decodeStd(p: IntArray, hashTable: Ft8CallsignHashTable): Ft8Text? {
        var n29a = p[0] shl 21
        n29a = n29a or (p[1] shl 13)
        n29a = n29a or (p[2] shl 5)
        n29a = n29a or (p[3] shr 3)
        var n29b = (p[3] and 0x07) shl 26
        n29b = n29b or (p[4] shl 18)
        n29b = n29b or (p[5] shl 10)
        n29b = n29b or (p[6] shl 2)
        n29b = n29b or (p[7] shr 6)
        val ir = (p[7] and 0x20) shr 5
        var igrid4 = (p[7] and 0x1F) shl 10
        igrid4 = igrid4 or (p[8] shl 2)
        igrid4 = igrid4 or (p[9] shr 6)
        val i3 = (p[9] shr 3) and 0x07

        val callTo = unpack28(n29a ushr 1, n29a and 1, i3, hashTable) ?: return null
        val callDe = unpack28(n29b ushr 1, n29b and 1, i3, hashTable) ?: return null
        val extra = unpackGrid(igrid4, ir)
        return Ft8Text(callTo, callDe, extra)
    }

    // Niestandardowe znaki (kod oryginalnie KD8CEC)
    private fun decodeNonstd(p: IntArray, hashTable: Ft8CallsignHashTable): Ft8Text {
        val n12 = (p[0] shl 4) or (p[1] shr 4)
        var n58 = (p[1] and 0x0F).toLong() shl 54
        n58 = n58 or (p[2].toLong() shl 46)
        n58 = n58 or (p[3].toLong() shl 38)
        n58 = n58 or (p[4].toLong() shl 30)
        n58 = n58 or (p[5].toLong() shl 22)
        n58 = n58 or (p[6].toLong() shl 14)
        n58 = n58 or (p[7].toLong() shl 6)
        n58 = n58 or (p[8].toLong() shr 2)

        val iflip = (p[8] shr 1) and 0x01
        val nrpt = ((p[8] and 0x01) shl 1) or (p[9] shr 7)
        val icq = (p[9] shr 6) and 0x01

        val callDecoded = unpack58(n58, hashTable)
        val call3 = lookupCallsign(hashTable, HashType.BITS_12, n12)
        val call1 = if (iflip != 0) callDecoded else call3
        val call2 = if (iflip != 0) call3 else callDecoded

        return if (icq == 0) {
            val extra = when (nrpt) {
                1 -> "RRR"
                2 -> "RR73"
                3 -> "73"
                else -> ""
            }
            Ft8Text(call1, call2, extra)
        } else {
            Ft8Text("CQ", call2, "")
        }
    }

    private fun telemetry(p: IntArray): IntArray {
        // Przesunięcie o 1 bit w prawo – wyrównanie 71 bitów danych
        val out = IntArray(9)
        var carry = 0
        for (i in 0 until 9) {
            out[i] = ((carry shl 7) or (p[i] shr 1)) and 0xFF
            carry = p[i] and 0x01
        }
        return out
    }

    private fun decodeFree(p: IntArray): String {
        val b71 = telemetry(p)
        val c14 = CharArray(13)
        for (idx in 12 downTo 0) {
            // Dzielenie długiej liczby w b71 przez 42
            var rem = 0
            for (i in 0 until 9) {
                rem = (rem shl 8) or b71[i]
                b71[i] = rem / 42
                rem %= 42
            }
            c14[idx] = charn(rem, CharTable.FULL)
        }
        return String(c14).trim(' ')
    }

    private fun decodeTelemetryHex(p: IntArray): String =
        telemetry(p).joinToString("") { String.format("%02X", it) }

    private fun unpack28(n28In: Int, ip: Int, i3: Int, hashTable: Ft8CallsignHashTable): String? {
        var n28 = n28In
        // Specjalne tokeny: DE, QRZ, CQ, CQ nnn, CQ abcd
        if (n28 < NTOKENS) {
            if (n28 <= 2) return when (n28) {
                0 -> "DE"
                1 -> "QRZ"
                else -> "CQ"
            }
            if (n28 <= 1002) return "CQ " + (n28 - 3).toString().padStart(3, '0')
            if (n28 <= 532443) {
                var n = n28 - 1003
                val aaaa = CharArray(4)
                for (i in 3 downTo 0) {
                    aaaa[i] = charn(n % 27, CharTable.LETTERS_SPACE)
                    if (i == 0) break
                    n /= 27
                }
                return "CQ " + String(aaaa).trimStart(' ')
            }
            return null
        }

        n28 -= NTOKENS
        if (n28 < MAX22) {
            // 22-bitowy hasz znaku
            return lookupCallsign(hashTable, HashType.BITS_22, n28)
        }

        // Znak standardowy
        var n = n28 - MAX22
        val c = CharArray(6)
        c[5] = charn(n % 27, CharTable.LETTERS_SPACE); n /= 27
        c[4] = charn(n % 27, CharTable.LETTERS_SPACE); n /= 27
        c[3] = charn(n % 27, CharTable.LETTERS_SPACE); n /= 27
        c[2] = charn(n % 10, CharTable.NUMERIC); n /= 10
        c[1] = charn(n % 36, CharTable.ALPHANUM); n /= 36
        c[0] = charn(n % 37, CharTable.ALPHANUM_SPACE)
        val callsign = String(c)

        var result = when {
            // Obejście dla prefiksu Eswatini: 3D0XYZ -> 3DA0XYZ
            callsign.startsWith("3D0") && callsign[3] != ' ' -> "3DA0" + callsign.substring(3).trim(' ')
            // Obejście dla prefiksów Gwinei: QA0XYZ -> 3XA0XYZ
            callsign[0] == 'Q' && callsign[1].isAsciiLetter() -> "3X" + callsign.substring(1).trim(' ')
            else -> callsign.trim(' ')
        }
        if (result.length < 3) return null

        if (ip != 0) {
            result += when (i3) {
                1 -> "/R"
                2 -> "/P"
                else -> return null
            }
        }
        saveCallsign(hashTable, result)
        return result
    }

    private fun unpack58(n58In: Long, hashTable: Ft8CallsignHashTable): String {
        var n58 = n58In
        val c11 = CharArray(11)
        for (i in 10 downTo 0) {
            c11[i] = charn((n58 % 38).toInt(), CharTable.ALPHANUM_SPACE_SLASH)
            if (i == 0) break
            n58 /= 38
        }
        val callsign = String(c11).trim(' ')
        if (callsign.length >= 3) saveCallsign(hashTable, callsign)
        return callsign
    }

    private fun unpackGrid(igrid4: Int, ir: Int): String {
        if (igrid4 <= MAXGRID4) {
            var n = igrid4
            val d3 = '0' + n % 10; n /= 10
            val d2 = '0' + n % 10; n /= 10
            val d1 = 'A' + n % 18; n /= 18
            val d0 = 'A' + n % 18
            val grid = "$d0$d1$d2$d3"
            return if (ir > 0) "R $grid" else grid
        }
        return when (val irpt = igrid4 - MAXGRID4) {
            1 -> ""
            2 -> "RRR"
            3 -> "RR73"
            4 -> "73"
            else -> {
                val value = irpt - 35
                val sign = if (value < 0) "-" else "+"
                (if (ir > 0) "R" else "") + sign + kotlin.math.abs(value).toString().padStart(2, '0')
            }
        }
    }

    private fun lookupCallsign(hashTable: Ft8CallsignHashTable, type: HashType, hash: Int): String {
        val found = hashTable.lookup(type, hash) ?: return "<...>"
        return "<$found>"
    }

    /** Hasz 22-bitowy znaku (save_callsign) zapisany w tablicy; false przy niedozwolonym znaku. */
    internal fun saveCallsign(hashTable: Ft8CallsignHashTable?, callsign: String): Boolean {
        var n58 = 0L
        var i = 0
        while (i < callsign.length && i < 11) {
            val j = nchar(callsign[i], CharTable.ALPHANUM_SPACE_SLASH)
            if (j < 0) return false
            n58 = 38 * n58 + j
            i++
        }
        // Dopełnienie spacjami (indeks 0) do 11 znaków
        while (i < 11) {
            n58 *= 38
            i++
        }
        // Mnożenie modulo 2^64 i przesunięcie logiczne – jak uint64_t w C
        val n22 = ((47055833459L * n58) ushr (64 - 22)).toInt() and 0x3FFFFF
        hashTable?.add(callsign, n22)
        return true
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    internal fun charn(cIn: Int, table: CharTable): Char {
        var c = cIn
        if (table != CharTable.ALPHANUM && table != CharTable.NUMERIC) {
            if (c == 0) return ' '
            c -= 1
        }
        if (table != CharTable.LETTERS_SPACE) {
            if (c < 10) return '0' + c
            c -= 10
        }
        if (table != CharTable.NUMERIC) {
            if (c < 26) return 'A' + c
            c -= 26
        }
        if (table == CharTable.FULL) {
            if (c < 5) return "+-./?"[c]
        } else if (table == CharTable.ALPHANUM_SPACE_SLASH) {
            if (c == 0) return '/'
        }
        return '_'
    }

    internal fun nchar(c: Char, table: CharTable): Int {
        var n = 0
        if (table != CharTable.ALPHANUM && table != CharTable.NUMERIC) {
            if (c == ' ') return n
            n += 1
        }
        if (table != CharTable.LETTERS_SPACE) {
            if (c in '0'..'9') return n + (c - '0')
            n += 10
        }
        if (table != CharTable.NUMERIC) {
            if (c in 'A'..'Z') return n + (c - 'A')
            n += 26
        }
        if (table == CharTable.FULL) {
            when (c) {
                '+' -> return n
                '-' -> return n + 1
                '.' -> return n + 2
                '/' -> return n + 3
                '?' -> return n + 4
            }
        } else if (table == CharTable.ALPHANUM_SPACE_SLASH) {
            if (c == '/') return n
        }
        return -1
    }
}
