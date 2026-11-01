package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.model.Repeater
import io.github.sp3wro.hamradiotoolkit.data.model.RepeaterResponse
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Kopia pliku na dysku: młodsza niż [maxAgeMs] jest używana bez sieci. Starsza jest zastępowana nowym
 * pobraniem, a gdy to się nie uda (albo plik jest niepoprawny) – używana dalej, o ile w ogóle istnieje.
 */
internal fun <T> readThroughCopy(
    file: File,
    maxAgeMs: Long,
    nowMs: Long,
    download: () -> String,
    decode: (String) -> T,
    onStored: () -> Unit = {}
): T {
    val copy = { runCatching { decode(file.readText()) } }
    if (file.exists() && nowMs - file.lastModified() < maxAgeMs) {
        copy().onSuccess { return it }
    }
    try {
        val text = download()
        val decoded = decode(text)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        onStored()
        return decoded
    } catch (e: Exception) {
        if (file.exists()) copy().onSuccess { return it }
        throw e
    }
}

/**
 * Jak [readThroughCopy], ale po [checkEveryMs] najpierw pyta o znacznik wersji: gdy jest taki sam jak
 * przy zapisie kopii, kopia zostaje na kolejny okres bez pobierania całego pliku.
 */
internal fun <T> readVersionedCopy(
    file: File,
    stampFile: File,
    checkEveryMs: Long,
    nowMs: Long,
    fetchStamp: () -> String?,
    download: () -> String,
    decode: (String) -> T
): T {
    val due = !file.exists() || nowMs - file.lastModified() >= checkEveryMs
    val stamp = if (due) runCatching(fetchStamp).getOrNull() else null
    if (stamp != null && file.exists() && stampFile.exists() && stampFile.readText() == stamp) {
        file.setLastModified(nowMs)
    }
    return readThroughCopy(file, checkEveryMs, nowMs, download, decode, onStored = {
        if (stamp != null) stampFile.writeText(stamp) else stampFile.delete()
    })
}

@Serializable
private class PrzemiennikiNetExport(val repeaters: List<PrzemiennikiNetRepeater> = emptyList())

// Serwis podaje liczby raz w cudzysłowach, raz bez – wszystko czytamy jako tekst
@Serializable
private class PrzemiennikiNetRepeater(
    val qra: String? = null,
    val status: String? = null,
    val visible: String? = null,
    val deleted: String? = null,
    @SerialName("qrg_tx") val qrgTx: String? = null,
    @SerialName("qrg_rx") val qrgRx: String? = null,
    @SerialName("mode_txt") val modeTxt: String? = null,
    @SerialName("ctcss_rx") val ctcssRx: String? = null,
    val qth: String? = null,
    val locator: String? = null,
    val lat: String? = null,
    val lng: String? = null,
    val loclat: String? = null,
    val loclng: String? = null
)

