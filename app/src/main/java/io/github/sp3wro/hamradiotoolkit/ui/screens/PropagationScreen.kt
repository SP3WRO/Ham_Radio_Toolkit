package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.model.BandCondition
import io.github.sp3wro.hamradiotoolkit.data.model.SolarData
import io.github.sp3wro.hamradiotoolkit.data.model.VhfCondition
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropagationScreen(
    tropocastArea: String,
    onSettingsClick: () -> Unit = {},
    viewModel: PropagationViewModel = viewModel()
) {
    val solarData by viewModel.solarData.collectAsState()
    val tropocastImageUrl by viewModel.tropocastImageUrl.collectAsState()
    val tropocastTimeOffset by viewModel.tropocastTimeOffset.collectAsState()
    
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val tropocastError by viewModel.tropocastError.collectAsState()

    // Aktualizujemy obszar w ViewModelu, gdy zmieni się on w preferencjach
    LaunchedEffect(tropocastArea) {
        viewModel.setTropocastArea(tropocastArea)
    }

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf(stringResource(R.string.prop_tab_hf), stringResource(R.string.prop_tab_vhf))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prop_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = { viewModel.fetchSolarData() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading && solarData == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null && solarData == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = error ?: "", color = MaterialTheme.colorScheme.error)
                }
            } else if (solarData != null) {
                // Dane z pamięci po nieudanym odświeżeniu – widoczna informacja zamiast cichego braku aktualizacji
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                if (selectedTabIndex == 0) {
                    SolarIndicatorsCard(data = solarData!!)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }

                when (selectedTabIndex) {
                    0 -> HfConditionsList(conditions = solarData!!.hfConditions)
                    1 -> TropocastMapTab(
                        imageUrl = tropocastImageUrl,
                        currentOffset = tropocastTimeOffset,
                        onOffsetChange = { viewModel.setTropocastOffset(it) },
                        error = tropocastError
                    )
                }
            }
        }
    }
}

@Composable
fun TropocastMapTab(
    imageUrl: String?,
    currentOffset: Int,
    onOffsetChange: (Int) -> Unit,
    error: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.prop_tropocast_title),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Przyciski offsetu
        val options = listOf(-6 to "-6h", -3 to "-3h", 0 to stringResource(R.string.prop_now), 3 to "+3h", 6 to "+6h")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            options.forEach { (offset, label) ->
                val isSelected = currentOffset == offset
                Button(
                    onClick = { onOffsetChange(offset) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                ) {
                    Text(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (imageUrl != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(R.string.prop_vhf_map_desc),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                    
                    // Legenda kolorów
                    TropocastLegend()
                }
            }
        } else if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(32.dp))
        } else {
            CircularProgressIndicator(modifier = Modifier.padding(32.dp))
        }
    }
}

@Composable
fun TropocastLegend() {
    val legendData = listOf(
        Color(0xFF191C20) to stringResource(R.string.prop_legend_background),
        Color(0xFF8822CC) to stringResource(R.string.prop_legend_marginal),
        Color(0xFF4488EE) to stringResource(R.string.prop_legend_weak),
        Color(0xFF22BBEE) to stringResource(R.string.prop_legend_moderate),
        Color(0xFF33CC44) to stringResource(R.string.prop_legend_good),
        Color(0xFFEEDD22) to stringResource(R.string.prop_legend_very_good),
        Color(0xFFFF9922) to stringResource(R.string.prop_legend_high),
        Color(0xFFEE4444) to stringResource(R.string.prop_legend_strong),
        Color(0xFFFFBBCC) to stringResource(R.string.prop_legend_very_strong)
    )

    // Siatka 3 kolumny – cała legenda widoczna od razu, bez przewijania
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        legendData.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (color, label) ->
                    LegendItem(color = color, label = label, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun SolarIndicatorsCard(data: SolarData) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.prop_solar_params), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text = stringResource(R.string.prop_updated, data.updated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IndicatorItem("SFI", data.solarFlux)
                IndicatorItem(stringResource(R.string.prop_sunspots), data.sunspots)
                IndicatorItem(stringResource(R.string.prop_a_index), data.aIndex)
                IndicatorItem(stringResource(R.string.prop_k_index), data.kIndex)
            }
        }
    }
}

@Composable
fun IndicatorItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HfConditionsList(conditions: List<BandCondition>) {
    val groupedByBand = conditions.groupBy { it.name }

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.prop_day),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.prop_night),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            HorizontalDivider()
        }

        items(groupedByBand.keys.toList()) { bandName ->
            val bandItems = groupedByBand[bandName] ?: emptyList()
            val dayCondition = bandItems.find { it.time.equals("day", ignoreCase = true) }?.condition ?: "-"
            val nightCondition = bandItems.find { it.time.equals("night", ignoreCase = true) }?.condition ?: "-"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bandName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (dayCondition != "-") ConditionBadge(dayCondition) else Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (nightCondition != "-") ConditionBadge(nightCondition) else Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun ConditionBadge(condition: String) {
    val backgroundColor = when (condition.lowercase()) {
        "good" -> Color(0xFF4CAF50) // Zielony
        "fair" -> Color(0xFFFF9800) // Pomarańczowy
        "poor" -> Color(0xFFF44336) // Czerwony
        "band closed" -> Color.DarkGray
        else -> Color.Gray
    }

    Box(
        modifier = Modifier
            .background(color = backgroundColor, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = when (condition.lowercase()) {
                "good" -> stringResource(R.string.prop_cond_good)
                "fair" -> stringResource(R.string.prop_cond_fair)
                "poor" -> stringResource(R.string.prop_cond_poor)
                "band closed" -> stringResource(R.string.prop_cond_closed)
                else -> condition
            },
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
