package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// Baza danych dla kabli, mapa z wartościami tłumienności w dB/100m dla wybranych częstotliwości (MHz)
data class CoaxData(val name: String, val att100m: Map<Double, Double>)

val coaxDatabase = listOf(
    CoaxData("H155 (Belden)", mapOf(5.0 to 2.5, 50.0 to 6.9, 100.0 to 9.1, 145.0 to 11.0, 230.0 to 13.4, 400.0 to 18.0, 430.0 to 18.7, 800.0 to 26.1, 1000.0 to 29.6)),
    CoaxData("RG58 Premium", mapOf(10.0 to 4.2, 50.0 to 10.5, 100.0 to 15.1, 145.0 to 18.2, 230.0 to 22.4, 430.0 to 32.5, 470.0 to 35.6, 860.0 to 49.4, 1000.0 to 54.0)),
    CoaxData("RG213 (Satec)", mapOf(10.0 to 1.8, 50.0 to 4.8, 100.0 to 6.8, 145.0 to 8.2, 200.0 to 9.0, 400.0 to 14.4, 430.0 to 15.0, 1000.0 to 24.7)),
    CoaxData("Trilan 400 (Dipol)", mapOf(30.0 to 2.3, 50.0 to 3.0, 150.0 to 4.9, 220.0 to 6.0, 450.0 to 8.7, 900.0 to 12.8, 1500.0 to 17.0)),
    CoaxData("Aircell 5 (SSB)", mapOf(10.0 to 2.78, 50.0 to 6.28, 100.0 to 8.93, 144.0 to 10.76, 432.0 to 18.99, 1000.0 to 29.54)),
    CoaxData("Aircell 7 (SSB)", mapOf(10.0 to 2.09, 50.0 to 4.29, 100.0 to 5.97, 144.0 to 7.22, 200.0 to 8.59, 432.0 to 12.92, 500.0 to 13.98, 800.0 to 18.05)),
    CoaxData("Ecoflex 10 (SSB)", mapOf(10.0 to 1.14, 50.0 to 2.66, 100.0 to 3.8, 144.0 to 4.66, 200.0 to 5.51, 432.0 to 8.46, 500.0 to 9.12, 1000.0 to 15.49)),
    CoaxData("Ecoflex 15 (SSB)", mapOf(10.0 to 0.86, 50.0 to 1.96, 100.0 to 2.81, 144.0 to 3.40, 432.0 to 6.10, 1000.0 to 9.80)),
    CoaxData("HyperFlex 5 (M&P)", mapOf(10.0 to 2.6, 50.0 to 5.5, 100.0 to 8.0, 144.0 to 9.6, 430.0 to 17.0, 800.0 to 23.4, 1000.0 to 26.4)),
    CoaxData("HyperFlex 10 (M&P)", mapOf(10.0 to 1.3, 50.0 to 2.7, 100.0 to 3.9, 144.0 to 4.7, 430.0 to 8.6, 800.0 to 11.9, 1000.0 to 13.4)),
    CoaxData("LMR-240 / CNT-240", mapOf(30.0 to 4.4, 50.0 to 5.4, 150.0 to 9.9, 220.0 to 12.0, 450.0 to 17.3, 900.0 to 24.8, 1500.0 to 35.0)),
    CoaxData("LMR-400 / CNT-400", mapOf(30.0 to 2.5, 50.0 to 3.2, 150.0 to 4.9, 220.0 to 6.2, 450.0 to 8.9, 900.0 to 12.8, 1500.0 to 16.7)),
    CoaxData("H1000 (Belden)", mapOf(10.0 to 1.4, 50.0 to 2.8, 100.0 to 4.0, 200.0 to 5.7, 400.0 to 8.4, 800.0 to 12.3, 1000.0 to 14.0)),
    CoaxData("RG-174 (Pigtail)", mapOf(10.0 to 9.5, 50.0 to 18.2, 100.0 to 31.0, 145.0 to 38.0, 200.0 to 51.0, 430.0 to 77.0, 1000.0 to 120.0)),
    CoaxData("RG-214", mapOf(50.0 to 4.5, 100.0 to 6.8, 200.0 to 10.3, 400.0 to 15.0, 600.0 to 20.0, 1000.0 to 27.0)),
    CoaxData("LDF4-50A (1/2\")", mapOf(10.0 to 0.67, 50.0 to 1.52, 100.0 to 2.17, 150.0 to 2.67, 450.0 to 4.75, 1000.0 to 7.28))
)

