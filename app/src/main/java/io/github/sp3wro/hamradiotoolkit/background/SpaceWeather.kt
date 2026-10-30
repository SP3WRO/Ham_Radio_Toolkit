package io.github.sp3wro.hamradiotoolkit.background

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class KpReading(val timeTag: String, val kp: Double)

data class GeomagneticAlert(val productId: String, val issued: String, val issuedMs: Long, val headline: String)

// Dane NOAA SWPC: services.swpc.noaa.gov/products/noaa-planetary-k-index.json i alerts.json
object SpaceWeather {
    const val KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"
    const val ALERTS_URL = "https://services.swpc.noaa.gov/products/alerts.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** Najnowszy odczyt Kp. Obsługuje format obiektów ({"time_tag","Kp"}) i starszy – tablicę z nagłówkiem. */
    fun latestKp(body: String): KpReading? {
        val rows = json.parseToJsonElement(body).jsonArray
        val readings = rows.mapNotNull { row ->
            when (row) {
                is JsonObject -> {
                    val time = (row["time_tag"] as? JsonPrimitive)?.contentOrNull
                    val kp = ((row["Kp"] ?: row["kp"]) as? JsonPrimitive)?.doubleOrNull
                    if (time != null && kp != null) KpReading(time, kp) else null
                }
                is JsonArray -> {
                    val time = row.getOrNull(0)?.jsonPrimitive?.contentOrNull
                    val kp = row.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    if (time != null && kp != null) KpReading(time, kp) else null
                }
                else -> null
            }
        }
        return readings.maxByOrNull { it.timeTag }
    }

    /** Ostrzeżenia geomagnetyczne (WATCH/WARNING/ALERT) wydane po [sinceMs]. */
    fun geomagneticAlerts(body: String, sinceMs: Long): List<GeomagneticAlert> {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val message = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val issued = (obj["issue_datetime"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val productId = (obj["product_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val issuedMs = runCatching { format.parse(issued.take(19))!!.time }.getOrNull() ?: return@mapNotNull null
            if (issuedMs < sinceMs) return@mapNotNull null
            val headline = message.lines().map { it.trim() }.firstOrNull { line ->
                (line.startsWith("WATCH:") || line.startsWith("WARNING:") || line.startsWith("ALERT:")) &&
                    line.contains("Geomagnetic", ignoreCase = true)
            } ?: return@mapNotNull null
            GeomagneticAlert(productId, issued, issuedMs, headline)
        }.sortedBy { it.issuedMs }
    }

    /** Skala NOAA: Kp 5 = G1 ... Kp 9 = G5. */
    fun gScale(kp: Double): Int = when {
        kp >= 9.0 -> 5
        kp >= 8.0 -> 4
        kp >= 7.0 -> 3
        kp >= 6.0 -> 2
        kp >= 5.0 -> 1
        else -> 0
    }

    fun describe(kp: Double): String {
        val g = gScale(kp)
        val base = String.format(Locale.US, "Kp = %.1f", kp) + if (g > 0) AppStrings.get(R.string.sw_storm_g, g) else ""
        val hint = when {
            kp >= 7.0 -> AppStrings.get(R.string.sw_kp7)
            kp >= 6.0 -> AppStrings.get(R.string.sw_kp6)
            kp >= 5.0 -> AppStrings.get(R.string.sw_kp5)
            else -> AppStrings.get(R.string.sw_elevated)
        }
        return "$base. $hint"
    }
}
