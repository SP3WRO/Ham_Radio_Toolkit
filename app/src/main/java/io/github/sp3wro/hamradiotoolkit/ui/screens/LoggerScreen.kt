package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.ui.components.QsoDialog
import io.github.sp3wro.hamradiotoolkit.utils.LatLon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggerScreen(
    stationCallsign: String = "",
    stationPosition: LatLon? = null,
    onSettingsClick: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    viewModel: LoggerViewModel = viewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val shownLogs by viewModel.filteredLogs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val busyMessage by viewModel.busyMessage.collectAsState()
    val message by viewModel.message.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingQso by remember { mutableStateOf<QsoEntry?>(null) }
    var qsoToDelete by remember { mutableStateOf<QsoEntry?>(null) }
    var searchOpen by remember { mutableStateOf(searchQuery.isNotEmpty()) }

    val context = LocalContext.current
    val res = LocalResources.current

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    // Launcher do tworzenia pliku (Storage Access Framework); zapis robi ViewModel w tle
    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) viewModel.exportAdif(uri, stationCallsign)
    }

    // Import ADIF z innego loggera
    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importAdif(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.log_title))
                        Text(
                            if (searchQuery.isBlank()) stringResource(R.string.log_count, logs.size) else stringResource(R.string.log_count_filtered, shownLogs.size, logs.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.setSearchQuery("")
                    }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.log_search),
                            tint = if (searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onStatsClick) {
                        Icon(Icons.Filled.BarChart, contentDescription = stringResource(R.string.log_stats))
                    }
                    IconButton(onClick = { viewModel.uploadPending(stationCallsign) }, enabled = busyMessage == null) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = stringResource(R.string.log_upload))
                    }
                    // Rzadziej używane akcje w menu, żeby tytuł się nie zawijał
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.log_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_import_adif)) },
                                leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                                enabled = busyMessage == null,
                                onClick = {
                                    menuOpen = false
                                    openFileLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_export_adif)) },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    if (logs.isEmpty()) {
                                        Toast.makeText(context, res.getString(R.string.log_nothing_to_export), Toast.LENGTH_SHORT).show()
                                    } else {
                                        // Okno zapisu pliku w telefonie z domyślną nazwą qso_log.adi
                                        createFileLauncher.launch("qso_log.adi")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_settings)) },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onSettingsClick()
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.log_add_qso))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            busyMessage?.let {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    placeholder = { Text(stringResource(R.string.log_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.common_clear))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (logs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (shownLogs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.log_no_match, searchQuery),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shownLogs, key = { it.id }) { qso ->
                            QsoItem(
                                qso = qso,
                                onClick = { editingQso = qso },
                                onDelete = { qsoToDelete = qso }
                            )
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            QsoDialog(
                defaultFrequency = viewModel.lastFrequency,
                defaultMode = viewModel.lastMode,
                defaultRstSent = viewModel.lastRstSent,
                defaultRstRcvd = viewModel.lastRstRcvd,
                stationPosition = stationPosition,
                onDismissRequest = { showAddDialog = false },
                onSave = { newQso ->
                    viewModel.addQso(newQso)
                    showAddDialog = false
                    Toast.makeText(context, res.getString(R.string.dx_qso_logged, newQso.callsign), Toast.LENGTH_SHORT).show()
                }
            )
        }

        editingQso?.let { qso ->
            QsoDialog(
                initialQso = qso,
                stationPosition = stationPosition,
                onDismissRequest = { editingQso = null },
                onSave = { updatedQso ->
                    viewModel.updateQso(updatedQso)
                    editingQso = null
                    Toast.makeText(context, res.getString(R.string.log_qso_updated), Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Usunięcia nie da się cofnąć – pytamy o potwierdzenie
        qsoToDelete?.let { qso ->
            AlertDialog(
                onDismissRequest = { qsoToDelete = null },
                title = { Text(stringResource(R.string.log_delete_title)) },
                text = { Text("${qso.callsign}, ${qso.datetimeUtc} UTC, ${qso.band} ${qso.mode}") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteQso(qso.id)
                        qsoToDelete = null
                    }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { qsoToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }
    }
}

@Composable
fun QsoItem(
    qso: QsoEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = qso.callsign,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Badge { Text(qso.mode) }
                    Badge { Text("${qso.frequency} kHz (${qso.band})") }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = qso.datetimeUtc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = "RST: ${qso.rstSent}/${qso.rstRcvd}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val details = listOf(qso.name, qso.country, qso.gridSquare, qso.huntedRef).filter { it.isNotBlank() }
                if (details.isNotEmpty()) {
                    Text(text = details.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (qso.comment.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = qso.comment, style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
                if (qso.qrzUploaded || qso.eqslUploaded) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = listOfNotNull("QRZ ✓".takeIf { qso.qrzUploaded }, "eQSL ✓".takeIf { qso.eqslUploaded }).joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
