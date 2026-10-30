package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.utils.LogStatistics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogStatsScreen(
    onSettingsClick: () -> Unit = {},
    viewModel: LoggerViewModel = viewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val stats = remember(logs) { LogStatistics.compute(logs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("QSO", stats.totalQso.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.stats_calls), stats.uniqueCalls.toString(), Modifier.weight(1f))
                    StatTile("DXCC", stats.dxccCount.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(stringResource(R.string.stats_pota_parks), stats.potaRefs.size.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.stats_wwff_refs), stats.wwffRefs.size.toString(), Modifier.weight(1f))
                }
            }
            if (stats.withoutDxcc > 0) {
                item {
                    Text(
                        stringResource(R.string.stats_without_dxcc, stats.withoutDxcc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.stats_worked_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { Text(stringResource(R.string.stats_by_band_mode), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }
            item { BandModeTable(stats.bandModeCounts, stats.dxccPerBand) }

            item { Text(stringResource(R.string.stats_dxcc_count, stats.dxccCount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }
            items(stats.entities, key = { it.dxcc }) { entity ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entity.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                LogStatistics.BAND_ORDER.filter { it in entity.bands }.joinToString(" ") +
                                    "  •  " + entity.modes.sorted().joinToString("/"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("${entity.qsoCount} QSO", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BandModeTable(counts: Map<String, Map<String, Int>>, dxccPerBand: Map<String, Int>) {
    val bands = LogStatistics.BAND_ORDER.filter { it in counts } + counts.keys.filter { it !in LogStatistics.BAND_ORDER }.sorted()
    if (bands.isEmpty()) {
        Text(stringResource(R.string.stats_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val columns = LogStatistics.MODE_GROUPS + "DXCC"
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Cell(stringResource(R.string.stats_band), bold = true)
            columns.forEach { Cell(if (it == "PHONE") stringResource(R.string.stats_phone) else it, bold = true) }
        }
        HorizontalDivider()
        for (band in bands) {
            Row {
                Cell(band, bold = true)
                LogStatistics.MODE_GROUPS.forEach { group -> Cell((counts[band]?.get(group) ?: 0).let { if (it == 0) "–" else it.toString() }) }
                Cell((dxccPerBand[band] ?: 0).toString())
            }
        }
    }
}

@Composable
private fun Cell(text: String, bold: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(64.dp).padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium
    )
}
