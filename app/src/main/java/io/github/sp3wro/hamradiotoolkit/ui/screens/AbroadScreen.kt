package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.data.model.BandplanText
import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyRepository
import io.github.sp3wro.hamradiotoolkit.data.dxcc.DxccEntity
import io.github.sp3wro.hamradiotoolkit.data.geo.CountryInfo
import io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion
import io.github.sp3wro.hamradiotoolkit.data.intl.CeptLicence
import io.github.sp3wro.hamradiotoolkit.data.model.RegionalInfo
import io.github.sp3wro.hamradiotoolkit.ui.components.InfoCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Asystent pracy z zagranicy: znak do użycia według CEPT T/R 61-01, region IARU z częstotliwościami,
 * strefy i czas. Domyślnie kraj z pozycji GPS; można też wybrać dowolny kraj (planowanie wyjazdu).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbroadScreen(
    callsign: String,
    detectedCountry: CountryInfo?,
    onOpenRepeaters: () -> Unit,
    onOpenBandplan: () -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf<CountryInfo?>(null) }
    val country = chosen ?: detectedCountry
    var menuOpen by remember { mutableStateOf(false) }
    val countries = remember { CeptLicence.allCountries() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ab_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = when {
                        chosen != null -> "${chosen!!.flag} ${chosen!!.namePl}"
                        detectedCountry != null -> stringResource(R.string.ab_from_gps, "${detectedCountry.flag} ${detectedCountry.namePl}")
                        else -> stringResource(R.string.ab_choose_country)
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.lk_country)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (detectedCountry != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ab_current_location, detectedCountry.flag, detectedCountry.namePl)) },
                            onClick = { chosen = null; menuOpen = false }
                        )
                        HorizontalDivider()
                    }
                    countries.forEach { c ->
                        DropdownMenuItem(text = { Text("${c.flag} ${c.namePl}") }, onClick = { chosen = c; menuOpen = false })
                    }
                }
            }

            if (country == null) {
                Text(
                    stringResource(R.string.ab_detecting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val advice = CeptLicence.advice(country, callsign)
            val cept = advice.cept
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (cept != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${country.flag} ${country.namePl}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    when {
                        country.isPoland -> Text(stringResource(R.string.ab_in_poland))
                        cept == null -> {
                            Text(stringResource(R.string.ab_no_cept), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.ab_need_permit))
                        }
                        else -> {
                            Text(stringResource(R.string.ab_transmit_as), style = MaterialTheme.typography.labelLarge)
                            Text(
                                advice.callsign ?: "${advice.prefix}/${stringResource(R.string.ab_your_call)}",
                                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (cept.cept) stringResource(R.string.ab_cept_member)
                                else stringResource(R.string.ab_cept_nonmember),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (cept.licences.isNotBlank()) {
                                Text(stringResource(R.string.ab_equivalent, localizedLicences(cept.licences)), style = MaterialTheme.typography.bodySmall)
                            }
                            if (cept.prefixes.size > 1) {
                                Text(stringResource(R.string.ab_guest_prefixes, cept.prefixes.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                            }
                            cept.note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                            if (country.isoCode == "CA" && advice.prefix == "VE") {
                                Text(stringResource(R.string.ab_canada),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(stringResource(R.string.ab_how_to_sign),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (!country.isPoland && cept != null) {
                InfoCard(title = stringResource(R.string.ab_remember)) {
                    stringArrayResource(R.array.ab_remember_items).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }

            val region = IaruRegion.forCountry(country.isoCode) ?: IaruRegion.R1
            val freqs = RegionalInfo.forRegion(region, country.isoCode)
            InfoCard(title = stringResource(R.string.ab_iaru_region, region.number)) {
                Text(region.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("APRS: ${BandplanText.localize(freqs.aprs)}", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.bp_fm_calling, BandplanText.localize(freqs.fmCalling)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.bp_emergency, BandplanText.localize(freqs.emergency)), style = MaterialTheme.typography.bodyMedium)
                if (region != IaruRegion.R1) {
                    Text(stringResource(R.string.ab_different_plan),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenBandplan) {
                        Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tool_bandplan))
                    }
                    if (chosen == null) {
                        TextButton(onClick = onOpenRepeaters) {
                            Icon(Icons.Filled.CellTower, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ab_repeaters_nearby))
                        }
                    }
                }
            }

            // Strefy i czas z bazy DXCC – po prefiksie kraju
            var entity by remember(country.isoCode) { mutableStateOf<DxccEntity?>(null) }
            LaunchedEffect(country.isoCode, advice.prefix) {
                val prefix = advice.prefix ?: return@LaunchedEffect
                // Przykładowy znak z prefiksem gościa: OH0 → OH0AA (Wyspy Alandzkie), DL → DL1AA
                val sample = if (prefix.last().isDigit()) prefix + "AA" else prefix + "1AA"
                entity = CtyRepository.get(context)?.resolve(sample)
            }
            entity?.let { e ->
                InfoCard(title = stringResource(R.string.ab_zones_time)) {
                    Text("DXCC: ${e.name} (${e.primaryPrefix}) • ${e.continent}", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ab_zones, e.cqZone.toString(), e.ituZone.toString()), style = MaterialTheme.typography.bodyMedium)
                    val offset = e.utcOffsetHours
                    val local = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date(System.currentTimeMillis() + (offset * 3_600_000).toLong()))
                    val sign = if (offset >= 0) "+" else "−"
                    val hours = abs(offset).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                    Text(stringResource(R.string.ab_standard_time, local, "$sign$hours"), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                stringResource(R.string.ab_source, CeptLicence.SOURCE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Krótka karta na ekran główny, gdy jesteśmy za granicą. */
@Composable
fun AbroadBanner(country: CountryInfo, callsign: String, onClick: () -> Unit) {
    val advice = CeptLicence.advice(country, callsign)
    InfoCard(title = stringResource(R.string.ab_title), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(country.flag, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(country.namePl, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        advice.cept == null -> stringResource(R.string.ab_outside_cept)
                        advice.callsign != null -> stringResource(R.string.ab_transmit_as_value, advice.callsign)
                        else -> stringResource(R.string.ab_guest_prefix, advice.prefix.orEmpty())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (advice.cept == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Opis licencji z tabeli CEPT (zapisany po polsku) w języku interfejsu. */
@Composable
private fun localizedLicences(raw: String): String = raw
    .replace("dawniej także", stringResource(R.string.cept_formerly_also))
    .replace("dawniej", stringResource(R.string.cept_formerly))
    .replace("gość", stringResource(R.string.cept_guest))
    .replace("uprawnienia", stringResource(R.string.cept_privileges))
    .replace("klasy", stringResource(R.string.cept_classes))
    .replace(" i ", " " + stringResource(R.string.cept_and) + " ")
