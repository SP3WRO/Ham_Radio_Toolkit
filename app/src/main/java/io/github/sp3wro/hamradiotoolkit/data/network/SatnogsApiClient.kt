package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class SatnogsTransmitter(
    val uuid: String? = null,
    val description: String? = null,
    val alive: Boolean = false,
    val type: String? = null,
    val uplink_low: Long? = null,
    val downlink_low: Long? = null,
    val mode: String? = null,
    val baud: Double? = null,
    val norad_cat_id: Long? = null,
    val status: String? = null
)

class SatnogsApiClient(private val client: OkHttpClient = HttpClients.default) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchTransmitters(): List<SatnogsTransmitter> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://db.satnogs.org/api/transmitters/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} z SatNOGS")
            val body = response.body?.string() ?: throw IOException(AppStrings.get(R.string.err_empty_response_from, "SatNOGS"))
            json.decodeFromString<List<SatnogsTransmitter>>(body)
        }
    }
}

// Satelita uznajemy za aktywny, jeśli SatNOGS ma dla niego choć jeden działający transmiter
fun activeNoradIds(transmitters: List<SatnogsTransmitter>): Set<Long> =
    transmitters
        .filter { it.alive && it.status.equals("active", ignoreCase = true) }
        .mapNotNull { it.norad_cat_id }
        .toSet()

fun extractNoradId(line2: String): String {
    return try {
        line2.substring(2, 7).trim()
    } catch (e: Exception) {
        ""
    }
}
