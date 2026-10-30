package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.sp3wro.hamradiotoolkit.data.model.satelliteDatabase
import io.github.sp3wro.hamradiotoolkit.data.network.*
import io.github.sp3wro.hamradiotoolkit.data.preferences.SatelliteCacheManager
import io.github.sp3wro.hamradiotoolkit.background.AppNotifications
import io.github.sp3wro.hamradiotoolkit.background.BackgroundJobs
import io.github.sp3wro.hamradiotoolkit.background.TleStore
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.me.g4dpz.satellite.GroundStationPosition
import uk.me.g4dpz.satellite.PassPredictor
import uk.me.g4dpz.satellite.SatelliteFactory
import uk.me.g4dpz.satellite.TLE
import java.text.SimpleDateFormat
import java.util.*

data class NextPassInfo(
    val aosTimeStr: String,
    val losTimeStr: String,
    val maxElevation: Double,
    val aosTimeMs: Long,
    val losTimeMs: Long
) {
    // Liczone na bieżąco z zegara, a nie zapamiętane w chwili obliczenia – inaczej "Leci!" wisiało po LOS
    fun isPassing(nowMs: Long): Boolean = nowMs in aosTimeMs until losTimeMs
}

// Pozycja stacji zaokrąglona do ~1 km i 100 m wysokości: takie różnice nie zmieniają przelotów,
// a każda drobna zmiana odczytu GPS wywoływała przeliczanie wszystkich satelitów od nowa
private data class PassSite(val lat: Double, val lon: Double, val alt: Double)

private fun roundedPassSite(lat: Double, lon: Double, alt: Double?) = PassSite(
    lat = Math.round(lat * 100) / 100.0,
    lon = Math.round(lon * 100) / 100.0,
    alt = Math.round((alt ?: 100.0) / 100.0) * 100.0
)

private const val TLE_MAX_AGE_MS = 24 * 3600_000L

private class CompassUpdate(
    val key: Pair<String, Long>,
    val azimuth: Double,
    val elevation: Double,
    val isPassing: Boolean,
    /** null = trajektoria bez zmian (ten sam przelot) */
    val trajectory: List<SkyTrackPoint>?
)

private fun computeNextPass(sat: SatelliteTle, site: GroundStationPosition, now: Date): NextPassInfo? {
    return try {
        val predictor = PassPredictor(TLE(arrayOf(sat.name, sat.line1, sat.line2)), site)
        // Start 30 min wstecz, żeby złapać przelot, który właśnie trwa
        var pass = predictor.nextSatPass(Date(now.time - 30 * 60 * 1000L))
        if (pass != null && pass.endTime.before(now)) {
            pass = predictor.nextSatPass(now)
        }
        pass?.let {
            val df = SimpleDateFormat("HH:mm:ss", Locale.US)
            df.timeZone = TimeZone.getDefault()
            NextPassInfo(
                aosTimeStr = df.format(it.startTime),
                losTimeStr = df.format(it.endTime),
                maxElevation = it.maxEl,
                aosTimeMs = it.startTime.time,
                losTimeMs = it.endTime.time
            )
        }
    } catch (_: Exception) {
        // np. satelita geostacjonarny lub nieaktualne TLE – brak przelotu
        null
    }
}

/**
 * Dane ekranu satelitów, które przeżywają wyjście z ekranu: TLE, częstotliwości i policzone przeloty.
 * Wcześniej wszystko było w stanie ekranu, więc każde wejście pobierało TLE i liczyło przeloty od zera.
 */
class SatelliteViewModel : ViewModel() {
    val tles = mutableStateOf(defaultAmateurTles)
    val passesCalculated = mutableStateOf<Map<String, NextPassInfo>>(emptyMap())
    val stablePasses = mutableStateOf<Map<String, NextPassInfo>>(emptyMap())
    val satnogsTransmitters = mutableStateOf<Map<Long, List<SatnogsTransmitter>>>(emptyMap())
    val amsatFrequenciesMap = mutableStateOf<Map<String, List<AmsatSatelliteFrequency>>>(emptyMap())
    val expandedSatellite = mutableStateOf<String?>(null)
    val searchQuery = mutableStateOf("")
    var dataLoaded = false

