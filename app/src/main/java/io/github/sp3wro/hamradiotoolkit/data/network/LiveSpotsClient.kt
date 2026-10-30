package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private fun potaTimeFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

data class LiveSpot(
    val program: String,      // "POTA" albo "WWFF"
    val activator: String,
    val frequencyKhz: Double,
    val mode: String,
    val reference: String,
    val referenceName: String,
    val spotter: String,
    val comment: String,
    val timeEpochSec: Long,
    val latitude: Double?,
    val longitude: Double?
)

@Serializable
private data class PotaSpot(
    val activator: String = "",
    val frequency: String = "",
    val mode: String = "",
    val reference: String = "",
    val name: String? = null,
    val spotTime: String = "",
    val spotter: String = "",
    val comments: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
private data class WwffSpot(
    val activator: String = "",
    @SerialName("frequency_khz") val frequencyKhz: Double = 0.0,
    val mode: String = "",
    val reference: String = "",
    @SerialName("reference_name") val referenceName: String? = null,
    val remarks: String? = null,
    val spotter: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("spot_time") val spotTime: Long = 0
)

/** Spoty aktywatorów na żywo: POTA (api.pota.app) i WWFF (spots.wwff.co). SOTA – patrz uwaga w ekranie. */
class LiveSpotsClient(private val client: OkHttpClient = HttpClients.default) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Zwraca spoty z obu programów; błąd jednego źródła nie blokuje drugiego (lista błędów w [errors]). */
    suspend fun fetchAll(): Pair<List<LiveSpot>, List<String>> = coroutineScope {
        val pota = async { runCatching { fetchPota() } }
        val wwff = async { runCatching { fetchWwff() } }
        val results = listOf("POTA" to pota.await(), "WWFF" to wwff.await())
        val spots = results.flatMap { it.second.getOrDefault(emptyList()) }
        val errors = results.mapNotNull { (name, r) -> r.exceptionOrNull()?.let { "$name: ${it.message}" } }
        spots.sortedByDescending { it.timeEpochSec } to errors
    }

    private suspend fun fetchPota(): List<LiveSpot> = parsePota(get("https://api.pota.app/spot/activator"))

    private suspend fun fetchWwff(): List<LiveSpot> = parseWwff(get("https://spots.wwff.co/static/spots.json"))

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException(AppStrings.get(R.string.err_empty_response))
        }
    }

    internal fun parsePota(body: String): List<LiveSpot> =
        json.decodeFromString<List<PotaSpot>>(body).mapNotNull { s ->
            val khz = s.frequency.toDoubleOrNull() ?: return@mapNotNull null
            LiveSpot(
                program = "POTA",
                activator = s.activator.uppercase(),
                frequencyKhz = khz,
                mode = s.mode.uppercase(),
                reference = s.reference.uppercase(),
                referenceName = s.name.orEmpty(),
                spotter = s.spotter,
                comment = s.comments.orEmpty(),
                // spotTime POTA jest w UTC, bez strefy: "2026-10-20T20:22:17" (java.time wymaga API 26)
                timeEpochSec = runCatching { potaTimeFormat().parse(s.spotTime)!!.time / 1000 }.getOrDefault(0L),
                latitude = s.latitude,
                longitude = s.longitude
            )
        }

    internal fun parseWwff(body: String): List<LiveSpot> =
        json.decodeFromString<List<WwffSpot>>(body).filter { it.frequencyKhz > 0 }.map { s ->
            LiveSpot(
                program = "WWFF",
                activator = s.activator.uppercase(),
                frequencyKhz = s.frequencyKhz,
                mode = s.mode.uppercase(),
                reference = s.reference.uppercase(),
                referenceName = s.referenceName.orEmpty(),
                spotter = s.spotter,
                comment = s.remarks.orEmpty(),
                timeEpochSec = s.spotTime,
                latitude = s.latitude,
                longitude = s.longitude
            )
        }
}
