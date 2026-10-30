package io.github.sp3wro.hamradiotoolkit.horus

import android.content.Context
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.File

/**
 * Lista numerów ładunków (payload_id_list.txt) i opisy pól dodatkowych (custom_field_list.json)
 * Projektu Horus. Kopia jest w assets (działa bez sieci), świeże pliki pobieramy z GitHuba raz w tygodniu.
 */
object HorusPayloadLists {
    private const val TAG = "HorusPayloadLists"
    private const val BASE_URL = "https://raw.githubusercontent.com/projecthorus/horusdemodlib/master/"
    private const val IDS_FILE = "payload_id_list.txt"
    private const val CUSTOM_FILE = "custom_field_list.json"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val RETRY_INTERVAL_MS = 3600L * 1000

    class Data(val payloadIds: Map<Int, String>, val customFormats: Map<String, HorusCustomFormat>)

    private val mutex = Mutex()
    @Volatile private var data: Data? = null
    private var lastDownloadAttemptMs = 0L

    fun cached(): Data? = data

    suspend fun get(context: Context): Data = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(context.applicationContext.filesDir, "horus").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val idsFile = File(dir, IDS_FILE)
            val customFile = File(dir, CUSTOM_FILE)
            val stale = !idsFile.exists() || now - idsFile.lastModified() > MAX_AGE_MS
            val downloaded = if (stale && now - lastDownloadAttemptMs > RETRY_INTERVAL_MS) {
                lastDownloadAttemptMs = now
                download(IDS_FILE, idsFile) { parsePayloadIds(it).size > 100 } and
                    download(CUSTOM_FILE, customFile) { parseCustomFields(it).isNotEmpty() }
            } else false
            val current = data
            if (current == null || downloaded) {
                val ids = read(context, idsFile, IDS_FILE)?.let(::parsePayloadIds).orEmpty()
                val custom = read(context, customFile, CUSTOM_FILE)?.let { runCatching { parseCustomFields(it) }.getOrNull() }.orEmpty()
                data = Data(ids, custom)
            }
            data!!
        }
    }

    private fun read(context: Context, file: File, asset: String): String? =
        runCatching { if (file.exists()) file.readText() else null }.getOrNull()
            ?: runCatching { context.assets.open("horus/$asset").bufferedReader().use { it.readText() } }
                .onFailure { Log.w(TAG, "Brak $asset w assets", it) }.getOrNull()

    private fun download(name: String, target: File, valid: (String) -> Boolean): Boolean = try {
        HttpClients.default.newCall(Request.Builder().url(BASE_URL + name).build()).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text == null || !runCatching { valid(text) }.getOrDefault(false)) {
                Log.w(TAG, "Nie udało się pobrać $name (HTTP ${response.code})")
                false
            } else {
                val tmp = File(target.parentFile, "$name.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
                true
            }
        }
    } catch (e: Exception) {
        // Listy tylko uzupełniają nazwy – bez sieci zostaje ostatnia kopia
        Log.w(TAG, "Nie udało się pobrać $name", e)
        false
    }

    /** "1234, ZNAK" w każdym wierszu; '#' – komentarz. */
    fun parsePayloadIds(text: String): Map<Int, String> {
        val result = HashMap<Int, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split(',', limit = 2)
            val id = parts[0].trim().toIntOrNull() ?: continue
            val name = parts.getOrNull(1)?.trim().orEmpty()
            if (name.isNotEmpty()) result[id] = name
        }
        return result
    }

    fun parseCustomFields(text: String): Map<String, HorusCustomFormat> {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
        val result = HashMap<String, HorusCustomFormat>()
        for ((name, element) in root) {
            val o = element as? JsonObject ?: continue
            val struct = (o["struct"] as? JsonPrimitive)?.contentOrNull ?: continue
            val fields = (o["fields"] as? JsonArray)?.mapNotNull { f ->
                val pair = f as? JsonArray ?: return@mapNotNull null
                val fieldName = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val type = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: "none"
                fieldName to type
            } ?: continue
            result[name] = HorusCustomFormat(struct, fields)
        }
        return result
    }
}
