package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.geo.CountryInfo
import io.github.sp3wro.hamradiotoolkit.data.model.Repeater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong

/** Identyfikatory źródeł przemienników (zapisywane w ustawieniach). */
object RepeaterSources {
    const val PRZEMIENNIKI_NET = "przemienniki_net"
    const val PRZEMIENNIKI_EU = "przemienniki_eu"
    /** HearHam + repeatermap.de scalone – domyślne poza Polską. */
    const val WORLD = "international"
    const val HEARHAM = "hearham"
    const val REPEATERMAP = "repeatermap"
    const val REPEATERBOOK = "repeaterbook_api"

    fun isPolish(source: String) = source == PRZEMIENNIKI_NET || source == PRZEMIENNIKI_EU

    fun label(source: String): String = when (source) {
        PRZEMIENNIKI_NET -> "przemienniki.net"
        PRZEMIENNIKI_EU -> "przemienniki.eu"
        WORLD -> "HearHam + repeatermap.de"
        HEARHAM -> "HearHam"
        REPEATERMAP -> "repeatermap.de"
        REPEATERBOOK -> "RepeaterBook"
        else -> source
    }
}

/** Prostokąt współrzędnych wokół punktu (do zapytań i wstępnego filtrowania). */
data class GeoBox(val north: Double, val south: Double, val east: Double, val west: Double) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east

    /** Podział na kafelki o boku [step] stopni (repeatermap.de zwraca szczegóły tylko dla małych obszarów). */
    fun tiles(step: Double): List<GeoBox> {
        val result = ArrayList<GeoBox>()
        var s = south
        while (s < north) {
            val n = minOf(s + step, north)
            var w = west
            while (w < east) {
                val e = minOf(w + step, east)
                result += GeoBox(n, s, e, w)
                w = e
            }
            s = n
        }
        return result
    }

    companion object {
        fun around(lat: Double, lon: Double, radiusKm: Double): GeoBox {
            val dLat = radiusKm / 111.0
            val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
            return GeoBox(lat + dLat, lat - dLat, lon + dLon, lon - dLon)
        }
    }
}

object InternationalRepeaters {
    private const val TAG = "IntlRepeaters"
    private const val HEARHAM_URL = "https://hearham.com/api/repeaters/v1"
    private const val HEARHAM_MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val REPEATERMAP_URL = "https://www.repeatermap.de/api/v1/map.php"
    private const val REPEATERBOOK_MAX_AGE_MS = 24L * 3600 * 1000

    /** Promień pobierania – maksymalny promień z ustawień, żeby zmiana suwaka nie wymagała nowego pobrania. */
    const val FETCH_RADIUS_KM = 210.0

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val hearHamMutex = Mutex()
    @Volatile private var hearHamCache: List<Repeater>? = null

    // ---------------------------------------------------------------- HearHam

    suspend fun hearHam(context: Context, box: GeoBox): List<Repeater> {
        val all = hearHamMutex.withLock {
            hearHamCache ?: withContext(Dispatchers.IO) {
                val file = File(cacheDir(context), "hearham.json")
                val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > HEARHAM_MAX_AGE_MS
                if (stale) {
                    try {
                        val text = get(HEARHAM_URL)
                        val parsed = parseHearHam(text)
                        if (parsed.size < 1000) throw IOException(AppStrings.get(R.string.err_hearham_incomplete))
                        writeAtomically(file, text)
                        parsed
                    } catch (e: Exception) {
                        Log.w(TAG, "HearHam niedostępny – używam kopii", e)
                        if (!file.exists()) throw e
                        parseHearHam(file.readText())
                    }
                } else parseHearHam(file.readText())
            }.also { hearHamCache = it }
        }
        return all.filter { box.contains(it.latitude, it.longitude) }
    }

