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
import okhttp3.Request
import java.io.File
import java.io.IOException

data class AwardListResult(val events: List<AwardEvent>, val fetchedAtMs: Long?, val fromCache: Boolean, val error: String?)

/**
 * Akcje dyplomowe z radiodyplom.pl – wyłącznie z public_events.json (najwyżej co 12 godzin)
 * i public_bandmap.json (najwyżej co 5 minut, tylko gdy widać ekran, który z niej korzysta).
 */
object AwardSources {
    private const val TAG = "AwardSources"
    private const val MAX_AGE_MS = 12 * 3_600_000L
    private const val BANDMAP_MAX_AGE_MS = 5 * 60_000L

    /** Co ile ekrany pytają o mapę pasm – chwilę dłużej niż ważność kopii, żeby nie trafiać tuż przed jej wygaśnięciem. */
    const val BANDMAP_POLL_MS = BANDMAP_MAX_AGE_MS + 10_000L

    private val listMutex = Mutex()
    private val bandmapMutex = Mutex()

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "awards").apply { mkdirs() }

    suspend fun loadEvents(context: Context, forceRefresh: Boolean = false): AwardListResult = listMutex.withLock {
        withContext(Dispatchers.IO) {
            removeOldCopies(context)
            val file = File(dir(context), "radiodyplom_events.json")
            val now = System.currentTimeMillis()
            val fresh = file.exists() && now - file.lastModified() < MAX_AGE_MS
            var error: String? = null
            if (!fresh || forceRefresh) {
                try {
                    val text = get(RadiodyplomFeed.EVENTS_URL)
                    val events = RadiodyplomFeed.parseEvents(text)
                    writeAtomically(file, text)
                    return@withContext AwardListResult(events, now, fromCache = false, error = null)
                } catch (e: Exception) {
                    Log.w(TAG, "Nie udało się pobrać listy akcji", e)
                    error = e.message ?: e.javaClass.simpleName
                }
            }
            if (!file.exists()) return@withContext AwardListResult(emptyList(), null, fromCache = false, error = error ?: AppStrings.get(R.string.common_no_data))
            try {
                AwardListResult(RadiodyplomFeed.parseEvents(file.readText()), file.lastModified(), fromCache = error != null, error = error)
            } catch (e: Exception) {
                AwardListResult(emptyList(), null, fromCache = false, error = e.message)
            }
        }
    }

    /** Stacje aktywne w ostatnich 30 minutach; null – nie wiadomo, bo nie ma sieci ani aktualnej kopii. */
    suspend fun loadBandmap(context: Context): List<BandmapSpot>? = bandmapMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(dir(context), "radiodyplom_bandmap.json")
            var failed = false
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() >= BANDMAP_MAX_AGE_MS) {
                try {
                    val text = get(RadiodyplomFeed.BANDMAP_URL)
                    RadiodyplomFeed.parseBandmap(text)
                    writeAtomically(file, text)
                } catch (e: Exception) {
                    Log.w(TAG, "Nie udało się pobrać mapy pasm", e)
                    failed = true
                }
            }
            if (!file.exists()) return@withContext null
            try {
                val age = System.currentTimeMillis() - file.lastModified()
                RadiodyplomFeed.activeSpots(RadiodyplomFeed.parseBandmap(file.readText()), age)
                    .takeUnless { failed && it.isEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Stacje obserwowanych akcji z mapy pasm: znak -> akcje. */
    suspend fun followedStations(context: Context, followed: Set<String>): Map<String, List<AwardStationTag>> =
        RadiodyplomFeed.followedTags(loadBandmap(context).orEmpty(), followed)

    // Pozostałości po eksporcie ICS i czytaniu stron akcji
    private fun removeOldCopies(context: Context) {
        dir(context).listFiles { f -> f.name == "radiodyplom.ics" || (f.name.startsWith("ses_") && f.name.endsWith(".json")) }
            ?.forEach { it.delete() }
    }

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
