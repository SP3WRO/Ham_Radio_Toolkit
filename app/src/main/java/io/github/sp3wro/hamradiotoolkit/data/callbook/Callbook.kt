package io.github.sp3wro.hamradiotoolkit.data.callbook

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyRepository
import io.github.sp3wro.hamradiotoolkit.data.dxcc.DxccEntity
import io.github.sp3wro.hamradiotoolkit.data.network.CallsignInfo
import io.github.sp3wro.hamradiotoolkit.data.network.sharedHamQthClient
import io.github.sp3wro.hamradiotoolkit.data.preferences.CredentialsRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.ServiceCredentials
import io.github.sp3wro.hamradiotoolkit.data.repository.LogbookRepository
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Lokator stacji i skąd go mamy. Pozycję stacji bierzemy wyłącznie z lokatora – nigdy z miejscowości ani kraju. */
data class StationGrid(val locator: String, val source: String) {
    val position: LatLon? get() = Maidenhead.toLatLon(locator)
}

data class CallbookResult(
    val callsign: String,
    val entity: DxccEntity?,
    val name: String,
    /** QTH opisowo (miejscowość/adres) – tylko informacyjnie, nie służy do liczenia odległości. */
    val qth: String,
    /** null = nie posiadamy lokatora tej stacji. */
    val grid: StationGrid?,
    val uke: UkeLicence?,
    val hamDb: HamDbEntry?,
    val qrzcq: QrzcqEntry?,
    val radioId: RadioIdEntry?,
    val hamQth: CallsignInfo?,
    val hamQthConfigured: Boolean,
    /** Ostrzeżenia i błędy źródeł do pokazania pod wynikiem. */
    val notes: List<String>
) {
    val sources: List<String>
        get() = listOfNotNull(
            "rejestr UKE".takeIf { uke != null }, "HamDB".takeIf { hamDb != null },
            "QRZCQ".takeIf { qrzcq != null }, "HamQTH".takeIf { hamQth != null },
            "RadioID.net".takeIf { radioId != null }
        )
}

/**
 * Dane o stacji z kilku źródeł naraz. Lokator, od najbardziej aktualnego:
 * PSK Reporter (stacja nadawała go w ciągu doby) → APRS-IS (pozycja z ostatnich 2 h) → Twój log →
 * QRZCQ → HamQTH → HamDB (USA, Kanada, Niemcy, Czechy, Australia – z adresu licencji).
 * Każde źródło ma własny limit czasu; błąd albo brak odpowiedzi jednego nie psuje wyniku pozostałych.
 */
object Callbook {
    private const val TAG = "Callbook"
    private const val CACHE_MS = 10L * 60 * 1000
    /** Najdłużej, ile czekamy na pojedyncze źródło (zapytania HTTP mają dodatkowo własny limit 10 s). */
    private const val SOURCE_TIMEOUT_MS = 12_000L
    // Pełny znak: cyfra w środku, litera na końcu (pomijamy niedokończone wpisy typu "SP3")
    private val COMPLETE_CALL = Regex("^[A-Z0-9]{1,4}[0-9][A-Z0-9]*[A-Z]$")

