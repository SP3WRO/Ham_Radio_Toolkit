package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestCalendar
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestEntry
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestMode
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestSource
import io.github.sp3wro.hamradiotoolkit.data.contests.ContestSources
import io.github.sp3wro.hamradiotoolkit.data.contests.SourceStatus
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

private enum class ContestFilter(@StringRes val label: Int) {
    ALL(R.string.common_all),
    POLISH(R.string.ct_filter_polish),
    INTERNATIONAL(R.string.ct_filter_international),
    VHF(R.string.ct_vhf),
    CW(R.string.ct_mode_cw),
    SSB(R.string.ct_mode_ssb),
    DIGI(R.string.ct_filter_digi),
    QRP(R.string.ct_qrp),
    LOCAL(R.string.ct_filter_local)
}

// Słowa w (angielskich) nazwach zawodów wskazujące kraj organizatora
private val COUNTRY_CONTEST_KEYWORDS: Map<String, Regex> = mapOf(
    "DE" to "DARC|German|Worked All Germany|\\bDIG\\b|AGCW", "AT" to "Austrian|\\bOE\\b|AOEC", "CH" to "Helvetia",
    "NL" to "Dutch|PACC", "BE" to "\\bUBA\\b|\\bON Contest", "FR" to "\\bREF\\b", "IT" to "\\bARI\\b|Marconi",
    "ES" to "\\bEA\\b|King of Spain", "PT" to "Portugal", "GB" to "RSGB|UK/EI|UKEICC|BERU", "IE" to "IRTS|UK/EI",
    "CZ" to "\\bOK\\b|OK/OM|OK1WC|\\bCRK\\b", "SK" to "OK/OM", "HU" to "Hungarian|\\bHA\\b", "RO" to "\\bYO\\b|Bucharest",
    "BG" to "\\bLZ\\b", "RS" to "\\bYU\\b", "LT" to "Lithuania|\\bLY\\b", "LV" to "Latvia|\\bYL\\b",
    "UA" to "Ukrain|\\bURC\\b|DNIEPER", "RU" to "Russia|Oblast|Krai|Siberia|Moscow|Ural|\\bSRR\\b|\\bRDXC\\b",
    "SE" to "Scandinavian", "NO" to "Scandinavian", "DK" to "Scandinavian", "FI" to "Scandinavian",
    "JP" to "Japan|JARL|JIDX", "AU" to "Oceania", "NZ" to "NZART|Oceania", "ZA" to "SARL",
    "CA" to "\\bRAC\\b|Canad|Ontario|British Columbia|Prairies", "US" to "QSO Party|ARRL|\\bCQ ?WW\\b|\\bNA\\b",
    "AR" to "Argentin", "MX" to "Mexico", "IL" to "Holyland", "ID" to "ORARI|\\bYB\\b"
).mapValues { Regex(it.value, RegexOption.IGNORE_CASE) }

