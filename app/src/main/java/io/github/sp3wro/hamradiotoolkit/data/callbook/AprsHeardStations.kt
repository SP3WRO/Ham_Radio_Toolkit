package io.github.sp3wro.hamradiotoolkit.data.callbook

import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import java.util.concurrent.ConcurrentHashMap

/**
 * Pozycje stacji odebrane z APRS-IS (nasłuch, który aplikacja i tak prowadzi) – bez dodatkowych zapytań.
 * Pamiętamy tylko ostatnie 2 godziny i najwyżej 2000 stacji.
 */
object AprsHeardStations {
    private const val MAX_AGE_MS = 2L * 60 * 60 * 1000
    private const val MAX_STATIONS = 2000

    data class Heard(val callsign: String, val position: LatLon, val timeMs: Long)

    private val stations = ConcurrentHashMap<String, Heard>()

    fun record(callsign: String, lat: Double, lon: Double, timeMs: Long = System.currentTimeMillis()) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return
        val call = callsign.trim().uppercase()
        if (call.isEmpty()) return
        stations[call] = Heard(call, LatLon(lat, lon), timeMs)
        if (stations.size > MAX_STATIONS) prune()
    }

    /** Najświeższa pozycja znaku (dowolne SSID: SP3ABC, SP3ABC-9 …) z ostatnich 2 godzin. */
    fun find(callsign: String, now: Long = System.currentTimeMillis()): Heard? {
        val call = callsign.trim().uppercase()
        if (call.isEmpty()) return null
        return stations.values
            .filter { it.callsign == call || it.callsign.startsWith("$call-") }
            .filter { now - it.timeMs < MAX_AGE_MS }
            .maxByOrNull { it.timeMs }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        stations.values.removeIf { it.timeMs < cutoff }
        if (stations.size > MAX_STATIONS) {
            stations.values.sortedBy { it.timeMs }.take(stations.size - MAX_STATIONS).forEach { stations.remove(it.callsign) }
        }
    }

    internal fun clear() = stations.clear()
}
