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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardDetails
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardEvent
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardStation
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardProgressCalculator
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardSources
import io.github.sp3wro.hamradiotoolkit.data.awards.RadiodyplomCalendar
import io.github.sp3wro.hamradiotoolkit.data.awards.RepeatLimit
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

private enum class AwardFilter(@StringRes val label: Int) {
    ALL(R.string.common_all),
    RUNNING(R.string.aw_running),
    UPCOMING(R.string.aw_upcoming),
    FOLLOWED(R.string.aw_followed)
}

private const val AWARD_DAY_MS = 86_400_000L
private const val STATIONS_PREVIEW = 24

/** Stan szczegółów jednej akcji na ekranie. */
private sealed class DetailsState {
    object Loading : DetailsState()
    data class Loaded(val details: AwardDetails) : DetailsState()
    data class Failed(val message: String) : DetailsState()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AwardsScreen(callsign: String, loggerViewModel: LoggerViewModel = viewModel()) {
    val context = LocalContext.current
    val res = LocalResources.current
    val prefs = remember { UserPreferencesRepository(context.applicationContext.dataStore) }
    val scope = rememberCoroutineScope()
    val followed by prefs.followedAwards.collectAsState(initial = emptySet())
    val logs by loggerViewModel.logs.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    var filter by remember { mutableStateOf(AwardFilter.ALL) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var events by remember { mutableStateOf<List<AwardEvent>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val details = remember { mutableStateMapOf<String, DetailsState>() }

    fun loadDetails(id: String, force: Boolean = false) {
        if (!force && details[id] is DetailsState.Loading) return
        details[id] = DetailsState.Loading
        scope.launch {
            details[id] = AwardSources.loadDetails(context, id, callsign, force).fold(
                onSuccess = { DetailsState.Loaded(it) },
                onFailure = { DetailsState.Failed(it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    LaunchedEffect(refreshTick) {
        loading = true
        val result = AwardSources.loadEvents(context, forceRefresh = refreshTick > 0)
        events = result.events
        val time = SimpleDateFormat("dd.MM HH:mm", UiLocale)
        status = when {
            result.fetchedAtMs == null -> res.getString(R.string.aw_unavailable, result.error ?: res.getString(R.string.common_no_data))
            result.fromCache -> res.getString(R.string.aw_cached, time.format(Date(result.fetchedAtMs)))
            else -> "radiodyplom.pl: ${time.format(Date(result.fetchedAtMs))}"
        }
        loading = false
        // Zapisane szczegóły obserwowanych akcji – od razu, potem odświeżenie z sieci (gdy starsze niż 12 h)
        val followedNow = prefs.followedAwards.first()
        AwardSources.pruneDetails(context, events.map { it.id }.toSet() + followedNow)
        for (id in followedNow) {
            AwardSources.cachedDetails(context, id)?.let { if (details[id] == null) details[id] = DetailsState.Loaded(it) }
        }
        for (id in followedNow) {
            details[id] = AwardSources.loadDetails(context, id, callsign, forceRefresh = refreshTick > 0).fold(
                onSuccess = { DetailsState.Loaded(it) },
                onFailure = { e -> (details[id] as? DetailsState.Loaded) ?: DetailsState.Failed(e.message ?: res.getString(R.string.aw_error)) }
            )
        }
    }

    val visible = events.filter { e ->
        e.endMs > now && when (filter) {
            AwardFilter.ALL -> true
            AwardFilter.RUNNING -> e.isRunning(now)
            AwardFilter.UPCOMING -> e.startMs > now
            AwardFilter.FOLLOWED -> e.id in followed
        }
    }
    val running = visible.filter { it.isRunning(now) }.sortedBy { it.endMs }
    val upcoming = visible.filter { it.startMs > now }.sortedBy { it.startMs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_awards)) },
                actions = {
                    IconButton(onClick = { refreshTick++ }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
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
                        AwardFilter.values().forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
                        }
                    }
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            val cardFor: @Composable (AwardEvent) -> Unit = { e ->
                AwardCard(
                    event = e,
                    now = now,
                    expanded = expanded == e.id,
                    followed = e.id in followed,
                    state = details[e.id],
                    logs = logs,
                    context = context,
                    onToggle = {
                        expanded = if (expanded == e.id) null else e.id
                        if (expanded == e.id && details[e.id] !is DetailsState.Loaded) loadDetails(e.id)
                    },
                    onFollow = { follow ->
                        scope.launch { prefs.setAwardFollowed(e.id, follow) }
                        if (follow && details[e.id] !is DetailsState.Loaded) loadDetails(e.id)
                    },
                    onRetry = { loadDetails(e.id, force = true) }
                )
            }

            if (running.isNotEmpty()) {
                item(key = "running_header") { AwardSectionHeader(stringResource(R.string.aw_running_header, running.size)) }
                running.forEach { e -> item(key = "r_${e.id}") { cardFor(e) } }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "upcoming_header") { AwardSectionHeader(stringResource(R.string.aw_upcoming_header, upcoming.size)) }
                upcoming.forEach { e -> item(key = "u_${e.id}") { cardFor(e) } }
            }
            if (!loading && visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (filter == AwardFilter.FOLLOWED) stringResource(R.string.aw_none_followed)
                        else stringResource(R.string.aw_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "footer") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.aw_footer),
                            
                            
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { openUrl(context, RadiodyplomCalendar.PAGE_URL) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("radiodyplom.pl")
                    }
                }
            }
        }
    }
}

@Composable
private fun AwardSectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AwardCard(
    event: AwardEvent,
    now: Long,
    expanded: Boolean,
    followed: Boolean,
    state: DetailsState?,
    logs: List<QsoEntry>,
    context: Context,
    onToggle: () -> Unit,
    onFollow: (Boolean) -> Unit,
    onRetry: () -> Unit
) {
    val runningNow = event.isRunning(now)
    val loaded = (state as? DetailsState.Loaded)?.details
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (runningNow) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable(onClick = onToggle)
                )
                IconButton(onClick = { onFollow(!followed) }) {
                    Icon(
                        if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (followed) stringResource(R.string.aw_unfollow) else stringResource(R.string.aw_follow),
                        tint = if (followed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.common_details))
                }
            }
            Text(
                if (runningNow) stringResource(R.string.aw_running_left, durationText(context, event.endMs - now))
else stringResource(R.string.ct_starts_in, durationText(context, event.startMs - now)),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (runningNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            )
            Text(
                loaded?.let { d -> if (d.startMs != null && d.endMs != null) exactRange(d.startMs, d.endMs) else null }
                    ?: dayRange(event.startMs, event.endMs),
                style = MaterialTheme.typography.bodyMedium
            )
            // Krótki postęp obserwowanej akcji widoczny bez rozwijania
            if (!expanded && loaded != null && followed) {
                loaded.myRanking?.let { r ->
                    Text(stringResource(R.string.aw_ranking_short, r.points, r.qsos, r.rank), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (!expanded && event.description.isNotBlank()) {
                Text(event.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                when (state) {
                    null, DetailsState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    is DetailsState.Failed -> {
                        Text(stringResource(R.string.aw_details_failed, state.message), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    }
                    is DetailsState.Loaded -> AwardDetailsSection(event, state.details, logs)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { openUrl(context, event.url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.aw_event_page))
                }
                TextButton(onClick = { addAwardToCalendar(context, event, loaded) }) {
                    Icon(Icons.Filled.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_add_to_calendar))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AwardDetailsSection(event: AwardEvent, d: AwardDetails, logs: List<QsoEntry>) {
    val progress = remember(d, logs) { AwardProgressCalculator.compute(d, logs, event.startMs, event.endMs) }
    var showAllStations by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (d.organizers.isNotBlank()) LabeledText(stringResource(R.string.aw_organizers), d.organizers)
        if (d.repeatRule.isNotBlank()) LabeledText(stringResource(R.string.aw_repeats), d.repeatRule)
        if (d.digiGroup.isNotEmpty()) LabeledText(stringResource(R.string.aw_digi_group), d.digiGroup.joinToString(", "))

        // Ranking serwisu – z logów organizatorów
        Text(stringResource(R.string.aw_your_result), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        val call = d.rankingCallsign
        Text(
            when {
                call.isEmpty() -> stringResource(R.string.aw_set_callsign)
                d.myRanking != null -> stringResource(R.string.aw_ranking_full, call, d.myRanking.points, d.myRanking.qsos, d.myRanking.rank) +
                    (if (d.participants > 0) stringResource(R.string.aw_of_participants, d.participants) else "")
                else -> stringResource(R.string.aw_not_ranked, call) +
                    (if (d.participants > 0) stringResource(R.string.aw_participants, d.participants) else "")
            },
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
        )
        if (d.rankingUpdated.isNotBlank()) {
            Text(stringResource(R.string.aw_ranking_from, d.rankingUpdated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Dyplomy i szacunek z własnego logu
        if (d.awards.isNotEmpty()) {
            Text(stringResource(R.string.aw_awards_estimate), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            progress.rules.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val min = p.rule.minPoints
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.rule.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            if (min != null) "${p.points} / $min ${p.rule.unit}" else stringResource(R.string.aw_points, p.points),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (p.achieved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (p.achieved) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (min != null && min > 0) {
                        LinearProgressIndicator(progress = { (p.points.toFloat() / min).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    val regions = listOfNotNull(
                        p.rule.allowedRegions.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = stringResource(R.string.aw_for)),
                        p.rule.excludedRegions.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = stringResource(R.string.aw_without))
                    )
                    if (regions.isNotEmpty()) {
                        Text(regions.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.rule.bands.isNotEmpty() || p.rule.modes.isNotEmpty()) {
                        Text(
                            listOf(p.rule.bands.joinToString(" "), p.rule.modes.joinToString(" ")).filter { it.isNotBlank() }.joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            val notes = listOfNotNull(
                stringResource(R.string.aw_no_multipliers).takeIf { d.hasTimeMultipliers },
                stringResource(R.string.aw_daily_utc).takeIf { d.repeatLimit == RepeatLimit.BAND_MODE_DAILY }
            )
            notes.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }

        // Stacje akcji – zrobione z logu wyróżnione
        if (d.stations.isNotEmpty()) {
            val worked = progress.workedStations
            Text(
                stringResource(R.string.aw_stations, d.stations.size, worked.size),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
            )
            val sorted = d.stations.sortedWith(compareByDescending<AwardStation> { it.callsign in worked }
                .thenByDescending { it.points }.thenBy { it.callsign })
            val shown = if (showAllStations) sorted else sorted.take(STATIONS_PREVIEW)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.forEach { s ->
                    val done = s.callsign in worked
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "${if (done) "✓ " else ""}${s.callsign} · ${s.points}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (done) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = if (done) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        else AssistChipDefaults.assistChipColors()
                    )
                }
            }
            if (sorted.size > STATIONS_PREVIEW) {
                TextButton(onClick = { showAllStations = !showAllStations }) {
                    Text(if (showAllStations) stringResource(R.string.common_show_less) else stringResource(R.string.aw_show_all, sorted.size))
                }
            }
        }

        if (d.description.isNotBlank()) {
            TextButton(onClick = { showDescription = !showDescription }) {
                Text(if (showDescription) stringResource(R.string.aw_hide_description) else stringResource(R.string.aw_description))
            }
            if (showDescription) Text(d.description, style = MaterialTheme.typography.bodySmall)
        }
        val time = remember { SimpleDateFormat("dd.MM HH:mm", UiLocale) }
        Text(stringResource(R.string.aw_fetched, time.format(Date(d.fetchedAtMs))), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}


private fun dayRange(startMs: Long, endExclusiveMs: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy", UiLocale).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return "${fmt.format(Date(startMs))} – ${fmt.format(Date(endExclusiveMs - 1))}"
}

private fun exactRange(startMs: Long, endMs: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", UiLocale).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return "${fmt.format(Date(startMs))} – ${fmt.format(Date(endMs - 60_000L))} UTC"
}

private fun addAwardToCalendar(context: Context, e: AwardEvent, d: AwardDetails?) {
    val start = d?.startMs ?: e.startMs
    val end = d?.endMs ?: e.endMs
    val description = listOfNotNull(
        e.description.takeIf { it.isNotBlank() },
        e.url,
        context.getString(R.string.common_source_value, "radiodyplom.pl")
    ).joinToString("\n")
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, d?.startMs == null)
        .putExtra(CalendarContract.Events.TITLE, e.name)
        .putExtra(CalendarContract.Events.DESCRIPTION, description)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (ex: Exception) {
        Toast.makeText(context, context.getString(R.string.common_no_calendar_app), Toast.LENGTH_SHORT).show()
    }
}
