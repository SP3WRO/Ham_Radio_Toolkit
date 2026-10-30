package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.utils.localizedString
import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.ui.components.AddSpotDialog
import io.github.sp3wro.hamradiotoolkit.ui.components.SpotItem

import io.github.sp3wro.hamradiotoolkit.data.awards.AwardSources
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardStationTag
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import io.github.sp3wro.hamradiotoolkit.data.model.DxSpot
import io.github.sp3wro.hamradiotoolkit.ui.components.QsoDialog
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

// Emisja ze spotu: z komentarza, a bez niego z segmentu bandplanu (CW na dole pasm)
internal fun guessModeFromSpot(khz: Double, info: String): String {
    val text = info.uppercase()
    return when {
        "FT8" in text -> "FT8"
        "FT4" in text -> "FT4"
        "CW" in text -> "CW"
        "RTTY" in text -> "RTTY"
        "SSB" in text || "USB" in text || "LSB" in text -> "SSB"
        "FM" in text -> "FM"
        isCwSegment(khz) -> "CW"
        else -> "SSB"
    }
}

private fun isCwSegment(khz: Double): Boolean =
    khz in 1810.0..1838.0 || khz in 3500.0..3570.0 || khz in 7000.0..7040.0 || khz in 10100.0..10130.0 ||
        khz in 14000.0..14070.0 || khz in 18068.0..18095.0 || khz in 21000.0..21070.0 ||
        khz in 24890.0..24915.0 || khz in 28000.0..28070.0 || khz in 50000.0..50100.0 || khz in 144000.0..144150.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DxClusterScreen(
    spotterCallsign: String,
    refreshInterval: Int,
    stationPosition: LatLon? = null,
    onSettingsClick: () -> Unit = {},
    viewModel: DxClusterViewModel = viewModel(),
    loggerViewModel: LoggerViewModel = viewModel()
) {
    val spots by viewModel.spots.collectAsState()
    val logs by loggerViewModel.logs.collectAsState()

    // Baza DXCC i kraje już zrobione – do odległości/azymutu i oznaczenia "NOWY KRAJ"
    val appContext = LocalContext.current.applicationContext
    var cty by remember { mutableStateOf(CtyRepository.cached()) }
    LaunchedEffect(Unit) { cty = CtyRepository.get(appContext) }
    val workedDxcc = remember(logs) { logs.mapNotNull { it.dxcc }.toSet() }
    // Stacje obserwowanych akcji dyplomowych (z zapisanych kopii stron radiodyplom.pl)
    val prefs = remember { UserPreferencesRepository(appContext.dataStore) }
    val followedAwards by prefs.followedAwards.collectAsState(initial = emptySet())
    var awardStations by remember { mutableStateOf<Map<String, List<AwardStationTag>>>(emptyMap()) }
    LaunchedEffect(followedAwards) { awardStations = AwardSources.followedStations(appContext, followedAwards) }
    val awardTextOf: (DxSpot) -> String? = { spot ->
        val call = spot.dxCall.trim().uppercase()
        (awardStations[call] ?: awardStations[Callsigns.baseCall(call)])
            ?.joinToString(" • ") { appContext.localizedString(R.string.dx_award_points, it.eventName, it.points) }
    }
    var spotToLog by remember { mutableStateOf<DxSpot?>(null) }
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastUpdatedTime by viewModel.lastUpdatedTime.collectAsState()

    val selectedBand by viewModel.selectedBand.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedCountries by viewModel.selectedCountries.collectAsState()

    var showFilterPanel by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var countryQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val res = LocalResources.current

    // Kraj spotu – ta sama nazwa, którą pokazuje karta (cty.dat, a bez bazy kraj podany przez serwer)
    val countryOf: (DxSpot) -> String? = { spot -> cty?.resolve(spot.dxCall)?.name ?: spot.dxCountry?.takeIf { it.isNotBlank() } }
    val shownSpots = remember(spots, cty, selectedCountries) {
        if (selectedCountries.isEmpty()) spots else spots.filter { countryOf(it) in selectedCountries }
    }
    // Kraje obecne w spotach (po filtrze pasma/emisji) – najczęstsze pierwsze; wybrane zawsze widoczne
    val countryCounts = remember(spots, cty) { spots.mapNotNull(countryOf).groupingBy { it }.eachCount() }
    val countryChips = remember(countryCounts, selectedCountries, countryQuery) {
        val all = (countryCounts.keys + selectedCountries)
            .sortedWith(compareByDescending<String> { it in selectedCountries }.thenByDescending { countryCounts[it] ?: 0 }.thenBy { it })
        if (countryQuery.isBlank()) all else all.filter { it in selectedCountries || it.contains(countryQuery.trim(), ignoreCase = true) }
    }

    val isFilterActive = selectedBand != "ALL" || selectedMode != "ALL" || searchQuery.isNotBlank() || selectedCountries.isNotEmpty()

    val bandsList = listOf("ALL", "160M", "80M", "40M", "30M", "20M", "17M", "15M", "12M", "10M", "6M", "2M", "70CM")
    val modesList = listOf("ALL", "CW", "SSB", "FT8", "DIGI")

    // Pętla odświeżania na podstawie ustawień
    LaunchedEffect(refreshInterval) {
        if (refreshInterval > 0) {
            while (true) {
                delay(refreshInterval.minutes)
                viewModel.fetchSpots()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DX Cluster") },
                actions = {
                    IconButton(onClick = { showFilterPanel = !showFilterPanel }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = stringResource(R.string.dx_filters),
                            tint = if (isFilterActive) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = { viewModel.fetchSpots() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.dx_spot_it))
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // PANEL FILTRÓW
                AnimatedVisibility(visible = showFilterPanel) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.dx_filtering), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                if (isFilterActive) {
                                    TextButton(onClick = { viewModel.clearFilters() }) {
                                        Text(stringResource(R.string.dx_reset_filters), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            // Wyszukiwarka Znaków / Uwagi
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                label = { Text(stringResource(R.string.dx_search_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Wybór Pasm (Bands)
                            Text(stringResource(R.string.dx_band), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                items(bandsList) { band ->
                                    FilterChip(
                                        selected = selectedBand == band,
                                        onClick = { viewModel.setBandFilter(band) },
                                        label = { Text(if (band == "ALL") stringResource(R.string.common_all) else band, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Wybór Emisji (Modes)
                            Text(stringResource(R.string.dx_mode), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                items(modesList) { mode ->
                                    FilterChip(
                                        selected = selectedMode == mode,
                                        onClick = { viewModel.setModeFilter(mode) },
                                        label = { Text(if (mode == "ALL") stringResource(R.string.common_all) else mode, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Wybór krajów DXCC (można zaznaczyć kilka)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (selectedCountries.isEmpty()) stringResource(R.string.dx_country) else stringResource(R.string.dx_country_count, selectedCountries.size),
                                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = countryQuery,
                                    onValueChange = { countryQuery = it },
                                    placeholder = { Text(stringResource(R.string.dx_country_hint), style = MaterialTheme.typography.bodySmall) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                                )
                            }
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                item(key = "__all") {
                                    FilterChip(
                                        selected = selectedCountries.isEmpty(),
                                        onClick = { viewModel.clearCountries() },
                                        label = { Text(stringResource(R.string.common_all), style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                                items(countryChips, key = { it }) { country ->
                                    FilterChip(
                                        selected = country in selectedCountries,
                                        onClick = { viewModel.toggleCountry(country) },
                                        label = { Text("$country (${countryCounts[country] ?: 0})", style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Czas ostatniej aktualizacji i podsumowanie
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (lastUpdatedTime != null) {
                        Text(
                            text = stringResource(R.string.dx_updated, lastUpdatedTime.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.dx_spots_count, shownSpots.size),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    if (isLoading && shownSpots.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (error != null && shownSpots.isEmpty()) {
                        Text(
                            text = error?.asString() ?: stringResource(R.string.common_unknown_error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    } else if (shownSpots.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dx_no_spots),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(shownSpots) { spot ->
                                val entity = cty?.resolve(spot.dxCall)
                                // Spot nie zawiera lokatora stacji – odległości nie pokazujemy (środek kraju byłby zgadywaniem)
                                SpotItem(
                                    spot = spot,
                                    countryName = entity?.name ?: spot.dxCountry,
                                    pathText = null,
                                    newCountry = entity != null && logs.isNotEmpty() && entity.dxcc !in workedDxcc,
                                    awardText = awardTextOf(spot),
                                    onClick = { spotToLog = spot }
                                )
                            }
                        }
                    }
                }
            }
        }

        spotToLog?.let { spot ->
            val khz = if (spot.frequency < 1000.0) spot.frequency * 1000.0 else spot.frequency
            QsoDialog(
                prefillCallsign = spot.dxCall,
                defaultFrequency = String.format(Locale.US, "%.1f", khz).removeSuffix(".0"),
                defaultMode = guessModeFromSpot(khz, spot.info),
                defaultRstSent = if (guessModeFromSpot(khz, spot.info) == "CW") "599" else "59",
                defaultRstRcvd = if (guessModeFromSpot(khz, spot.info) == "CW") "599" else "59",
                stationPosition = stationPosition,
                onDismissRequest = { spotToLog = null },
                onSave = { qso ->
                    loggerViewModel.addQso(qso)
                    spotToLog = null
                    Toast.makeText(context, res.getString(R.string.dx_qso_logged, qso.callsign), Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showAddDialog) {
            AddSpotDialog(
                onDismissRequest = { showAddDialog = false },
                onSpotSubmit = { dxCall, frequency, info ->
                    val freqDouble = frequency.toDoubleOrNull()
                    if (freqDouble != null) {
                        viewModel.sendSpot(
                            dxCall = dxCall,
                            deCall = spotterCallsign,
                            frequency = freqDouble,
                            info = info
                        )
                        Toast.makeText(context, res.getString(R.string.dx_sending_spot), Toast.LENGTH_SHORT).show()
                        showAddDialog = false
                    } else {
                        Toast.makeText(context, res.getString(R.string.dx_invalid_freq), Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}