private val QRP = Regex("""\bQRP\b""", RegexOption.IGNORE_CASE)
private const val DAY_MS = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContestCalendarScreen(countryIso: String? = null, onSettingsClick: () -> Unit = {}) {
    val localKeywords = countryIso?.takeIf { it != "PL" }?.let { COUNTRY_CONTEST_KEYWORDS[it] }
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    var filter by remember { mutableStateOf(ContestFilter.ALL) }
    var showWeekly by remember { mutableStateOf(false) }
    val prefs = remember { UserPreferencesRepository(context.applicationContext.dataStore) }
    val scope = rememberCoroutineScope()
    val showRussianRegional by prefs.contestsShowRussianRegional.collectAsState(initial = false)
    var refreshTick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<ContestEntry>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<SourceStatus>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        loading = true
        try {
            // Wymuszone pobranie tylko po naciśnięciu "Odśwież" – przy wejściu na ekran korzystamy z kopii (do 24 h)
            val result = ContestSources.load(context, forceRefresh = refreshTick > 0)
            statuses = result.statuses
            offline = result.entries.isEmpty()
            entries = if (offline) builtinEntries(System.currentTimeMillis()) else result.entries
        } catch (e: Exception) {
            offline = true
            entries = builtinEntries(System.currentTimeMillis())
        } finally {
            loading = false
        }
    }

    val cyclic = remember(entries) { ContestSources.cyclicNames(entries) }
    // Cotygodniowe CWT podajemy z kalendarza wbudowanego – źródła ich nie obejmują
    val weeklyBuiltin = remember(showWeekly, offline, now / 3_600_000L) {
        if (showWeekly && !offline) builtinEntries(now).filter { it.name.contains("CWT") && it.startMs < now + 7 * DAY_MS } else emptyList()
    }
    val visible = (entries + weeklyBuiltin).sortedBy { it.startMs }.distinctBy { it.key }.filter { c ->
        val isCyclic = c.name.lowercase() in cyclic || c.name.contains("CWT")
        c.endMs > now &&
            (showWeekly || !isCyclic) &&
            (showRussianRegional || c.originalName == null || c.sources.size > 1) &&
            when (filter) {
                ContestFilter.ALL -> true
                ContestFilter.POLISH -> c.polish
                ContestFilter.INTERNATIONAL -> !c.polish && c.originalName == null
                ContestFilter.VHF -> c.vhf
                ContestFilter.CW -> ContestMode.CW in c.modes
                ContestFilter.SSB -> ContestMode.SSB in c.modes
                ContestFilter.DIGI -> ContestMode.DIGI in c.modes || ContestMode.RTTY in c.modes
                ContestFilter.QRP -> QRP.containsMatchIn(c.name)
                ContestFilter.LOCAL -> localKeywords?.containsMatchIn(c.name) == true
            }
    }
    val running = visible.filter { it.isRunning(now) }
    val upcoming = visible.filterNot { it.isRunning(now) }
    val monthFormat = remember { SimpleDateFormat("LLLL yyyy", UiLocale).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ct_title)) },
                actions = {
                    IconButton(onClick = { refreshTick++ }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ContestFilter.values().filter { it != ContestFilter.LOCAL || localKeywords != null }.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showWeekly, onCheckedChange = { showWeekly = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ct_show_weekly), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = showRussianRegional, onCheckedChange = { show ->
                            scope.launch { prefs.saveContestsShowRussianRegional(show) }
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ct_show_russian), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(statusText(context, statuses, offline), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (running.isNotEmpty()) {
                item(key = "running_header") { SectionHeader(stringResource(R.string.ct_running_now)) }
                running.forEach { c ->
                    item(key = "r_${c.key}") { ContestCard(c, now, expanded == c.key, context) { expanded = if (expanded == c.key) null else c.key } }
                }
            }

            var lastMonth = ""
            upcoming.forEach { c ->
                val month = monthFormat.format(Date(c.startMs)).replaceFirstChar { it.uppercase() }
                if (month != lastMonth) {
                    lastMonth = month
                    item(key = "m_$month") { SectionHeader(month) }
                }
                item(key = "u_${c.key}") { ContestCard(c, now, expanded == c.key, context) { expanded = if (expanded == c.key) null else c.key } }
            }

            if (!loading && visible.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.ct_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            item(key = "footer") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.ct_footer),
                            
                            
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        stringResource(R.string.ct_calendar_of, "SP9CXN") to ContestSource.SP9CXN.pageUrl,
                        stringResource(R.string.ct_calendar_of, ContestSource.PK_UKF.label) to ContestSource.PK_UKF.pageUrl,
                        stringResource(R.string.ct_calendar_of, "QRZ.RU") to ContestSource.QRZ_RU.pageUrl
                    ).forEach { (label, url) ->
                        TextButton(onClick = { openUrl(context, url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(context: Context, statuses: List<SourceStatus>, offline: Boolean): String {
    if (offline) return context.getString(R.string.ct_offline)
    val time = SimpleDateFormat("dd.MM HH:mm", UiLocale)
    return statuses.joinToString(" • ") { s ->
        when {
            s.fetchedAtMs == null -> context.getString(R.string.ct_unavailable, s.source.label)
            s.fromCache -> context.getString(R.string.ct_copy_from, s.source.label, time.format(Date(s.fetchedAtMs)))
            else -> "${s.source.label}: ${time.format(Date(s.fetchedAtMs))}"
        }
    }
}

private fun builtinEntries(now: Long): List<ContestEntry> =
    ContestCalendar.occurrences(now - 3_600_000L, now + 365 * DAY_MS)
        .filter { o -> !o.contest.id.startsWith("cwt") || o.startMs <= now + 7 * DAY_MS }
        .map { o ->
            ContestEntry(
                name = o.contest.name,
                startMs = o.startMs,
                endMs = o.endMs,
                source = ContestSource.BUILTIN,
                polish = o.contest.polish,
                vhf = ContestMode.VHF in o.contest.modes,
                modes = o.contest.modes,
                bands = io.github.sp3wro.hamradiotoolkit.data.model.ContestText.localize(o.contest.bands),
                exchange = io.github.sp3wro.hamradiotoolkit.data.model.ContestText.localize(o.contest.exchange),
                rulesUrl = o.contest.rulesUrl
            )
        }

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun ContestCard(c: ContestEntry, now: Long, expanded: Boolean, context: Context, onToggle: () -> Unit) {
    val runningNow = c.isRunning(now)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (runningNow) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onToggle)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (c.polish) {
                    Text("SP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                }
                if (c.vhf) {
                    Text(stringResource(R.string.ct_vhf), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(4.dp))
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.common_details))
            }
            c.originalName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (runningNow) stringResource(R.string.ct_running_left, durationText(context, c.endMs - now))
else stringResource(R.string.ct_starts_in, durationText(context, c.startMs - now)),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (runningNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            )
            Text(rangeText(c.startMs, c.endMs, utc = true), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.common_local_time, rangeText(c.startMs, c.endMs, utc = false)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val modes = modesText(c)
            if (modes.isNotBlank() || c.bands.isNotBlank()) {
                Text(listOf(modes, c.bands).filter { it.isNotBlank() }.joinToString("  •  "), style = MaterialTheme.typography.bodySmall)
            }
            c.notes.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                listOf(
                    stringResource(R.string.ct_organizer) to c.organizer,
                    stringResource(R.string.ct_exchange) to c.exchange,
                    stringResource(R.string.ct_log_deadline) to c.logDeadline,
                    stringResource(R.string.common_source) to c.sources.joinToString(", ") { it.label }
                ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value, style = MaterialTheme.typography.bodySmall)
                    }
                }
                c.homepage?.let { url ->
                    TextButton(onClick = { openUrl(context, url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ct_organizer_page))
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    openUrl(context, c.rulesUrl ?: ("https://www.google.com/search?q=" + URLEncoder.encode("${c.name} rules", "UTF-8")))
                }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (c.rulesUrl != null) stringResource(R.string.ct_rules) else stringResource(R.string.ct_search_rules))
                }
                c.rulesPlUrl?.takeIf { it != c.rulesUrl }?.let { url ->
                    TextButton(onClick = { openUrl(context, url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ct_rules_pl))
                    }
                }
                c.sourcePageUrl?.takeIf { it != c.rulesUrl }?.let { url ->
                    TextButton(onClick = { openUrl(context, url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ct_source_page, ContestSource.QRZ_RU.label))
                    }
                }
                TextButton(onClick = { addToCalendar(context, c) }) {
                    Icon(Icons.Filled.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_add_to_calendar))
                }
            }
        }
    }
}

