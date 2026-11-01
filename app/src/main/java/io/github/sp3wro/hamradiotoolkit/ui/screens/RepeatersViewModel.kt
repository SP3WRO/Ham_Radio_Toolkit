package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.utils.localizedString
import io.github.sp3wro.hamradiotoolkit.R
import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sp3wro.hamradiotoolkit.data.model.Repeater
import io.github.sp3wro.hamradiotoolkit.data.model.RepeaterGroup
import io.github.sp3wro.hamradiotoolkit.data.network.InternationalRepeaters
import io.github.sp3wro.hamradiotoolkit.data.network.RepeaterDataSourceFetcher
import io.github.sp3wro.hamradiotoolkit.data.network.RepeaterSources
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RepeatersViewModel(application: Application) : AndroidViewModel(application) {
    private val fetcher = RepeaterDataSourceFetcher()

    // Jedna pozycja listy = jeden przemiennik ze wszystkimi częstotliwościami
    private val _repeaters = MutableStateFlow<List<RepeaterGroup>>(emptyList())
    val repeaters: StateFlow<List<RepeaterGroup>> = _repeaters.asStateFlow()

    // Najbliższy przemiennik dla strony głównej – niezależny od filtrów i promienia ekranu Przemienniki
    private val _nearest = MutableStateFlow<RepeaterGroup?>(null)
    val nearest: StateFlow<RepeaterGroup?> = _nearest.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Filtry
    private val _selectedBand = MutableStateFlow("ALL") // ALL, 2M, 70CM, 10M, 23CM, 6M
    val selectedBand: StateFlow<String> = _selectedBand.asStateFlow()

    private val _selectedModeFilter = MutableStateFlow("ALL") // ALL, FM, DMR, C4FM, D-STAR
    val selectedModeFilter: StateFlow<String> = _selectedModeFilter.asStateFlow()

    private val _onlyWorking = MutableStateFlow(false)
    val onlyWorking: StateFlow<Boolean> = _onlyWorking.asStateFlow()

    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private var currentRadiusKm: Int = 100
    private var currentDataSource: String = RepeaterSources.PRZEMIENNIKI_NET
    // Środek obszaru, dla którego pobrano dane zagraniczne
    private var fetchedAround: LatLon? = null
    private var fetchJob: Job? = null

    @Volatile private var fullDatabase: List<Repeater> = emptyList()
    private var refreshJob: Job? = null

    fun updateLocation(lat: Double, lon: Double) {
        if (currentLat == lat && currentLon == lon) return
        currentLat = lat
        currentLon = lon
        val around = fetchedAround
        val movedFar = !RepeaterSources.isPolish(currentDataSource) &&
            (around == null || GreatCircle.distanceKm(around, LatLon(lat, lon)) > 100)
        if ((fullDatabase.isEmpty() || movedFar) && !_isLoading.value) {
            fetchRepeaters()
        } else {
            refreshList()
        }
    }

    fun updateRadius(radiusKm: Int) {
        if (currentRadiusKm != radiusKm) {
            currentRadiusKm = radiusKm
            refreshList()
        }
    }

    fun updateDataSource(source: String) {
        if (currentDataSource != source || fullDatabase.isEmpty()) {
            currentDataSource = source
            fullDatabase = emptyList()
            fetchRepeaters()
        }
    }

    fun setBandFilter(band: String) {
        _selectedBand.value = band
        refreshList()
    }

    fun setModeFilter(mode: String) {
        _selectedModeFilter.value = mode
        refreshList()
    }

    fun setOnlyWorking(working: Boolean) {
        _onlyWorking.value = working
        refreshList()
    }

    fun fetchRepeaters() {
        val source = currentDataSource
        val lat = currentLat
        val lon = currentLon
        // Bez pozycji zagranicznych źródeł nie da się zapytać – poczekamy na updateLocation
        if (!RepeaterSources.isPolish(source) && (lat == null || lon == null)) return
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val position = if (lat != null && lon != null) LatLon(lat, lon) else null
                // Scalenie wpisów tego samego przemiennika (np. osobno FM i DMR) – jedna karta z kilkoma emisjami
                fullDatabase = InternationalRepeaters.merge(fetcher.fetchRepeaters(source, getApplication(), position))
                fetchedAround = position
                refreshList()
            } catch (e: Exception) {
                _error.value = getApplication<Application>().localizedString(R.string.rep_download_error, RepeaterSources.label(source), e.message.orEmpty())
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Szczegóły DMR z RadioID (na żądanie, po rozwinięciu karty). */
    suspend fun dmrDetails(repeater: Repeater): InternationalRepeaters.DmrDetails? =
        runCatching { InternationalRepeaters.radioIdDetails(repeater.callsign, repeater.userRx) }.getOrNull()

    // Liczenie odległości do całej bazy w tle – wcześniej szło na wątku głównym przy każdym odczycie GPS.
    // Nowe wywołanie przerywa poprzednie, więc przy szybkiej jeździe nie kolejkują się zaległe przeliczenia.
    private fun refreshList() {
        refreshJob?.cancel()
        val lat = currentLat
        val lon = currentLon
        val radiusKm = currentRadiusKm
        val band = _selectedBand.value
        val mode = _selectedModeFilter.value
        val workingOnly = _onlyWorking.value
        val database = fullDatabase
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            val (list, nearest) = buildList(database, lat, lon, radiusKm, band, mode, workingOnly)
            ensureActive()
            _repeaters.value = list
            _nearest.value = nearest
        }
    }

    private fun buildList(
        database: List<Repeater>, lat: Double?, lon: Double?, radiusKm: Int,
        band: String, mode: String, workingOnly: Boolean
    ): Pair<List<RepeaterGroup>, RepeaterGroup?> {
        val results = FloatArray(1)
        val withDistance = database.mapNotNull { rep ->
            // Pomijamy przemienniki bez lokalizacji, jeśli mamy współrzędne użytkownika
            if (lat != null && lon != null && (rep.latitude == 0.0 || rep.longitude == 0.0)) {
                return@mapNotNull null
            }

            val newRep = rep.copy()
            if (lat != null && lon != null) {
                Location.distanceBetween(lat, lon, newRep.latitude, newRep.longitude, results)
                newRep.distanceKm = Math.round((results[0] / 1000.0) * 10.0) / 10.0
            }
            newRep
        }
        val nearest = if (lat != null && lon != null) {
            withDistance.minByOrNull { it.distanceKm }?.let { closest ->
                RepeaterGroup.of(withDistance.filter { it.callsign.equals(closest.callsign, ignoreCase = true) })
                    .firstOrNull { closest in it.channels }
            }
        } else null

        val filtered = withDistance.filter { rep ->
            // 1. Filtr promienia
            val matchesRadius = (lat == null || rep.distanceKm <= radiusKm)

            // 2. Filtr pasma (Band) – zakresy obejmują wszystkie regiony IARU
            val rxFreq = rep.userRx
            val matchesBand = when (band) {
                "10M" -> rxFreq in 28.0..29.7
                "6M" -> rxFreq in 50.0..54.0
                "2M" -> rxFreq in 144.0..148.0
                "70CM" -> rxFreq in 420.0..450.0
                "23CM" -> rxFreq in 1240.0..1300.0
                else -> true
            }

            // 3. Filtr trybu emisji (Mode)
            val matchesMode = when (mode) {
                "FM" -> rep.modes.any { it.uppercase().contains("FM") || it.uppercase().contains("ANALOG") }
                "DMR" -> rep.modes.any { it.uppercase().contains("DMR") }
                "C4FM" -> rep.modes.any { it.uppercase().contains("C4FM") || it.uppercase().contains("FUSION") }
                "DSTAR" -> rep.modes.any { it.uppercase().contains("DSTAR") || it.uppercase().contains("D-STAR") }
                else -> true
            }

            // 4. Filtr statusu
            val matchesStatus = if (workingOnly) rep.status == "working" else true

            matchesRadius && matchesBand && matchesMode && matchesStatus
        }.distinctBy { it.listKey }.sortedBy { it.distanceKm }

        return RepeaterGroup.of(filtered) to nearest
    }
}
