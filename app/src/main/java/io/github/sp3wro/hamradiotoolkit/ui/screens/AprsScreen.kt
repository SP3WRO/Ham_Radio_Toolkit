package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.model.AprsPacket
import io.github.sp3wro.hamradiotoolkit.ui.components.AprsIcon
import io.github.sp3wro.hamradiotoolkit.ui.components.InfoCard
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AprsScreen(
    callsign: String,
    ssid: String,
    icon: String,
    comment: String,
    aprsRadiusKm: Int,
    position: LatLon?,
    onSettingsClick: () -> Unit = {},
    viewModel: AprsViewModel = viewModel()
) {
    val packets by viewModel.packets.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val context = LocalContext.current
    val res = LocalResources.current

    // Nadawanie tylko z poprawnym znakiem krótkofalarskim; bez niego – wyłącznie nasłuch ramek
    val transmitCall = Callsigns.aprsTransmitCall(callsign)
    val canTransmit = transmitCall != null
    val fullCallsign = when {
        transmitCall == null -> stringResource(R.string.aprs_listen_only)
        ssid.isBlank() || ssid == "-0" || ssid == "0" -> transmitCall
        ssid.startsWith("-") -> "$transmitCall$ssid"
        else -> "$transmitCall-$ssid"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APRS-IS") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            if (canTransmit) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.aprs_send_position)) },
                    icon = { Icon(Icons.Filled.CellTower, contentDescription = null) },
                    onClick = {
                        when {
                            position == null -> Toast.makeText(context, res.getString(R.string.aprs_no_gps), Toast.LENGTH_SHORT).show()
                            viewModel.sendBeacon(callsign, ssid, icon, comment, position) ->
                                Toast.makeText(context, res.getString(R.string.aprs_position_sent, fullCallsign), Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            InfoCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                title = stringResource(R.string.aprs_my_station),
                trailing = { ConnectionDot(isConnected) }
            ) {
                Text(fullCallsign, style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.aprs_radius, aprsRadiusKm) + if (comment.isNotBlank()) stringResource(R.string.aprs_comment, comment) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.aprs_always_listening),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!canTransmit) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.aprs_need_callsign),
                            
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    TextButton(onClick = onSettingsClick, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.aprs_enter_callsign))
                    }
                }
            }

            if (packets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            position == null -> stringResource(R.string.aprs_waiting_gps)
                            isConnected -> stringResource(R.string.aprs_waiting_frames, aprsRadiusKm)
                            else -> stringResource(R.string.aprs_connecting)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(packets, key = { it.callsign }) { packet ->
                        AprsItem(packet)
                    }
                }
            }
        }
    }
}

@Composable
fun AprsItem(packet: AprsPacket) {
    var expanded by remember { mutableStateOf(false) }
    val minutesAgo = ((System.currentTimeMillis() - packet.timestampMs) / 60_000L).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AprsIcon(table = packet.table, symbol = packet.symbol, overlay = packet.overlay)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(packet.callsign, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (packet.comment.isNotEmpty() && !expanded) {
                        Text(
                            packet.comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${packet.distanceKm} km", style = MaterialTheme.typography.titleSmall)
                    Text(if (minutesAgo == 0L) stringResource(R.string.aprs_now) else stringResource(R.string.aprs_min_ago, minutesAgo.toInt()), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Detail(
                        stringResource(R.string.aprs_position),
                        String.format(Locale.US, "%.4f° %s, %.4f° %s",
                            kotlin.math.abs(packet.latitude), if (packet.latitude >= 0) "N" else "S",
                            kotlin.math.abs(packet.longitude), if (packet.longitude >= 0) "E" else "W")
                    )
                    if (packet.speedKmh != null || packet.courseDegrees != null) {
                        Detail(stringResource(R.string.aprs_movement), listOfNotNull(
                            packet.speedKmh?.let { "${it.toInt()} km/h" },
                            packet.courseDegrees?.let { stringResource(R.string.aprs_course, it.toString()) }
                        ).joinToString(", "))
                    }
                    packet.altitudeM?.let { Detail(stringResource(R.string.aprs_altitude), stringResource(R.string.aprs_altitude_value, it.toInt())) }
                    if (packet.powerW != null) {
                        Detail(stringResource(R.string.aprs_phg), listOfNotNull(
                            packet.powerW?.let { "$it W" },
                            packet.antennaHeightM?.let { stringResource(R.string.aprs_antenna, it.toInt()) },
                            packet.antennaGainDb?.let { stringResource(R.string.aprs_gain, it.toString()) }
                        ).joinToString(", "))
                    }
                    val weather = listOfNotNull(
                        packet.tempCelsius?.let { String.format(Locale.US, "%.1f °C", it) },
                        packet.humidityPercent?.let { stringResource(R.string.aprs_humidity, it.toString()) },
                        packet.pressureHpa?.let { String.format(Locale.US, "%.1f hPa", it) },
                        packet.windSpeedKmh?.let { spd -> stringResource(R.string.aprs_wind, spd.toInt()) + (packet.windDirDeg?.let { stringResource(R.string.aprs_wind_from, it.toString()) } ?: "") },
                        packet.windGustKmh?.let { stringResource(R.string.aprs_gusts, it.toInt()) },
                        packet.rain1hMm?.let { stringResource(R.string.aprs_rain_1h, String.format(Locale.US, "%.1f", it)) },
                        packet.rain24hMm?.let { stringResource(R.string.aprs_rain_24h, String.format(Locale.US, "%.1f", it)) }
                    )
                    if (weather.isNotEmpty()) Detail(stringResource(R.string.aprs_weather), weather.joinToString(", "))
                    if (packet.comment.isNotEmpty()) {
                        Text(packet.comment, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