    // Dla jakich TLE i pozycji policzono przeloty – ponowne wejście na ekran nie liczy ich od nowa
    internal var passesFor: Pair<List<SatelliteTle>, Any>? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteScreen(
    position: LatLon? = null,
    altitudeM: Double? = null,
    onSettingsClick: () -> Unit = {},
    viewModel: SatelliteViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var tles by viewModel.tles
    var isLoading by remember { mutableStateOf(false) }
    var expandedSatellite by viewModel.expandedSatellite

    // Pozycja wspólna dla całej aplikacji (GPS na żywo albo ostatnia zapisana)
    val currentLat = position?.lat
    val currentLon = position?.lon
    val currentAlt = altitudeM
    var searchQuery by viewModel.searchQuery

    var passesCalculated by viewModel.passesCalculated
    // Przeloty użyte do sortowania listy i wyboru satelity w kompasie – zmieniane tylko po zakończeniu
    // obliczeń, żeby lista nie przesuwała się w trakcie liczenia
    var stablePasses by viewModel.stablePasses
    var isCalculatingPasses by remember { mutableStateOf(false) }
    var calculationProgress by remember { mutableStateOf(0 to 0) }

    var satnogsTransmitters by viewModel.satnogsTransmitters
    var amsatFrequenciesMap by viewModel.amsatFrequenciesMap
    var currentTimeMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    // Dane do kompasu na żywo
    var activeCompassSatName by remember { mutableStateOf<String?>(null) }
    var activeCompassAzimuth by remember { mutableStateOf<Double?>(null) }
    var activeCompassElevation by remember { mutableStateOf<Double?>(null) }
    var activeCompassIsPassing by remember { mutableStateOf(false) }
    var activeCompassTrajectory by remember { mutableStateOf<List<SkyTrackPoint>>(emptyList()) }

    // Obserwowane satelity – przypomnienia o przelotach (powiadomienia w tle)
    val preferences = remember { UserPreferencesRepository(context.applicationContext.dataStore) }
    val watchedSatellites by preferences.watchedSatellites.collectAsState(initial = emptySet())
    var pendingWatch by remember { mutableStateOf<String?>(null) }

    suspend fun setWatched(name: String, watch: Boolean) {
        preferences.setSatelliteWatched(name, watch)
        BackgroundJobs.sync(context.applicationContext, preferences)
        BackgroundJobs.refreshSatelliteReminders(context.applicationContext)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val name = pendingWatch
        pendingWatch = null
        if (granted && name != null) coroutineScope.launch { setWatched(name, true) }
    }

    val apiClient = remember { SatelliteApiClient() }
    val satnogsClient = remember { SatnogsApiClient() }
    val amsatClient = remember { AmsatFrequencyApiClient() }

    // Odświeżanie czasu co sekundę
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            currentTimeMillis = System.currentTimeMillis()
        }
    }

    // TLE z pliku (o ile jest) – lista satelitów działa od razu i bez sieci
    fun applyTles(allTles: List<SatelliteTle>, transmitters: List<SatnogsTransmitter>) {
        if (allTles.isEmpty()) return
        val activeNorads = activeNoradIds(transmitters)
        tles = if (activeNorads.isNotEmpty()) {
            allTles.filter { tle -> extractNoradId(tle.line2).toLongOrNull() in activeNorads }.ifEmpty { allTles }
        } else allTles
    }

    /** [forceTle] – pobierz TLE z sieci także wtedy, gdy zapisane są świeże (przycisk "Aktualizuj"). */
    fun fetchOnlineTles(forceTle: Boolean) {
        isLoading = true
        coroutineScope.launch {
            try {
                val cacheManager = SatelliteCacheManager(context)
                val cachedData = withContext(Dispatchers.IO) { cacheManager.getCachedData() }
                var transmitters = cachedData?.transmitters ?: emptyList()
                var amsatList = cachedData?.amsatFrequencies ?: emptyList()

                if (transmitters.isEmpty() || amsatList.isEmpty()) {
                    try {
                        transmitters = satnogsClient.fetchTransmitters()
                    } catch (e: Exception) {
                        Log.w("SatelliteScreen", "Nie udało się pobrać transmiterów SatNOGS", e)
                    }
                    try {
                        amsatList = amsatClient.fetchAmsatFrequencies()
                    } catch (e: Exception) {
                        Log.w("SatelliteScreen", "Nie udało się pobrać częstotliwości AMSAT", e)
                    }

                    if (transmitters.isNotEmpty() || amsatList.isNotEmpty()) {
                        withContext(Dispatchers.IO) { cacheManager.saveCache(transmitters, amsatList) }
                    }
                }

                if (transmitters.isNotEmpty()) {
                    satnogsTransmitters = transmitters.filter { it.norad_cat_id != null }.groupBy { it.norad_cat_id!! }
                }

                if (amsatList.isNotEmpty()) {
                    val mapByNorad = amsatList.filter { !it.noradId.isNullOrBlank() }.groupBy { it.noradId!! }
                    val mapByName = amsatList.groupBy { it.name.uppercase() }
                    amsatFrequenciesMap = mapByNorad + mapByName
                }

                val stored = withContext(Dispatchers.IO) { TleStore.load(context) }
                applyTles(stored, transmitters)

                // TLE starzeją się powoli – z sieci najwyżej raz na dobę (albo na żądanie)
                val ageMs = withContext(Dispatchers.IO) { TleStore.ageMs(context) }
                if (forceTle || stored.isEmpty() || ageMs > TLE_MAX_AGE_MS) {
                    val rawTles = apiClient.fetchAmateurTles()
                    // Zapis dla przypomnień o przelotach i widżetu, które liczą przeloty w tle
                    withContext(Dispatchers.IO) { TleStore.save(context, rawTles) }
                    applyTles(parseTleData(rawTles), transmitters)
                }
            } catch (e: Exception) {
                Log.w("SatelliteScreen", "Nie udało się pobrać danych satelitów", e)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!viewModel.dataLoaded) {
            viewModel.dataLoaded = true
            fetchOnlineTles(forceTle = false)
        }
    }

    val passSite = remember(currentLat, currentLon, currentAlt) {
        val lat = currentLat
        val lon = currentLon
        if (lat != null && lon != null) roundedPassSite(lat, lon, currentAlt) else null
    }

    // Pełne liczenie przelotów tylko po zmianie TLE lub lokalizacji, potem wyłącznie odświeżanie
    // satelitów, których przelot się zakończył (wcześniej wszystko liczono od zera co minutę)
    LaunchedEffect(tles, passSite) {
        val site = passSite ?: return@LaunchedEffect
        val ground = GroundStationPosition(site.lat, site.lon, site.alt)

        val key = tles to site
        if (viewModel.passesFor != key) {
            isCalculatingPasses = true
            calculationProgress = 0 to tles.size

            val popularSatNames = listOf("ISS", "SO-50", "AO-91", "PO-101", "FO-29", "XW-3", "AO-73", "AO-7", "FUNCUBE", "LILACSAT", "CAS-4A", "CAS-4B")
            val sortedTles = tles.sortedByDescending { sat ->
                popularSatNames.any { sat.name.uppercase().contains(it) }
            }

            // Nowe wyniki są dokładane do poprzednich (nie zastępują ich), więc satelity jeszcze
            // nieprzeliczone nie tracą danych; kolejność listy zmienia się dopiero po zakończeniu
            val previous = passesCalculated
            val result = mutableMapOf<String, NextPassInfo>()

            withContext(Dispatchers.Default) {
                val now = Date()
                for ((index, sat) in sortedTles.withIndex()) {
                    computeNextPass(sat, ground, now)?.let { result[sat.name] = it }

                    if (index % 5 == 0 || index == sortedTles.lastIndex) {
                        val snapshot = previous + result
                        val progressCount = index + 1
                        withContext(Dispatchers.Main) {
                            passesCalculated = snapshot
                            calculationProgress = progressCount to sortedTles.size
                        }
                    }
                }
            }
            passesCalculated = result.toMap()
            stablePasses = passesCalculated
            viewModel.passesFor = key
            isCalculatingPasses = false
        }

        while (true) {
            val nowMs = System.currentTimeMillis()
            val ended = passesCalculated.filterValues { it.losTimeMs <= nowMs }.keys
            if (ended.isNotEmpty()) {
                val updates = withContext(Dispatchers.Default) {
                    val now = Date(nowMs)
                    tles.filter { it.name in ended }.associate { it.name to computeNextPass(it, ground, now) }
                }
                val merged = passesCalculated.toMutableMap()
                for ((name, info) in updates) {
                    if (info != null) merged[name] = info else merged.remove(name)
                }
                passesCalculated = merged
                stablePasses = merged
            }
            delay(15_000L)
        }
    }

    // Kompas: azymut i elewacja co sekundę, trajektoria przelotu tylko raz na przelot
    // (wcześniej cała trajektoria i cały ekran były przeliczane co sekundę)
    LaunchedEffect(expandedSatellite, tles, passSite, stablePasses) {
        val site = passSite
        if (site == null) {
            activeCompassSatName = null
            return@LaunchedEffect
        }
        val ground = GroundStationPosition(site.lat, site.lon, site.alt)
        var trajectoryKey: Pair<String, Long>? = null

        while (true) {
            val nowMs = System.currentTimeMillis()
            // Szukamy wybranego satelity lub domyślnie pierwszego przelatującego / najbliższego.
            // Wybór na podstawie stablePasses, żeby kompas nie skakał między satelitami w trakcie liczenia.
            val targetSatName = expandedSatellite
                ?: stablePasses.entries.find { it.value.isPassing(nowMs) }?.key
                ?: stablePasses.entries.filter { it.value.losTimeMs > nowMs }.minByOrNull { it.value.aosTimeMs }?.key
                ?: tles.firstOrNull()?.name
            val satTle = targetSatName?.let { name -> tles.find { it.name == name } }

            if (satTle == null) {
                activeCompassSatName = null
                activeCompassAzimuth = null
                activeCompassElevation = null
                activeCompassIsPassing = false
                activeCompassTrajectory = emptyList()
            } else {
                val passInfo = passesCalculated[satTle.name]
                val previousKey = trajectoryKey
                val update = withContext(Dispatchers.Default) {
                    runCatching {
                        val tle = TLE(arrayOf(satTle.name, satTle.line1, satTle.line2))
                        val satObj = SatelliteFactory.createSatellite(tle)
                        val satPos = satObj.getPosition(ground, Date(nowMs))

                        // Dokładny czas AOS i LOS dla tego samego przelotu
                        val (startTime, endTime) = if (passInfo != null && nowMs < passInfo.losTimeMs) {
                            passInfo.aosTimeMs to passInfo.losTimeMs
                        } else {
                            val nextPass = PassPredictor(tle, ground).nextSatPass(Date(nowMs))
                            (nextPass?.startTime?.time ?: 0L) to (nextPass?.endTime?.time ?: 0L)
                        }

                        val newKey = satTle.name to startTime
                        val trajectory: List<SkyTrackPoint>? = if (newKey == previousKey) null else buildList<SkyTrackPoint> {
                            if (startTime > 0L && endTime > startTime) {
                                val steps = 20
                                val stepMs = (endTime - startTime) / steps
                                for (i in 0..steps) {
                                    val stepPos = satObj.getPosition(ground, Date(startTime + i * stepMs))
                                    val stepEl = Math.toDegrees(stepPos.elevation)
                                    if (stepEl >= 0.0) add(SkyTrackPoint(Math.toDegrees(stepPos.azimuth), stepEl))
                                }
                            }
                        }
                        CompassUpdate(newKey, Math.toDegrees(satPos.azimuth), Math.toDegrees(satPos.elevation), satPos.isAboveHorizon, trajectory)
                    }.getOrNull()
                }
                if (update != null) {
                    activeCompassSatName = satTle.name
                    activeCompassAzimuth = update.azimuth
                    activeCompassElevation = update.elevation
                    activeCompassIsPassing = update.isPassing
                    update.trajectory?.let { activeCompassTrajectory = it }
                    trajectoryKey = update.key
                }
            }
            delay(1000L)
        }
    }

    val qthLocatorStr = remember(currentLat, currentLon) {
        if (currentLat != null && currentLon != null) {
            calculateMaidenheadLocator(currentLat!!, currentLon!!)
        } else null
    }

    // Filtrowanie i sortowanie WSZYSTKICH satelitów z bazy. Trwające przeloty mają AOS w przeszłości,
    // więc sortowanie po AOS samo stawia je na górze; satelity bez przelotu na końcu.
    val filteredSortedTles = remember(tles, searchQuery, stablePasses) {
        tles.filter { it.name.contains(searchQuery, ignoreCase = true) }
            .sortedWith(compareBy<SatelliteTle> { tle ->
                stablePasses[tle.name]?.aosTimeMs ?: Long.MAX_VALUE
            }.thenBy { tle -> tle.name })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sat_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = { fetchOnlineTles(forceTle = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sat_update_online))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. HEADER: KOMPAS NIEBOSKŁONU (SKYPLOT)
            item(key = "compass_widget") {
                SatelliteCompassWidget(
                    satName = activeCompassSatName,
                    azimuthDeg = activeCompassAzimuth,
                    elevationDeg = activeCompassElevation,
                    isPassingNow = activeCompassIsPassing,
                    trajectoryPoints = activeCompassTrajectory,
                    qthLocator = qthLocatorStr,
                    latitude = currentLat,
                    longitude = currentLon,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 2. HEADER: WYSZUKIWARKA
            item(key = "search_field") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.sat_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }

            // 3. HEADER: NAGŁÓWEK LISTY + PROGRES
            item(key = "list_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.sat_list_header, filteredSortedTles.size), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    if (isCalculatingPasses) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.sat_calculating, calculationProgress.first, calculationProgress.second),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // 4. LISTA SATELITÓW
            if (filteredSortedTles.isEmpty()) {
                item(key = "empty_state") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.sat_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(filteredSortedTles, key = { it.name }) { sat ->
                    val isExpanded = expandedSatellite == sat.name
                    val passInfo = passesCalculated[sat.name]
                    val isPassingNow = passInfo?.isPassing(currentTimeMillis) == true

                    val cardColor = if (isPassingNow) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else if (isExpanded) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedSatellite = if (isExpanded) null else sat.name },
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Satellite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sat.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                    if (passInfo != null) {
                                        if (isPassingNow) {
                                            val minutesToLos = maxOf(0L, (passInfo.losTimeMs - currentTimeMillis) / 60000)
                                            val secsToLos = maxOf(0L, ((passInfo.losTimeMs - currentTimeMillis) / 1000) % 60)
                                            Text(
                                                stringResource(R.string.sat_in_pass, minutesToLos.toInt(), secsToLos.toInt()),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            val minutesToAos = maxOf(0L, (passInfo.aosTimeMs - currentTimeMillis) / 60000)
                                            val hours = minutesToAos / 60
                                            val mins = minutesToAos % 60
                                            val timeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

                                            Text(stringResource(R.string.sat_aos_los, passInfo.aosTimeStr, timeStr, passInfo.losTimeStr), style = MaterialTheme.typography.bodySmall)
                                        }
                                    } else {
                                        Text(stringResource(R.string.sat_calculating_pass), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (passInfo != null) {
                                    Text(
                                        text = "Max El\n${String.format(Locale.US, "%.1f°", passInfo.maxElevation)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (passInfo.maxElevation > 30) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(stringResource(R.string.sat_remind), style = MaterialTheme.typography.bodyMedium)
                                        Switch(
                                            checked = sat.name in watchedSatellites,
                                            onCheckedChange = { watch ->
                                                if (watch && !AppNotifications.canNotify(context)) {
                                                    pendingWatch = sat.name
                                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else {
                                                    coroutineScope.launch { setWatched(sat.name, watch) }
                                                }
                                            }
                                        )
                                    }
                                    val noradStr = extractNoradId(sat.line2)
                                    val noradLong = noradStr.toLongOrNull()

                                    val amsatFreqList = amsatFrequenciesMap[noradStr] ?: amsatFrequenciesMap[sat.name.uppercase()]
                                    val satnogsData = if (noradLong != null) satnogsTransmitters[noradLong] else null
                                    val fallbackFreqs = satelliteDatabase.entries.find {
                                        it.key.contains(sat.name, ignoreCase = true) || sat.name.contains(it.key, ignoreCase = true)
                                    }?.value

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    if (!amsatFreqList.isNullOrEmpty()) {
                                        Text(stringResource(R.string.sat_freq_amsat), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                                        amsatFreqList.forEach { freq ->
                                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "Mode: ${freq.mode ?: "N/A"}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    if (!freq.callsign.isNullOrBlank()) {
                                                        Badge { Text(stringResource(R.string.sat_callsign, freq.callsign.orEmpty())) }
                                                    }
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text("Up: ${freq.uplink ?: stringResource(R.string.sat_none_short)}", style = MaterialTheme.typography.bodySmall)
                                                    Text("Dn: ${freq.downlink ?: stringResource(R.string.sat_none_short)}", style = MaterialTheme.typography.bodySmall)
                                                    if (!freq.beacon.isNullOrBlank()) {
                                                        Text("Bcn: ${freq.beacon}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                    } else if (!satnogsData.isNullOrEmpty()) {
                                        Text(stringResource(R.string.sat_freq_satnogs), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                                        satnogsData.forEach { tx ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                val modeDesc = tx.mode ?: "N/A"
                                                val upStr = if (tx.uplink_low != null) "${String.format(Locale.US, "%.3f", tx.uplink_low / 1000000.0)}" else stringResource(R.string.sat_none_short)
                                                val dnStr = if (tx.downlink_low != null) "${String.format(Locale.US, "%.3f", tx.downlink_low / 1000000.0)}" else stringResource(R.string.sat_none_short)

                                                Text("$modeDesc:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.3f))
                                                Text("Up: $upStr", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.35f))
                                                Text("Dn: $dnStr", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.35f))
                                            }
                                        }
                                    } else if (!fallbackFreqs.isNullOrEmpty()) {
                                        Text(stringResource(R.string.sat_freq_builtin), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                                        fallbackFreqs.forEach { freq ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("${freq.mode}:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.35f))
                                                Text("Up: ${freq.uplink}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.25f))
                                                Text("Dn: ${freq.downlink}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.25f))
                                                Text("Info: ${freq.tone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.15f))
                                            }
                                        }
                                    } else {
                                        Text(stringResource(R.string.sat_no_freqs), style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun calculateMaidenheadLocator(lat: Double, lon: Double): String {
    var longitude = lon + 180.0
    var latitude = lat + 90.0

    val chars = CharArray(6)

    chars[0] = ('A' + (longitude / 20).toInt())
    chars[1] = ('A' + (latitude / 10).toInt())

    longitude %= 20.0
    latitude %= 10.0

    chars[2] = ('0' + (longitude / 2).toInt())
    chars[3] = ('0' + latitude.toInt())

    longitude %= 2.0
    latitude %= 1.0

    chars[4] = ('a' + (longitude * 12).toInt())
    chars[5] = ('a' + (latitude * 24).toInt())

    return String(chars)
}