    private val cache = object : LinkedHashMap<String, Pair<Long, CallbookResult>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, CallbookResult>>?) = size > 50
    }

    /** Czy znak wygląda na kompletny – dopiero wtedy warto pytać serwisy w sieci. */
    fun isCompleteCall(callsign: String): Boolean = COMPLETE_CALL.matches(baseCall(callsign.trim().uppercase()))

    /** Znak bez dodatków /P, /M, SP/… – najdłuższa część zawierająca cyfrę. */
    fun baseCall(call: String): String =
        call.split('/').filter { part -> part.any { it.isDigit() } && part.any { it.isLetter() } }.maxByOrNull { it.length } ?: call

    /**
     * Nigdy nie rzuca wyjątku (poza anulowaniem). [usePskReporter] – tylko przy jawnym wyszukiwaniu
     * (nie przy pisaniu znaku w oknie QSO), bo PSK Reporter pozwala na jedno zapytanie na 5 minut.
     */
    suspend fun lookup(context: Context, callsign: String, usePskReporter: Boolean = false): CallbookResult {
        val call = callsign.trim().uppercase()
        synchronized(cache) {
            cache[call]?.let { (time, result) ->
                // Wynik bez lokatora odświeżamy, gdy teraz możemy dodatkowo zapytać PSK Reporter
                val canImprove = usePskReporter && result.grid == null
                if (System.currentTimeMillis() - time < CACHE_MS && !canImprove) return withFreshAprs(result)
            }
        }
        val result = try {
            fetch(context.applicationContext, call, usePskReporter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Wyszukiwanie $call", e)
            emptyResult(call, CtyRepository.cached()?.resolve(call), listOf(AppStrings.get(R.string.cb_failed, e.message.orEmpty())))
        }
        synchronized(cache) { cache[call] = System.currentTimeMillis() to result }
        return result
    }

    /** Źródło wywołane z limitem czasu; błąd zamienia na null i notatkę zamiast przerywać całe wyszukiwanie. */
    private suspend fun <T> source(name: String, notes: MutableList<String>?, block: suspend () -> T?): T? =
        try {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, name, e)
            notes?.let { synchronized(it) { it += "$name: ${e.message ?: AppStrings.get(R.string.cb_connection_error)}" } }
            null
        }

    private suspend fun fetch(context: Context, call: String, usePskReporter: Boolean): CallbookResult = coroutineScope {
        val cty = CtyRepository.get(context)
        val entity = cty?.resolve(call)
        val base = baseCall(call)
        val homeEntity = cty?.resolve(base)
        // SP/DL1ABC, DL1ABC/SP: stacja pracuje poza krajem pozwolenia – jej domowy lokator nie pasuje
        val abroad = entity != null && homeEntity != null && entity.dxcc != homeEntity.dxcc
        val online = isCompleteCall(call)
        val notes = mutableListOf<String>()
        val credentials = runCatching { CredentialsRepository(context).current() }.getOrDefault(ServiceCredentials())

        val ukeJob = async {
            if (homeEntity?.dxcc != 269) null else source("Rejestr UKE", null) { UkeRegistry.find(context, base) }
        }
        val hamDbJob = async {
            if (!online || homeEntity?.dxcc !in HamDbClient.COVERED_DXCC) null
            else source("HamDB", null) { HamDbClient.lookup(base) }
        }
        val radioIdJob = async {
            if (!online) null else source("RadioID.net", null) { RadioIdClient.lookup(base) }
        }
        val hamQthJob = async {
            if (!online || !credentials.hasHamQth) null
            else source("HamQTH", notes) { sharedHamQthClient.lookup(credentials.hamQthUser, credentials.hamQthPassword, base) }
        }
        val qrzcqJob = async {
            if (!online || !credentials.hasQrzcq) null
            else source("QRZCQ", notes) { QrzcqClient.lookup(credentials.qrzcqUser, credentials.qrzcqPassword, base) }
        }
        val logJob = async {
            source("Log", null) {
                withContext(Dispatchers.IO) {
                    // Tylko dokładnie ten sam znak – lokator z pracy /P nie opisuje stacji domowej i odwrotnie
                    LogbookRepository.get(context).previousContacts(call)
                        .filter { it.callsign.equals(call, ignoreCase = true) && it.gridSquare.length >= 4 && Maidenhead.isValid(it.gridSquare) }
                        .maxByOrNull { it.datetimeUtc }
                }
            }
        }
        val pskJob = async {
            if (online && usePskReporter) source("PSK Reporter", null) { PskReporterClient.lookup(call) } else null
        }

        val uke = ukeJob.await()
        val hamDb = hamDbJob.await()
        val radioId = radioIdJob.await()
        val hamQth = hamQthJob.await()
        val qrzcq = qrzcqJob.await()
        val lastLogged = logJob.await()
        val psk = pskJob.await()
        val aprs = AprsHeardStations.find(call)

        val grid = when {
            psk is PskReporterResult.Found ->
                StationGrid(psk.spot.locator, AppStrings.get(R.string.cb_src_psk, agoSeconds(psk.spot.timeSeconds)))
            aprs != null -> aprsGrid(aprs)
            lastLogged != null ->
                StationGrid(lastLogged.gridSquare.uppercase(), AppStrings.get(R.string.cb_src_log, lastLogged.datetimeUtc))
            !abroad && qrzcq != null && qrzcq.gridSquare.isNotEmpty() ->
                StationGrid(qrzcq.gridSquare, AppStrings.get(R.string.cb_src_operator, "QRZCQ"))
            !abroad && hamQth != null && hamQth.gridSquare.length >= 4 && Maidenhead.isValid(hamQth.gridSquare) ->
                StationGrid(hamQth.gridSquare.uppercase(), AppStrings.get(R.string.cb_src_operator, "HamQTH"))
            !abroad && hamDb != null && hamDb.gridSquare.isNotEmpty() ->
                StationGrid(hamDb.gridSquare, AppStrings.get(R.string.cb_src_hamdb))
            else -> null
        }
        if (grid == null) {
            when (psk) {
                is PskReporterResult.RateLimited ->
                    notes += AppStrings.get(R.string.cb_psk_wait, psk.waitMinutes.toInt())
                is PskReporterResult.Failed -> notes += "PSK Reporter: ${psk.message}"
                else -> Unit
            }
        }
        if (abroad) notes += AppStrings.get(R.string.cb_abroad)
        else if (call != base) notes += AppStrings.get(R.string.cb_suffix, call.substringAfterLast('/'))

        val name = listOf(
            hamQth?.name.orEmpty(), qrzcq?.name.orEmpty(), hamDb?.name.orEmpty(), radioId?.name.orEmpty(), uke?.clubName.orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val qth = listOf(
            hamQth?.qth.orEmpty(), qrzcq?.qth.orEmpty(), hamDb?.qth.orEmpty(),
            uke?.let { listOf(it.stationStreet, it.stationCity).filter { s -> s.isNotBlank() }.joinToString(", ") }.orEmpty(),
            radioId?.let { listOf(it.city, it.state).filter { s -> s.isNotBlank() }.joinToString(", ") }.orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        CallbookResult(
            callsign = call, entity = entity, name = name, qth = qth, grid = grid,
            uke = uke, hamDb = hamDb, qrzcq = qrzcq, radioId = radioId, hamQth = hamQth,
            hamQthConfigured = credentials.hasHamQth, notes = synchronized(notes) { notes.toList() }
        )
    }

    // APRS nadaje stale – pozycja z pamięci może być świeższa niż wynik zapisany kilka minut temu
    private fun withFreshAprs(result: CallbookResult): CallbookResult {
        val aprs = AprsHeardStations.find(result.callsign) ?: return result
        // PSK Reporter ma pierwszeństwo, tak jak w pełnym wyszukiwaniu
        if (result.grid?.source?.startsWith("PSK") == true) return result
        return result.copy(grid = aprsGrid(aprs))
    }

    private fun aprsGrid(heard: AprsHeardStations.Heard) = StationGrid(
        Maidenhead.fromLatLon(heard.position.lat, heard.position.lon, 6),
        "APRS-IS – pozycja ${heard.callsign} ${agoSeconds(heard.timeMs / 1000)}"
    )

    private fun emptyResult(call: String, entity: DxccEntity?, notes: List<String>) = CallbookResult(
        callsign = call, entity = entity, name = "", qth = "", grid = null, uke = null, hamDb = null, qrzcq = null,
        radioId = null, hamQth = null, hamQthConfigured = false, notes = notes
    )

    private fun agoSeconds(epochSeconds: Long): String {
        val minutes = ((System.currentTimeMillis() / 1000 - epochSeconds) / 60).coerceAtLeast(0)
        return when {
            minutes < 1 -> AppStrings.get(R.string.cb_just_now)
            minutes < 60 -> AppStrings.get(R.string.cb_minutes_ago, minutes.toInt())
            else -> AppStrings.get(R.string.cb_hours_ago, (minutes / 60).toInt())
        }
    }
}
