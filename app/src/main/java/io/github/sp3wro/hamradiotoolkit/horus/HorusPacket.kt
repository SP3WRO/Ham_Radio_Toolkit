package io.github.sp3wro.hamradiotoolkit.horus

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import java.util.Locale
import kotlin.math.abs

/** Telemetria balonu z jednej ramki Horus Binary. */
data class HorusTelemetry(
    val version: Int,
    /** Numer z listy Projektu Horus (v1/v2); w v3 znak jest w samej ramce. */
    val payloadId: Int?,
    val callsign: String,
    val sequence: Int,
    /** Czas UTC z ładunku "HH:MM:SS" albo null, gdy nieznany. */
    val timeUtc: String?,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Int?,
    val speedKmh: Int? = null,
    val satellites: Int? = null,
    val temperatureC: Double? = null,
    val batteryV: Double? = null,
    /** Prędkość wznoszenia nadana przez ładunek (m/s), jeśli ją podaje. */
    val ascentRateMs: Double? = null,
    /** Pozostałe pola: etykieta → wartość z jednostką. */
    val extra: List<Pair<String, String>> = emptyList(),
    val raw: String = ""
) {
    val hasPosition: Boolean get() = !(latitude == 0.0 && longitude == 0.0)
}

/** Opis 9 bajtów pól dodatkowych ramki v2 (custom_field_list.json). */
data class HorusCustomFormat(val struct: String, val fields: List<Pair<String, String>>)

/**
 * Rozpakowanie treści ramki – formaty i walidacja jak w horusdemodlib/decoder.py i delegates.py
 * (struktury v1/v2 little-endian, v3 w ASN.1 UPER wg horusbinaryv3/HorusBinaryV3.asn1).
 */
object HorusPacketDecoder {

