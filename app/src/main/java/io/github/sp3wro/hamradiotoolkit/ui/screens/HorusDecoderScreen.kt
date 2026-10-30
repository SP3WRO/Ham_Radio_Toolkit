package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.horus.HorusFrameEntry
import io.github.sp3wro.hamradiotoolkit.horus.HorusPayloadLists
import io.github.sp3wro.hamradiotoolkit.horus.HorusReceiver
import io.github.sp3wro.hamradiotoolkit.horus.HorusTelemetry
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Kąt elewacji (°) do balonu na wysokości [altitudeM] w odległości [groundKm] po powierzchni Ziemi. */
internal fun balloonElevationDeg(groundKm: Double, altitudeM: Int): Double {
    val r = 6371.0
    val theta = groundKm / r
    return Math.toDegrees(atan2(cos(theta) - r / (r + altitudeM / 1000.0), sin(theta)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HorusDecoderScreen(
    stationPosition: LatLon?,
    onSettingsClick: () -> Unit = {}
) {
    val receiver = remember { HorusReceiver() }
    DisposableEffect(Unit) { onDispose { receiver.stop() } }
    val context = LocalContext.current
    val res = LocalResources.current
    LaunchedEffect(Unit) { receiver.lists = HorusPayloadLists.get(context.applicationContext) }

    val listening by receiver.listening.collectAsState()
    val busy by receiver.busy.collectAsState()
    val level by receiver.level.collectAsState()
    val snr by receiver.snrDb.collectAsState()
    val tones by receiver.tones.collectAsState()
    val spectrum by receiver.spectrum.collectAsState()
    val frames by receiver.frames.collectAsState()
    var showHelp by remember { mutableStateOf(false) }

    val startListening = rememberMicStarter { receiver.start() }
    val wavPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            receiver.decodeFile({ context.contentResolver.openInputStream(uri) }) { found, error ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, error?.let { res.getString(R.string.dec_file_error, it.toString()) } ?: res.getString(R.string.hor_file_result, found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Zegar do "odebrano X s temu"
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val latest = frames.firstOrNull { it.telemetry != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hor_title)) },
                actions = {
                    IconButton(onClick = { showHelp = !showHelp }) {
                        Icon(if (showHelp) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.dec_how_to))
                    }
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showHelp) {
                Text(
                    stringResource(R.string.hor_help),
                        
                        
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Strojenie: widmo z zaznaczonymi tonami, poziom i SNR
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                busy -> stringResource(R.string.dec_decoding_file)
                                listening -> snr?.let { String.format(UiLocale, "SNR %.1f dB", it) } ?: stringResource(R.string.dec_listening)
                                else -> stringResource(R.string.dec_ready)
                            },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                        )
                        if (listening) LevelBar("Audio", level, Modifier.width(90.dp))
                    }
                    HorusSpectrum(spectrum, tones, Modifier.fillMaxWidth().height(64.dp))
                    Text(
                        if (tones.isEmpty() || tones.all { it <= 0f }) stringResource(R.string.hor_tones_none)
                        else stringResource(R.string.hor_tones, tones.joinToString(" / ") { it.toInt().toString() }),
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (latest != null) {
                LatestTelemetryCard(latest, stationPosition, nowMs)
            }

            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (frames.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.hor_wait),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    FrameList(frames)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                ListenButton(listening = listening, enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (listening) receiver.stop() else startListening()
                }
                FilledTonalIconButton(
                    onClick = { wavPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    enabled = !listening && !busy,
                    modifier = Modifier.size(52.dp)
                ) { Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.ft8_decode_wav)) }
                FilledTonalIconButton(onClick = { receiver.clear() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_clear))
                }
            }
        }
    }
}

@Composable
private fun HorusSpectrum(spectrum: FloatArray, tones: FloatArray, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val toneColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        // Siatka co 1 kHz
        for (k in 1 until (HorusReceiver.SPECTRUM_MAX_HZ / 1000).toInt()) {
            val x = size.width * k * 1000f / HorusReceiver.SPECTRUM_MAX_HZ
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
        }
        for (f in tones) {
            if (f <= 0f || f > HorusReceiver.SPECTRUM_MAX_HZ) continue
            val x = size.width * f / HorusReceiver.SPECTRUM_MAX_HZ
            drawLine(toneColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
        if (spectrum.size < 2) return@Canvas
        val path = Path()
        spectrum.forEachIndexed { i, db ->
            val x = size.width * i / (spectrum.size - 1)
            val y = size.height * (-db / 60f).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2f))
    }
}

