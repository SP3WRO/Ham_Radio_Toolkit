package io.github.sp3wro.hamradiotoolkit.data.preferences

import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.network.AmsatSatelliteFrequency
import io.github.sp3wro.hamradiotoolkit.data.network.SatnogsTransmitter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Starsze wersje zapisywały tu też listę satelitów z n2yo ("satellites") – jest ignorowana przy odczycie
@kotlinx.serialization.Serializable
data class CachedSatelliteData(
    val timestamp: Long,
    val transmitters: List<SatnogsTransmitter> = emptyList(),
    val amsatFrequencies: List<AmsatSatelliteFrequency> = emptyList()
)

class SatelliteCacheManager(context: Context) {
    private val file = File(context.filesDir, "n2yo_satellites_cache.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun getCachedData(): CachedSatelliteData? {
        try {
            if (!file.exists()) return null
            val text = file.readText()
            val cached = json.decodeFromString<CachedSatelliteData>(text)
            val twoDaysMillis = 2 * 24 * 3600 * 1000L
            if (System.currentTimeMillis() - cached.timestamp < twoDaysMillis) {
                return cached
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się odczytać cache satelitów", e)
        }
        return null
    }

    fun saveCache(
        transmitters: List<SatnogsTransmitter>,
        amsatFrequencies: List<AmsatSatelliteFrequency> = emptyList()
    ) {
        try {
            val cached = CachedSatelliteData(
                timestamp = System.currentTimeMillis(),
                transmitters = transmitters,
                amsatFrequencies = amsatFrequencies
            )
            file.writeText(json.encodeToString(cached))
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się zapisać cache satelitów", e)
        }
    }

    private companion object {
        const val TAG = "SatelliteCacheManager"
    }
}
