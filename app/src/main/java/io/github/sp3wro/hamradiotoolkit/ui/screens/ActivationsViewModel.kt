package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.utils.localizedString
import io.github.sp3wro.hamradiotoolkit.R
import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sp3wro.hamradiotoolkit.data.model.ActivationSpot
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import io.github.sp3wro.hamradiotoolkit.utils.CsvParser
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID

/** Zapis lokalnej bazy obiektów: obiekty w promieniu [ActivationsViewModel.SYNC_RADIUS_KM] od [centerLat]/[centerLon]. */
@Serializable
private data class ActivationsDb(val centerLat: Double, val centerLon: Double, val spots: List<ActivationSpot>)

class ActivationsViewModel(application: Application) : AndroidViewModel(application) {

    private val _spots = MutableStateFlow<List<ActivationSpot>>(emptyList())
    val spots: StateFlow<List<ActivationSpot>> = _spots.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // Pola czytane z wątku IO (synchronizacja) i z wątku UI (filtry, lokalizacja)
    @Volatile private var activeFilters: Set<String> = emptySet()
    @Volatile private var fullDatabase: List<ActivationSpot> = emptyList()
    @Volatile private var currentLocation: Pair<Double, Double>? = null
    @Volatile private var syncCenter: LatLon? = null
    @Volatile private var loaded = false

    private val json = Json { ignoreUnknownKeys = true }
    private val dbFile get() = File(getApplication<Application>().filesDir, "activations_world.json")

    init {
        loadLocalDatabase()
    }

    private fun loadLocalDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = dbFile
                if (file.exists()) {
                    val db = json.decodeFromString<ActivationsDb>(file.readText())
                    fullDatabase = db.spots
                    syncCenter = LatLon(db.centerLat, db.centerLon)
                } else {
                    // Przykładowe obiekty z aplikacji – do pierwszej synchronizacji
                    val text = getApplication<Application>().assets.open("activations.json").bufferedReader().use { it.readText() }
                    fullDatabase = json.decodeFromString<List<ActivationSpot>>(text).map(::migrateProgram)
                }
                loaded = true
                refreshList()
                maybeAutoSync()
            } catch (e: Exception) {
                Log.w(TAG, "Nie udało się wczytać lokalnej bazy aktywacji", e)
                loaded = true
            }
        }
    }

    private fun migrateProgram(spot: ActivationSpot) = if (spot.program == "SPFF") spot.copy(program = "WWFF") else spot

    /** Pobranie obiektów POTA, SOTA i WWFF w promieniu [SYNC_RADIUS_KM] od bieżącej pozycji (dowolny kraj). */
    fun syncDatabases() {
        val location = currentLocation
        if (location == null) {
            _status.value = getApplication<Application>().localizedString(R.string.act_no_position)
            return
        }
        if (_isSyncing.value) return
        val center = LatLon(location.first, location.second)
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            _status.value = null
            try {
                val newDb = mutableListOf<ActivationSpot>()

                // POTA – kolumny: reference,name,active,entityId,locationDesc,latitude,longitude,grid
                streamCsv("https://pota.app/all_parks_ext.csv", "reference") { row ->
                    if (row["active"] != "0") row.toSpot("POTA", center, "reference", "name", "latitude", "longitude")?.let(newDb::add)
                }
                // SOTA – pierwsza linia to tytuł, nagłówek jest w drugiej
                streamCsv("https://www.sotadata.org.uk/summitslist.csv", "SummitCode") { row ->
                    row.toSpot("SOTA", center, "SummitCode", "SummitName", "Latitude", "Longitude")?.let(newDb::add)
                }
                // WWFF – wszystkie programy krajowe (SPFF, DLFF, ONFF…)
                streamCsv("https://wwff.co/wwff-data/wwff_directory.csv", "reference") { row ->
                    if (row["status"] == "active") row.toSpot("WWFF", center, "reference", "name", "latitude", "longitude")?.let(newDb::add)
                }

                if (newDb.isNotEmpty()) {
                    fullDatabase = newDb
                    syncCenter = center
                    dbFile.writeText(json.encodeToString(ActivationsDb(center.lat, center.lon, newDb)))
                    refreshList()
                } else {
                    _status.value = getApplication<Application>().localizedString(R.string.act_download_failed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Synchronizacja baz aktywacji nie powiodła się", e)
                _status.value = getApplication<Application>().localizedString(R.string.act_sync_error, e.message.orEmpty())
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // Pliki światowe mają dziesiątki MB – czytamy linia po linii i od razu odrzucamy dalekie obiekty
    private fun streamCsv(url: String, headerMarker: String, onRow: (io.github.sp3wro.hamradiotoolkit.utils.CsvRow) -> Unit) {
        try {
            HttpClients.default.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} z $url")
                    return
                }
                val reader = response.body?.charStream()?.buffered() ?: return
                CsvParser.forEachRow(reader.lineSequence(), headerMarker, onRow)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Błąd pobierania $url", e)
        }
    }

    private fun io.github.sp3wro.hamradiotoolkit.utils.CsvRow.toSpot(
        program: String, center: LatLon, ref: String, name: String, lat: String, lon: String
    ): ActivationSpot? {
        val reference = this[ref]
        val latitude = this[lat].toDoubleOrNull() ?: return null
        val longitude = this[lon].toDoubleOrNull() ?: return null
        if (reference.isBlank()) return null
        // Szybkie odrzucenie po szerokości (~111 km/°), dokładnie – po ortodromie
        if (kotlin.math.abs(latitude - center.lat) > SYNC_RADIUS_KM / 111.0 + 0.5) return null
        if (GreatCircle.distanceKm(center, LatLon(latitude, longitude)) > SYNC_RADIUS_KM) return null
        return ActivationSpot(UUID.randomUUID().toString(), reference, this[name], program, latitude, longitude)
    }

    fun updateFilters(filters: Set<String>) {
        if (activeFilters != filters) {
            activeFilters = filters
            refreshList()
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        currentLocation = lat to lon
        refreshList()
        maybeAutoSync()
    }

    // Pierwsze uruchomienie albo wyjazd daleko od miejsca ostatniej synchronizacji – pobieramy obiekty dla nowej okolicy
    private fun maybeAutoSync() {
        val location = currentLocation ?: return
        if (!loaded || _isSyncing.value) return
        val center = syncCenter
        if (center == null || GreatCircle.distanceKm(center, LatLon(location.first, location.second)) > AUTO_SYNC_DISTANCE_KM) {
            syncDatabases()
        }
    }

    private fun refreshList() {
        val filters = activeFilters
        val location = currentLocation
        val results = FloatArray(1)
        val filtered = fullDatabase
            .filter { filters.contains(it.program) || (it.program == "WWFF" && "SPFF" in filters) }
            .map { spot ->
                val newSpot = spot.copy()
                if (location != null) {
                    Location.distanceBetween(location.first, location.second, newSpot.latitude, newSpot.longitude, results)
                    newSpot.distanceKm = Math.round((results[0] / 1000.0) * 10.0) / 10.0
                }
                newSpot
            }
            .filter { location == null || it.distanceKm <= LIST_RADIUS_KM }
            .sortedBy { it.distanceKm }
        _spots.value = filtered
    }

    companion object {
        private const val TAG = "ActivationsViewModel"
        const val SYNC_RADIUS_KM = 500.0
        const val AUTO_SYNC_DISTANCE_KM = 250.0
        const val LIST_RADIUS_KM = 100.0
    }
}