    fun parseHearHam(text: String): List<Repeater> {
        val array = json.parseToJsonElement(text).jsonArray
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val call = o.str("callsign").trim().uppercase()
            val lat = o.num("latitude") ?: return@mapNotNull null
            val lon = o.num("longitude") ?: return@mapNotNull null
            val freqHz = o.long("frequency") ?: return@mapNotNull null
            if (call.isEmpty() || freqHz <= 0 || (lat == 0.0 && lon == 0.0)) return@mapNotNull null
            val offsetHz = o.long("offset") ?: 0L
            val encode = o.str("encode").trim()
            val colorCode = Regex("^CC\\s*(\\d+)$", RegexOption.IGNORE_CASE).find(encode)?.groupValues?.get(1).orEmpty()
            val group = o.str("group").trim()
            val description = o.str("description").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            Repeater(
                callsign = call,
                qth = o.str("city").trim(),
                modes = normalizeModes(o.str("mode").split('/', ' ')),
                userRx = freqHz / 1e6,
                userTx = (freqHz + offsetHz) / 1e6,
                requiredCtcss = ctcssOrDash(encode),
                status = if ((o.int("operational") ?: 1) == 1) "working" else "off",
                latitude = lat,
                longitude = lon,
                source = "HearHam",
                note = listOf(group.takeIf { it.isNotEmpty() && it != "DMR" }, description.takeIf { it.isNotEmpty() })
                    .filterNotNull().joinToString(" • "),
                dmrColorCode = colorCode
            )
        }
    }

    // ---------------------------------------------------------------- repeatermap.de

    suspend fun repeatermap(box: GeoBox): List<Repeater> = withContext(Dispatchers.IO) {
        val result = ArrayList<Repeater>()
        var failures = 0
        val tiles = box.tiles(2.0)
        for (tile in tiles) {
            val url = REPEATERMAP_URL.toHttpUrl().newBuilder()
                .addQueryParameter("bounds", "${tile.north},${tile.east},${tile.south},${tile.west}")
                .build().toString()
            try {
                result += parseRepeatermap(get(url))
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "repeatermap.de: kafelek $tile", e)
            }
        }
        if (failures == tiles.size && tiles.isNotEmpty()) throw IOException(AppStrings.get(R.string.err_repeatermap))
        // Ten sam przemiennik bywa osobnym wpisem dla FM i DMR (i wraca z dwóch kafelków, gdy leży na granicy)
        merge(result)
    }

    fun parseRepeatermap(text: String): List<Repeater> {
        val root = json.parseToJsonElement(text).jsonObject
        val locations = root["locations"] as? JsonArray ?: return emptyList()
        val result = ArrayList<Repeater>()
        for (locEl in locations) {
            val loc = locEl as? JsonObject ?: continue
            val pos = loc["position"] as? JsonObject ?: continue
            val lat = pos.num("lat") ?: continue
            val lon = pos.num("lon") ?: continue
            val relais = loc["relais"] as? JsonArray ?: continue
            for (rEl in relais) {
                val r = rEl as? JsonObject ?: continue
                val call = r.str("call").trim().uppercase()
                // tx – nadawanie przemiennika (słuchamy), rx – jego wejście (nadajemy)
                val output = r.num("tx") ?: continue
                val input = r.num("rx") ?: output
                if (call.isEmpty() || output <= 0) continue
                val remarks = r.str("remarks").trim()
                // Pola szczegółowe emisji (gdy puste, API zwraca [] zamiast obiektu); ctcss_rx – ton wejścia przemiennika
                val details = r["mode_details"] as? JsonObject
                val ctcss = details?.str("ctcss_rx")?.takeIf { it.isNotBlank() }
                    ?: details?.str("ctcss_tx")?.takeIf { it.isNotBlank() }
                    ?: CTCSS_IN_TEXT.find(remarks)?.groupValues?.get(1)
                val colorCode = details?.str("cc")?.takeIf { it.isNotBlank() }
                    ?: COLOR_CODE_IN_TEXT.find(remarks)?.groupValues?.get(1).orEmpty()
                result += Repeater(
                    callsign = call,
                    qth = r.str("qth").ifBlank { loc.str("qth") }.trim(),
                    modes = normalizeModes(r.str("mode").split('/', ',', ' ')),
                    userRx = output,
                    userTx = input,
                    requiredCtcss = ctcss?.let { ctcssOrDash(it.replace(Regex("(?i)\\s*hz$"), "")) } ?: "-",
                    status = "working",
                    latitude = lat,
                    longitude = lon,
                    source = "repeatermap.de",
                    note = listOf(remarks, r.str("url").trim()).filter { it.isNotEmpty() }.joinToString(" • "),
                    dmrColorCode = colorCode
                )
            }
        }
        return result
    }

    private val CTCSS_IN_TEXT = Regex("""(?i)(?:CTCSS|PL|Ton|Tone)\s*:?\s*(\d{2,3}[.,]\d)""")
    private val COLOR_CODE_IN_TEXT = Regex("""(?i)\bCC\s*:?\s*(\d{1,2})\b""")

    // ---------------------------------------------------------------- RepeaterBook (token użytkownika)

    suspend fun repeaterBook(context: Context, country: CountryInfo, token: String, contact: String): List<Repeater> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) throw IOException("Wpisz token RepeaterBook w Ustawieniach → Konta w serwisach")
            val url = repeaterBookUrl(country)
            val file = File(cacheDir(context), "rb_" + url.hashCode().toString(16) + ".json")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < REPEATERBOOK_MAX_AGE_MS
            val text = if (fresh) file.readText() else {
                val ua = "HamRadioToolkit/1.0 (Android" + (if (contact.isNotBlank()) "; $contact" else "") + ")"
                val request = Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("X-RB-App-Token", token.trim())
                    .build()
                HttpClients.default.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code == 429) throw IOException(AppStrings.get(R.string.err_rb_429))
                    if (!response.isSuccessful) {
                        val code = Regex(""""(?:error|code)"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                        throw IOException("RepeaterBook: HTTP ${response.code}" + (code?.let { " ($it)" } ?: ""))
                    }
                    writeAtomically(file, body)
                    body
                }
            }
            parseRepeaterBook(text)
        }

    private fun repeaterBookUrl(country: CountryInfo): String {
        val base = "https://www.repeaterbook.com/api/"
        return when (country.isoCode) {
            "US" -> {
                val fips = US_FIPS[country.adminArea]
                (base + "export.php").toHttpUrl().newBuilder().addQueryParameter("country", "United States")
                    .apply { if (fips != null) addQueryParameter("state_id", fips) }.build().toString()
            }
            "CA", "MX" -> (base + "export.php").toHttpUrl().newBuilder()
                .addQueryParameter("country", country.nameEn).build().toString()
            else -> (base + "exportROW.php").toHttpUrl().newBuilder()
                .addQueryParameter("country", country.nameEn).build().toString()
        }
    }

    /** Odpowiedź eksportu RepeaterBook: {"count": n, "results": [{...}]} – nazwy pól wg dokumentacji API. */
    fun parseRepeaterBook(text: String): List<Repeater> {
        val root = json.parseToJsonElement(text)
        val results = (root as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val call = o.str("Callsign").trim().uppercase()
            val output = o.str("Frequency").toDoubleOrNull() ?: return@mapNotNull null
            val input = o.str("Input Freq").toDoubleOrNull() ?: output
            val lat = o.str("Lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.str("Long").toDoubleOrNull() ?: return@mapNotNull null
            val modes = buildList {
                if (o.yes("FM Analog")) add("FM")
                if (o.yes("DMR")) add("DMR")
                if (o.yes("D-Star")) add("D-STAR")
                if (o.yes("System Fusion")) add("C4FM")
                if (o.yes("NXDN")) add("NXDN")
                if (o.yes("APCO P-25")) add("P25")
                if (o.yes("Tetra")) add("TETRA")
            }.ifEmpty { listOf("FM") }
            Repeater(
                callsign = call,
                qth = listOf(o.str("Nearest City"), o.str("Landmark")).filter { it.isNotBlank() }.joinToString(", "),
                modes = modes,
                userRx = output,
                userTx = input,
                requiredCtcss = ctcssOrDash(o.str("PL")),
                status = if (o.str("Operational Status").contains("off", ignoreCase = true)) "off" else "working",
                latitude = lat,
                longitude = lon,
                source = "RepeaterBook",
                note = o.str("Notes").trim(),
                dmrColorCode = o.str("DMR Color Code").trim()
            )
        }
    }

    // ---------------------------------------------------------------- RadioID (szczegóły DMR na żądanie)

    data class DmrDetails(val colorCode: String, val network: String, val talkgroups: List<String>, val trustee: String)

    private val radioIdCache = HashMap<String, DmrDetails?>()

    /** Szczegóły przemiennika DMR z RadioID (color code, sieć, grupy na slotach) albo null, gdy brak w bazie. */
    suspend fun radioIdDetails(callsign: String, outputMhz: Double): DmrDetails? = withContext(Dispatchers.IO) {
        val key = callsign.uppercase() + "@" + (outputMhz * 1000).roundToLong()
        synchronized(radioIdCache) { if (radioIdCache.containsKey(key)) return@withContext radioIdCache[key] }
        val url = "https://radioid.net/api/dmr/repeater/".toHttpUrl().newBuilder()
            .addQueryParameter("callsign", callsign.uppercase()).build().toString()
        val all = parseRadioId(get(url))
        // Ten sam znak bywa na kilku częstotliwościach – bierzemy pasującą (albo jedyną)
        val best = all.minByOrNull { abs(it.first - outputMhz) }
        val details = best?.takeIf { abs(it.first - outputMhz) < 0.01 || all.size == 1 }?.second
        synchronized(radioIdCache) { radioIdCache[key] = details }
        details
    }

    /** Lista (częstotliwość wyjścia, szczegóły) z odpowiedzi RadioID. */
    fun parseRadioId(text: String): List<Pair<Double, DmrDetails>> {
        val root = json.parseToJsonElement(text).jsonObject
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val freq = o.str("frequency").toDoubleOrNull() ?: return@mapNotNull null
            val talkgroups = (o["talkgroups"] as? JsonArray).orEmpty().mapNotNull { tgEl ->
                val tg = tgEl as? JsonObject ?: return@mapNotNull null
                val number = tg.str("talkgroup")
                if (number.isBlank()) null
                else "TS${tg.str("timeslot")}: $number" + tg.str("description").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            }
            val trustee = (o["trustee"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
            freq to DmrDetails(o.str("color_code"), o.str("ipsc_network"), talkgroups, trustee)
        }
    }

    // ---------------------------------------------------------------- scalanie

    /**
     * Scalanie list z kilku źródeł: ten sam znak i ta sama częstotliwość wyjścia (±5 kHz) to jeden przemiennik.
     * Łączymy emisje, uzupełniamy brakujący CTCSS/color code/opis; pozycja z pierwszego źródła.
     */
    fun merge(vararg lists: List<Repeater>): List<Repeater> {
        val byKey = LinkedHashMap<String, Repeater>()
        for (list in lists) {
            for (r in list) {
                val key = baseCall(r.callsign) + "@" + (r.userRx * 200).roundToLong()
                val existing = byKey[key]
                byKey[key] = if (existing == null) r else existing.copy(
                    modes = (existing.modes + r.modes).distinct(),
                    requiredCtcss = if (existing.requiredCtcss == "-") r.requiredCtcss else existing.requiredCtcss,
                    qth = existing.qth.ifBlank { r.qth },
                    note = listOf(existing.note, r.note).filter { it.isNotBlank() }.distinct().joinToString(" • "),
                    dmrColorCode = existing.dmrColorCode.ifBlank { r.dmrColorCode },
                    status = if (existing.status == "working" || r.status == "working") "working" else existing.status,
                    source = if (r.source in existing.source.split(" + ")) existing.source else existing.source + " + " + r.source
                )
            }
        }
        return byKey.values.toList()
    }

    private fun baseCall(call: String): String = call.uppercase().split('/').maxByOrNull { it.length }.orEmpty()

    // ---------------------------------------------------------------- pomocnicze

    internal fun normalizeModes(raw: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (part in raw) {
            val u = part.trim().uppercase()
            when {
                u.isEmpty() -> Unit
                u.contains("DMR") && u.contains("STAR") -> { result += "D-STAR"; result += "DMR" }
                u.contains("DMR") -> result += "DMR"
                u.contains("STAR") -> result += "D-STAR"
                u == "YSF" || u.contains("C4FM") || u.contains("FUSION") -> result += "C4FM"
                u.contains("P25") -> result += "P25"
                u.contains("NXDN") -> result += "NXDN"
                u == "FM" || u == "NFM" || u.contains("ANALOG") -> result += "FM"
                u == "AX25" -> result += "APRS"
                u.length <= 8 -> result += u
            }
        }
        return result.toList().ifEmpty { listOf("FM") }
    }

    /** "082.5" → "82.5"; "0", "", "CC1" → "-". */
    internal fun ctcssOrDash(raw: String): String {
        val value = raw.trim().replace(',', '.').toDoubleOrNull() ?: return "-"
        if (value < 60.0 || value > 260.0) return "-"
        return String.format(java.util.Locale.US, "%.1f", value)
    }

    private fun cacheDir(context: Context) = File(context.applicationContext.filesDir, "repeaters").apply { mkdirs() }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun get(url: String): String =
        HttpClients.default.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} z ${url.toHttpUrl().host}")
            response.body?.string() ?: throw IOException(AppStrings.get(R.string.err_empty_response_from, url.toHttpUrl().host))
        }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.yes(key: String): Boolean = str(key).equals("Yes", ignoreCase = true)

    // Kody FIPS stanów USA – RepeaterBook filtruje eksport po state_id
    private val US_FIPS = mapOf(
        "Alabama" to "01", "Alaska" to "02", "Arizona" to "04", "Arkansas" to "05", "California" to "06",
        "Colorado" to "08", "Connecticut" to "09", "Delaware" to "10", "District of Columbia" to "11", "Florida" to "12",
        "Georgia" to "13", "Hawaii" to "15", "Idaho" to "16", "Illinois" to "17", "Indiana" to "18", "Iowa" to "19",
        "Kansas" to "20", "Kentucky" to "21", "Louisiana" to "22", "Maine" to "23", "Maryland" to "24",
        "Massachusetts" to "25", "Michigan" to "26", "Minnesota" to "27", "Mississippi" to "28", "Missouri" to "29",
        "Montana" to "30", "Nebraska" to "31", "Nevada" to "32", "New Hampshire" to "33", "New Jersey" to "34",
        "New Mexico" to "35", "New York" to "36", "North Carolina" to "37", "North Dakota" to "38", "Ohio" to "39",
        "Oklahoma" to "40", "Oregon" to "41", "Pennsylvania" to "42", "Rhode Island" to "44", "South Carolina" to "45",
        "South Dakota" to "46", "Tennessee" to "47", "Texas" to "48", "Utah" to "49", "Vermont" to "50",
        "Virginia" to "51", "Washington" to "53", "West Virginia" to "54", "Wisconsin" to "55", "Wyoming" to "56",
        "Puerto Rico" to "72"
    )
}