private fun modesText(c: ContestEntry): String =
    listOf(ContestMode.CW, ContestMode.SSB, ContestMode.RTTY, ContestMode.DIGI).filter { it in c.modes }.joinToString(" • ") { it.label }

private fun rangeText(startMs: Long, endMs: Long, utc: Boolean): String {
    val fmt = SimpleDateFormat("EEE dd.MM HH:mm", UiLocale).apply {
        timeZone = if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }
    // Koniec jak w kalendarzach zawodów: 23:59 zamiast 00:00 dnia następnego
    val end = if (endMs % 3_600_000L == 0L) endMs - 60_000L else endMs
    return "${fmt.format(Date(startMs))} – ${fmt.format(Date(end))}" + if (utc) " UTC" else ""
}

internal fun durationText(context: Context, ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> context.resources.getQuantityString(R.plurals.dur_days, days.toInt(), days.toInt()) + " " +
            context.getString(R.string.dur_hours, hours.toInt())
        hours > 0 -> context.getString(R.string.dur_hours_minutes, hours.toInt(), minutes.toInt())
        else -> context.getString(R.string.dur_minutes, minutes.toInt())
    }
}

private fun addToCalendar(context: Context, c: ContestEntry) {
    val description = listOfNotNull(
        c.originalName,
        listOf(modesText(c), c.bands).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() },
        c.exchange.takeIf { it.isNotBlank() },
        c.logDeadline.takeIf { it.isNotBlank() }?.let { context.getString(R.string.ct_logs_by, it) },
        c.rulesUrl,
        c.rulesPlUrl?.takeIf { it != c.rulesUrl }?.let { context.getString(R.string.ct_rules_pl_value, it) },
        c.sourcePageUrl?.takeIf { it != c.rulesUrl },
        c.sources.filter { it != ContestSource.BUILTIN }.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = context.getString(R.string.common_source) + ": ") { it.label }
    ).joinToString("\n")
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.startMs)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.endMs)
        .putExtra(CalendarContract.Events.TITLE, c.name)
        .putExtra(CalendarContract.Events.DESCRIPTION, description)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.common_no_calendar_app), Toast.LENGTH_SHORT).show()
    }
}
