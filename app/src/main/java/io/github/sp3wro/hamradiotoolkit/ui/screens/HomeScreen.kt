package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.geo.CountryInfo
import io.github.sp3wro.hamradiotoolkit.ui.GpsAltitude
import io.github.sp3wro.hamradiotoolkit.ui.components.InfoCard
import io.github.sp3wro.hamradiotoolkit.ui.components.LabeledValue
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale

import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    callsign: String,
    position: LatLon?,
    altitude: GpsAltitude? = null,
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
    repeaterDataSource: String = "przemienniki_net",
    aprsRadius: Int = 50,
    onSettingsClick: () -> Unit = {},
    onAprsClick: () -> Unit = {},
    country: CountryInfo? = null,
    onAbroadClick: () -> Unit = {},
    onRepeatersClick: () -> Unit = {},
    repeatersViewModel: RepeatersViewModel = viewModel(),
    aprsViewModel: AprsViewModel = viewModel()
) {
    LaunchedEffect(repeaterDataSource) {
        repeatersViewModel.updateDataSource(repeaterDataSource)
    }
    LaunchedEffect(position) {
        position?.let { repeatersViewModel.updateLocation(it.lat, it.lon) }
    }

    val aprsPackets by aprsViewModel.packets.collectAsState()
    val aprsConnected by aprsViewModel.isConnected.collectAsState()
    val nearestRepeater by repeatersViewModel.nearest.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ham Radio Toolkit") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Powitanie: "Witaj, SP3WRO" (znak wyróżniony), a bez znaku – samo "Witaj!"
            val primary = MaterialTheme.colorScheme.primary
            Text(
                text = if (callsign.isBlank() || callsign == "NOCALL") {
                    AnnotatedString(stringResource(R.string.home_hello))
                } else {
                    val hello = stringResource(R.string.home_hello_call)
                    buildAnnotatedString {
                        append(hello)
                        withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append(callsign) }
                    }
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            country?.takeIf { !it.isPoland }?.let { AbroadBanner(it, callsign, onAbroadClick) }

            InfoCard(title = stringResource(R.string.home_utc_time)) {
                UtcClock()
            }

            InfoCard(title = stringResource(R.string.home_qth_locator)) {
                when {
                    !locationGranted -> {
                        Text(stringResource(R.string.home_no_location), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = onRequestLocation, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.home_allow_location)) }
                    }
                    position == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.home_waiting_gps), style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(Maidenhead.fromLatLon(position.lat, position.lon, 6), style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.alignByBaseline())
                            Text(
                                altitude?.let {
                                    String.format(UiLocale, "%,.0f m", it.meters) + if (it.aboveSeaLevel) stringResource(R.string.home_asl) else " (WGS84)"
                                } ?: stringResource(R.string.home_no_altitude),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (altitude != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alignByBaseline()
                            )
                        }
                        Text(
                            String.format(Locale.US, "%.5f°, %.5f°", position.lat, position.lon),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            InfoCard(
                title = "APRS-IS",
                onClick = onAprsClick,
                trailing = { ConnectionDot(aprsConnected) }
            ) {
                Text(
                    when {
                        !aprsConnected -> stringResource(R.string.home_aprs_connecting)
                        aprsPackets.isEmpty() -> stringResource(R.string.home_aprs_no_stations, aprsRadius)
                        else -> pluralStringResource(R.plurals.home_aprs_stations, aprsPackets.size, aprsPackets.size, aprsRadius)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                aprsPackets.take(3).forEach { packet ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(packet.callsign, style = MaterialTheme.typography.bodyMedium)
                        Text("${packet.distanceKm} km", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            nearestRepeater?.takeIf { position != null && it.distanceKm <= 100 }?.let { group ->
                // Dotknięcie karty otwiera listę przemienników
                InfoCard(title = stringResource(R.string.home_nearest_repeater), onClick = onRepeatersClick) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(group.callsign, style = MaterialTheme.typography.headlineSmall)
                        Text("${group.distanceKm} km", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(group.qth, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        // Kilka częstotliwości (np. 2 m i 70 cm) – jedna pod drugą w tej samej kolumnie
                        LabeledValue(stringResource(R.string.home_tx), group.channels.joinToString("\n") { String.format(Locale.US, "%.4f", it.userTx) })
                        LabeledValue(stringResource(R.string.home_rx), group.channels.joinToString("\n") { String.format(Locale.US, "%.4f", it.userRx) })
                        // Emisje jedna pod drugą
                        LabeledValue(stringResource(R.string.home_mode), group.channels.flatMap { it.modes }.map { it.uppercase() }.distinct().joinToString("\n"))
                        val none = stringResource(R.string.home_none)
                        LabeledValue("CTCSS", group.channels.joinToString("\n") { if (it.requiredCtcss != "-") "${it.requiredCtcss} Hz" else none })
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionDot(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(if (connected) stringResource(R.string.home_connected) else stringResource(R.string.home_disconnected), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Zegar w osobnym komponencie – co sekundę przerysowuje się tylko on, a nie cały ekran główny
@Composable
private fun UtcClock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val utcTime = remember { utcFormat("HH:mm:ss") }
    val utcDate = remember { utcFormat("yyyy-MM-dd") }
    val localTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    // Cyfry o równej szerokości (tnum) – zegar nie "skacze" przy zmianie sekund
    Text(
        utcTime.format(now),
        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum")
    )
    Text(
        stringResource(R.string.home_date_local, utcDate.format(now), localTime.format(now)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
