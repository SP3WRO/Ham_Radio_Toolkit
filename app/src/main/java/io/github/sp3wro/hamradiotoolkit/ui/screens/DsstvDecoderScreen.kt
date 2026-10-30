package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.dsstv.DrmSyncStatus
import io.github.sp3wro.hamradiotoolkit.dsstv.DsstvPicture
import io.github.sp3wro.hamradiotoolkit.dsstv.DsstvReceiver
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zapis obrazka DSSTV w Galerii (Pictures/HamRadioToolkit) jako PNG. */
private fun savePicture(context: Context, picture: DsstvPicture): String? {
    val bitmap = picture.bitmap ?: return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(picture.receivedMs))
        val call = picture.callsign.replace(Regex("[^A-Za-z0-9/-]"), "").replace('/', '-')
        val filename = "DSSTV_${stamp}" + (if (call.isNotEmpty()) "_$call" else "") + ".png"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HamRadioToolkit")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        filename
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun syncText(s: DrmSyncStatus, listening: Boolean, busy: Boolean): String = when {
    busy && !s.locked -> stringResource(R.string.ds_searching_file)
    !listening && !busy -> stringResource(R.string.dec_ready)
    !s.locked -> stringResource(R.string.ds_searching)
    s.fac == null -> stringResource(R.string.ds_sync_fac, s.mode?.label.orEmpty(), s.occupancy?.label.orEmpty())
    else -> {
        val f = s.fac
        stringResource(R.string.ds_mode, s.mode?.label.orEmpty()) + " • ${f.qamLabel} • ${f.occupancy.label} • " +
            (if (f.longInterleave) stringResource(R.string.ds_interleave_long) else stringResource(R.string.ds_interleave_short)) +
            (if (f.highProtection) stringResource(R.string.ds_strong_protection) else "")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsstvDecoderScreen(onSettingsClick: () -> Unit = {}) {
    val context = LocalContext.current
    val res = LocalResources.current
    fun toast(msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }

    val receiver = remember {
        DsstvReceiver().apply {
            // Autozapis każdego obrazka – jak w dekoderze SSTV
            onPicture = { p -> savePicture(context.applicationContext, p)?.let { toast(res.getString(R.string.ds_saved, it)) } }
        }
    }
    DisposableEffect(Unit) { onDispose { receiver.release() } }

    val listening by receiver.listening.collectAsState()
    val busy by receiver.busy.collectAsState()
    val level by receiver.level.collectAsState()
    val sync by receiver.sync.collectAsState()
    val progress by receiver.progress.collectAsState()
    val preview by receiver.preview.collectAsState()
    val pictures by receiver.pictures.collectAsState()
    val callsign by receiver.callsign.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(pictures.size) { selected = 0 }

    val startListening = rememberMicStarter { receiver.start() }
    val wavPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            receiver.decodeFile({ context.contentResolver.openInputStream(uri) }) { found, error ->
                toast(error?.let { res.getString(R.string.dec_file_error, it.toString()) } ?: if (found > 0) res.getString(R.string.ds_received_count, found) else res.getString(R.string.ds_none_found))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DSSTV (HamDRM)") },
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
                    stringResource(R.string.ds_help),
                        
                        
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            syncText(sync, listening, busy),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (listening) LevelBar("Audio", level, Modifier.width(80.dp))
                    }
                    if (sync.locked) {
                        Text(
                            buildString {
                                if (callsign.isNotEmpty()) append(res.getString(R.string.ds_sender, callsign))
                                sync.snrDb?.let { append(String.format(UiLocale, "MER %.1f dB • ", it)) }
                                append(res.getString(R.string.ds_offset, String.format(UiLocale, "%+.0f", sync.frequencyOffsetHz), sync.facOkPercent))
                            },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    progress?.let { p ->
                        val total = p.totalSegments
                        Text(
                            stringResource(R.string.ds_receiving, p.name ?: stringResource(R.string.ds_file),
    total?.let { stringResource(R.string.ds_of, p.receivedSegments, it) } ?: p.receivedSegments.toString()),
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (total != null && total > 0) {
                            LinearProgressIndicator(
                                progress = { (p.receivedSegments.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Obrazek: odebrany albo podgląd w trakcie odbioru
            val current = pictures.getOrNull(selected)
            val shown: Bitmap? = preview ?: current?.bitmap
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(), contentDescription = stringResource(R.string.ds_picture),
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (current != null) stringResource(R.string.ds_cannot_show, current.name) else stringResource(R.string.ds_will_appear),
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (current != null && preview == null) {
                Text(
                    "${current.name}" + (if (current.callsign.isNotEmpty()) " • ${current.callsign}" else "") +
                        (current.bitmap?.let { " • ${it.width}×${it.height}" } ?: "") +
                        String.format(UiLocale, " • %.1f kB", current.bytes.size / 1024.0) +
                        (current.rsCorrected?.let { stringResource(R.string.ds_rs_fixed, it.toString()) } ?: ""),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (pictures.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(pictures, key = { i, p -> "${p.receivedMs}_$i" }) { i, p ->
                        val bmp = p.bitmap
                        Box(
                            Modifier.size(64.dp)
                                .border(if (i == selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                                .clickable { selected = i },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Text(p.extension, style = MaterialTheme.typography.labelSmall)
                        }
                    }
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
