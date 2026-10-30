package io.github.sp3wro.hamradiotoolkit.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sp3wro.hamradiotoolkit.data.callbook.AprsHeardStations
import io.github.sp3wro.hamradiotoolkit.data.model.AprsPacket
import io.github.sp3wro.hamradiotoolkit.data.network.AprsClient
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Nasłuch APRS-IS działający przez cały czas, gdy aplikacja jest na ekranie (niezależnie od karty).
 * Ponowne połączenie tylko przy zmianie znaku/promienia lub większym przemieszczeniu;
 * po zerwaniu łączy się sam z narastającym odstępem (5 s ... 2 min).
 */
class AprsViewModel : ViewModel() {

    private data class Station(val callsign: String, val position: LatLon, val radiusKm: Int)

    private val _packets = MutableStateFlow<List<AprsPacket>>(emptyList())
    val packets: StateFlow<List<AprsPacket>> = _packets.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var station: Station? = null          // aktualne parametry (pozycja z GPS)
    private var connectedStation: Station? = null // parametry, z którymi zalogowano się do serwera
    private var active = false                    // aplikacja na pierwszym planie
    private var client: AprsClient? = null
    private var listenJob: Job? = null

    /** Włącza/wyłącza nasłuch – wołane przy przejściu aplikacji na pierwszy plan / w tło. */
    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        if (isActive) reconnect() else stop()
    }

    fun updateStation(callsign: String, position: LatLon, radiusKm: Int) {
        val newStation = Station(callsign, position, radiusKm)
        station = newStation
        refreshDistances()

        val current = connectedStation
        // Filtr serwera to okrąg wokół pozycji z logowania – przy większym ruchu trzeba go odświeżyć
        val moved = current != null && GreatCircle.distanceKm(current.position, position) > max(2.0, radiusKm * 0.1)
        if (current == null || current.callsign != callsign || current.radiusKm != radiusKm || moved || listenJob?.isActive != true) {
            if (active) reconnect()
        }
    }

    private fun reconnect() {
        stop()
        val initial = station ?: return
        connectedStation = initial
        listenJob = viewModelScope.launch {
            var backoffMs = 5_000L
            while (isActive) {
                val s = station ?: break
                connectedStation = s
                val c = AprsClient(s.callsign, s.position.lat, s.position.lon, s.radiusKm)
                client = c
                c.connectAndListen(onConnected = {
                    _isConnected.value = true
                    backoffMs = 5_000L
                }).collect { addPacket(it) }
                _isConnected.value = false
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, 120_000L)
            }
        }
    }

    private fun stop() {
        listenJob?.cancel()
        listenJob = null
        client?.close()
        client = null
        _isConnected.value = false
    }

    private fun addPacket(packet: AprsPacket) {
        // Pozycja przyda się wyszukiwarce znaków jako lokator stacji
        AprsHeardStations.record(packet.callsign, packet.latitude, packet.longitude, packet.timestampMs)
        val s = station ?: return
        packet.distanceKm = roundKm(GreatCircle.distanceKm(s.position, LatLon(packet.latitude, packet.longitude)))
        // Serwer filtruje z zapasem – na liście tylko stacje w zadanym promieniu
        if (packet.distanceKm > s.radiusKm) return
        val hourAgo = System.currentTimeMillis() - 60 * 60_000L
        _packets.value = (_packets.value.filter { it.callsign != packet.callsign && it.timestampMs > hourAgo } + packet)
            .sortedBy { it.distanceKm }
    }

    private fun refreshDistances() {
        val s = station ?: return
        val current = _packets.value
        if (current.isEmpty()) return
        // Te same obiekty dla niezmienionych odległości – inaczej każdy odczyt GPS przerysowuje całą listę
        val updated = current.map {
            val km = roundKm(GreatCircle.distanceKm(s.position, LatLon(it.latitude, it.longitude)))
            if (km == it.distanceKm) it else it.copy(distanceKm = km)
        }.filter { it.distanceKm <= s.radiusKm }.sortedBy { it.distanceKm }
        if (updated.size != current.size || updated.indices.any { updated[it] !== current[it] }) {
            _packets.value = updated
        }
    }

    private fun roundKm(km: Double) = Math.round(km * 10.0) / 10.0

    /**
     * Wysyła pozycję stacji; bez aktywnego połączenia otwiera chwilowe.
     * Zwraca false (i nic nie wysyła), gdy nie ma poprawnego znaku krótkofalarskiego.
     */
    fun sendBeacon(callsign: String, ssid: String, icon: String, comment: String, position: LatLon): Boolean {
        if (Callsigns.aprsTransmitCall(callsign) == null) return false
        viewModelScope.launch {
            // Bieżące połączenie mogło zostać zalogowane tylko do odczytu (np. przed wpisaniem znaku)
            val active = client?.takeIf { it.isOpen && _isConnected.value && it.canTransmit }
            if (active != null) {
                active.sendBeacon(ssid, icon, comment, position.lat, position.lon)
                return@launch
            }
            // Chwilowe połączenie tylko do wysłania pozycji
            val temp = AprsClient(callsign, position.lat, position.lon, 10)
            val tempJob = launch { temp.connectAndListen().collect {} }
            delay(1500)
            temp.sendBeacon(ssid, icon, comment, position.lat, position.lon)
            delay(500)
            temp.close()
            tempJob.cancel()
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
