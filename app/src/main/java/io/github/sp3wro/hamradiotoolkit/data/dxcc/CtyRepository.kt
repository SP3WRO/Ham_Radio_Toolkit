package io.github.sp3wro.hamradiotoolkit.data.dxcc

import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

// Wspólna dla całej aplikacji baza DXCC: plik cty.csv pobierany z country-files.com
// i trzymany lokalnie; odświeżany co tydzień, przy braku sieci używana jest ostatnia kopia
object CtyRepository {
    private const val TAG = "CtyRepository"
    private const val URL = "https://www.country-files.com/cty/cty.csv"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private const val RETRY_INTERVAL_MS = 3600L * 1000
    private const val BUNDLED_ASSET = "cty.csv"

    private val mutex = Mutex()
    @Volatile private var database: CtyDatabase? = null
    private var lastDownloadAttemptMs = 0L

    /** Baza już wczytana do pamięci (bez czekania) – null, jeśli jeszcze jej nie ma. */
    fun cached(): CtyDatabase? = database

    suspend fun get(context: Context): CtyDatabase? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(context.applicationContext.filesDir, "cty.csv")
            val now = System.currentTimeMillis()
            val stale = !file.exists() || now - file.lastModified() > MAX_AGE_MS
            // Bez sieci nie próbujemy pobierać przy każdym wywołaniu – najwyżej raz na godzinę
            val downloaded = if (stale && now - lastDownloadAttemptMs > RETRY_INTERVAL_MS) {
                lastDownloadAttemptMs = now
                download(file)
            } else false
            if (database == null || downloaded) {
                database = runCatching {
                    // Bez pobranej kopii (pierwsze uruchomienie bez sieci) – baza dołączona do aplikacji.
                    // Nie kopiujemy jej do filesDir, żeby świeża data pliku nie wstrzymała pobrania aktualnej
                    val text = if (file.exists()) file.readText()
                    else context.applicationContext.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
                    CtyDatabase.parse(text)
                }
                    .onFailure { Log.w(TAG, "Nie udało się wczytać cty.csv", it) }
                    .getOrNull()
            }
            database
        }
    }

    private fun download(target: File): Boolean {
        try {
            HttpClients.default.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} przy pobieraniu cty.csv")
                    return false
                }
                val text = response.body?.string() ?: return false
                if (CtyDatabase.parse(text).entities.size < 300) return false
                // Zapis przez plik tymczasowy, żeby przerwane pobieranie nie zostawiło uszkodzonej bazy
                val tmp = File(target.parentFile, "cty.csv.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
                return true
            }
        } catch (e: Throwable) {
            // Baza DXCC tylko wzbogaca ekrany – jej błąd (sieć, uszkodzony plik) nie może zamknąć aplikacji
            Log.w(TAG, "Nie udało się pobrać cty.csv – używam zapisanej kopii", e)
            return false
        }
    }
}
