package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.utils.UiText
import io.github.sp3wro.hamradiotoolkit.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sp3wro.hamradiotoolkit.data.model.DxSpot
import io.github.sp3wro.hamradiotoolkit.data.model.PostSpotRequest
import io.github.sp3wro.hamradiotoolkit.data.network.DxClusterApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DxClusterViewModel : ViewModel() {
    private val api = DxClusterApi.create()

    private val _rawSpots = MutableStateFlow<List<DxSpot>>(emptyList())
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastUpdatedTime = MutableStateFlow<String?>(null)
    val lastUpdatedTime: StateFlow<String?> = _lastUpdatedTime.asStateFlow()

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    // Filtry
    private val _selectedBand = MutableStateFlow("ALL") // ALL, 160M, 80M, 40M, 30M, 20M, 17M, 15M, 12M, 10M, 6M, 2M, 70CM
    val selectedBand: StateFlow<String> = _selectedBand.asStateFlow()

    private val _selectedMode = MutableStateFlow("ALL") // ALL, CW, SSB, FT8/FT4, DIGI
    val selectedMode: StateFlow<String> = _selectedMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Kraje DXCC (nazwy jak na karcie spotu); pusty zbiór = wszystkie. Filtr stosuje ekran, bo tam jest baza cty.dat.
    private val _selectedCountries = MutableStateFlow<Set<String>>(emptySet())
    val selectedCountries: StateFlow<Set<String>> = _selectedCountries.asStateFlow()

    // Przefiltrowane spoty
    val spots: StateFlow<List<DxSpot>> = combine(
        _rawSpots,
        _selectedBand,
        _selectedMode,
        _searchQuery
    ) { rawList, band, mode, query ->
        rawList.filter { spot ->
            val matchesBand = matchesBandFilter(spot.frequency, band)
            val matchesMode = matchesModeFilter(spot.frequency, spot.info, mode)
            val matchesQuery = query.isBlank() || 
                    spot.dxCall.contains(query, ignoreCase = true) || 
                    spot.deCall.contains(query, ignoreCase = true) || 
                    spot.info.contains(query, ignoreCase = true)

            matchesBand && matchesMode && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        fetchSpots()
    }

    fun setBandFilter(band: String) {
        _selectedBand.value = band
    }

    fun setModeFilter(mode: String) {
        _selectedMode.value = mode
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleCountry(country: String) {
        val current = _selectedCountries.value
        _selectedCountries.value = if (country in current) current - country else current + country
    }

    fun clearCountries() {
        _selectedCountries.value = emptySet()
    }

    fun clearFilters() {
        _selectedBand.value = "ALL"
        _selectedMode.value = "ALL"
        _searchQuery.value = ""
        _selectedCountries.value = emptySet()
    }

    fun fetchSpots() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _rawSpots.value = api.getSpots(limit = 100)
                _lastUpdatedTime.value = getCurrentTime()
            } catch (e: Exception) {
                _error.value = UiText(R.string.dx_error_download, e.message.orEmpty())
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendSpot(dxCall: String, deCall: String, frequency: Double, info: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val request = PostSpotRequest(
                    dxCall = dxCall,
                    deCall = deCall,
                    frequency = frequency,
                    info = info
                )
                val response = api.postSpot(request)
                if (response.isSuccessful) {
                    fetchSpots()
                } else {
                    _error.value = UiText(R.string.dx_error_send, "${response.code()} ${response.message()}")
                }
            } catch (e: Exception) {
                _error.value = UiText(R.string.dx_error_send, e.message.orEmpty())
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun matchesBandFilter(frequency: Double, bandFilter: String): Boolean {
        if (bandFilter == "ALL") return true
        val freqKHz = if (frequency < 1000.0) frequency * 1000.0 else frequency
        return when (bandFilter) {
            "160M" -> freqKHz in 1800.0..2000.0
            "80M" -> freqKHz in 3500.0..3800.0
            "40M" -> freqKHz in 7000.0..7300.0
            "30M" -> freqKHz in 10100.0..10150.0
            "20M" -> freqKHz in 14000.0..14350.0
            "17M" -> freqKHz in 18068.0..18168.0
            "15M" -> freqKHz in 21000.0..21450.0
            "12M" -> freqKHz in 24890.0..24990.0
            "10M" -> freqKHz in 28000.0..29700.0
            "6M" -> freqKHz in 50000.0..54000.0
            "2M" -> freqKHz in 144000.0..146000.0
            "70CM" -> freqKHz in 430000.0..440000.0
            else -> true
        }
    }

    private fun matchesModeFilter(frequency: Double, info: String, modeFilter: String): Boolean {
        if (modeFilter == "ALL") return true
        val infoUpper = info.uppercase()
        val freqKHz = if (frequency < 1000.0) frequency * 1000.0 else frequency

        val isFt8 = infoUpper.contains("FT8") || infoUpper.contains("FT4")
        val isDigi = isFt8 || infoUpper.contains("RTTY") || infoUpper.contains("PSK") || 
                infoUpper.contains("JT65") || infoUpper.contains("DIGI") || infoUpper.contains("DATA") || infoUpper.contains("JS8")
        val isCw = infoUpper.contains("CW") || (!isDigi && !infoUpper.contains("SSB") && !infoUpper.contains("USB") && !infoUpper.contains("LSB") && isCwSegment(freqKHz))
        val isSsb = infoUpper.contains("SSB") || infoUpper.contains("USB") || infoUpper.contains("LSB") || infoUpper.contains("PHONE") || (!isDigi && !isCw)

        return when (modeFilter) {
            "FT8" -> isFt8
            "DIGI" -> isDigi
            "CW" -> isCw
            "SSB" -> isSsb
            else -> true
        }
    }

    private fun isCwSegment(freqKHz: Double): Boolean {
        return (freqKHz in 1810.0..1838.0) ||
                (freqKHz in 3500.0..3570.0) ||
                (freqKHz in 7000.0..7040.0) ||
                (freqKHz in 10100.0..10130.0) ||
                (freqKHz in 14000.0..14070.0) ||
                (freqKHz in 18068.0..18095.0) ||
                (freqKHz in 21000.0..21070.0) ||
                (freqKHz in 24890.0..24915.0) ||
                (freqKHz in 28000.0..28070.0) ||
                (freqKHz in 50000.0..50100.0) ||
                (freqKHz in 144000.0..144150.0)
    }

    private fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}