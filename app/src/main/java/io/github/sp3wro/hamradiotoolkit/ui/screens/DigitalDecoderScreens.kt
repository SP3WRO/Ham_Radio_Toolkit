package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.sp3wro.hamradiotoolkit.ft8.Ft8Decode
import io.github.sp3wro.hamradiotoolkit.ft8.Ft8Receiver
import io.github.sp3wro.hamradiotoolkit.ft8.FtxMode
import io.github.sp3wro.hamradiotoolkit.rtty.RttyDecoder
import io.github.sp3wro.hamradiotoolkit.rtty.RttyReceiver
import io.github.sp3wro.hamradiotoolkit.rtty.TuningSpectrum
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Uprawnienie do mikrofonu + start nasłuchu (wspólne dla FT8 i RTTY). */
@Composable
internal fun rememberMicStarter(start: () -> Boolean): () -> Unit {
    val context = LocalContext.current
    val res = LocalResources.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, res.getString(R.string.dec_mic_denied), Toast.LENGTH_SHORT).show()
        else if (!start()) Toast.makeText(context, res.getString(R.string.dec_mic_failed), Toast.LENGTH_SHORT).show()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!start()) Toast.makeText(context, res.getString(R.string.dec_mic_failed), Toast.LENGTH_SHORT).show()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
internal fun ListenButton(
    listening: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onToggle: () -> Unit
) {
    Button(
        onClick = onToggle,
        enabled = enabled,
        colors = if (listening) ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ) else ButtonDefaults.buttonColors(),
        modifier = modifier.height(52.dp)
    ) {
        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (listening) stringResource(R.string.dec_stop_listening) else stringResource(R.string.dec_start_listening), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DecoderActionButton(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        modifier = modifier.height(48.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
internal fun LevelBar(label: String, value: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)
        )
    }
}

// ---------------------------------------------------------------- FT8

