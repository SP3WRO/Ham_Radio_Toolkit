package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.data.model.BandplanText
import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sp3wro.hamradiotoolkit.data.model.Band
import io.github.sp3wro.hamradiotoolkit.data.model.BandSegment
import io.github.sp3wro.hamradiotoolkit.data.model.BandplanProvider
import io.github.sp3wro.hamradiotoolkit.data.model.RegionalInfo
import io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BandplanScreen(
    detectedRegion: IaruRegion? = null,
    countryIso: String? = null,
    onSettingsClick: () -> Unit = {}
) {
    // Region z pozycji (za granicą R2/R3), ale do podejrzenia można wybrać dowolny
    var region by remember(detectedRegion) { mutableStateOf(detectedRegion ?: IaruRegion.R1) }
    val bands = BandplanProvider.bandsFor(region)
    val info = RegionalInfo.forRegion(region, countryIso.takeIf { detectedRegion == region })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bp_title, region.number)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "region") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IaruRegion.values().forEach { r ->
                            FilterChip(
                                selected = region == r,
                                onClick = { region = r },
                                label = { Text(stringResource(R.string.bp_region, r.number) + if (r == detectedRegion) stringResource(R.string.bp_you_are_here) else "") }
                            )
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(region.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("APRS: ${BandplanText.localize(info.aprs)}", style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.bp_fm_calling, BandplanText.localize(info.fmCalling)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.bp_emergency, BandplanText.localize(info.emergency)), style = MaterialTheme.typography.bodySmall)
                            Text(
                                (if (region == IaruRegion.R1) stringResource(R.string.bp_r1_poland) else "") +
                                    stringResource(R.string.bp_source_note, info.source),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            items(bands, key = { "${region.number}_${it.name}" }) { band ->
                BandCard(band)
            }
        }
    }
}

@Composable
fun BandCard(band: Band) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = band.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = band.range,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    
                    band.segments.forEach { segment ->
                        SegmentRow(segment)
                    }
                }
            }
        }
    }
}

@Composable
fun SegmentRow(segment: BandSegment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.5f)) {
            Text(text = segment.range, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(text = BandplanText.localize(segment.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            ModeBadge(segment.mode)
        }
    }
}

@Composable
fun ModeBadge(mode: String) {
    val backgroundColor = when {
        mode.contains("CW") -> Color(0xFF2196F3)        // Blue
        mode.contains("Wąskopasmowe") || mode.contains("Digi") -> Color(0xFF9C27B0) // Purple
        mode.contains("Phone") || mode.contains("SSB") || mode.contains("FM") -> Color(0xFF4CAF50) // Green
        mode.contains("Sat") -> Color(0xFFFF9800)       // Orange
        else -> Color.Gray
    }

    Box(
        modifier = Modifier
            .background(color = backgroundColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = BandplanText.localize(mode),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
