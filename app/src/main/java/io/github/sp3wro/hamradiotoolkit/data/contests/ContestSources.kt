package io.github.sp3wro.hamradiotoolkit.data.contests

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Stan jednego źródła po wczytaniu: czas pobrania kopii albo błąd (gdy nie ma też kopii). */
data class SourceStatus(val source: ContestSource, val fetchedAtMs: Long?, val fromCache: Boolean, val error: String? = null)

data class ContestCalendarResult(val entries: List<ContestEntry>, val statuses: List<SourceStatus>)

/**
 * Kalendarz zawodów z trzech źródeł: SP9CXN (zawody krajowe i międzynarodowe), PK-UKF (UKF)
 * i QRZ.RU (ICS, ok. rok do przodu). Każde źródło pobierane najwyżej co 12 godzin i trzymane na dysku;
 * te same zawody z kilku źródeł są łączone w jeden wpis.
 */
object ContestSources {
    private const val TAG = "ContestSources"
    private const val MAX_AGE_MS = 12 * 3_600_000L
    private const val HOUR_MS = 3_600_000L
    private val mutex = Mutex()

    suspend fun load(context: Context, forceRefresh: Boolean = false): ContestCalendarResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(context.applicationContext.filesDir, "contests").apply { mkdirs() }
            // Pozostałość po dawnym kalendarzu WA7BNM
            File(context.applicationContext.filesDir, "wa7bnm").takeIf { it.exists() }?.deleteRecursively()
            val now = System.currentTimeMillis()
            val year = CivilDate.fromMillis(now).year
            val month = CivilDate.fromMillis(now).month
            coroutineScope {
                val sp = async { fetch(File(dir, "sp9cxn.html"), Sp9cxnCalendar.URL, forceRefresh, Sp9cxnCalendar::parse) }
                val pk = async {
                    // Od listopada sprawdzamy też kalendarz na kolejny rok (jeśli już jest opublikowany)
                    val years = if (month >= 11) listOf(year, year + 1) else listOf(year)
                    years.map { y -> fetch(File(dir, "pkukf_$y.html"), PkUkfCalendar.url(y), forceRefresh, PkUkfCalendar::parse) }
                }
                val qrz = async { fetch(File(dir, "qrzru.ics"), QrzRuCalendar.ICS_URL, forceRefresh, QrzRuCalendar::parse) }

                val spResult = sp.await()
                val pkResults = pk.await()
                val qrzResult = qrz.await()
                val pkMain = pkResults.first()
                val pkEntries = pkResults.flatMap { it.entries }
                val statuses = listOf(
                    spResult.status(ContestSource.SP9CXN),
                    pkMain.status(ContestSource.PK_UKF),
                    qrzResult.status(ContestSource.QRZ_RU)
                )
                ContestCalendarResult(merge(listOf(spResult.entries, pkEntries, qrzResult.entries)), statuses)
            }
        }
    }

    private class Fetched(val entries: List<ContestEntry>, val fetchedAtMs: Long?, val fromCache: Boolean, val error: String?) {
        fun status(source: ContestSource) = SourceStatus(source, fetchedAtMs, fromCache, error)
    }

    private fun fetch(file: File, url: String, force: Boolean, parse: (String) -> List<ContestEntry>): Fetched {
        val now = System.currentTimeMillis()
        val fresh = file.exists() && now - file.lastModified() < MAX_AGE_MS
        var error: String? = null
        if (!fresh || force) {
            try {
                val text = get(url)
                val parsed = parse(text)
                if (parsed.isEmpty()) throw IOException(AppStrings.get(R.string.err_no_contests))
                writeAtomically(file, text)
                return Fetched(parsed, now, fromCache = false, error = null)
            } catch (e: Exception) {
                Log.w(TAG, "Nie udało się pobrać $url", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
        if (!file.exists()) return Fetched(emptyList(), null, fromCache = false, error = error ?: AppStrings.get(R.string.common_no_data))
        return try {
            Fetched(parse(file.readText()), file.lastModified(), fromCache = error != null, error = null)
        } catch (e: Exception) {
            Fetched(emptyList(), null, fromCache = false, error = e.message)
        }
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

    // ---------------------------------------------------------------- łączenie źródeł

    // Emisje, pasma, jednostki i numeracja tur – nie odróżniają zawodów
    private val MODE_WORDS = setOf(
        "cw", "ssb", "rtty", "digi", "digital", "ft8", "ft4", "psk", "psk31", "psk63", "mix", "mixed", "phone", "fonia",
        "hf", "vhf", "uhf", "shf", "mhz", "ghz", "khz", "m", "up", "wyżej", "tura", "round",
        "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii",
        "styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień"
    )
    private val STOP_WORDS = setOf("contest", "zawody", "test", "the", "dx", "international", "int", "of", "and", "w", "na")
    // Ta sama nazwa zapisywana różnie w źródłach
    private val ALIASES = mapOf("waedc" to "wae")

    private fun words(name: String): List<String> =
        name.lowercase().split(Regex("[^\\p{L}\\p{N}]+|(?<=\\p{N})(?=\\p{L})|(?<=\\p{L})(?=\\p{N})"))
            .filter { it.isNotEmpty() && !it.matches(Regex("(19|20)\\d\\d")) }
            .map { ALIASES[it] ?: it }
            .filter { it !in MODE_WORDS }

    internal fun tokens(name: String): Set<String> {
        val all = words(name)
        val significant = all.filter { it !in STOP_WORDS }.toSet()
        return significant.ifEmpty { all.toSet() }
    }

    /** Skrót z pierwszych liter słów ("Worked All Germany" -> "wag"), co najmniej 3 litery. */
    private fun initials(name: String): String? =
        words(name).filter { it.first().isLetter() }.joinToString("") { it.take(1) }.takeIf { it.length >= 3 }

    internal fun sameContest(a: ContestEntry, b: ContestEntry, tokensOf: (String) -> Set<String> = ::tokens): Boolean {
        if (Math.abs(a.startMs - b.startMs) > HOUR_MS) return false
        val ta = tokensOf(a.name)
        val tb = tokensOf(b.name)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val common = ta.intersect(tb).size
        if (common.toDouble() / minOf(ta.size, tb.size) >= 0.6) return true
        return initials(a.name)?.let { it in tb } == true || initials(b.name)?.let { it in ta } == true
    }

    /** Listy w kolejności ważności (SP9CXN, PK-UKF, QRZ.RU); duplikaty uzupełniają pierwszy wpis. */
    internal fun merge(lists: List<List<ContestEntry>>): List<ContestEntry> {
        val result = ArrayList<ContestEntry>()
        val cache = HashMap<String, Set<String>>()
        val tokensOf = { name: String -> cache.getOrPut(name) { tokens(name) } }
        for (list in lists) {
            for (entry in list) {
                val index = result.indexOfFirst { it.source != entry.source && sameContest(it, entry, tokensOf) }
                if (index < 0) {
                    result += entry
                } else {
                    val base = result[index]
                    result[index] = base.copy(
                        polish = base.polish || entry.polish,
                        vhf = base.vhf || entry.vhf,
                        modes = base.modes + entry.modes,
                        bands = base.bands.ifEmpty { entry.bands },
                        originalName = base.originalName ?: entry.originalName,
                        rulesUrl = base.rulesUrl ?: entry.rulesUrl,
                        rulesPlUrl = base.rulesPlUrl ?: entry.rulesPlUrl,
                        homepage = base.homepage ?: entry.homepage,
                        organizer = base.organizer.ifEmpty { entry.organizer },
                        logDeadline = base.logDeadline.ifEmpty { entry.logDeadline },
                        notes = (base.notes + entry.notes).distinct(),
                        sources = base.sources + entry.sources
                    )
                }
            }
        }
        return result.sortedBy { it.startMs }
    }

    /**
     * Klucze zawodów cyklicznych (co tydzień, np. MWC, Sunday Contest): ta sama nazwa co najmniej
     * 3 razy z odstępami nie większymi niż 8 dni.
     */
    fun cyclicNames(entries: List<ContestEntry>): Set<String> =
        entries.groupBy { it.name.lowercase() }.filter { (_, group) ->
            if (group.size < 3) return@filter false
            val starts = group.map { it.startMs }.sorted()
            starts.zipWithNext().count { (a, b) -> b - a <= 8 * 24 * HOUR_MS } >= 2
        }.keys
}
