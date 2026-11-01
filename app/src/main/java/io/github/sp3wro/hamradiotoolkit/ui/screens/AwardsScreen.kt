package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.EventAvailable
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
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardEvent
import io.github.sp3wro.hamradiotoolkit.data.awards.AwardSources
import io.github.sp3wro.hamradiotoolkit.data.awards.BandmapSpot
import io.github.sp3wro.hamradiotoolkit.data.awards.RadiodyplomFeed
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class AwardFilter(@StringRes val label: Int) {
    ALL(R.string.common_all),
    RUNNING(R.string.aw_running),
    UPCOMING(R.string.aw_upcoming),
    FOLLOWED(R.string.aw_followed)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AwardsScreen() {
    val context = LocalContext.current
    val res = LocalResources.current
    val prefs = remember { UserPreferencesRepository(context.applicationContext.dataStore) }
    val scope = rememberCoroutineScope()
    val followed by prefs.followedAwards.collectAsState(initial = emptySet())

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
    // null – nie udało się ustalić (brak sieci), pusta lista – nikogo nie ma na pasmach
    var spots by remember { mutableStateOf<List<BandmapSpot>?>(null) }

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
    }
    LaunchedEffect(Unit) {
        while (true) {
            spots = AwardSources.loadBandmap(context)
            delay(AwardSources.BANDMAP_POLL_MS)
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
    val visibleSpots = spots?.filter { spot ->
        when (filter) {
            AwardFilter.ALL, AwardFilter.RUNNING -> true
            AwardFilter.UPCOMING -> false
            AwardFilter.FOLLOWED -> spot.events.any { it.id in followed }
        }
    }

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

            if (filter != AwardFilter.UPCOMING && visibleSpots != null) {
                item(key = "on_air_header") { AwardSectionHeader(stringResource(R.string.aw_on_air_header, visibleSpots.size)) }
                if (visibleSpots.isEmpty()) {
                    item(key = "on_air_none") {
                        Text(stringResource(R.string.aw_on_air_none), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                visibleSpots.forEach { s -> item(key = "s_${s.callsign}_${s.band}_${s.mode}") { BandmapSpotCard(s) } }
            }

            val cardFor: @Composable (AwardEvent) -> Unit = { e ->
                AwardCard(
                    event = e,
                    now = now,
                    followed = e.id in followed,
                    context = context,
                    onFollow = { follow -> scope.launch { prefs.setAwardFollowed(e.id, follow) } }
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
                    TextButton(onClick = { openUrl(context, RadiodyplomFeed.PAGE_URL) }) {
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

@Composable
private fun BandmapSpotCard(spot: BandmapSpot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spot.callsign, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                spot.frequencyKhz?.let { khz ->
                    Text(String.format(Locale.US, "%.3f MHz", khz / 1000.0), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                listOf(spot.band, spot.mode, spot.timeUtc.takeIf { it.isNotEmpty() }?.let { "$it UTC" }.orEmpty())
                    .filter { it.isNotEmpty() }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            spot.events.forEach { e ->
                Text("${e.title} ${e.pointsInfo}".trim(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AwardCard(
    event: AwardEvent,
    now: Long,
    followed: Boolean,
    context: Context,
    onFollow: (Boolean) -> Unit
) {
    val runningNow = event.isRunning(now)
    val polish = UiLocale.language == "pl"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (runningNow) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.title(polish), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onFollow(!followed) }) {
                    Icon(
                        if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (followed) stringResource(R.string.aw_unfollow) else stringResource(R.string.aw_follow),
                        tint = if (followed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                if (runningNow) stringResource(R.string.aw_running_left, durationText(context, event.endMs - now))
                else stringResource(R.string.ct_starts_in, durationText(context, event.startMs - now)),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (runningNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            )
            Text(exactRange(event.startMs, event.endMs), style = MaterialTheme.typography.bodyMedium)
            val organizers = event.organizersText(polish)
            if (organizers.isNotBlank()) {
                Text(stringResource(R.string.aw_organizers_value, organizers), style = MaterialTheme.typography.bodySmall)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { openUrl(context, event.url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.aw_event_page))
                }
                TextButton(onClick = { addAwardToCalendar(context, event, polish) }) {
                    Icon(Icons.Filled.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_add_to_calendar))
                }
            }
        }
    }
}

private fun exactRange(startMs: Long, endMs: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", UiLocale).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return "${fmt.format(Date(startMs))} – ${fmt.format(Date(endMs - 60_000L))} UTC"
}

private fun addAwardToCalendar(context: Context, e: AwardEvent, polish: Boolean) {
    val description = listOfNotNull(
        e.organizersText(polish).takeIf { it.isNotBlank() },
        e.url,
        context.getString(R.string.common_source_value, "radiodyplom.pl")
    ).joinToString("\n")
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startMs)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMs)
        .putExtra(CalendarContract.Events.TITLE, e.title(polish))
        .putExtra(CalendarContract.Events.DESCRIPTION, description)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (ex: Exception) {
        Toast.makeText(context, context.getString(R.string.common_no_calendar_app), Toast.LENGTH_SHORT).show()
    }
}
