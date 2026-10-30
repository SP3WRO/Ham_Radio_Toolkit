package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.sp3wro.hamradiotoolkit.sstv.robot36.Robot36Receiver
import io.github.sp3wro.hamradiotoolkit.sstv.robot36.Robot36Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SstvDecoderScreen(
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val res = LocalResources.current

    fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Zapis jak w Robot36: bezstratny PNG z nazwą yyyyMMdd_HHmmss
    fun saveBitmap(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showToast(res.getString(R.string.sstv_unsupported))
            return
        }
        try {
            val filename = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HamRadioToolkit")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                showToast(res.getString(R.string.sstv_save_error))
                return
            }
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            showToast(res.getString(R.string.sstv_saved_as, filename))
        } catch (e: Exception) {
            showToast(res.getString(R.string.sstv_save_error))
        }
    }

    val receiver = remember {
        Robot36Receiver().apply {
            // Autozapis każdego odebranego obrazu (domyślne ustawienie Robot36)
            onImageDecoded = { bmp -> saveBitmap(bmp) }
        }
    }

    val statusText by receiver.status.collectAsState()
    val scope by receiver.scope.collectAsState()
    val audioLevel by receiver.level.collectAsState()
    val modeName by receiver.currentModeName.collectAsState()
    val receiverState by receiver.state.collectAsState()

    val isListening = receiverState == Robot36Receiver.State.LISTENING
    var selectedMode by remember { mutableStateOf(Robot36Session.AUTO_MODE) }

    val wavPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            receiver.decodeFile { context.contentResolver.openInputStream(uri) }
        }
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            receiver.startListening()
        } else {
            Toast.makeText(context, res.getString(R.string.dec_mic_denied), Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            receiver.stopListening()
        }
    }

    fun toggleListening() {
        if (isListening) {
            receiver.stopListening()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            receiver.startListening()
        } else {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sstv_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Robot36 dobiera wysokość podglądu do proporcji ekranu: szerokość 640, wysokość 496..1280
                    val aspectHeight = if (maxWidth.value > 0f) (640 * maxHeight.value / maxWidth.value).toInt() else 496
                    LaunchedEffect(aspectHeight) {
                        receiver.scopeHeight = aspectHeight
                    }
                    val bitmap = scope
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.sstv_preview),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(text = stringResource(R.string.sstv_ready), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.sstv_level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${(audioLevel * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(
                    progress = { audioLevel },
                    modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
                    color = if (audioLevel > 0.05f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status odbioru: wykryty tryb + stan dekodera
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.sstv_receiving, modeName ?: "—"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Wybór trybu: Auto (VIS + długość linii) albo wymuszony tryb, jak w menu Robot36
            var modeMenuExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = modeMenuExpanded,
                onExpandedChange = { modeMenuExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedMode,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.sstv_mode)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = modeMenuExpanded,
                    onDismissRequest = { modeMenuExpanded = false }
                ) {
                    (listOf(Robot36Session.AUTO_MODE) + Robot36Session.MODE_NAMES).forEach { name ->
                        DropdownMenuItem(
                            text = { Text(if (name == Robot36Session.AUTO_MODE) stringResource(R.string.sstv_auto) else name) },
                            onClick = {
                                selectedMode = name
                                receiver.setMode(name)
                                modeMenuExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Główna akcja na całą szerokość, pozostałe w jednym równym rzędzie – bez przewijania
            Button(
                onClick = { toggleListening() },
                enabled = receiverState != Robot36Receiver.State.DECODING_FILE,
                colors = if (isListening) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(if (isListening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isListening) stringResource(R.string.dec_stop_listening) else stringResource(R.string.dec_start_listening), style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SstvActionButton(
                    icon = Icons.Filled.FolderOpen,
                    label = stringResource(R.string.dec_wav_file),
                    enabled = !isListening,
                    modifier = Modifier.weight(1f),
                    onClick = { wavPickerLauncher.launch(arrayOf("audio/*", "application/octet-stream")) }
                )
                SstvActionButton(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.common_clear),
                    modifier = Modifier.weight(1f),
                    onClick = { receiver.clearScope() }
                )
                SstvActionButton(
                    icon = Icons.Filled.Save,
                    label = stringResource(R.string.common_save),
                    enabled = scope != null,
                    modifier = Modifier.weight(1f),
                    onClick = { saveBitmap(receiver.captureScope()) }
                )
            }
        }
    }
}

@Composable
private fun SstvActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        modifier = modifier.height(56.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