// Funkcja interpolacji liniowej tłumienności
fun getAttenuationForFreq(cable: CoaxData, freqMhz: Double): Double {
    val entries = cable.att100m.entries.sortedBy { it.key }
    
    // Częstotliwość mniejsza niż pierwszy punkt
    if (freqMhz <= entries.first().key) {
        val lower = Pair(0.0, 0.0)
        val upper = Pair(entries.first().key, entries.first().value)
        return (freqMhz - lower.first) / (upper.first - lower.first) * (upper.second - lower.second) + lower.second
    }
    
    // Częstotliwość większa niż ostatni punkt
    if (freqMhz >= entries.last().key) return 10000.0 // Oznacza błąd / zbyt dużą tłumienność
    
    // Trafienie
    val exactMatch = entries.find { it.key == freqMhz }
    if (exactMatch != null) return exactMatch.value
    
    // Szukanie 2 najbliższych punktów
    for (i in 0 until entries.size) {
        if (entries[i].key > freqMhz) {
            val lower = Pair(entries[i-1].key, entries[i-1].value)
            val upper = Pair(entries[i].key, entries[i].value)
            // Interpolacja liniowa
            return (freqMhz - lower.first) / (upper.first - lower.first) * (upper.second - lower.second) + lower.second
        }
    }
    return 10000.0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoaxCalcScreen(
    onSettingsClick: () -> Unit = {}
) {
    var freqInput by remember { mutableStateOf("145") }
    var lengthInput by remember { mutableStateOf("10") }
    var pwrInput by remember { mutableStateOf("50") }
    
    var expandedDropdown by remember { mutableStateOf(false) }
    var selectedCable by remember { mutableStateOf(coaxDatabase[0]) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.coax_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Dropdown do wyboru kabla
            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = { expandedDropdown = !expandedDropdown },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedCable.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.coax_choose)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false }
                ) {
                    coaxDatabase.forEach { cable ->
                        DropdownMenuItem(
                            text = { Text(text = cable.name) },
                            onClick = {
                                selectedCable = cable
                                expandedDropdown = false
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = freqInput,
                    onValueChange = { freqInput = it },
                    label = { Text(stringResource(R.string.coax_freq)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = lengthInput,
                    onValueChange = { lengthInput = it },
                    label = { Text(stringResource(R.string.coax_length)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = pwrInput,
                onValueChange = { pwrInput = it },
                label = { Text(stringResource(R.string.coax_power)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            val freq = freqInput.replace(",", ".").toDoubleOrNull() ?: 0.0
            val length = lengthInput.replace(",", ".").toDoubleOrNull() ?: 0.0
            val pwr = pwrInput.replace(",", ".").toDoubleOrNull() ?: 0.0
            
            val att100m = getAttenuationForFreq(selectedCable, freq)
            
            if (att100m >= 10000.0) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.coax_no_data), modifier = Modifier.padding(16.dp))
                }
            } else if (freq > 0 && length > 0 && pwr > 0) {
                val totalAttDb = att100m * (length / 100.0)
                val powerOut = 10.0.pow(log10(pwr) - (totalAttDb / 10.0))
                val powerLostW = pwr - powerOut
                val lossPercentage = 100.0 - 10.0.pow(log10(100.0) - (totalAttDb / 10.0))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.coax_result, selectedCable.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.coax_attenuation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(String.format(Locale.US, "%.2f dB", totalAttDb), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.coax_power_at_antenna), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(String.format(Locale.US, "%.1f W", powerOut), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(stringResource(R.string.coax_loss_percent, String.format(Locale.US, "%.1f", lossPercentage)), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.coax_power_lost, String.format(Locale.US, "%.1f", powerLostW)))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.coax_credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
