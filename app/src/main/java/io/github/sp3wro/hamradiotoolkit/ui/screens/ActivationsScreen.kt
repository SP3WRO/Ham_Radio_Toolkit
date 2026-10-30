package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.model.ActivationSpot
import io.github.sp3wro.hamradiotoolkit.utils.LatLon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivationsScreen(
    activePrograms: Set<String>,
    position: LatLon? = null,
    onSettingsClick: () -> Unit = {},
    viewModel: ActivationsViewModel = viewModel()
) {
    val spots by viewModel.spots.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val status by viewModel.status.collectAsState()
    val context = LocalContext.current

    // Aktualizujemy listę przy zmianie filtrów w preferencjach
    LaunchedEffect(activePrograms) {
        viewModel.updateFilters(activePrograms)
    }

    // Pozycja wspólna dla całej aplikacji – do obliczenia dystansu
    LaunchedEffect(position) {
        position?.let { viewModel.updateLocation(it.lat, it.lon) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.act_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = { viewModel.syncDatabases() }) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.act_sync))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (spots.isEmpty()) {
                Text(
                    text = status ?: if (isSyncing) stringResource(R.string.act_syncing)
                    else stringResource(R.string.act_none, ActivationsViewModel.LIST_RADIUS_KM.toInt()),
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(spots, key = { it.id }) { spot ->
                        ActivationItem(
                            spot = spot,
                            onClick = {
                                val uri = Uri.parse("geo:${spot.latitude},${spot.longitude}?q=${spot.latitude},${spot.longitude}(${Uri.encode(spot.name)})")
                                val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                                mapIntent.setPackage("com.google.android.apps.maps")
                                if (mapIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(mapIntent)
                                } else {
                                    // Jeśli Google Maps brak, użyj domyślnej nawigacji
                                    val defaultIntent = Intent(Intent.ACTION_VIEW, uri)
                                    context.startActivity(defaultIntent)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActivationItem(
    spot: ActivationSpot,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgramBadge(program = spot.program)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = spot.reference,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = spot.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = "${spot.distanceKm} km",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun ProgramBadge(program: String) {
    val backgroundColor = when (program) {
        "SOTA" -> Color(0xFFE91E63)   // Pink
        "POTA" -> Color(0xFFFF9800)   // Orange
        "WWFF", "SPFF" -> Color(0xFF4CAF50)   // Green
        "PGA" -> Color(0xFF2196F3)    // Blue
        "ZAMEK" -> Color(0xFF9C27B0)  // Purple
        else -> Color.Gray
    }

    Box(
        modifier = Modifier
            .background(color = backgroundColor, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = program,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
