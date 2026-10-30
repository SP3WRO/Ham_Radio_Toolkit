package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.utils.localizedString
import io.github.sp3wro.hamradiotoolkit.R
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sp3wro.hamradiotoolkit.data.model.SolarData
import io.github.sp3wro.hamradiotoolkit.data.model.TropocastFrame
import io.github.sp3wro.hamradiotoolkit.data.network.SolarApi
import io.github.sp3wro.hamradiotoolkit.data.network.SolarXmlParser
import io.github.sp3wro.hamradiotoolkit.data.network.TropocastApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PropagationViewModel(application: Application) : AndroidViewModel(application) {
    private val solarApi = SolarApi.create()
    private val tropocastApi = TropocastApi.create()

    // Ostatnie dane z hamqsl.com – bez sieci ekran pokazuje je zamiast pustej strony
    private val solarCache = File(application.filesDir, "solar_cache.xml")

    private val _solarData = MutableStateFlow<SolarData?>(null)
    val solarData: StateFlow<SolarData?> = _solarData.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Tropocast State
    private val _tropocastFrames = MutableStateFlow<List<TropocastFrame>>(emptyList())
    private val _tropocastMetadataRun = MutableStateFlow("")

    private val _tropocastImageUrl = MutableStateFlow<String?>(null)
    val tropocastImageUrl: StateFlow<String?> = _tropocastImageUrl.asStateFlow()

    private val _tropocastError = MutableStateFlow<String?>(null)
    val tropocastError: StateFlow<String?> = _tropocastError.asStateFlow()

    // -6, -3, 0, 3, 6 (offset w godzinach)
    private val _tropocastTimeOffset = MutableStateFlow(0)
    val tropocastTimeOffset: StateFlow<Int> = _tropocastTimeOffset.asStateFlow()

    // Obszar ustawia ekran (z preferencji) – dopiero wtedy pobieramy metadane, żeby nie ściągać
    // najpierw domyślnej Polski, której odpowiedź mogła nadpisać właściwy obszar
    private var currentTropocastArea: String? = null
    private var tropocastJob: Job? = null

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { if (solarCache.exists()) SolarXmlParser.parse(solarCache.readText()) else null }.getOrNull()
            }
            if (cached != null && _solarData.value == null) _solarData.value = cached
        }
        fetchSolarData()
    }

    fun fetchSolarData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val rawXml = solarApi.getSolarDataRaw()
                val parsedData = SolarXmlParser.parse(rawXml)
                _solarData.value = parsedData
                withContext(Dispatchers.IO) { runCatching { solarCache.writeText(rawXml) } }
            } catch (e: Exception) {
                _error.value = if (_solarData.value != null) {
                    getApplication<Application>().localizedString(R.string.prop_offline_cached)
                } else {
                    getApplication<Application>().localizedString(R.string.prop_download_error, e.message.orEmpty())
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun fetchTropocastMetadata(area: String) {
        tropocastJob?.cancel()
        tropocastJob = viewModelScope.launch {
            _tropocastError.value = null
            try {
                val metadata = tropocastApi.getMetadata(area = area)
                _tropocastMetadataRun.value = metadata.run
                _tropocastFrames.value = metadata.frames

                // Po pobraniu od razu budujemy URL dla offsetu 0 (Teraz)
                updateTropocastImageUrl()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PropagationViewModel", "Tropocast niedostępny", e)
                _tropocastError.value = getApplication<Application>().localizedString(R.string.prop_tropocast_error, e.message.orEmpty())
            }
        }
    }

    fun setTropocastOffset(offsetHours: Int) {
        _tropocastTimeOffset.value = offsetHours
        updateTropocastImageUrl()
    }

    fun setTropocastArea(area: String) {
        if (currentTropocastArea != area) {
            currentTropocastArea = area
            // Jeśli obszar sie zmienil, musimy pobrac metadane dla nowego obszaru
            // (w szczegolnosci nowe granice / domyslny `run`)
            _tropocastFrames.value = emptyList()
            _tropocastImageUrl.value = null
            fetchTropocastMetadata(area)
        }
    }

    private fun updateTropocastImageUrl() {
        val frames = _tropocastFrames.value
        val area = currentTropocastArea ?: return
        if (frames.isEmpty()) return

        val offsetHours = _tropocastTimeOffset.value

        // Czas GMT teraz plus offset
        val targetTimeMs = System.currentTimeMillis() + (offsetHours * 60 * 60 * 1000L)
        val targetDate = Date(targetTimeMs)

        // Z formatu "2026-09-17T03:00:00Z" (ISO8601) szukamy najblizszego
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")

        var bestFrame = frames.first()
        var minDiff = Long.MAX_VALUE

        for (frame in frames) {
            try {
                val frameDate = sdf.parse(frame.validAt)
                if (frameDate != null) {
                    val diff = Math.abs(frameDate.time - targetDate.time)
                    if (diff < minDiff) {
                        minDiff = diff
                        bestFrame = frame
                    }
                }
            } catch (e: Exception) { continue }
        }

        // Zbuduj URL w zaleznosci od tego czy klatka ma wlasny "run" czy nie
        val baseUrl = "https://tropocast.eu/api/v1/tropo"
        val url = if (bestFrame.run != null && bestFrame.forecastHour != null) {
            "$baseUrl/archive/frames/${bestFrame.run}/${bestFrame.forecastHour}.png?model=full&area=$area&source=icon-eu"
        } else {
            val runString = _tropocastMetadataRun.value.ifEmpty { "current" }
            "$baseUrl/frames/${bestFrame.hour}.png?run=${runString}&model=full&area=$area&source=icon-eu"
        }

        _tropocastImageUrl.value = url
    }
}
