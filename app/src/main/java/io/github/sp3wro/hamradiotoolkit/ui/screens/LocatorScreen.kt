package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.sp3wro.hamradiotoolkit.data.callbook.Callbook
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.ui.components.AzimuthalMap
import io.github.sp3wro.hamradiotoolkit.ui.components.FullScreenAzimuthalMapDialog
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import java.util.Locale

/** [note] – skąd pochodzi lokator stacji (puste, gdy wpisano lokator lub współrzędne). */
private data class LocatorTarget(val description: String, val position: LatLon, val note: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocatorScreen(
    repository: UserPreferencesRepository,
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val stationPosition by repository.stationPosition.collectAsState(initial = null)
    val manualLocator by repository.userLocator.collectAsState(initial = "")
    var manualLocatorInput by remember(manualLocator) { mutableStateOf(manualLocator) }

    var targetInput by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<LocatorTarget?>(null) }
    var targetError by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var showFullMap by remember { mutableStateOf(false) }

    var convLat by remember { mutableStateOf("") }
    var convLon by remember { mutableStateOf("") }

    fun refreshGps() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) scope.launch { repository.saveLastKnownPosition(loc.latitude, loc.longitude) }
            }
        } catch (_: SecurityException) {
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refreshGps()
    }

    LaunchedEffect(Unit) { refreshGps() }

    fun searchTarget() {
        val text = targetInput.trim()
        if (text.isEmpty()) return
        targetError = null
        isSearching = true
        scope.launch {
            try {
                target = resolveTarget(text, context)
                if (target == null) targetError = res.getString(R.string.loc_not_recognized)
            } catch (e: NoLocatorException) {
                target = null
                targetError = e.message
            } catch (e: Exception) {
                targetError = res.getString(R.string.loc_search_error, e.message.orEmpty())
            } finally {
                isSearching = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.loc_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Moja stacja ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.loc_my_station), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    val pos = stationPosition
                    if (pos != null) {
                        Text(
                            Maidenhead.fromLatLon(pos.lat, pos.lon, 6),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            String.format(Locale.US, "%.4f°, %.4f°  •  %s", pos.lat, pos.lon,
                                if (Maidenhead.isValid(manualLocator)) stringResource(R.string.loc_manual) else stringResource(R.string.loc_from_gps)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(stringResource(R.string.loc_no_position), color = MaterialTheme.colorScheme.error)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualLocatorInput,
                            onValueChange = { manualLocatorInput = it.uppercase().take(8) },
                            label = { Text(stringResource(R.string.loc_my_locator)) },
                            singleLine = true,
                            isError = manualLocatorInput.isNotEmpty() && !Maidenhead.isValid(manualLocatorInput),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { scope.launch { repository.saveUserLocator(manualLocatorInput) } },
                            enabled = manualLocatorInput.isEmpty() || Maidenhead.isValid(manualLocatorInput)
                        ) { Text(if (manualLocatorInput.isEmpty()) stringResource(R.string.loc_use_gps) else stringResource(R.string.common_save)) }
                    }
                    TextButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            refreshGps()
                        } else {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.loc_refresh_gps))
                    }
                }
            }

            // --- Cel ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.loc_distance_bearing), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        label = { Text(stringResource(R.string.loc_target_label)) },
                        placeholder = { Text(stringResource(R.string.loc_target_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchTarget() }),
                        trailingIcon = {
                            if (isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = { searchTarget() }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search)) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    targetError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                    val pos = stationPosition
                    val t = target
                    if (t != null) {
                        Text(t.description, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.loc_target_locator, Maidenhead.fromLatLon(t.position.lat, t.position.lon, 6)) +
                                if (t.note.isNotEmpty()) "\n${t.note}" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (pos != null) {
                            val path = GreatCircle.path(pos, t.position)
                            PathRow(stringResource(R.string.loc_short_path), path.shortPathKm, path.shortPathBearing, Color(0xFFB8860B))
                            PathRow(stringResource(R.string.loc_long_path), path.longPathKm, path.longPathBearing, Color(0xFFC2185B))
                        }
                    }
                }
            }

            stationPosition?.let { pos ->
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                    AzimuthalMap(
                        center = pos, target = target?.position,
                        modifier = Modifier.fillMaxSize().clickable { showFullMap = true }
                    )
                    FilledTonalIconButton(onClick = { showFullMap = true }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.loc_enlarge_map))
                    }
                }
                Text(
                    stringResource(R.string.loc_map_note),
                        
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showFullMap) {
                    FullScreenAzimuthalMapDialog(center = pos, target = target?.position, onDismiss = { showFullMap = false }) {
                        Text(
                            stringResource(R.string.loc_my_station_value, Maidenhead.fromLatLon(pos.lat, pos.lon, 6)),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                        )
                        target?.let { t ->
                            val path = GreatCircle.path(pos, t.position)
                            Text(t.description, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                String.format(UiLocale, "SP: %,.0f km • %.0f°   LP: %,.0f km • %.0f°",
                                    path.shortPathKm, path.shortPathBearing, path.longPathKm, path.longPathBearing),
                                style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFD166)
                            )
                        }
                    }
                }
            }

            // --- Przelicznik współrzędne -> lokator ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.loc_coords_to_locator), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = convLat, onValueChange = { convLat = it }, label = { Text(stringResource(R.string.loc_lat)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = convLon, onValueChange = { convLon = it }, label = { Text(stringResource(R.string.loc_lon)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                        )
                    }
                    val lat = convLat.replace(',', '.').toDoubleOrNull()
                    val lon = convLon.replace(',', '.').toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        Text(Maidenhead.fromLatLon(lat, lon, 8), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PathRow(label: String, km: Double, bearing: Double, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold)
        Text(
            String.format(UiLocale, "%,.0f km  •  %.0f° %s", km, bearing, GreatCircle.compassPoint(bearing)),
            fontWeight = FontWeight.Bold
        )
    }
}

private val COORDINATES = Regex("""^\s*(-?\d+(?:[.,]\d+)?)\s*[;,\s]\s*(-?\d+(?:[.,]\d+)?)\s*$""")

private suspend fun resolveTarget(text: String, context: android.content.Context): LocatorTarget? {
    if (Maidenhead.isValid(text)) {
        val pos = Maidenhead.toLatLon(text) ?: return null
        return LocatorTarget(context.getString(R.string.loc_locator_value, text.uppercase()), pos)
    }
    COORDINATES.find(text)?.let { m ->
        val lat = m.groupValues[1].replace(',', '.').toDouble()
        val lon = m.groupValues[2].replace(',', '.').toDouble()
        if (lat in -90.0..90.0 && lon in -180.0..180.0) {
            return LocatorTarget(context.getString(R.string.loc_point, String.format(Locale.US, "%.4f", lat), String.format(Locale.US, "%.4f", lon)), LatLon(lat, lon))
        }
    }
    val call = text.uppercase()
    val result = Callbook.lookup(context, call, usePskReporter = true)
    if (result.entity == null && result.sources.isEmpty() && result.grid == null) return null
    val grid = result.grid
    val position = grid?.position
        ?: throw NoLocatorException((listOf(context.getString(R.string.loc_no_locator_for, call)) + result.notes).joinToString("\n"))
    val details = listOf(result.name, result.qth, result.entity?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(", ")
    return LocatorTarget(if (details.isEmpty()) call else "$call – $details", position, note = grid.source)
}

private class NoLocatorException(message: String) : Exception(message)
