package io.github.sp3wro.hamradiotoolkit.data.callbook

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/** Pozwolenie z rejestru UKE. Dla osób fizycznych rejestr podaje tylko miejscowość stacji. */
data class UkeLicence(
    val callsign: String,
    val number: String,
    val validTo: String,
    val category: String,
    val powerW: Int?,
    val stationCity: String,
    /** Ulica i numer stacji – tylko dla klubów. */
    val stationStreet: String = "",
    /** Nazwa klubu (pusta dla osób fizycznych). */
    val clubName: String = "",
    /** Współrzędne stacji klubowej, jeśli UKE je podaje. */
    val position: LatLon? = null
) {
    val isClub: Boolean get() = clubName.isNotEmpty()
}

/**
 * Publiczny rejestr pozwoleń radioamatorskich UKE (amator.uke.gov.pl): eksport CSV osób fizycznych
 * i klubów, pobierany raz w tygodniu i trzymany w pamięci (ok. 20 tys. znaków).
 */
object UkeRegistry {
    private const val TAG = "UkeRegistry"
    private const val BASE = "https://amator.uke.gov.pl/pl"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    // Eksport UKE jest w Windows-1250, nie w UTF-8
    private val CHARSET: Charset = Charset.forName("windows-1250")

    private const val RETRY_AFTER_MS = 10L * 60 * 1000

    private val mutex = Mutex()
    @Volatile private var byCall: Map<String, UkeLicence>? = null
    private var lastFailureMs = 0L

    // Wczytywanie rejestru żyje dłużej niż pojedyncze wyszukiwanie – przerwane wyszukiwanie nie przerywa pobierania
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Pozwolenie dla znaku. Czeka na rejestr najwyżej [waitMs] – przy pierwszym pobieraniu (wolny internet)
     * wynik przyjdzie bez danych UKE, a rejestr będzie gotowy przy kolejnym wyszukiwaniu.
     */
    suspend fun find(context: Context, callsign: String, waitMs: Long = 4_000): UkeLicence? {
        val call = callsign.trim().uppercase()
        byCall?.let { return it[call] }
        val loading = loaderScope.async { registry(context.applicationContext) }
        return withTimeoutOrNull(waitMs) { loading.await() }?.get(call)
    }

    private suspend fun registry(context: Context): Map<String, UkeLicence>? = mutex.withLock {
        byCall?.let { return it }
        // Bez internetu i bez zapisanej kopii nie ponawiamy pobierania przy każdym wpisanym znaku
        if (System.currentTimeMillis() - lastFailureMs < RETRY_AFTER_MS) return null
        withContext(Dispatchers.IO) {
            val dir = File(context.applicationContext.filesDir, "uke").apply { mkdirs() }
            val individuals = File(dir, "individuals.csv")
            val clubs = File(dir, "clubs.csv")
            if (!individuals.exists() || System.currentTimeMillis() - individuals.lastModified() > MAX_AGE_MS) {
                download("$BASE/individuals/export.csv", individuals)
                download("$BASE/clubs/export.csv", clubs)
            }
            val map = HashMap<String, UkeLicence>()
            runCatching {
                if (individuals.exists()) parseIndividuals(individuals.readText(CHARSET)).forEach { map.putLatest(it) }
                if (clubs.exists()) parseClubs(clubs.readText(CHARSET)).forEach { map.putLatest(it) }
            }.onFailure { Log.w(TAG, "Nie udało się wczytać rejestru UKE", it) }
            if (map.isEmpty()) {
                lastFailureMs = System.currentTimeMillis()
                null
            } else {
                map.also { byCall = it }
            }
        }
    }

    // Ten sam znak może mieć kilka pozwoleń (np. po przedłużeniu) – liczy się najdłużej ważne
    private fun HashMap<String, UkeLicence>.putLatest(licence: UkeLicence) {
        val existing = this[licence.callsign]
        if (existing == null || licence.validTo > existing.validTo) this[licence.callsign] = licence
    }

    private fun download(url: String, target: File) {
        try {
            HttpClients.default.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException(AppStrings.get(R.string.err_empty_response))
                // Zabezpieczenie przed zapisaniem strony błędu zamiast CSV
                if (!String(bytes, 0, minOf(bytes.size, 200), CHARSET).startsWith("number;")) throw IOException("Nieoczekiwany format")
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się pobrać $url – używam zapisanej kopii", e)
        }
    }

    // number;valid_to;call_sign;category;transmitter_power;station_location
    internal fun parseIndividuals(csv: String): List<UkeLicence> = rows(csv).mapNotNull { f ->
        if (f.size < 6 || f[2].isBlank()) return@mapNotNull null
        UkeLicence(
            callsign = f[2].trim().uppercase(), number = f[0], validTo = f[1], category = f[3],
            powerW = f[4].toIntOrNull(), stationCity = f[5].trim()
        )
    }

    // number;valid_to;call_sign;category;transmitter_power;applicant_name;…;station_city;station_street;station_house;station_number;lat;lng
    internal fun parseClubs(csv: String): List<UkeLicence> = rows(csv).mapNotNull { f ->
        if (f.size < 19 || f[2].isBlank()) return@mapNotNull null
        val street = listOf(f[16], f[17]).filter { it.isNotBlank() }.joinToString(" ") +
            f[18].takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
        val lat = f.getOrNull(19)?.toDoubleOrNull()
        val lon = f.getOrNull(20)?.toDoubleOrNull()
        UkeLicence(
            callsign = f[2].trim().uppercase(), number = f[0], validTo = f[1], category = f[3],
            powerW = f[4].toIntOrNull(), stationCity = f[15].trim().toTitleCase(),
            stationStreet = street.trim(), clubName = f[10].trim().ifEmpty { f[5].trim() },
            position = if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) LatLon(lat, lon) else null
        )
    }

    /** Wiersze CSV z separatorem ";" (pola mogą być w cudzysłowach), bez nagłówka. */
    private fun rows(csv: String): List<List<String>> = csv.lineSequence().drop(1)
        .filter { it.isNotBlank() }
        .map { splitCsvLine(it.trimEnd('\r')) }
        .toList()

    internal fun splitCsvLine(line: String): List<String> {
        val fields = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ';' && !quoted -> { fields.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    // Kluby mają miejscowości WIELKIMI LITERAMI ("STAROGARD GDAŃSKI") – ładniej i pewniej dla geokodera
    internal fun String.toTitleCase(): String {
        if (this != uppercase()) return this
        val sb = StringBuilder(length)
        var wordStart = true
        for (ch in this) {
            sb.append(if (wordStart) ch.uppercaseChar() else ch.lowercaseChar())
            wordStart = ch == ' ' || ch == '-'
        }
        return sb.toString()
    }
}
