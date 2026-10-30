package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class AmsatSatelliteFrequency(
    val name: String,
    @SerialName("norad_id") val noradId: String? = null,
    val uplink: String? = null,
    val downlink: String? = null,
    val beacon: String? = null,
    val mode: String? = null,
    val callsign: String? = null,
    @SerialName("satnogs_id") val satnogsId: String? = null
)

class AmsatFrequencyApiClient(private val client: OkHttpClient = HttpClients.default) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun fetchAmsatFrequencies(): List<AmsatSatelliteFrequency> = withContext(Dispatchers.IO) {
        val url = "https://raw.githubusercontent.com/palewire/amateur-satellite-database/main/data/amsat-active-frequencies.json"
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} z AMSAT DB")
            val body = response.body?.string() ?: throw Exception(AppStrings.get(R.string.err_empty_response_from, "AMSAT DB"))
            json.decodeFromString(body)
        }
    }
}