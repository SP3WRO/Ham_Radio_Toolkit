package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.utils.localizedString
import io.github.sp3wro.hamradiotoolkit.R
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyRepository
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.data.network.LogUploadClient
import io.github.sp3wro.hamradiotoolkit.data.network.UploadOutcome
import io.github.sp3wro.hamradiotoolkit.data.preferences.CredentialsRepository
import io.github.sp3wro.hamradiotoolkit.data.repository.LogbookRepository
import io.github.sp3wro.hamradiotoolkit.data.repository.UploadService
import io.github.sp3wro.hamradiotoolkit.utils.AdifGenerator
import io.github.sp3wro.hamradiotoolkit.utils.AdifParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LoggerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LogbookRepository.get(application)
    private val credentialsRepository = CredentialsRepository(application)
    private val uploadClient = LogUploadClient()

    // Kolejne operacje na dzienniku wykonywane po kolei, w kolejności wywołań
    private val fileMutex = Mutex()

    private val _logs = MutableStateFlow<List<QsoEntry>>(emptyList())
    val logs: StateFlow<List<QsoEntry>> = _logs.asStateFlow()

    // Wyszukiwanie w logu: znak, pasmo, emisja, kraj, lokator, referencja, imię, komentarz, data
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredLogs: StateFlow<List<QsoEntry>> = combine(_logs, _searchQuery) { list, query ->
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) list
        else list.filter { qso ->
            // Każde słowo musi pasować do któregoś pola, np. "SP 40m CW"
            terms.all { term ->
                qso.callsign.contains(term, true) || qso.band.equals(term, true) || qso.mode.equals(term, true) ||
                    qso.country.contains(term, true) || qso.gridSquare.startsWith(term, true) ||
                    qso.huntedRef.contains(term, true) || qso.name.contains(term, true) ||
                    qso.qth.contains(term, true) || qso.comment.contains(term, true) || qso.datetimeUtc.startsWith(term)
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    // Jednorazowy komunikat dla użytkownika (Toast/Snackbar), czyszczony po wyświetleniu
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // Zapamiętywanie ostatnich ustawień dla szybszego logowania (lista jest posortowana od najnowszego QSO)
    val lastFrequency: String get() = _logs.value.firstOrNull()?.frequency ?: "14195"
    val lastMode: String get() = _logs.value.firstOrNull()?.mode ?: "SSB"
    val lastRstSent: String get() = _logs.value.firstOrNull()?.rstSent ?: "59"
    val lastRstRcvd: String get() = _logs.value.firstOrNull()?.rstRcvd ?: "59"

    init {
        loadLogs()
        // Stare wpisy (sprzed bazy DXCC) dostają kraj, gdy tylko baza cty będzie dostępna
        viewModelScope.launch {
            val cty = CtyRepository.get(application) ?: return@launch
            runOnLog { repository.backfillDxcc(cty) }
        }
    }

    fun loadLogs() = launchOnLog {}

    // Pojedyncze zmiany poprawiają listę w pamięci zamiast wczytywać cały dziennik z bazy od nowa
    fun addQso(entry: QsoEntry) = launchEdit({ repository.addQso(entry) }) { list ->
        val withoutOld = list.filter { it.id != entry.id }
        // Lista posortowana od najnowszego QSO – wstawiamy przed pierwszym starszym
        val index = withoutOld.indexOfFirst { it.datetimeUtc <= entry.datetimeUtc }.let { if (it < 0) withoutOld.size else it }
        withoutOld.toMutableList().apply { add(index, entry) }
    }

    fun updateQso(entry: QsoEntry) = launchEdit({ repository.updateQso(entry) }) { list ->
        val old = list.find { it.id == entry.id }
        // Zmiana daty zmienia miejsce na liście – wtedy prościej posortować od nowa
        if (old != null && old.datetimeUtc == entry.datetimeUtc) list.map { if (it.id == entry.id) entry else it }
        else (list.filter { it.id != entry.id } + entry).sortedByDescending { it.datetimeUtc }
    }

    fun deleteQso(id: String) = launchEdit({ repository.deleteQso(id) }) { list -> list.filter { it.id != id } }

    private fun s(id: Int, vararg args: Any): String = getApplication<Application>().localizedString(id, *args)

    fun consumeMessage() {
        _message.value = null
    }

    /** Eksport ADIF do pliku wskazanego przez użytkownika – generowanie i zapis poza wątkiem UI. */
    fun exportAdif(uri: Uri, stationCallsign: String) {
        val entries = _logs.value
        viewModelScope.launch {
            _busyMessage.value = s(R.string.log_busy_export)
            try {
                withContext(Dispatchers.IO) {
                    val text = AdifGenerator.generateAdif(entries, stationCallsign)
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: throw IOException(s(R.string.log_cannot_open_file))
                }
                _message.value = s(R.string.log_exported, entries.size)
            } catch (e: Exception) {
                Log.e(TAG, "Eksport ADIF nie powiódł się", e)
                _message.value = s(R.string.log_file_write_error, e.message.orEmpty())
            } finally {
                _busyMessage.value = null
            }
        }
    }

    /** Import ADIF z pliku – odczyt poza wątkiem UI (duże logi czytały się na nim kilka sekund). */
    fun importAdif(uri: Uri) {
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            } catch (e: Exception) {
                _message.value = s(R.string.log_file_read_error, e.message.orEmpty())
                return@launch
            }
            if (text != null) importAdif(text)
        }
    }

    fun importAdif(text: String) {
        viewModelScope.launch {
            _busyMessage.value = s(R.string.log_busy_import)
            try {
                val parsed = withContext(Dispatchers.Default) { AdifParser.parse(text) }
                val cty = CtyRepository.get(getApplication())
                val withDxcc = parsed.entries.map { qso ->
                    if (qso.dxcc != null || cty == null) qso
                    else cty.resolve(qso.callsign)?.let { qso.copy(dxcc = it.dxcc, country = qso.country.ifEmpty { it.name }) } ?: qso
                }
                var added = 0
                runOnLog { added = repository.importAll(withDxcc) }
                val duplicates = withDxcc.size - added
                _message.value = buildString {
                    append(s(R.string.log_imported, added))
                    if (duplicates > 0) append(s(R.string.log_imported_dupes, duplicates))
                    if (parsed.skipped > 0) append(s(R.string.log_imported_incomplete, parsed.skipped))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import ADIF nie powiódł się", e)
                _message.value = s(R.string.log_import_error, e.message.orEmpty())
            } finally {
                _busyMessage.value = null
            }
        }
    }

    /** Wysyła do QRZ/eQSL wszystkie QSO, których jeszcze tam nie ma (tylko do skonfigurowanych serwisów). */
    fun uploadPending(stationCallsign: String) {
        viewModelScope.launch {
            val credentials = credentialsRepository.current()
            if (!credentials.hasQrz && !credentials.hasEqsl) {
                _message.value = s(R.string.log_upload_no_credentials)
                return@launch
            }
            val summary = mutableListOf<String>()
            try {
                if (credentials.hasQrz) {
                    summary += uploadTo(UploadService.QRZ, "QRZ") { uploadClient.uploadToQrz(credentials.qrzLogbookKey, it, stationCallsign) }
                }
                if (credentials.hasEqsl) {
                    summary += uploadTo(UploadService.EQSL, "eQSL") {
                        uploadClient.uploadToEqsl(credentials.eqslUser, credentials.eqslPassword, it, stationCallsign)
                    }
                }
            } finally {
                _busyMessage.value = null
                loadLogs()
            }
            _message.value = summary.joinToString("\n")
        }
    }

    private suspend fun uploadTo(
        service: UploadService,
        label: String,
        upload: suspend (QsoEntry) -> UploadOutcome
    ): String {
        val pending = _logs.value.filter { if (service == UploadService.QRZ) !it.qrzUploaded else !it.eqslUploaded }
        if (pending.isEmpty()) return s(R.string.log_upload_all_done, label)
        val done = mutableListOf<String>()
        var rejected = 0
        var lastReason: String? = null
        for ((index, qso) in pending.withIndex()) {
            _busyMessage.value = s(R.string.log_upload_progress, label, index + 1, pending.size)
            when (val outcome = upload(qso)) {
                UploadOutcome.Uploaded, UploadOutcome.AlreadyThere -> done += qso.id
                is UploadOutcome.Rejected -> {
                    rejected++
                    lastReason = outcome.reason
                }
                is UploadOutcome.Fatal -> {
                    lastReason = outcome.reason
                    break
                }
            }
        }
        runOnLog { repository.markUploaded(done, service) }
        return buildString {
            append(s(R.string.log_upload_result, label, done.size, pending.size))
            if (rejected > 0) append(s(R.string.log_upload_rejected, rejected))
            if (lastReason != null) append(" ($lastReason)")
        }
    }

    private fun launchEdit(action: () -> Unit, transform: (List<QsoEntry>) -> List<QsoEntry>) {
        viewModelScope.launch {
            fileMutex.withLock {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        action()
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Błąd zapisu dziennika QSO", e)
                        _message.value = s(R.string.log_save_error, e.message.orEmpty())
                        false
                    }
                }
                // Po błędzie wczytujemy stan z bazy, żeby lista nie pokazywała niezapisanej zmiany
                _logs.value = if (ok) transform(_logs.value) else withContext(Dispatchers.IO) { repository.getLogs() }
            }
        }
    }

    private fun launchOnLog(action: () -> Unit) {
        viewModelScope.launch { runOnLog(action) }
    }

    private suspend fun runOnLog(action: () -> Unit) {
        fileMutex.withLock {
            _logs.value = withContext(Dispatchers.IO) {
                try {
                    action()
                } catch (e: Exception) {
                    Log.e(TAG, "Błąd zapisu dziennika QSO", e)
                    _message.value = s(R.string.log_save_error, e.message.orEmpty())
                }
                repository.getLogs()
            }
        }
    }

    private companion object {
        const val TAG = "LoggerViewModel"
    }
}
