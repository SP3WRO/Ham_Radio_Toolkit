package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.model.Repeater
import io.github.sp3wro.hamradiotoolkit.data.model.RepeaterGroup
import io.github.sp3wro.hamradiotoolkit.data.network.InternationalRepeaters
import io.github.sp3wro.hamradiotoolkit.data.network.RepeaterSources
import io.github.sp3wro.hamradiotoolkit.utils.LatLon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepeatersScreen(
    repeaterRadiusKm: Int,
    repeaterDataSource: String = "przemienniki_net",
    position: LatLon? = null,
    onSettingsClick: () -> Unit = {},
    viewModel: RepeatersViewModel = viewModel()
) {
    val repeaters by viewModel.repeaters.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val selectedBand by viewModel.selectedBand.collectAsState()
    val selectedModeFilter by viewModel.selectedModeFilter.collectAsState()
    val onlyWorking by viewModel.onlyWorking.collectAsState()

    var showFiltersPanel by remember { mutableStateOf(true) }

    LaunchedEffect(repeaterRadiusKm) {
        viewModel.updateRadius(repeaterRadiusKm)
    }

    LaunchedEffect(repeaterDataSource) {
        viewModel.updateDataSource(repeaterDataSource)
    }

    // Pozycja wspólna dla całej aplikacji (GPS na żywo albo ostatnia zapisana)
    LaunchedEffect(position) {
        position?.let { viewModel.updateLocation(it.lat, it.lon) }
    }

    val sourceLabel = RepeaterSources.label(repeaterDataSource)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(stringResource(R.string.rep_title, repeaterRadiusKm), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.common_source_value, sourceLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = { showFiltersPanel = !showFiltersPanel }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = stringResource(R.string.dx_filters),
                            tint = if (selectedBand != "ALL" || selectedModeFilter != "ALL" || onlyWorking) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { viewModel.fetchRepeaters() }) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.common_refresh))
                        }
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
        ) {
            // PANEL FILTRÓW
            AnimatedVisibility(visible = showFiltersPanel) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Pasma
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(stringResource(R.string.dx_band), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                            val bands = listOf("ALL" to stringResource(R.string.common_all), "2M" to "2m", "70CM" to "70cm", "10M" to "10m", "6M" to "6m", "23CM" to "23cm")
                            bands.forEach { (code, label) ->
                                FilterChip(
                                    selected = selectedBand == code,
                                    onClick = { viewModel.setBandFilter(code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.height(32.dp)
                                )
                            }
                        }

                        // Emisje / Tryby
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(stringResource(R.string.dx_mode), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                            val modes = listOf("ALL" to stringResource(R.string.common_all), "FM" to "FM", "DMR" to "DMR", "C4FM" to "C4FM", "DSTAR" to "D-STAR")
                            modes.forEach { (code, label) ->
                                FilterChip(
                                    selected = selectedModeFilter == code,
                                    onClick = { viewModel.setModeFilter(code) },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.height(32.dp)
                                )
                            }
                        }

                        // Opcje dodatkowe (Sprawne)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            FilterChip(
                                selected = onlyWorking,
                                onClick = { viewModel.setOnlyWorking(!onlyWorking) },
                                label = { Text(stringResource(R.string.rep_only_working), style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.height(32.dp)
                            )
                            
                            Text(
                                text = stringResource(R.string.rep_found, repeaters.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (repeaters.isEmpty() && !isLoading) {
                    Text(
                        text = error ?: stringResource(R.string.rep_none),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(repeaters, key = { it.listKey }) { group ->
                            RepeaterCard(group, loadDmr = { viewModel.dmrDetails(it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RepeaterCard(
    group: RepeaterGroup,
    loadDmr: (suspend (Repeater) -> InternationalRepeaters.DmrDetails?)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    // Szczegóły DMR (RadioID) dla częstotliwości z emisją DMR
    val dmrChannel = group.channels.firstOrNull { ch -> ch.modes.any { it.contains("DMR", ignoreCase = true) } }
    val isDmr = dmrChannel != null
    var dmr by remember { mutableStateOf<InternationalRepeaters.DmrDetails?>(null) }
    var dmrLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded && dmrChannel != null && !dmrLoaded && loadDmr != null) {
            dmr = loadDmr(dmrChannel)
            dmrLoaded = true
        }
    }
    // Emisje wszystkich częstotliwości przemiennika, bez powtórzeń
    val modes = group.channels.flatMap { it.modes }.map { it.uppercase() }.distinct()
    val notes = group.channels.map { it.note }.filter { it.isNotBlank() }.distinct()
    val sources = group.channels.flatMap { it.source.split(" + ") }.filter { it.isNotBlank() }.distinct()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.isWorking) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.callsign,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (group.isWorking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = group.qth,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${group.distanceKm} km",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // Emisje jedna pod drugą – przy kilku (np. D-STAR i C4FM) czytelniej niż w jednym rzędzie
                    Spacer(modifier = Modifier.height(4.dp))
                    ModeBadges(modes)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                    // Każda para częstotliwości w osobnym wierszu, jedna pod drugą
                    group.channels.forEachIndexed { index, channel ->
                        if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.rep_tx), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = String.format(java.util.Locale.US, "%.4f", channel.userRx),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.rep_rx), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = String.format(java.util.Locale.US, "%.4f", channel.userTx),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.rep_ctcss), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (channel.requiredCtcss != "-") "${channel.requiredCtcss} Hz" else stringResource(R.string.rep_none_short),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // DMR: color code ze źródła, a talkgrupy i sieć z RadioID
                    if (dmrChannel != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val cc = dmr?.colorCode?.takeIf { it.isNotBlank() } ?: dmrChannel.dmrColorCode
                        Text(
                            buildString {
                                append("DMR")
                                if (cc.isNotBlank()) append(" • CC $cc")
                                dmr?.network?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                            },
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                        )
                        when {
                            !dmrLoaded && loadDmr != null -> Text(stringResource(R.string.rep_checking_radioid), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            dmr != null -> {
                                dmr!!.talkgroups.take(12).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (dmr!!.trustee.isNotBlank()) {
                                    Text(stringResource(R.string.rep_trustee, dmr!!.trustee), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    notes.forEach { note ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (sources.isNotEmpty()) {
                        Text(
                            stringResource(R.string.common_source_value, sources.joinToString(" + ")) + if (isDmr && dmr != null) " • DMR: RadioID.net" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Emisje przemiennika jedna pod drugą (używane też na stronie głównej). */
@Composable
fun ModeBadges(modes: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEach { mode ->
            Badge { Text(mode.uppercase()) }
        }
    }
}
