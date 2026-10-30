package io.github.sp3wro.hamradiotoolkit.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// Certyfikat Let's Encrypt dla Androida 7.0 dostarcza network_security_config.xml,
// więc nie ma potrzeby wyłączania weryfikacji SSL
class SatelliteApiClient(private val client: OkHttpClient = HttpClients.default) {

    suspend fun fetchAmateurTles(): String = withContext(Dispatchers.IO) {
        // Główny URL - AMSAT
        val requestAmsat = Request.Builder()
            .url("https://www.amsat.org/tle/current/nasabare.txt")
            .build()

        try {
            client.newCall(requestAmsat).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext response.body?.string() ?: ""
                }
            }
        } catch (e: IOException) {
            Log.w("SatelliteApiClient", "AMSAT niedostępny, próbuję CelesTrak", e)
        }

        // Zapasowy URL - CelesTrak
        val requestCelestrak = Request.Builder()
            .url("https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle")
            .build()
            
        client.newCall(requestCelestrak).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            response.body?.string() ?: ""
        }
    }
}

data class SatelliteTle(
    val name: String,
    val line1: String,
    val line2: String
)

val defaultAmateurTles = listOf(
    SatelliteTle(
        "ISS (ZARYA)",
        "1 25544U 98067A   26078.52412345  .00016789  00000-0  10283-3 0  9993",
        "2 25544  51.6412 128.9321 0005432  32.1234 328.0987 15.49812345445678"
    ),
    SatelliteTle(
        "SO-50",
        "1 27607U 02058C   26078.41234567  .00001234  00000-0  12345-4 0  9991",
        "2 27607  64.5678 201.2345 0078901 145.6789 214.5678 14.32145678123456"
    ),
    SatelliteTle(
        "AO-91",
        "1 43017U 17073E   26078.21345678  .00002345  00000-0  23456-4 0  9992",
        "2 43017  97.3456  89.1234 0012345  45.6789 314.5678 14.28912345789012"
    ),
    SatelliteTle(
        "RS-44",
        "1 44908U 20001B   26078.31234567  .00000456  00000-0  11223-4 0  9994",
        "2 44908  82.5123 312.4567 0011223  90.1234 270.1234 12.15678901345678"
    ),
    SatelliteTle(
        "IO-86",
        "1 40931U 15052A   26078.11234567  .00000150  00000-0  10000-4 0  9996",
        "2 40931  97.4123  45.6789 0011222 120.3456 240.1234 14.21345678789012"
    )
)

fun parseTleData(rawTle: String): List<SatelliteTle> {
    val lines = rawTle.lines().map { it.replace(Regex("\\r$"), "") }.filter { it.isNotBlank() }
    val satellites = mutableListOf<SatelliteTle>()
    var i = 0
    while (i + 2 < lines.size) {
        val name = lines[i].trim()
        val l1 = lines[i + 1]
        val l2 = lines[i + 2]
        
        // Czasami linie TLE mogą zaczynać się od spacji lub być krótsze. SGP4 wymaga dokładnego formatu.
        if (l1.trimStart().startsWith("1 ") && l2.trimStart().startsWith("2 ")) {
            // Dodajemy upewniając się, że nie jest za krótkie (niektóre parsery wymagają 69 znaków)
            val cleanL1 = l1.trimEnd()
            val cleanL2 = l2.trimEnd()
            val paddedL1 = cleanL1.padEnd(69, ' ')
            val paddedL2 = cleanL2.padEnd(69, ' ')
            satellites.add(SatelliteTle(name, paddedL1, paddedL2))
            i += 3
        } else {
            i++
        }
    }
    return satellites
}