private val FTX_FREQUENCIES = mapOf(
    FtxMode.FT8 to "160 m 1,840 • 80 m 3,573 • 40 m 7,074 • 30 m 10,136 • 20 m 14,074 • 17 m 18,100 • " +
        "15 m 21,074 • 12 m 24,915 • 10 m 28,074 • 6 m 50,313 • 2 m 144,174 MHz (USB)",
    FtxMode.FT4 to "80 m 3,575 • 40 m 7,0475 • 30 m 10,140 • 20 m 14,080 • 17 m 18,104 • 15 m 21,140 • " +
        "12 m 24,919 • 10 m 28,180 • 6 m 50,318 • 2 m 144,170 MHz (USB)"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Ft8DecoderScreen(
    onCallsignClick: (String) -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    val receiver = remember { Ft8Receiver() }
    DisposableEffect(Unit) { onDispose { receiver.release() } }

    val state by receiver.state.collectAsState()
    val mode by receiver.mode.collectAsState()
    val decodes by receiver.decodes.collectAsState()
    val level by receiver.level.collectAsState()
    val slotSecond by receiver.slotSecond.collectAsState()
    val status by receiver.status.collectAsState()
    val busy by receiver.busy.collectAsState()
    val listening = state == Ft8Receiver.State.LISTENING

    var onlyCq by remember { mutableStateOf(false) }
    var showFrequencies by remember { mutableStateOf(false) }
    // Lista na cały ekran: chowa zegar, filtry i przyciski pliku – zostaje tylko pasek slotu i Start/Stop
    var expandedList by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val res = LocalResources.current
    val startListening = rememberMicStarter { receiver.startListening() }
    val wavPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) receiver.decodeFile { context.contentResolver.openInputStream(uri) }
    }

    // Zegar UTC odświeżany co 0,25 s (poza nasłuchem też – żeby było widać, kiedy zaczyna się slot)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    val utcFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val shown = if (onlyCq) decodes.filter { it.isCq } else decodes
    val inSlot = if (listening) slotSecond else ((nowMs % mode.slotMs) / 1000f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ft8_title)) },
                actions = {
                    IconButton(onClick = { expandedList = !expandedList }) {
                        Icon(
                            if (expandedList) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (expandedList) stringResource(R.string.ft8_show_controls) else stringResource(R.string.ft8_full_list)
                        )
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
            if (!expandedList) {
                // Zwarty panel: zegar, emisja i stan w dwóch wierszach – reszta wysokości dla listy
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(utcFormat.format(Date(nowMs)) + " UTC", style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            if (busy) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                            Spacer(Modifier.weight(1f))
                            FtxMode.values().forEach { m ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = { receiver.setMode(m) },
                                    label = { Text(m.label) },
                                    enabled = state != Ft8Receiver.State.DECODING_FILE,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                        LinearProgressIndicator(progress = { (inSlot / mode.slotTime).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (listening) LevelBar("Audio", level, Modifier.width(90.dp))
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !onlyCq, onClick = { onlyCq = false }, label = { Text(stringResource(R.string.common_all)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = onlyCq, onClick = { onlyCq = true }, label = { Text(stringResource(R.string.ft8_only_cq)) })
                    Spacer(Modifier.weight(1f))
                    Text("${shown.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { showFrequencies = !showFrequencies }) {
                        Icon(if (showFrequencies) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.ft8_frequencies, mode.label))
                    }
                }
                if (showFrequencies) {
                    Text(
                        stringResource(R.string.ft8_freq_hint, mode.label, FTX_FREQUENCIES.getValue(mode)),
                            
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LinearProgressIndicator(progress = { (inSlot / mode.slotTime).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }

            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (decodes.isEmpty()) stringResource(R.string.ft8_wait_slot, mode.label, if (mode == FtxMode.FT4) "7.5" else "15")
                            else stringResource(R.string.ft8_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Ft8DecodeList(shown, onCallsignClick)
                }
            }

            // Start/Stop, plik i czyszczenie w jednym wierszu – więcej miejsca na listę
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                ListenButton(listening = listening, enabled = state != Ft8Receiver.State.DECODING_FILE, modifier = Modifier.weight(1f)) {
                    if (listening) receiver.stopListening() else startListening()
                }
                if (!expandedList) {
                    FilledTonalIconButton(
                        onClick = { wavPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                        enabled = state == Ft8Receiver.State.IDLE,
                        modifier = Modifier.size(52.dp)
                    ) { Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.ft8_decode_wav)) }
                    FilledTonalIconButton(onClick = { receiver.clear() }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun Ft8DecodeList(decodes: List<Ft8Decode>, onCallsignClick: (String) -> Unit) {
    val slotFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val listState = rememberLazyListState()
    // Nowy slot pojawia się na górze – przewiń do niego, jeśli użytkownik był na górze
    LaunchedEffect(decodes.firstOrNull()) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    // Średnia zieleń – czytelna i w jasnym, i w ciemnym motywie
    val cqColor = Color(0xFF43A047)
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
        var lastSlot = Long.MIN_VALUE
        decodes.forEachIndexed { index, d ->
            if (d.slotStartMs != lastSlot) {
                lastSlot = d.slotStartMs
                item(key = "slot_${d.slotStartMs}_$index") {
                    val label = if (d.fromFile) stringResource(R.string.ft8_file_segment, (d.slotStartMs / d.mode.slotMs + 1).toInt(), d.mode.label)
                    else slotFormat.format(Date(d.slotStartMs)) + " UTC • ${d.mode.label}"
                    Text(
                        label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            item(key = "d_${d.slotStartMs}_${d.freqHz}_$index") {
                val call = d.senderCall
                // Gęste wiersze; długa wiadomość zawija się zamiast ucinać
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (call != null) Modifier.clickable { onCallsignClick(call) } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        String.format(Locale.US, "%+3d %4s %4d", d.snr, Ft8Receiver.dtLabel(d.dt), d.freqHz),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        d.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (d.isCq) FontWeight.Bold else FontWeight.Normal,
                        color = if (d.isCq) cqColor else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        item(key = "legend") {
            Text(
                stringResource(R.string.ft8_legend),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- RTTY

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RttyDecoderScreen(onSettingsClick: () -> Unit = {}) {
    val receiver = remember { RttyReceiver() }
    DisposableEffect(Unit) { onDispose { receiver.stop() } }

    val text by receiver.text.collectAsState()
    val listening by receiver.listening.collectAsState()
    val level by receiver.level.collectAsState()
    val quality by receiver.quality.collectAsState()
    val spectrum by receiver.spectrum.collectAsState()
    val tuning by receiver.tuning.collectAsState()
    val center = tuning.centerHz

    var autoFrequency by remember { mutableStateOf(true) }
    var autoShift by remember { mutableStateOf(true) }
    var autoBaud by remember { mutableStateOf(true) }
    var autoPolarity by remember { mutableStateOf(true) }
    var usos by remember { mutableStateOf(true) }
    var squelch by remember { mutableFloatStateOf(0.45f) }
    var showOptions by remember { mutableStateOf(false) }
    val busy by receiver.busy.collectAsState()
    val recordingSeconds by receiver.recordingSeconds.collectAsState()
    val context = LocalContext.current
    val res = LocalResources.current
    val startListening = rememberMicStarter { receiver.start() }
    val wavPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            receiver.decodeFile({ context.contentResolver.openInputStream(uri) }) { error ->
                if (error != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, res.getString(R.string.dec_file_error, error.toString()), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rtty_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RttySpectrum(
                spectrum = spectrum,
                binHz = receiver.binHz,
                centerHz = center,
                shiftHz = tuning.shiftHz,
                reverse = tuning.reverse,
                onTap = { receiver.setCenter(it) },
                modifier = Modifier.fillMaxWidth().height(110.dp)
            )

            // Bieżące parametry – "A" przy wartościach wykrywanych automatycznie
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { receiver.setCenter(center - 5) }) { Icon(Icons.Filled.Remove, contentDescription = "−5 Hz") }
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${center.toInt()} Hz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            listOf(
                                "${tuning.shiftHz.toInt()} Hz" + if (autoShift) " A" else "",
                                String.format(Locale.US, "%s Bd", formatBaud(tuning.baud)) + if (autoBaud) " A" else "",
                                (if (tuning.reverse) stringResource(R.string.rtty_reverse) else stringResource(R.string.rtty_normal)) + if (autoPolarity) " A" else ""
                            ).joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                        )
                        if (listening && autoFrequency) {
                            Text(
                                if (tuning.locked) stringResource(R.string.rtty_afc_locked) else stringResource(R.string.rtty_afc_searching),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tuning.locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { receiver.setCenter(center + 5) }) { Icon(Icons.Filled.Add, contentDescription = "+5 Hz") }
                    IconButton(onClick = { showOptions = !showOptions }) {
                        Icon(if (showOptions) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.rtty_options))
                    }
                }
            }

            if (showOptions) {
                Card(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    Column(
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AutoManualRow(stringResource(R.string.rtty_tuning), autoFrequency) {
                            autoFrequency = it
                            receiver.setAutoFrequency(it)
                        }
                        Text(
                            if (autoFrequency) stringResource(R.string.rtty_tuning_auto)
                            else stringResource(R.string.rtty_tuning_manual),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        AutoManualRow(stringResource(R.string.rtty_shift), autoShift) {
                            autoShift = it
                            receiver.setAutoShift(it)
                        }
                        if (!autoShift) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TuningSpectrum.STANDARD_SHIFTS.forEach { s ->
                                    FilterChip(selected = tuning.shiftHz == s, onClick = { receiver.setShift(s) },
                                        label = { Text("${s.toInt()} Hz") })
                                }
                            }
                        }

                        AutoManualRow(stringResource(R.string.rtty_speed), autoBaud) {
                            autoBaud = it
                            receiver.setAutoBaud(it)
                        }
                        if (!autoBaud) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RttyDecoder.STANDARD_BAUDS.forEach { b ->
                                    FilterChip(selected = tuning.baud == b, onClick = { receiver.setBaud(b) },
                                        label = { Text("${formatBaud(b)} Bd") })
                                }
                            }
                        }

                        AutoManualRow(stringResource(R.string.rtty_polarity), autoPolarity) {
                            autoPolarity = it
                            receiver.setAutoPolarity(it)
                        }
                        if (!autoPolarity) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = tuning.reverse, onCheckedChange = { receiver.setReverse(it) })
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.rtty_reversed_long), style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = usos, onCheckedChange = {
                                usos = it
                                receiver.setUnshiftOnSpace(it)
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.rtty_usos), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(stringResource(R.string.rtty_squelch, (squelch * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                        Slider(value = squelch, onValueChange = {
                            squelch = it
                            receiver.setSquelch(it)
                        }, valueRange = 0f..0.9f)
                        Text(stringResource(R.string.rtty_info),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LevelBar(stringResource(R.string.rtty_audio_level), level, Modifier.weight(1f))
                LevelBar(stringResource(R.string.rtty_quality), quality, Modifier.weight(1f))
            }

            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                    Text(
                        text.ifEmpty { stringResource(R.string.rtty_tune_hint) },
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                        color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            ListenButton(listening = listening, enabled = !busy) {
                if (listening) {
                    // Zatrzymanie nasłuchu kończy też nagrywanie – zapisujemy to, co jest
                    if (recordingSeconds != null) saveRttyRecording(context, receiver.stopRecording())
                    receiver.stop()
                } else startListening()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                DecoderActionButton(Icons.Filled.FolderOpen, if (busy) stringResource(R.string.dec_decoding) else stringResource(R.string.dec_wav_file), Modifier.weight(1f),
                    enabled = !listening && !busy) {
                    wavPicker.launch(arrayOf("audio/*", "application/octet-stream"))
                }
                val rec = recordingSeconds
                DecoderActionButton(
                    if (rec != null) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                    if (rec != null) stringResource(R.string.dec_save_rec, rec.toInt()) else stringResource(R.string.dec_record),
                    Modifier.weight(1f), enabled = listening
                ) {
                    if (rec != null) saveRttyRecording(context, receiver.stopRecording()) else receiver.startRecording()
                }
                DecoderActionButton(Icons.Filled.Delete, stringResource(R.string.common_clear), Modifier.weight(1f)) { receiver.clear() }
            }
        }
    }
}

/** Zapis nagranej próbki RTTY do Muzyka/HamRadioToolkit (WAV 12 kHz, mono). */
private fun saveRttyRecording(context: android.content.Context, samples: FloatArray) {
    if (samples.isEmpty()) return
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
        Toast.makeText(context, context.getString(R.string.dec_save_unsupported), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val name = "rtty_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC + "/HamRadioToolkit")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException(context.getString(R.string.dec_no_storage))
        resolver.openOutputStream(uri)?.use {
            io.github.sp3wro.hamradiotoolkit.dsp.WavPcmWriter.write(it, samples, io.github.sp3wro.hamradiotoolkit.dsp.MicInput.TARGET_RATE)
        }
        values.clear()
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Toast.makeText(context, context.getString(R.string.dec_saved_music, name), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.dec_save_error, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
    }
}

private fun formatBaud(baud: Double): String =
    if (baud % 1.0 == 0.0) baud.toInt().toString() else String.format(io.github.sp3wro.hamradiotoolkit.utils.UiLocale, "%.2f", baud)

@Composable
private fun AutoManualRow(label: String, auto: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        FilterChip(selected = auto, onClick = { onChange(true) }, label = { Text("Auto") })
        Spacer(Modifier.width(6.dp))
        FilterChip(selected = !auto, onClick = { onChange(false) }, label = { Text(stringResource(R.string.dec_manual)) })
    }
}

/** Widmo 0–3 kHz z zaznaczonymi tonami mark (M) i space (S); dotknięcie ustawia środek. */
@Composable
private fun RttySpectrum(
    spectrum: FloatArray,
    binHz: Double,
    centerHz: Double,
    shiftHz: Double,
    reverse: Boolean,
    onTap: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val maxHz = 3000.0
    val background = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val markColor = Color(0xFFE53935)
    val spaceColor = Color(0xFF1E88E5)
    Canvas(
        modifier = modifier
            .background(background, MaterialTheme.shapes.medium)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onTap(offset.x / size.width * maxHz) }
            }
    ) {
        val w = size.width
        val h = size.height
        if (spectrum.isNotEmpty()) {
            val sorted = spectrum.sorted()
            val floor = sorted[sorted.size / 4]
            val top = (sorted.last() + 3f).coerceAtLeast(floor + 20f)
            val path = Path()
            spectrum.forEachIndexed { i, db ->
                val x = (i * binHz / maxHz * w).toFloat()
                val y = h - ((db - floor) / (top - floor)).coerceIn(0f, 1f) * (h - 4f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 2f))
        }
        val higher = centerHz + shiftHz / 2
        val lower = centerHz - shiftHz / 2
        val markHz = if (reverse) lower else higher
        val spaceHz = if (reverse) higher else lower
        drawLine(markColor, Offset((markHz / maxHz * w).toFloat(), 0f), Offset((markHz / maxHz * w).toFloat(), h), strokeWidth = 3f)
        drawLine(spaceColor, Offset((spaceHz / maxHz * w).toFloat(), 0f), Offset((spaceHz / maxHz * w).toFloat(), h), strokeWidth = 3f)
        // Podziałka co 500 Hz
        for (f in 500 until 3000 step 500) {
            val x = (f / maxHz * w).toFloat()
            drawLine(Color.Gray.copy(alpha = 0.4f), Offset(x, h - 10f), Offset(x, h), strokeWidth = 2f)
        }
    }
}