    fun decode(
        frame: HorusRawFrame,
        payloadIds: Map<Int, String> = emptyMap(),
        customFormats: Map<String, HorusCustomFormat> = emptyMap()
    ): HorusTelemetry? = try {
        when (frame.version) {
            1 -> decodeV1(frame.payload, payloadIds, frame.hex)
            2 -> decodeV2(frame.payload, payloadIds, customFormats, frame.hex)
            3 -> decodeV3(frame.payload, frame.hex)
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        // Pola poza zakresem (np. godzina 25) – jak ValueError w decoder.py: ramki nie pokazujemy
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun callsignFor(id: Int, payloadIds: Map<Int, String>) = payloadIds[id] ?: "ID $id"

    // '<BH3sffHBBbBH'
    private fun decodeV1(p: IntArray, ids: Map<Int, String>, raw: String): HorusTelemetry {
        val r = LeReader(p)
        val id = r.u8()
        val seq = r.u16()
        val time = timeHms(r.u8(), r.u8(), r.u8())
        val lat = degrees(r.f32())
        val lon = degrees(r.f32())
        return HorusTelemetry(
            version = 1, payloadId = id, callsign = callsignFor(id, ids), sequence = seq, timeUtc = time,
            latitude = lat, longitude = lon, altitudeM = r.u16(), speedKmh = r.u8(), satellites = r.u8(),
            temperatureC = r.i8().toDouble(), batteryV = 5.0 * r.u8() / 255.0, raw = raw
        )
    }

    // '<HH3sffHBBbB9sH'
    private fun decodeV2(
        p: IntArray,
        ids: Map<Int, String>,
        formats: Map<String, HorusCustomFormat>,
        raw: String
    ): HorusTelemetry {
        val r = LeReader(p)
        val id = r.u16()
        val seq = r.u16()
        val time = timeHms(r.u8(), r.u8(), r.u8())
        val lat = degrees(r.f32())
        val lon = degrees(r.f32())
        val alt = r.u16()
        val speed = r.u8()
        val sats = r.u8()
        val temp = r.i8()
        val batt = r.u8()
        val custom = IntArray(9) { r.u8() }
        val callsign = callsignFor(id, ids)
        // Bez opisu dla tego znaku – domyślne pola RS41ng (jak w decoder.py)
        val format = formats[callsign] ?: formats[DEFAULT_CUSTOM] ?: RS41NG_CUSTOM
        val fields = runCatching { decodeCustom(custom, format) }.getOrDefault(emptyList())
        val ascent = fields.firstOrNull { it.first == "ascent_rate" }?.second as? Double
        return HorusTelemetry(
            version = 2, payloadId = id, callsign = callsign, sequence = seq, timeUtc = time,
            latitude = lat, longitude = lon, altitudeM = alt, speedKmh = speed, satellites = sats,
            temperatureC = temp.toDouble(), batteryV = 5.0 * batt / 255.0, ascentRateMs = ascent,
            extra = fields.filter { it.first != "ascent_rate" }.map { (name, value) -> fieldLabel(name) to formatValue(name, value) },
            raw = raw
        )
    }

    private fun timeHms(h: Int, m: Int, s: Int): String {
        require(h < 24 && m < 60 && s < 60) { "czas poza zakresem" }
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun degrees(v: Float): Double {
        require(v in -180f..180f) { "współrzędna poza zakresem" }
        return v.toDouble()
    }

    /** Rozpakowanie pól dodatkowych wg łańcucha struct Pythona (np. "<hhBHxx") i typów z delegates.py. */
    internal fun decodeCustom(data: IntArray, format: HorusCustomFormat): List<Pair<String, Any>> {
        val values = ArrayList<Any>()
        val r = LeReader(data)
        var count = ""
        for (c in format.struct) {
            if (c == '<') continue
            if (c.isDigit()) {
                count += c
                continue
            }
            val n = count.toIntOrNull() ?: 1
            count = ""
            when (c) {
                'x' -> repeat(n) { r.u8() }
                's' -> values += IntArray(n) { r.u8() }
                else -> repeat(n) {
                    values += when (c) {
                        'b' -> r.i8()
                        'B' -> r.u8()
                        'h' -> r.i16()
                        'H' -> r.u16()
                        'i' -> r.i32()
                        'I' -> r.i32().toLong() and 0xFFFFFFFFL
                        'f' -> r.f32().toDouble()
                        else -> throw IllegalArgumentException("nieobsługiwany typ '$c'")
                    }
                }
            }
        }
        require(values.size == format.fields.size) { "liczba pól się nie zgadza" }
        return format.fields.mapIndexed { i, (name, type) ->
            val v = values[i]
            name to when (type) {
                "battery_5v_byte" -> 5.0 * (v as Number).toDouble() / 255.0
                "divide_by_10" -> (v as Number).toDouble() / 10.0
                "divide_by_100" -> (v as Number).toDouble() / 100.0
                else -> v
            }
        }
    }

    private fun fieldLabel(name: String): String = FIELD_LABELS[name] ?: name.replace('_', ' ')

    private fun formatValue(name: String, value: Any): String {
        val unit = FIELD_UNITS[name]?.let { " $it" }.orEmpty()
        return when (value) {
            is Double -> String.format(Locale.US, if (abs(value) >= 100) "%.1f" else "%.2f", value).trimEnd('0').trimEnd('.') + unit
            is IntArray -> value.joinToString("") { "%02X".format(it) }
            else -> "$value$unit"
        }
    }

    // ------------------------------------------------------------------ Horus Binary v3 (ASN.1 UPER)

    private const val CALLSIGN_ALPHABET = "-/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val SENSOR_NAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789-"
    private const val STR_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ +/=-."
    private val GNSS_STATES = listOf("psmNotActive", "enabled", "acquisition", "tracking", "optimised", "inactive")

    private fun decodeV3(p: IntArray, raw: String): HorusTelemetry {
        val u = UperReader(p, startByte = 2) // dwa pierwsze bajty to CRC
        val extended = u.bit()
        val opt = BooleanArray(11) { u.bit() }
        val callsign = u.restrictedString(CALLSIGN_ALPHABET, 1, 15)
        val seq = u.constrained(0, 65535).toInt()
        val tod = u.constrained(-1, 86400).toInt()
        val lat = u.constrained(-9000000, 9000000) / 100000.0
        val lon = u.constrained(-18000000, 18000000) / 100000.0
        val alt = u.constrained(-1000, 50000).toInt()
        val extra = ArrayList<Pair<String, String>>()

        if (opt[0]) { // extraSensors
            val n = u.constrained(1, 4).toInt()
            for (s in 0 until n) {
                val hasName = u.bit()
                val hasValues = u.bit()
                val name = if (hasName) u.restrictedString(SENSOR_NAME_ALPHABET, 1, 20) else "czujnik ${s + 1}"
                if (hasValues) {
                    val value = when (u.constrained(0, 3).toInt()) {
                        0 -> u.restrictedString(STR_ALPHABET, 0, 255)
                        1 -> List(u.constrained(1, 4).toInt()) { u.unconstrainedInt() }.joinToString(", ")
                        2 -> List(u.constrained(1, 4).toInt()) { u.real() }.joinToString(", ") { formatDouble(it) }
                        else -> (0 until 8).joinToString("") { if (u.bit()) "1" else "0" }
                    }
                    extra += name to value
                } else {
                    extra += name to "–"
                }
            }
        }
        val speed = if (opt[1]) u.constrained(0, 512).toInt() else null
        val sats = if (opt[2]) u.constrained(0, 31).toInt() else null
        val ascent = if (opt[3]) u.constrained(-32767, 32767) / 100.0 else null
        if (opt[4]) extra += AppStrings.get(R.string.hp_pressure) to formatDouble(u.constrained(0, 12000) / 10.0) + " hPa"
        var temperature: Double? = null
        if (opt[5]) {
            val present = BooleanArray(4) { u.bit() }
            val names = listOf(null, AppStrings.get(R.string.hp_temp_ext), AppStrings.get(R.string.hp_temp1), AppStrings.get(R.string.hp_temp2))
            for (i in 0 until 4) {
                if (!present[i]) continue
                val t = u.constrained(-1023, 1023) / 10.0
                if (i == 0) temperature = t else extra += names[i]!! to formatDouble(t) + " °C"
            }
        }
        if (opt[6]) extra += AppStrings.get(R.string.hp_humidity) to "${u.constrained(0, 100)} %"
        var battery: Double? = null
        if (opt[7]) {
            val present = BooleanArray(4) { u.bit() }
            val names = listOf(null, AppStrings.get(R.string.hp_solar), AppStrings.get(R.string.hp_voltage1), AppStrings.get(R.string.hp_voltage2))
            for (i in 0 until 4) {
                if (!present[i]) continue
                val v = u.constrained(0, 16383) / 1000.0
                if (i == 0) battery = v else extra += names[i]!! to formatDouble(v) + " V"
            }
        }
        if (opt[8]) {
            val n = u.constrained(1, 8).toInt()
            for (i in 0 until n) extra += "Licznik ${i + 1}" to u.unconstrainedInt().toString()
        }
        if (opt[9]) extra += "GNSS" to (GNSS_STATES.getOrNull(u.constrained(0, 5).toInt()) ?: "?")
        if (opt[10]) {
            val n = u.constrained(0, 255).toInt()
            val bytes = IntArray(n) { u.bits(8).toInt() }
            extra += "Dane" to bytes.joinToString("") { "%02X".format(it) }
        }
        if (extended) {
            // Pola rozszerzeń: mapa obecności, potem każde jako "open type" (długość + bajty)
            val count = u.normallySmallLength()
            val present = BooleanArray(count) { u.bit() }
            for (i in 0 until count) {
                if (!present[i]) continue
                val len = u.length()
                val start = u.position
                if (i == 0) {
                    val via = u.constrained(0, 7).toInt()
                    extra += "Via" to when (via) { 0 -> "sondehub"; 1 -> "nohub"; else -> "?" }
                }
                u.position = start + len * 8
            }
        }

        val time = if (tod < 0) null else {
            val t = tod % 86400
            String.format(Locale.US, "%02d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
        }
        return HorusTelemetry(
            version = 3, payloadId = null, callsign = callsign, sequence = seq, timeUtc = time,
            latitude = lat, longitude = lon, altitudeM = alt.takeIf { it != -1000 },
            speedKmh = speed, satellites = sats, temperatureC = temperature, batteryV = battery,
            ascentRateMs = ascent, extra = extra, raw = raw
        )
    }

    private fun formatDouble(v: Double): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    private const val DEFAULT_CUSTOM = "4FSKTEST-V2"
    // Domyślne pola RS41ng – gdy lista pól dodatkowych jeszcze się nie wczytała
    private val RS41NG_CUSTOM = HorusCustomFormat(
        "<hhBHxx",
        listOf("ascent_rate" to "divide_by_100", "ext_temperature" to "divide_by_10", "ext_humidity" to "none", "ext_pressure" to "divide_by_10")
    )
    private val FIELD_LABELS get() = mapOf(
        "ext_temperature" to AppStrings.get(R.string.hp_temp_ext),
        "ext_humidity" to AppStrings.get(R.string.hp_humidity),
        "ext_pressure" to AppStrings.get(R.string.hp_pressure),
        "cutdown_battery_voltage" to AppStrings.get(R.string.hp_cutdown_battery)
    )
    private val FIELD_UNITS = mapOf(
        "ext_temperature" to "°C",
        "ext_humidity" to "%",
        "ext_pressure" to "hPa",
        "cutdown_battery_voltage" to "V"
    )
}

/** Odczyt little-endian z tablicy bajtów 0..255 (jak struct.unpack('<…')). */
private class LeReader(private val p: IntArray) {
    private var pos = 0
    fun u8(): Int = p[pos++] and 0xFF
    fun i8(): Int = u8().toByte().toInt()
    fun u16(): Int = u8() or (u8() shl 8)
    fun i16(): Int = u16().toShort().toInt()
    fun i32(): Int = u16() or (u16() shl 16)
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
}

/** Czytnik ASN.1 PER bez wyrównania (UPER), tylko konstrukcje używane w HorusBinaryV3.asn1. */
internal class UperReader(private val data: IntArray, startByte: Int) {
    var position = startByte * 8

    fun bit(): Boolean {
        val byte = position / 8
        require(byte < data.size) { "koniec danych" }
        val b = (data[byte] shr (7 - position % 8)) and 1
        position++
        return b == 1
    }

    fun bits(n: Int): Long {
        var v = 0L
        repeat(n) { v = (v shl 1) or (if (bit()) 1L else 0L) }
        return v
    }

    private fun bitsFor(range: Long): Int {
        var n = 0
        while ((1L shl n) < range) n++
        return n
    }

    /** INTEGER (lb..ub): przesunięcie od lb na minimalnej liczbie bitów. */
    fun constrained(lb: Long, ub: Long): Long {
        val v = lb + bits(bitsFor(ub - lb + 1))
        require(v <= ub) { "wartość poza zakresem" }
        return v
    }

    fun constrained(lb: Int, ub: Int): Long = constrained(lb.toLong(), ub.toLong())

    /** Nieograniczony wyznacznik długości (do 16383; fragmentacja nie występuje w ramkach Horus). */
    fun length(): Int = when {
        !bit() -> bits(7).toInt()
        !bit() -> bits(14).toInt()
        else -> throw IllegalArgumentException("fragmentacja UPER nieobsługiwana")
    }

    /** "Normally small length" – liczba pól rozszerzeń. */
    fun normallySmallLength(): Int =
        if (!bit()) bits(6).toInt() + 1 else length()

    /** INTEGER bez ograniczeń: długość w bajtach + liczba w kodzie U2. */
    fun unconstrainedInt(): Long {
        val len = length()
        require(len in 1..8)
        var v = bits(8)
        if (v >= 128) v -= 256
        repeat(len - 1) { v = (v shl 8) or bits(8) }
        return v
    }

    /** REAL – kodowanie binarne/specjalne z X.690 8.5 (po długości w bajtach). */
    fun real(): Double {
        val len = length()
        if (len == 0) return 0.0
        val first = bits(8).toInt()
        if (first and 0x80 == 0) {
            if (first and 0x40 != 0) {
                return when (first) {
                    0x40 -> Double.POSITIVE_INFINITY
                    0x41 -> Double.NEGATIVE_INFINITY
                    0x43 -> -0.0
                    else -> Double.NaN
                }
            }
            // Zapis dziesiętny ISO 6093
            val chars = CharArray(len - 1) { bits(8).toInt().toChar() }
            return String(chars).trim().replace(',', '.').toDouble()
        }
        val negative = first and 0x40 != 0
        val base = when ((first shr 4) and 0x3) { 0 -> 2.0; 1 -> 8.0; 2 -> 16.0; else -> throw IllegalArgumentException("zła podstawa REAL") }
        val scale = (first shr 2) and 0x3
        var used = 1
        val expLen = when (first and 0x3) {
            0 -> 1; 1 -> 2; 2 -> 3
            else -> bits(8).toInt().also { used++ }
        }
        var exp = bits(8)
        if (exp >= 128) exp -= 256
        repeat(expLen - 1) { exp = (exp shl 8) or bits(8) }
        used += expLen
        var mantissa = 0.0
        repeat(len - used) { mantissa = mantissa * 256 + bits(8) }
        val value = mantissa * Math.pow(2.0, scale.toDouble()) * Math.pow(base, exp.toDouble())
        return if (negative) -value else value
    }

    /**
     * IA5String z ograniczonym alfabetem i rozmiarem (X.691 30.5): znak na b = ceil(log2 N) bitach –
     * jego kod, jeśli największy kod alfabetu się mieści, inaczej numer w alfabecie posortowanym po kodach.
     */
    fun restrictedString(alphabet: String, minLen: Int, maxLen: Int): String {
        val len = constrained(minLen, maxLen).toInt()
        val sorted = alphabet.toCharArray().sorted()
        val b = bitsFor(sorted.size.toLong())
        val useCodes = sorted.last().code <= (1 shl b) - 1
        val sb = StringBuilder(len)
        repeat(len) {
            val v = bits(b).toInt()
            sb.append(if (useCodes) v.toChar() else sorted.getOrNull(v) ?: throw IllegalArgumentException("zły znak"))
        }
        return sb.toString()
    }
}