class RepeaterDataSourceFetcher(private val client: OkHttpClient = HttpClients.default) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val przemiennikiNetJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Przemienniki z wybranego źródła. Bazy polskie zwracają całą bazę; zagraniczne – obszar ok. 200 km
     * wokół [position] (bez pozycji nie da się ich zapytać).
     */
    suspend fun fetchRepeaters(
        source: String,
        context: Context,
        position: LatLon?
    ): List<Repeater> = withContext(Dispatchers.IO) {
        // Pozostałości po źródle RepeaterBook
        File(context.applicationContext.filesDir, "repeaters").listFiles { f -> f.name.startsWith("rb_") }?.forEach { it.delete() }
        when (source) {
            RepeaterSources.PRZEMIENNIKI_NET -> fetchPrzemiennikiNet(context).map { it.copy(source = "przemienniki.net") }
            RepeaterSources.PRZEMIENNIKI_EU -> fetchPrzemiennikiEu(context).map { it.copy(source = "przemienniki.eu") }
            else -> {
                val pos = position ?: throw IllegalStateException(AppStrings.get(R.string.err_rep_no_position))
                val box = GeoBox.around(pos.lat, pos.lon, InternationalRepeaters.FETCH_RADIUS_KM)
                when (source) {
                    RepeaterSources.HEARHAM -> InternationalRepeaters.hearHam(context, box)
                    RepeaterSources.REPEATERMAP -> InternationalRepeaters.repeatermap(box)
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

    // Autor przemienniki.eu prosi o rzadkie pobieranie, bo dane zmieniają się rzadko:
    // kopia na dysku jest ważna 7 dni – także przy ręcznym odświeżaniu
    private suspend fun przemiennikiEuExport(context: Context): List<RepeaterResponse> = euExportMutex.withLock {
        readThroughCopy(File(repeatersDir(context), "przemienniki_eu.json"), EU_EXPORT_MAX_AGE_MS, System.currentTimeMillis(), ::downloadPrzemiennikiEu, { text ->
            json.decodeFromString<List<RepeaterResponse>>(text).ifEmpty { throw Exception(AppStrings.get(R.string.err_empty_response_from, "przemienniki.eu")) }
        })
    }

    private fun repeatersDir(context: Context) = File(context.applicationContext.filesDir, "repeaters").apply { mkdirs() }

    private fun downloadPrzemiennikiEu(): String {
        val request = Request.Builder()
            .url("https://przemienniki.eu/api/export/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception(AppStrings.get(R.string.err_http_from, response.code, "przemienniki.eu"))
            return response.body?.string() ?: throw Exception(AppStrings.get(R.string.err_empty_response_from, "przemienniki.eu"))
        }
    }

    private suspend fun fetchPrzemiennikiEu(context: Context): List<Repeater> =
        przemiennikiEuExport(context).mapNotNull { resp ->
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

    // rpts.json ma ok. 4 MB, więc raz na dobę pytamy tylko o timestamp.xml, a całą bazę pobieramy,
    // gdy się zmienił. radar.xml nie jest potrzebny: to podzbiór rpts.json (wpisy z country = pl)
    private suspend fun fetchPrzemiennikiNet(context: Context): List<Repeater> = netExportMutex.withLock {
        val dir = repeatersDir(context)
        readVersionedCopy(
            file = File(dir, "przemienniki_net.json"),
            stampFile = File(dir, "przemienniki_net.timestamp"),
            checkEveryMs = NET_CHECK_EVERY_MS,
            nowMs = System.currentTimeMillis(),
            fetchStamp = { przemiennikiNetTimestamp(downloadPrzemiennikiNet("timestamp.xml")) },
            download = { downloadPrzemiennikiNet("rpts.json") },
            decode = { text ->
                parsePrzemiennikiNet(text).ifEmpty { throw Exception(AppStrings.get(R.string.err_empty_response_from, "przemienniki.net")) }
            }
        )
    }

    private fun downloadPrzemiennikiNet(name: String): String {
        val request = Request.Builder()
            .url("https://przemienniki.net/export/$name/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception(AppStrings.get(R.string.err_http_from, response.code, "przemienniki.net/export/$name"))
            return response.body?.string() ?: throw Exception(AppStrings.get(R.string.err_empty_response_from, "przemienniki.net"))
        }
    }

    internal fun przemiennikiNetTimestamp(xml: String): String? =
        NET_TIMESTAMP.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    internal fun parsePrzemiennikiNet(text: String): List<Repeater> =
        przemiennikiNetJson.decodeFromString<PrzemiennikiNetExport>(text).repeaters.mapNotNull { r ->
            val callsign = r.qra?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (r.deleted == "1" || r.visible == "0") return@mapNotNull null
            // qrg_tx nadaje przemiennik, czyli na niej odbiera użytkownik
            val userRx = r.qrgTx?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: return@mapNotNull null
            val userTx = r.qrgRx?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: userRx
            // Pozycja z lokatora, gdy nie ma dokładnej; bez żadnej nie da się policzyć odległości
            val lat = coordinate(r.lat) ?: coordinate(r.loclat) ?: return@mapNotNull null
            val lon = coordinate(r.lng) ?: coordinate(r.loclng) ?: return@mapNotNull null
            val ctcss = r.ctcssRx?.trim().orEmpty()
            Repeater(
                callsign = callsign,
                qth = r.qth?.trim().orEmpty().ifBlank { r.locator?.trim().orEmpty() }.ifBlank { "Przemienniki.net" },
                modes = normalizeModes(r.modeTxt.orEmpty().split('+')).ifEmpty { listOf("FM") },
                userRx = userRx,
                userTx = userTx,
                requiredCtcss = if (ctcss.isEmpty() || ctcss.toDoubleOrNull() == 0.0) "-" else ctcss,
                status = NET_STATUS[r.status] ?: "unknown",
                latitude = lat,
                longitude = lon
            )
        }

    private fun coordinate(value: String?): Double? = value?.toDoubleOrNull()?.takeIf { it != 0.0 }

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
        const val EU_EXPORT_MAX_AGE_MS = 7 * 24 * 3_600_000L
        val euExportMutex = Mutex()
        const val NET_CHECK_EVERY_MS = 24 * 3_600_000L
        val netExportMutex = Mutex()
        val NET_TIMESTAMP = Regex("""<timestamp>([^<]*)</timestamp>""")
        // Maski "status" z sekcji masks w rpts.json
        val NET_STATUS = mapOf("1" to "working", "2" to "off", "8" to "testing", "16" to "building", "32" to "planned")
    }
}
