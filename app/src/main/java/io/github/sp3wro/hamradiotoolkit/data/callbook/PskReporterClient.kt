package io.github.sp3wro.hamradiotoolkit.data.callbook

import android.util.Log
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Lokator, który stacja sama nadawała w emisjach cyfrowych (FT8, FT4, JS8…) – z raportów odbioru PSK Reporter. */
data class PskReporterSpot(val locator: String, val timeSeconds: Long)

sealed class PskReporterResult {
    data class Found(val spot: PskReporterSpot) : PskReporterResult()
    object NotHeard : PskReporterResult()
    /** Pominięte, żeby nie przekroczyć limitu serwisu – [waitMinutes] do następnej możliwości. */
    data class RateLimited(val waitMinutes: Int) : PskReporterResult()
    data class Failed(val message: String) : PskReporterResult()
}

/**
 * PSK Reporter (retrieve.pskreporter.info). Autor serwisu prosi o zapytania nie częściej niż raz na 5 minut,
 * dlatego pytamy tylko przy jawnym wyszukiwaniu znaku, wynik trzymamy godzinę, a kolejne zapytanie
 * w ciągu 5 minut jest pomijane.
 */
object PskReporterClient {
    private const val TAG = "PskReporter"
    private const val URL = "https://retrieve.pskreporter.info/query"
    private const val MIN_INTERVAL_MS = 5L * 60 * 1000
    private const val CACHE_MS = 60L * 60 * 1000

    private val mutex = Mutex()
    private var lastQueryMs = 0L
    private val cache = HashMap<String, Pair<Long, PskReporterSpot?>>()

    suspend fun lookup(callsign: String): PskReporterResult = mutex.withLock {
        val call = callsign.trim().uppercase()
        val now = System.currentTimeMillis()
        cache[call]?.let { (time, spot) ->
            if (now - time < CACHE_MS) return@withLock spot?.let { PskReporterResult.Found(it) } ?: PskReporterResult.NotHeard
        }
        val wait = MIN_INTERVAL_MS - (now - lastQueryMs)
        if (wait > 0) return@withLock PskReporterResult.RateLimited(((wait + 59_999) / 60_000).toInt())
        lastQueryMs = now
        try {
            val spot = withContext(Dispatchers.IO) { query(call) }
            cache[call] = now to spot
            spot?.let { PskReporterResult.Found(it) } ?: PskReporterResult.NotHeard
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Zapytanie nieudane", e)
            PskReporterResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun query(call: String): PskReporterSpot? {
        val url = URL.toHttpUrl().newBuilder()
            .addQueryParameter("senderCallsign", call)
            .addQueryParameter("flowStartSeconds", "-86400")
            .addQueryParameter("rronly", "1")
            .addQueryParameter("noactive", "1")
            .addQueryParameter("rptlimit", "50")
            .build()
        return parse(lookupGet(url.toString()), call)
    }

    private val REPORT = Regex("""<receptionReport\b([^>]*)/?>""")
    private val ATTRIBUTE = Regex("""(\w+)="([^"]*)"""")

    /**
     * Najnowszy lokator nadawany przez [call]. Gdy najnowszy raport ma lokator 4-znakowy, a inny raport
     * podaje 6-znakowy w tym samym kwadracie – bierzemy dokładniejszy.
     */
    internal fun parse(xml: String, call: String): PskReporterSpot? {
        val spots = REPORT.findAll(xml).mapNotNull { m ->
            val attrs = ATTRIBUTE.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val sender = attrs["senderCallsign"].orEmpty()
            val locator = attrs["senderLocator"].orEmpty().trim().uppercase()
            if (!sender.equals(call, ignoreCase = true) || locator.length < 4 || !Maidenhead.isValid(locator)) return@mapNotNull null
            PskReporterSpot(locator, attrs["flowStartSeconds"]?.toLongOrNull() ?: 0L)
        }.toList()
        val latest = spots.maxByOrNull { it.timeSeconds } ?: return null
        val finer = spots.filter { it.locator.length > latest.locator.length && it.locator.startsWith(latest.locator.take(4)) }
            .maxByOrNull { it.timeSeconds }
        return finer?.copy(timeSeconds = latest.timeSeconds) ?: latest
    }
}
