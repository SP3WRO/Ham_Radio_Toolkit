package io.github.sp3wro.hamradiotoolkit.ui.components

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sp3wro.hamradiotoolkit.data.callbook.Callbook
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyRepository
import io.github.sp3wro.hamradiotoolkit.data.dxcc.DxccEntity
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.data.model.calculateBandFromFreq
import io.github.sp3wro.hamradiotoolkit.data.repository.LogbookRepository
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private data class CallsignLookup(
    val entity: DxccEntity?,
    val newCountry: Boolean,
    val previous: List<QsoEntry>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QsoDialog(
    initialQso: QsoEntry? = null,
    defaultFrequency: String = "14195",
    defaultMode: String = "SSB",
    defaultRstSent: String = "59",
    defaultRstRcvd: String = "59",
    prefillCallsign: String = "",
    prefillHuntedRef: String = "",
    stationPosition: LatLon? = null,
    onDismissRequest: () -> Unit,
    onSave: (QsoEntry) -> Unit
) {
    val context = LocalContext.current
    val res = LocalResources.current
    var callsign by remember { mutableStateOf(initialQso?.callsign ?: prefillCallsign.uppercase()) }
    var frequency by remember { mutableStateOf(initialQso?.frequency ?: defaultFrequency) }
    var mode by remember { mutableStateOf(initialQso?.mode ?: defaultMode) }
    var rstSent by remember { mutableStateOf(initialQso?.rstSent ?: defaultRstSent) }
    var rstRcvd by remember { mutableStateOf(initialQso?.rstRcvd ?: defaultRstRcvd) }
    var comment by remember { mutableStateOf(initialQso?.comment ?: "") }
    var name by remember { mutableStateOf(initialQso?.name ?: "") }
    var qth by remember { mutableStateOf(initialQso?.qth ?: "") }
    var gridSquare by remember { mutableStateOf(initialQso?.gridSquare ?: "") }
    var huntedRef by remember { mutableStateOf(initialQso?.huntedRef ?: prefillHuntedRef.uppercase()) }

    var lookup by remember { mutableStateOf<CallsignLookup?>(null) }
    var hamQthStatus by remember { mutableStateOf<String?>(null) }
    // Wartości wpisane automatycznie z HamQTH – czyszczone po zmianie znaku, jeśli użytkownik ich nie ruszał
    var autoName by remember { mutableStateOf<String?>(null) }
    var autoQth by remember { mutableStateOf<String?>(null) }
    var autoGrid by remember { mutableStateOf<String?>(null) }

    // Dropdown dla emisji (Mode)
    var expandedModeDropdown by remember { mutableStateOf(false) }
    val availableModes = listOf("SSB", "CW", "FT8", "FT4", "FM", "AM", "RTTY", "PSK31", "JS8", "SSTV", "DMR", "C4FM")

    // Real-time vs manual time toggle
    var isRealTime by remember { mutableStateOf(initialQso == null) }

    val currentUtcString = remember {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(Date())
    }

    var datetimeUtcInput by remember {
        mutableStateOf(initialQso?.datetimeUtc ?: currentUtcString)
    }

    // Sprawdzenie znaku po chwili bez pisania: kraj DXCC, wcześniejsze QSO, HamQTH
    LaunchedEffect(callsign) {
        val call = callsign.trim()
        if (call.length < 3) {
            lookup = null
            return@LaunchedEffect
        }
        delay(600)
        if (autoName != null && name == autoName) name = ""
        if (autoQth != null && qth == autoQth) qth = ""
        if (autoGrid != null && gridSquare == autoGrid) gridSquare = ""
        autoName = null; autoQth = null; autoGrid = null
        hamQthStatus = null

        val cty = CtyRepository.get(context)
        val entity = cty?.resolve(call)
        val (previous, worked) = withContext(Dispatchers.IO) {
            val repo = LogbookRepository.get(context)
            repo.previousContacts(call).filter { it.id != initialQso?.id } to repo.workedDxcc()
        }
        lookup = CallsignLookup(
            entity = entity,
            newCountry = entity != null && entity.dxcc !in worked,
            previous = previous
        )

        if (Callbook.isCompleteCall(call)) {
            hamQthStatus = res.getString(R.string.qso_looking_up)
            val result = Callbook.lookup(context, call)
            val grid = result.grid
            if (name.isBlank() && result.name.isNotBlank()) { name = result.name; autoName = result.name }
            if (qth.isBlank() && result.qth.isNotBlank()) { qth = result.qth; autoQth = result.qth }
            if (gridSquare.isBlank() && grid != null) { gridSquare = grid.locator; autoGrid = grid.locator }
            hamQthStatus = when {
                grid != null -> res.getString(R.string.qso_locator_from, grid.locator, grid.source)
                gridSquare.isBlank() -> res.getString(R.string.qso_no_locator)
                else -> null
            }.let { listOfNotNull(it) + result.notes }.joinToString("\n").ifEmpty { null }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(if (initialQso == null) stringResource(R.string.qso_new) else stringResource(R.string.qso_edit)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = callsign,
                    onValueChange = { callsign = it.uppercase().trim() },
                    label = { Text(stringResource(R.string.qso_callsign)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )

                CallsignInfoPanel(
                    lookup = lookup,
                    band = calculateBandFromFreq(frequency),
                    mode = mode,
                    gridSquare = gridSquare,
                    stationPosition = stationPosition,
                    hamQthStatus = hamQthStatus
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = frequency,
                        onValueChange = { frequency = it },
                        label = { Text(stringResource(R.string.qso_frequency)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.2f)
                    )

                    // Dropdown Emisja (Mode)
                    ExposedDropdownMenuBox(
                        expanded = expandedModeDropdown,
                        onExpandedChange = { expandedModeDropdown = !expandedModeDropdown },
                        modifier = Modifier.weight(0.8f)
                    ) {
                        OutlinedTextField(
                            value = mode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.qso_mode)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedModeDropdown) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedModeDropdown,
                            onDismissRequest = { expandedModeDropdown = false }
                        ) {
                            availableModes.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        mode = m
                                        expandedModeDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = rstSent,
                        onValueChange = { rstSent = it },
                        label = { Text(stringResource(R.string.qso_rst_sent)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rstRcvd,
                        onValueChange = { rstRcvd = it },
                        label = { Text(stringResource(R.string.qso_rst_rcvd)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.qso_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = qth,
                        onValueChange = { qth = it },
                        label = { Text("QTH") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = gridSquare,
                        onValueChange = { gridSquare = it.uppercase().take(8) },
                        label = { Text(stringResource(R.string.qso_locator)) },
                        singleLine = true,
                        isError = gridSquare.isNotEmpty() && !Maidenhead.isValid(gridSquare),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = huntedRef,
                        onValueChange = { huntedRef = it.uppercase().trim() },
                        label = { Text("POTA/WWFF") },
                        placeholder = { Text("SP-0012") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Czas rzeczywisty toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.qso_utc_now))
                    Switch(
                        checked = isRealTime,
                        onCheckedChange = {
                            isRealTime = it
                            if (it) {
                                datetimeUtcInput = currentUtcString
                            }
                        }
                    )
                }

                if (!isRealTime) {
                    OutlinedTextField(
                        value = datetimeUtcInput,
                        onValueChange = { datetimeUtcInput = it },
                        label = { Text(stringResource(R.string.qso_datetime)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.qso_comment)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalDatetime = if (isRealTime) currentUtcString else datetimeUtcInput
                    val calculatedBand = calculateBandFromFreq(frequency)
                    val entity = lookup?.entity
                    val entry = QsoEntry(
                        id = initialQso?.id ?: UUID.randomUUID().toString(),
                        callsign = callsign.trim(),
                        datetimeUtc = finalDatetime.trim(),
                        frequency = frequency.trim().replace(',', '.'),
                        band = calculatedBand,
                        mode = mode.trim(),
                        rstSent = rstSent.trim(),
                        rstRcvd = rstRcvd.trim(),
                        comment = comment.trim(),
                        name = name.trim(),
                        qth = qth.trim(),
                        gridSquare = gridSquare.trim().takeIf { Maidenhead.isValid(it) }.orEmpty(),
                        dxcc = entity?.dxcc ?: initialQso?.dxcc,
                        country = entity?.name ?: initialQso?.country.orEmpty(),
                        huntedRef = huntedRef.trim(),
                        qrzUploaded = initialQso?.qrzUploaded ?: false,
                        eqslUploaded = initialQso?.eqslUploaded ?: false
                    )
                    onSave(entry)
                },
                enabled = callsign.isNotBlank() && frequency.isNotBlank()
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun CallsignInfoPanel(
    lookup: CallsignLookup?,
    band: String,
    mode: String,
    gridSquare: String,
    stationPosition: LatLon?,
    hamQthStatus: String?
) {
    if (lookup == null && hamQthStatus == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lookup?.entity?.let { entity ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${entity.name} • ${entity.continent} • CQ ${entity.cqZone}", style = MaterialTheme.typography.bodyMedium)
                if (lookup.newCountry) {
                    Text(stringResource(R.string.spot_new_country), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        // Odległość wyłącznie z lokatora korespondenta (wpisanego albo pobranego)
        val target = gridSquare.takeIf { it.length >= 4 }?.let { Maidenhead.toLatLon(it) }
        if (stationPosition != null && target != null) {
            val path = GreatCircle.path(stationPosition, target)
            Text(
                stringResource(R.string.qso_distance, String.format(UiLocale, "%,.0f", path.shortPathKm),
                    String.format(UiLocale, "%.0f", path.shortPathBearing), GreatCircle.compassPoint(path.shortPathBearing)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        lookup?.previous?.takeIf { it.isNotEmpty() }?.let { previous ->
            val dupe = previous.any { it.band.equals(band, ignoreCase = true) && it.mode.equals(mode, ignoreCase = true) }
            val last = previous.first()
            Text(
                if (dupe) stringResource(R.string.qso_dupe, band, mode)
                else stringResource(R.string.qso_worked_before, previous.size, last.datetimeUtc, last.band, last.mode),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (dupe) FontWeight.Bold else FontWeight.Normal,
                color = if (dupe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        hamQthStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
