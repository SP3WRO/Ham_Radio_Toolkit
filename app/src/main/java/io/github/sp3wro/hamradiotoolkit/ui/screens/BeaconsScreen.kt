package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sp3wro.hamradiotoolkit.data.beacons.Beacon
import io.github.sp3wro.hamradiotoolkit.data.beacons.NcdxfBeacons
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconsScreen(
    stationPosition: LatLon? = null,
    onSettingsClick: () -> Unit = {}
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L - nowMs % 1000L) // tyknięcie na pełnej sekundzie
        }
    }
    val secondOfDay = (nowMs / 1000) % 86_400
    val slots = NcdxfBeacons.nowTransmitting(secondOfDay)
    var selected by remember { mutableStateOf<Beacon?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bcn_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    String.format(Locale.US, "%02d:%02d:%02d UTC", secondOfDay / 3600, secondOfDay / 60 % 60, secondOfDay % 60),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.bcn_info),
                        
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(slots, key = { it.bandKhz }) { slot ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(String.format(Locale.US, "%.3f MHz", slot.bandKhz / 1000.0), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(slot.beacon.call, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("${slot.beacon.entity}, ${slot.beacon.location}", style = MaterialTheme.typography.bodyMedium)
                            beaconPath(stationPosition, slot.beacon)?.let { (km, bearing, compass) -> Text(stringResource(R.string.qso_distance, km, bearing, compass), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${slot.secondsLeft} s", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.bcn_next, slot.next.call), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.bcn_all), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(NcdxfBeacons.BEACONS, key = { it.call }) { beacon ->
                val isSelected = selected == beacon
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selected = if (isSelected) null else beacon },
                    colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else CardDefaults.cardColors()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(beacon.call, fontWeight = FontWeight.Bold)
                            Text("${beacon.entity} • ${beacon.gridSquare}", style = MaterialTheme.typography.bodyMedium)
                        }
                        beaconPath(stationPosition, beacon)?.let { (km, bearing, compass) -> Text(stringResource(R.string.qso_distance, km, bearing, compass), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (isSelected) {
                            NcdxfBeacons.BANDS_KHZ.forEachIndexed { index, khz ->
                                val wait = NcdxfBeacons.secondsUntil(beacon, index, secondOfDay)
                                Text(
                                    String.format(Locale.US, "%.3f MHz: %s", khz / 1000.0,
                                        if (wait == 0) stringResource(R.string.bcn_on_air_now) else stringResource(R.string.bcn_in, "${wait / 60}:${String.format(Locale.US, "%02d", wait % 60)}")),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (wait == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (wait == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.bcn_off_note),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun beaconPath(station: LatLon?, beacon: Beacon): Triple<String, String, String>? {
    val target = Maidenhead.toLatLon(beacon.gridSquare) ?: return null
    station ?: return null
    val path = GreatCircle.path(station, target)
    return Triple(String.format(UiLocale, "%,.0f", path.shortPathKm), String.format(UiLocale, "%.0f", path.shortPathBearing),
        GreatCircle.compassPoint(path.shortPathBearing))
}