@Composable
private fun LatestTelemetryCard(entry: HorusFrameEntry, station: LatLon?, nowMs: Long) {
    val t = entry.telemetry ?: return
    val context = LocalContext.current
    val res = LocalResources.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.callsign, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val ago = ((nowMs - entry.receivedMs) / 1000).coerceAtLeast(0)
                    Text(
                        "v${t.version} • #${t.sequence}" + (t.timeUtc?.let { " • $it UTC" } ?: "") +
                            if (entry.fromFile) stringResource(R.string.hor_from_file) else stringResource(R.string.hor_seconds_ago, ago.toInt()),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (t.hasPosition) {
                    IconButton(onClick = { openMap(context, t) }) { Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.hor_show_map)) }
                    IconButton(onClick = { copyPosition(context, t) }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.hor_copy_position)) }
                }
            }

            val values = buildList {
                t.altitudeM?.let { add(stringResource(R.string.hor_altitude) to String.format(UiLocale, "%,d m", it)) }
                entry.ascentRateMs?.let { add(stringResource(R.string.hor_ascent) to String.format(UiLocale, "%+.1f m/s", it)) }
                t.speedKmh?.let { add(stringResource(R.string.hor_speed) to "$it km/h") }
                t.satellites?.let { add(stringResource(R.string.hor_satellites) to "$it") }
                t.temperatureC?.let { add(stringResource(R.string.hor_temperature) to String.format(UiLocale, "%.0f °C", it)) }
                t.batteryV?.let { add(stringResource(R.string.hor_battery) to String.format(UiLocale, "%.2f V", it)) }
                add("SNR" to String.format(UiLocale, "%.1f dB", entry.snrDb))
            }
            ValueGrid(values)

            if (t.hasPosition) {
                Text(
                    String.format(Locale.US, "%.5f, %.5f", t.latitude, t.longitude) + "  •  " + Maidenhead.fromLatLon(t.latitude, t.longitude),
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace
                )
                if (station != null) {
                    val path = GreatCircle.path(station, LatLon(t.latitude, t.longitude))
                    val elevation = t.altitudeM?.let { balloonElevationDeg(path.shortPathKm, it) }
                    Text(
                        stringResource(R.string.qso_distance, String.format(UiLocale, "%,.1f", path.shortPathKm), String.format(UiLocale, "%.0f", path.shortPathBearing),
                            GreatCircle.compassPoint(path.shortPathBearing)) +
                            (elevation?.let { stringResource(R.string.hor_elevation, String.format(UiLocale, "%.1f", it)) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Text(stringResource(R.string.hor_no_gps), style = MaterialTheme.typography.bodySmall)
            }
            if (t.extra.isNotEmpty()) {
                Text(
                    t.extra.joinToString("  •  ") { (k, v) -> "$k: $v" },
                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ValueGrid(values: List<Pair<String, String>>) {
    values.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun FrameList(frames: List<HorusFrameEntry>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(frames, key = { i, f -> "${f.receivedMs}_${f.raw.hex.hashCode()}_$i" }) { _, f ->
            val t = f.telemetry
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    if (f.fromFile) stringResource(R.string.hor_file) else timeFormat.format(Date(f.receivedMs)),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (t != null) {
                        "${t.callsign} #${t.sequence}" +
                            (t.altitudeM?.let { String.format(UiLocale, " %,d m", it) } ?: "") +
                            (if (t.hasPosition) String.format(Locale.US, " %.4f %.4f", t.latitude, t.longitude) else "") +
                            String.format(UiLocale, " %.0f dB", f.snrDb)
                    } else stringResource(R.string.hor_unknown_format, f.raw.version.toString(), f.raw.hex),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun openMap(context: Context, t: HorusTelemetry) {
    val label = Uri.encode(t.callsign)
    val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", t.latitude, t.longitude, t.latitude, t.longitude, label))
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.hor_no_map_app), Toast.LENGTH_SHORT).show()
    }
}

private fun copyPosition(context: Context, t: HorusTelemetry) {
    val text = String.format(Locale.US, "%s %.5f, %.5f", t.callsign, t.latitude, t.longitude) +
        (t.altitudeM?.let { " $it m" } ?: "")
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.hor_balloon_position), text))
    Toast.makeText(context, context.getString(R.string.hor_copied), Toast.LENGTH_SHORT).show()
}
