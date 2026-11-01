package io.github.sp3wro.hamradiotoolkit.data.callbook

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/** Wpis z bazy RadioID.net (identyfikatory DMR) – imię i miejscowość z całego świata, bez logowania. */
data class RadioIdEntry(val callsign: String, val name: String, val city: String, val state: String, val country: String)

/** Wpis z HamDB.org – urzędowe bazy USA, Kanady, Niemiec, Czech i Australii, z lokatorem z adresu. */
data class HamDbEntry(
    val callsign: String,
    val name: String,
    val qth: String,
    val gridSquare: String,
    val licenceClass: String,
    val expires: String,
    val country: String
)

/** Wpis z QRZCQ.com (wymaga konta). */
data class QrzcqEntry(val callsign: String, val name: String, val qth: String, val gridSquare: String)

private val json = Json { ignoreUnknownKeys = true }

/** Zapytanie GET z limitem czasu wyszukiwarki; wołać poza wątkiem głównym. */
internal fun lookupGet(url: String): String =
    HttpClients.lookup.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body?.string() ?: throw IOException(AppStrings.get(R.string.err_empty_response))
    }

private fun JsonObject.str(key: String): String =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty().trim()

private fun validGrid(value: String): String =
    value.trim().uppercase().takeIf { it.length >= 4 && Maidenhead.isValid(it) }.orEmpty()

object RadioIdClient {
    suspend fun lookup(callsign: String): RadioIdEntry? = withContext(Dispatchers.IO) {
        val url = "https://radioid.net/api/dmr/user/".toHttpUrl().newBuilder()
            .addQueryParameter("callsign", callsign.trim().uppercase())
            .build()
        parse(lookupGet(url.toString()), callsign)
    }

    internal fun parse(text: String, callsign: String): RadioIdEntry? {
        val results = json.parseToJsonElement(text).jsonObject["results"]?.jsonArray ?: return null
        // Jeden znak może mieć kilka identyfikatorów DMR (kilka radiotelefonów) – dane osobowe są te same
        val row = results.map { it.jsonObject }.firstOrNull { it.str("callsign").equals(callsign.trim(), ignoreCase = true) }
            ?: return null
        val name = listOf(row.str("fname"), row.str("surname")).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { row.str("name") }
        return RadioIdEntry(
            callsign = row.str("callsign").uppercase(),
            name = name,
            city = row.str("city"),
            state = row.str("state"),
            country = row.str("country")
        )
    }
}

object HamDbClient {
    /** Kraje DXCC obecne w HamDB: USA z terytoriami, Kanada, Niemcy, Czechy, Australia. */
    val COVERED_DXCC = setOf(291, 6, 110, 202, 285, 9, 103, 166, 105, 1, 230, 503, 150)

    suspend fun lookup(callsign: String): HamDbEntry? = withContext(Dispatchers.IO) {
        val call = callsign.trim().uppercase()
        // Znak trafia do ścieżki URL – tylko litery i cyfry
        if (!call.all { it.isLetterOrDigit() }) return@withContext null
        parse(lookupGet("https://api.hamdb.org/$call/json/HamRadioToolkit"))
    }

    internal fun parse(text: String): HamDbEntry? {
        val root = json.parseToJsonElement(text).jsonObject["hamdb"]?.jsonObject ?: return null
        if (root["messages"]?.jsonObject?.str("status") != "OK") return null
        val c = root["callsign"]?.jsonObject ?: return null
        fun field(key: String) = c.str(key).takeIf { it != "NOT_FOUND" }.orEmpty()
        val call = field("call").uppercase()
        if (call.isEmpty()) return null
        return HamDbEntry(
            callsign = call,
            name = listOf(field("fname"), field("mi"), field("name"), field("suffix")).filter { it.isNotEmpty() }.joinToString(" "),
            qth = listOf(field("addr2"), field("state")).filter { it.isNotEmpty() }.joinToString(", "),
            gridSquare = validGrid(field("grid")),
            licenceClass = field("class"),
            expires = field("expires"),
            country = field("country")
        )
    }
}

class QrzcqException(message: String) : IOException(message)

/**
 * QRZCQ.com (API zgodne z QRZ.com). Klucz sesji ważny 3 dni – trzymamy go i logujemy się ponownie
 * tylko po wygaśnięciu. Po błędnym loginie/haśle albo braku Premium nie próbujemy przez godzinę.
 */
object QrzcqClient {
    private const val URL = "https://ssl.qrzcq.com/xml"
    private const val AGENT = "HamRadioToolkit"
    private const val LOGIN_RETRY_MS = 60L * 60 * 1000

    private val mutex = Mutex()
    private var sessionKey: String? = null
    private var sessionUser: String? = null
    private var loginError: Pair<Long, String>? = null

    suspend fun lookup(user: String, password: String, callsign: String): QrzcqEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (sessionUser != user) {
                sessionKey = null
                loginError = null
            }
            loginError?.let { (time, message) ->
                if (System.currentTimeMillis() - time < LOGIN_RETRY_MS) throw QrzcqException(message)
            }
            val key = sessionKey ?: login(user, password)
            val xml = query(key, callsign)
            val error = tag(xml, "Error")
            when {
                error.isEmpty() -> parse(xml)
                error.startsWith("Not found", ignoreCase = true) -> null
                // Wygasła albo nieważna sesja – jedno ponowne logowanie
                error.contains("session", ignoreCase = true) || error.contains("key", ignoreCase = true) -> {
                    val fresh = login(user, password)
                    val retry = query(fresh, callsign)
                    val retryError = tag(retry, "Error")
                    if (retryError.isEmpty()) parse(retry)
                    else if (retryError.startsWith("Not found", ignoreCase = true)) null
                    else throw QrzcqException(retryError)
                }
                else -> throw QrzcqException(error)
            }
        }
    }

    // Hasło musi iść w adresie: API nie przyjmuje POST (sprawdzone 2026-11-01), a połączenie to HTTPS
    private fun login(user: String, password: String): String {
        val url = URL.toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", password)
            .addQueryParameter("agent", AGENT)
            .build()
        val xml = lookupGet(url.toString())
        val key = tag(xml, "Key")
        if (key.isEmpty()) {
            val message = when (val error = tag(xml, "Error")) {
                "" -> AppStrings.get(R.string.cb_login_failed)
                else -> if (error.contains("premium", ignoreCase = true)) AppStrings.get(R.string.cb_premium_required) else error
            }
            sessionKey = null
            loginError = System.currentTimeMillis() to message
            throw QrzcqException(message)
        }
        sessionKey = key
        sessionUser = user
        loginError = null
        return key
    }

    private fun query(key: String, callsign: String): String {
        val url = URL.toHttpUrl().newBuilder()
            .addQueryParameter("s", key)
            .addQueryParameter("callsign", callsign.trim().uppercase())
            .addQueryParameter("agent", AGENT)
            .build()
        return lookupGet(url.toString())
    }

    internal fun parse(xml: String): QrzcqEntry? {
        val block = Regex("(?s)<Callsign>(.*?)</Callsign>").find(xml)?.groupValues?.get(1) ?: return null
        val call = tag(block, "call").uppercase()
        if (call.isEmpty()) return null
        return QrzcqEntry(
            callsign = call,
            name = tag(block, "name"),
            qth = tag(block, "qth").ifEmpty { tag(block, "city") },
            gridSquare = validGrid(tag(block, "locator").ifEmpty { tag(block, "grid") })
        )
    }

    internal fun tag(xml: String, name: String): String {
        val raw = Regex("(?s)<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)?.trim() ?: return ""
        return raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }
}
