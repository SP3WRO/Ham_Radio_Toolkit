package io.github.sp3wro.hamradiotoolkit.data.awards

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException

data class AwardListResult(val events: List<AwardEvent>, val fetchedAtMs: Long?, val fromCache: Boolean, val error: String?)

/** Stacja obserwowanej akcji – do oznaczania spotów w DX Clusterze. */
data class AwardStationTag(val eventName: String, val points: Int)

/**
 * Akcje dyplomowe z radiodyplom.pl. Kalendarz (ICS) pobierany najwyżej co 12 godzin; strony akcji są duże
 * (z rankingiem nawet kilka MB), więc pobieramy je tylko na żądanie – po rozwinięciu akcji albo dla akcji
 * obserwowanych – i trzymamy na dysku już przetworzone (JSON), też do 12 godzin.
 */
object AwardSources {
    private const val TAG = "AwardSources"
    private const val MAX_AGE_MS = 12 * 3_600_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val listMutex = Mutex()
    private val detailsMutex = Mutex()

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "awards").apply { mkdirs() }

    suspend fun loadEvents(context: Context, forceRefresh: Boolean = false): AwardListResult = listMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(dir(context), "radiodyplom.ics")
            val now = System.currentTimeMillis()
            val fresh = file.exists() && now - file.lastModified() < MAX_AGE_MS
            var error: String? = null
            if (!fresh || forceRefresh) {
                try {
                    val text = get(RadiodyplomCalendar.ICS_URL)
                    val events = RadiodyplomCalendar.parse(text)
                    if (events.isEmpty() && !text.contains("BEGIN:VCALENDAR")) throw IOException(AppStrings.get(R.string.err_invalid_calendar))
                    writeAtomically(file, text)
                    return@withContext AwardListResult(events, now, fromCache = false, error = null)
                } catch (e: Exception) {
                    Log.w(TAG, "Nie udało się pobrać kalendarza akcji", e)
                    error = e.message ?: e.javaClass.simpleName
                }
            }
            if (!file.exists()) return@withContext AwardListResult(emptyList(), null, fromCache = false, error = error ?: AppStrings.get(R.string.common_no_data))
            try {
                AwardListResult(RadiodyplomCalendar.parse(file.readText()), file.lastModified(), fromCache = error != null, error = error)
            } catch (e: Exception) {
                AwardListResult(emptyList(), null, fromCache = false, error = e.message)
            }
        }
    }

    /**
     * Szczegóły akcji. Kopia jest aktualna 12 godzin i tylko dla tego samego znaku (ranking jest czytany
     * dla znaku użytkownika). Bez sieci zwraca starszą kopię; błąd – gdy nie ma żadnej.
     */
    suspend fun loadDetails(context: Context, eventId: String, callsign: String, forceRefresh: Boolean = false): Result<AwardDetails> =
        detailsMutex.withLock {
            withContext(Dispatchers.IO) {
                val call = callsign.trim().uppercase().takeUnless { it == "NOCALL" }.orEmpty()
                val cached = cachedDetails(context, eventId)
                val now = System.currentTimeMillis()
                if (!forceRefresh && cached != null && now - cached.fetchedAtMs < MAX_AGE_MS && cached.rankingCallsign == call) {
                    return@withContext Result.success(cached)
                }
                try {
                    val html = get(RadiodyplomCalendar.eventUrl(eventId))
                    val details = RadiodyplomEventPage.parse(html, eventId, call, now)
                    if (details.stations.isEmpty() && details.awards.isEmpty()) throw IOException(AppStrings.get(R.string.err_award_page))
                    writeAtomically(detailsFile(context, eventId), json.encodeToString(AwardDetails.serializer(), details))
                    Result.success(details)
                } catch (e: Exception) {
                    Log.w(TAG, "Nie udało się pobrać akcji $eventId", e)
                    if (cached != null) Result.success(cached) else Result.failure(e)
                }
            }
        }

    fun cachedDetails(context: Context, eventId: String): AwardDetails? = try {
        detailsFile(context, eventId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(AwardDetails.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    /** Stacje trwających akcji obserwowanych (z zapisanych kopii, bez sieci): znak -> akcje. */
    suspend fun followedStations(context: Context, followed: Set<String>): Map<String, List<AwardStationTag>> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val result = HashMap<String, MutableList<AwardStationTag>>()
            for (id in followed) {
                val details = cachedDetails(context, id) ?: continue
                if (details.endMs != null && details.endMs <= now) continue
                for (s in details.stations) {
                    result.getOrPut(s.callsign.uppercase()) { ArrayList() } += AwardStationTag(details.name.ifEmpty { "Akcja $id" }, s.points)
                }
            }
            result
        }

    /** Usuwa kopie szczegółów akcji, których nie ma już w kalendarzu i nie są obserwowane. */
    fun pruneDetails(context: Context, keep: Set<String>) {
        dir(context).listFiles { f -> f.name.startsWith("ses_") && f.name.endsWith(".json") }?.forEach { f ->
            val id = f.name.removePrefix("ses_").removeSuffix(".json")
            if (id !in keep) f.delete()
        }
    }

    private fun detailsFile(context: Context, eventId: String) = File(dir(context), "ses_$eventId.json")

    private fun get(url: String): String =
        HttpClients.default.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException(AppStrings.get(R.string.err_empty_response))
        }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
