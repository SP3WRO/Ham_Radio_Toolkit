package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.data.callbook.Callbook
import io.github.sp3wro.hamradiotoolkit.data.callbook.CallbookResult
import io.github.sp3wro.hamradiotoolkit.data.dxcc.DxccEntity
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.data.repository.LogbookRepository
import io.github.sp3wro.hamradiotoolkit.ui.components.InfoCard
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import io.github.sp3wro.hamradiotoolkit.utils.polishPlural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private data class CallsignReport(
    val callbook: CallbookResult,
    val newCountry: Boolean,
    val previous: List<QsoEntry>
) {
    val callsign: String get() = callbook.callsign
    val entity: DxccEntity? get() = callbook.entity
}

// Ostatnie wyszukiwania – w pamięci, do końca działania aplikacji
private val recentCallsigns = mutableStateListOf<String>()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CallsignLookupScreen(
    initialCall: String = "",
    stationPosition: LatLon?,
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf(initialCall.uppercase()) }
    var report by remember { mutableStateOf<CallsignReport?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search(text: String = input) {
        val call = text.trim().uppercase()
        if (call.length < 3) {
            error = res.getString(R.string.lk_min_chars)
            return
        }
        input = call
        error = null
        searching = true
        focusManager.clearFocus()
        recentCallsigns.remove(call)
        recentCallsigns.add(0, call)
        if (recentCallsigns.size > 8) recentCallsigns.removeAt(recentCallsigns.lastIndex)
        scope.launch {
            try {
                report = lookup(context, call)
                if (report?.entity == null && report?.callbook?.sources.isNullOrEmpty()) error = res.getString(R.string.lk_unknown_prefix)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = res.getString(R.string.lk_failed, e.message.orEmpty())
            } finally {
                searching = false
            }
        }
    }

    LaunchedEffect(initialCall) {
        if (initialCall.length >= 3) search(initialCall)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lk_title)) },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(15) },
                label = { Text(stringResource(R.string.settings_callsign)) },
                placeholder = { Text(stringResource(R.string.lk_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = {
                    if (searching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search)) }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (recentCallsigns.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentCallsigns.forEach { call ->
                        SuggestionChip(onClick = { search(call) }, label = { Text(call) })
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            report?.let { r -> CallsignReportView(r, stationPosition, onSettingsClick) }

            if (report == null && !searching) {
                Text(
                    stringResource(R.string.lk_info),
                        
                        
                        
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private suspend fun lookup(context: Context, call: String): CallsignReport {
    val callbook = Callbook.lookup(context, call, usePskReporter = true)
    val entity = callbook.entity
    val (previous, worked) = withContext(Dispatchers.IO) {
        val repo = LogbookRepository.get(context)
        repo.previousContacts(call) to repo.workedDxcc()
    }
    return CallsignReport(
        callbook = callbook,
        newCountry = entity != null && entity.dxcc !in worked,
        previous = previous
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallsignReportView(r: CallsignReport, stationPosition: LatLon?, onSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val entity = r.entity
    val cb = r.callbook
    val info = cb.hamQth

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.callsign, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (r.newCountry) {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.spot_new_country), fontWeight = FontWeight.Bold) })
            }
        }
        val subtitle = listOfNotNull(cb.name.takeIf { it.isNotBlank() }, entity?.name ?: info?.country).joinToString(" • ")
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.titleMedium)
    }

    InfoCard(title = stringResource(R.string.lk_station)) {
        if (cb.sources.isEmpty()) {
            Text(stringResource(R.string.lk_not_found), style = MaterialTheme.typography.bodyMedium)
        }
        if (cb.name.isNotBlank()) ReportRow(if (cb.uke?.isClub == true) stringResource(R.string.lk_club) else stringResource(R.string.lk_name), cb.name)
        if (cb.qth.isNotBlank()) ReportRow("QTH", cb.qth)
        cb.uke?.let { uke ->
            ReportRow(stringResource(R.string.lk_uke_licence), uke.category + (uke.powerW?.let { " • $it W" } ?: ""))
            ReportRow(stringResource(R.string.lk_valid_to), uke.validTo)
        }
        cb.hamDb?.let { licence ->
            if (licence.licenceClass.isNotBlank()) ReportRow(stringResource(R.string.lk_licence_class), licence.licenceClass)
            if (licence.expires.isNotBlank()) ReportRow(stringResource(R.string.lk_valid_to), licence.expires)
        }
        if (cb.sources.isNotEmpty()) {
            Text(stringResource(R.string.lk_sources, cb.sources.joinToString(", ")), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        cb.notes.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }

    if (entity != null) {
        InfoCard(title = stringResource(R.string.lk_dxcc_country)) {
            ReportRow(stringResource(R.string.lk_country), entity.name)
            ReportRow(stringResource(R.string.lk_main_prefix), entity.primaryPrefix)
            ReportRow(stringResource(R.string.lk_dxcc_number), entity.dxcc.toString())
            ReportRow(stringResource(R.string.lk_continent), entity.continent)
            ReportRow(stringResource(R.string.lk_zones), "${entity.cqZone} / ${entity.ituZone}")
        }
    }

    // Odległość i kierunek wyłącznie z lokatora stacji
    InfoCard(title = stringResource(R.string.lk_locator_bearing)) {
        val grid = cb.grid
        val target = grid?.position
        when {
            grid == null || target == null -> {
                Text(stringResource(R.string.lk_no_locator),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.lk_ask_locator),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                ReportRow(stringResource(R.string.qso_locator), grid.locator)
                Text(grid.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (stationPosition != null) {
                    val path = GreatCircle.path(stationPosition, target)
                    ReportRow(stringResource(R.string.lk_short_path), String.format(UiLocale, "%.0f° %s • %,.0f km", path.shortPathBearing,
                        GreatCircle.compassPoint(path.shortPathBearing), path.shortPathKm))
                    ReportRow(stringResource(R.string.lk_long_path), String.format(UiLocale, "%.0f° %s • %,.0f km", path.longPathBearing,
                        GreatCircle.compassPoint(path.longPathBearing), path.longPathKm))
                } else {
                    Text(stringResource(R.string.lk_no_my_position),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (entity != null) {
        InfoCard(title = stringResource(R.string.lk_local_time)) {
            val offset = entity.utcOffsetHours
            val localMs = System.currentTimeMillis() + (offset * 3_600_000).toLong()
            val fmt = SimpleDateFormat("HH:mm, EEE", UiLocale).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val offsetText = if (offset == 0.0) "UTC" else {
                val h = abs(offset)
                "UTC" + (if (offset > 0) "+" else "−") +
                    if (h % 1.0 == 0.0) "%.0f".format(Locale.US, h) else "%.1f".format(Locale.US, h)
            }
            ReportRow(offsetText, fmt.format(Date(localMs)))
            Text(stringResource(R.string.lk_tz_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    InfoCard(title = "QSL (HamQTH)") {
        when {
            !cb.hamQthConfigured -> {
                Text(stringResource(R.string.lk_hamqth_hint),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onSettingsClick) { Text(stringResource(R.string.lk_open_settings)) }
            }
            info != null -> {
                if (info.qslVia.isNotBlank()) ReportRow("QSL via", info.qslVia)
                val qsl = listOfNotNull(
                    yesNo(stringResource(R.string.lk_bureau), info.qslBureau), yesNo("Direct", info.qslDirect),
                    yesNo("LoTW", info.lotw), yesNo("eQSL", info.eqsl)
                )
                if (qsl.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        qsl.forEach { (label, yes) ->
                            FilterChip(selected = yes, onClick = {}, label = { Text(label + if (yes) " ✓" else " ✗") })
                        }
                    }
                }
            }
            else -> Text(stringResource(R.string.lk_not_in_hamqth), style = MaterialTheme.typography.bodyMedium)
        }
    }

    InfoCard(title = stringResource(R.string.lk_your_log)) {
        if (r.previous.isEmpty()) {
            Text(stringResource(R.string.lk_no_qso_yet), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(pluralStringResource(R.plurals.lk_qso_count, r.previous.size, r.previous.size),
                style = MaterialTheme.typography.titleSmall)
            val bands = r.previous.map { "${it.band} ${it.mode}" }.distinct()
            Text(stringResource(R.string.lk_bands_modes, bands.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
            r.previous.take(5).forEach { q ->
                Text("${q.datetimeUtc} UTC • ${q.band} ${q.mode} • ${q.rstSent}/${q.rstRcvd}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val base = r.callsign.split('/').maxByOrNull { it.length } ?: r.callsign
        OutlinedButton(onClick = { openUrl(context, "https://www.qrz.com/db/$base") }, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("QRZ.com")
        }
        OutlinedButton(onClick = { openUrl(context, "https://www.hamqth.com/$base") }, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("HamQTH")
        }
    }
}

private fun yesNo(label: String, value: String): Pair<String, Boolean>? = when (value.uppercase()) {
    "Y" -> label to true
    "N" -> label to false
    else -> null
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.common_no_browser), Toast.LENGTH_SHORT).show()
    }
}
