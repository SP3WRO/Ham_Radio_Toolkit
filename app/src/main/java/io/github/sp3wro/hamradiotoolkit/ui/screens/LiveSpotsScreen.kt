package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.model.bandForKhz
import io.github.sp3wro.hamradiotoolkit.data.network.LiveSpot
import io.github.sp3wro.hamradiotoolkit.data.network.LiveSpotsClient
import io.github.sp3wro.hamradiotoolkit.ui.components.QsoDialog
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSpotsScreen(
    stationPosition: LatLon? = null,
    onSettingsClick: () -> Unit = {},
    loggerViewModel: LoggerViewModel = viewModel()
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val client = remember { LiveSpotsClient() }
    val logs by loggerViewModel.logs.collectAsState()

    var spots by remember { mutableStateOf<List<LiveSpot>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var programFilter by remember { mutableStateOf("ALL") }
    var bandFilter by remember { mutableStateOf("ALL") }
    var modeFilter by remember { mutableStateOf("ALL") }
    var sortByDistance by remember { mutableStateOf(false) }
    var spotToLog by remember { mutableStateOf<LiveSpot?>(null) }

    // Odświeżanie co minutę, dopóki ekran jest otwarty
    LaunchedEffect(refreshTrigger) {
        while (true) {
            isLoading = true
            val (result, failed) = client.fetchAll()
            if (result.isNotEmpty() || failed.isEmpty()) spots = result
            errors = failed
            isLoading = false
            delay(60_000L)
        }
    }

    // Aktywacje już zalogowane dziś (znak + referencja, dzień UTC)
    val todayUtc = remember(logs) {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }
    val workedToday = remember(logs, todayUtc) {
        logs.filter { it.datetimeUtc.startsWith(todayUtc) && it.huntedRef.isNotBlank() }
            .map { Callsigns.baseCall(it.callsign) to it.huntedRef.uppercase() }.toSet()
    }

    val bands = listOf("ALL", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m")
    val modes = listOf("ALL", "CW", "SSB", "FT8", "FT4", "FM")

    val visible = remember(spots, programFilter, bandFilter, modeFilter, sortByDistance, stationPosition) {
        spots.filter { s ->
            (programFilter == "ALL" || s.program == programFilter) &&
                (bandFilter == "ALL" || bandForKhz(s.frequencyKhz) == bandFilter) &&
                (modeFilter == "ALL" || s.mode == modeFilter)
        }.let { list ->
            if (sortByDistance && stationPosition != null) list.sortedBy { distanceKm(stationPosition, it) ?: Double.MAX_VALUE }
            else list
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_title)) },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            FilterRow(listOf("ALL", "POTA", "WWFF"), programFilter) { programFilter = it }
            FilterRow(bands, bandFilter) { bandFilter = it }
            FilterRow(modes, modeFilter) { modeFilter = it }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.live_count, visible.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.live_nearest), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = sortByDistance, onCheckedChange = { sortByDistance = it }, enabled = stationPosition != null)
                }
            }
            errors.forEach { Text(stringResource(R.string.live_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visible, key = { "${it.program}-${it.activator}-${it.reference}-${it.timeEpochSec}" }) { spot ->
                    val worked = (Callsigns.baseCall(spot.activator) to spot.reference) in workedToday
                    LiveSpotCard(spot, stationPosition, worked) { spotToLog = spot }
                }
                item {
                    Text(
                        stringResource(R.string.live_sota_note),
                            
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        spotToLog?.let { spot ->
            QsoDialog(
                prefillCallsign = spot.activator,
                prefillHuntedRef = spot.reference,
                defaultFrequency = String.format(Locale.US, "%.1f", spot.frequencyKhz).removeSuffix(".0"),
                defaultMode = spot.mode.ifBlank { "SSB" },
                defaultRstSent = if (spot.mode == "CW") "599" else "59",
                defaultRstRcvd = if (spot.mode == "CW") "599" else "59",
                stationPosition = stationPosition,
                onDismissRequest = { spotToLog = null },
                onSave = { qso ->
                    loggerViewModel.addQso(qso)
                    spotToLog = null
                    Toast.makeText(context, res.getString(R.string.live_logged, qso.callsign, qso.huntedRef), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

private fun distanceKm(station: LatLon, spot: LiveSpot): Double? {
    val lat = spot.latitude ?: return null
    val lon = spot.longitude ?: return null
    return GreatCircle.distanceKm(station, LatLon(lat, lon))
}

@Composable
private fun FilterRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(if (option == "ALL") stringResource(R.string.common_all) else option) }
            )
        }
    }
}

@Composable
private fun LiveSpotCard(spot: LiveSpot, station: LatLon?, workedToday: Boolean, onClick: () -> Unit) {
    val minutesAgo = ((System.currentTimeMillis() / 1000 - spot.timeEpochSec) / 60).coerceAtLeast(0)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (workedToday) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(spot.activator, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Badge { Text(spot.program) }
                    if (spot.mode.isNotBlank()) Badge { Text(spot.mode) }
                }
                Text(String.format(Locale.US, "%.1f", spot.frequencyKhz), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            }
            Text("${spot.reference} – ${spot.referenceName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val distance = station?.let { distanceKm(it, spot) }
                val bearing = if (station != null && spot.latitude != null && spot.longitude != null)
                    GreatCircle.bearingDeg(station, LatLon(spot.latitude, spot.longitude)) else null
                Text(
                    listOfNotNull(
                        stringResource(R.string.aprs_min_ago, minutesAgo.toInt()),
                        distance?.let { String.format(UiLocale, "%,.0f km", it) },
                        bearing?.let { String.format(Locale.US, "%.0f° %s", it, GreatCircle.compassPoint(it)) }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (workedToday) Text(stringResource(R.string.live_logged_badge), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            }
            if (spot.comment.isNotBlank()) {
                Text(spot.comment, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        }
    }
}
