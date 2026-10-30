package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.geo.CountryInfo
import io.github.sp3wro.hamradiotoolkit.data.model.Repeater
import io.github.sp3wro.hamradiotoolkit.data.model.RepeaterResponse
import io.github.sp3wro.hamradiotoolkit.data.preferences.CredentialsRepository
import io.github.sp3wro.hamradiotoolkit.utils.CsvParser
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class RepeaterDataSourceFetcher(private val client: OkHttpClient = HttpClients.default) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Przemienniki z wybranego źródła. Bazy polskie zwracają cały kraj; zagraniczne – obszar ok. 200 km
     * wokół [position] (bez pozycji nie da się ich zapytać).
     */
    suspend fun fetchRepeaters(
        source: String,
        context: Context,
        position: LatLon?,
        country: CountryInfo?
    ): List<Repeater> = withContext(Dispatchers.IO) {
        when (source) {
            RepeaterSources.PRZEMIENNIKI_NET -> fetchPrzemiennikiNet().map { it.copy(source = "przemienniki.net") }
            RepeaterSources.PRZEMIENNIKI_EU -> fetchPrzemiennikiEu().map { it.copy(source = "przemienniki.eu") }
            else -> {
                val pos = position ?: throw IllegalStateException(AppStrings.get(R.string.err_rep_no_position))
                val box = GeoBox.around(pos.lat, pos.lon, InternationalRepeaters.FETCH_RADIUS_KM)
                when (source) {
                    RepeaterSources.HEARHAM -> InternationalRepeaters.hearHam(context, box)
                    RepeaterSources.REPEATERMAP -> InternationalRepeaters.repeatermap(box)
                    RepeaterSources.REPEATERBOOK -> {
                        val c = country ?: throw IllegalStateException(AppStrings.get(R.string.err_rep_no_country))
                        val credentials = CredentialsRepository(context).current()
                        InternationalRepeaters.repeaterBook(context, c, credentials.repeaterBookToken, credentials.repeaterBookContact)
                    }
                    else -> fetchWorld(context, box)
                }
            }
        }
    }

    // HearHam + repeatermap.de: każde źródło jest mocne w innych krajach; błąd jednego nie blokuje drugiego
    private suspend fun fetchWorld(context: Context, box: GeoBox): List<Repeater> = coroutineScope {
        val hearHam = async { runCatching { InternationalRepeaters.hearHam(context, box) } }
        val repeatermap = async { runCatching { InternationalRepeaters.repeatermap(box) } }
        val a = hearHam.await()
        val b = repeatermap.await()
        if (a.isFailure && b.isFailure) throw a.exceptionOrNull() ?: Exception(AppStrings.get(R.string.common_no_data))
        a.exceptionOrNull()?.let { Log.w(TAG, "HearHam", it) }
        b.exceptionOrNull()?.let { Log.w(TAG, "repeatermap.de", it) }
        // repeatermap.de pierwszy – ma dokładniejsze współrzędne i uwagi w Europie
        InternationalRepeaters.merge(b.getOrDefault(emptyList()), a.getOrDefault(emptyList()))
    }

    private fun fetchPrzemiennikiEu(): List<Repeater> {
        val request = Request.Builder()
            .url("https://przemienniki.eu/api/export/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} z przemienniki.eu")
            val body = response.body?.string() ?: throw Exception(AppStrings.get(R.string.err_empty_response_from, "przemienniki.eu"))
            val responses: List<RepeaterResponse> = json.decodeFromString(body)

            return responses.mapNotNull { resp ->
                if (resp.coordinates.size >= 2) {
                    Repeater(
                        callsign = resp.callsign,
                        qth = resp.qth,
                        modes = normalizeModes(resp.modes),
                        userRx = resp.txFrequency ?: 0.0,
                        userTx = resp.rxFrequency ?: 0.0,
                        requiredCtcss = resp.getRxCtcssString(),
                        status = if (resp.status.isBlank()) "working" else resp.status,
                        latitude = resp.coordinates[0],
                        longitude = resp.coordinates[1]
                    )
                } else null
            }
        }
    }

    private fun fetchPrzemiennikiNet(): List<Repeater> {
        // 1. Pobieramy mapę współrzędnych GPS z oficjalnego pliku radar.xml
        val coordsMap = fetchPrzemiennikiNetCoordinates()

        // 2. Pobieramy mapę pełnych trybów (np. C4FM, DMR, FM) z bazy
        val modesMap = fetchPrzemiennikiEuModes()

        // 3. Pobieramy plik chirp.csv z przemienniki.net (dedykowany eksport CHIRP)
        val request = Request.Builder()
            .url("https://przemienniki.net/export/chirp.csv")
            .header("User-Agent", "CHIRP/1.0 (HamRadioToolkit)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} z przemienniki.net/export/chirp.csv")
            val csvText = response.body?.string() ?: throw Exception(AppStrings.get(R.string.err_empty_response_from, "przemienniki.net"))

            return parseChirpCsv(csvText, coordsMap, modesMap)
        }
    }

    private fun fetchPrzemiennikiNetCoordinates(): Map<String, Pair<Double, Double>> {
        return try {
            val request = Request.Builder()
                .url("https://przemienniki.net/export/radar.xml")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyMap()
                val xmlText = response.body?.string() ?: return emptyMap()

                val repeaterTagRegex = Regex("""(?s)<repeater\s+.*?>""")
                val attributeRegex = Regex("""(\w+)="([^"]*)"""")
                val map = mutableMapOf<String, Pair<Double, Double>>()

                for (match in repeaterTagRegex.findAll(xmlText)) {
                    val attrString = match.value
                    val attrs = mutableMapOf<String, String>()

                    for (attrMatch in attributeRegex.findAll(attrString)) {
                        attrs[attrMatch.groupValues[1]] = attrMatch.groupValues[2].trim()
                    }

                    val cs = attrs["callsign"]?.trim() ?: continue
                    val lat = attrs["latitude"]?.toDoubleOrNull() ?: 0.0
                    val lon = attrs["longitude"]?.toDoubleOrNull() ?: 0.0

                    if (cs.isNotBlank() && lat != 0.0 && lon != 0.0) {
                        map[cs] = Pair(lat, lon)
                    }
                }
                map
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się pobrać radar.xml z przemienniki.net", e)
            emptyMap()
        }
    }

    private fun fetchPrzemiennikiEuModes(): Map<String, List<String>> {
        return try {
            val request = Request.Builder()
                .url("https://przemienniki.eu/api/export/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyMap()
                val body = response.body?.string() ?: return emptyMap()
                val responses: List<RepeaterResponse> = json.decodeFromString(body)

                val map = mutableMapOf<String, MutableList<String>>()
                for (resp in responses) {
                    if (resp.callsign.isNotBlank()) {
                        val existing = map.getOrPut(resp.callsign) { mutableListOf() }
                        existing.addAll(resp.modes)
                    }
                }
                map.mapValues { (_, modes) -> normalizeModes(modes) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się pobrać trybów z przemienniki.eu", e)
            emptyMap()
        }
    }

    private fun parseChirpCsv(
        csvText: String,
        coordsMap: Map<String, Pair<Double, Double>>,
        modesMap: Map<String, List<String>>
    ): List<Repeater> {
        val result = mutableListOf<Repeater>()

        // Kolumny CHIRP: Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,Mode,TStep,Comment
        for (row in CsvParser.parseWithHeader(csvText, headerMarker = "Frequency")) {
            val callsign = row["Name"] // np. SR3WR, SR3XX
            if (callsign.isBlank()) continue

            val userRx = row["Frequency"].toDoubleOrNull() ?: continue // MHz, np. 438.825
            if (userRx == 0.0) continue

            val offset = row["Offset"].toDoubleOrNull() ?: 0.0 // MHz, np. 7.6
            val userTx = when (row["Duplex"]) {
                "-" -> userRx - offset
                "+" -> userRx + offset
                else -> userRx
            }

            // CHIRP zawsze wypełnia rToneFreq/cToneFreq (domyślnie 88.5) – ton obowiązuje tylko,
            // gdy kolumna Tone go włącza: "Tone" = ton nadawany, "TSQL" = ton w obie strony
            val ctcssRaw = when (row["Tone"]) {
                "Tone" -> row["rToneFreq"]
                "TSQL" -> row["cToneFreq"]
                else -> ""
            }
            val ctcss = if (ctcssRaw.isBlank() || ctcssRaw.toDoubleOrNull() == 0.0) "-" else ctcssRaw

            val csvMode = row["Mode"].ifBlank { "FM" }
            val qth = row["Comment"]

            // Współrzędne GPS – bez nich nie da się policzyć odległości, więc pomijamy
            val coords = coordsMap[callsign] ?: continue

            // Wyciągamy tryby emisji z mapy oraz z komentarza CSV
            val rawModesList = mutableListOf<String>()
            modesMap[callsign]?.let { rawModesList.addAll(it) }
            rawModesList.add(csvMode)

            // Jeśli komentarz wspomina o C4FM, DMR, DSTAR lub Fusion
            val qthUpper = qth.uppercase()
            if (qthUpper.contains("C4FM") || qthUpper.contains("FUSION") || qthUpper.contains("WIRES-X")) {
                rawModesList.add("C4FM")
            }
            if (qthUpper.contains("DMR")) {
                rawModesList.add("DMR")
            }
            if (qthUpper.contains("DSTAR") || qthUpper.contains("D-STAR")) {
                rawModesList.add("D-STAR")
            }

            result.add(
                Repeater(
                    callsign = callsign,
                    qth = qth.ifBlank { "Przemienniki.net" },
                    modes = normalizeModes(rawModesList).ifEmpty { listOf("FM") },
                    userRx = userRx,
                    userTx = userTx,
                    requiredCtcss = ctcss,
                    status = "working",
                    latitude = coords.first,
                    longitude = coords.second
                )
            )
        }

        return result
    }

    // Jeden wpis może zawierać kilka emisji naraz (np. "DSTAR/DV+C4FM/Fusion" w SR3DOS) – każdą
    // sprawdzamy osobno; wcześniej pierwsze trafienie (C4FM) kończyło sprawdzanie i D-STAR ginął
    internal fun normalizeModes(rawModes: List<String>): List<String> {
        val result = linkedSetOf<String>()
        for (raw in rawModes) {
            val upper = raw.trim().uppercase()
            if (upper.isBlank()) continue
            val digital = mutableListOf<String>()
            if (upper.contains("DSTAR") || upper.contains("D-STAR")) digital += "D-STAR"
            if (upper.contains("C4FM") || upper.contains("FUSION") || upper.contains("YSF")) digital += "C4FM"
            if (upper.contains("DMR")) digital += "DMR"
            result += digital
            // "FM" szukamy jako osobnego słowa – w "C4FM" też są litery FM
            val words = upper.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            when {
                "FM" in words || "NFM" in words || "ANALOG" in words -> result += "FM"
                // Samo "DV" w CHIRP to cyfrowa emisja bez nazwy systemu – zwykle Yaesu C4FM
                digital.isEmpty() && upper == "DV" -> result += "C4FM"
                digital.isEmpty() && words.isNotEmpty() && upper.length <= 8 -> result += upper
            }
        }
        return result.toList()
    }

    private companion object {
        const val TAG = "RepeaterFetcher"
    }
}
