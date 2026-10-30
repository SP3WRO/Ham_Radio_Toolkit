package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class CallsignInfo(
    val callsign: String,
    val name: String,
    val qth: String,
    val gridSquare: String,
    val country: String,
    val dxcc: Int?,
    // Szczegóły do wyszukiwarki znaków (puste, gdy HamQTH ich nie podaje)
    val qslVia: String = "",
    val qslBureau: String = "",  // Y / N / ?
    val qslDirect: String = "",
    val lotw: String = "",
    val eqsl: String = "",
    val web: String = "",
    val cqZone: String = "",
    val ituZone: String = "",
    val continent: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null
)

class HamQthException(message: String) : IOException(message)

// Jedna sesja HamQTH na całą aplikację
val sharedHamQthClient: HamQthClient by lazy { HamQthClient(HttpClients.lookup) }

/**
 * Wyszukiwanie znaków w HamQTH (darmowe konto). Sesja jest ważna godzinę –
 * przy błędzie "Session does not exist or expired" logujemy się ponownie.
 */
class HamQthClient(private val client: OkHttpClient = HttpClients.default) {
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var sessionUser: String? = null

    suspend fun lookup(user: String, password: String, callsign: String): CallsignInfo? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (sessionUser != user) sessionId = null
            val session = sessionId ?: login(user, password)
            val xml = query(session, callsign)
            val error = tag(xml, "error")
            when {
                error.contains("expired", ignoreCase = true) || error.contains("does not exist", ignoreCase = true) -> {
                    val fresh = login(user, password)
                    parseSearch(query(fresh, callsign))
                }
                error.contains("not found", ignoreCase = true) -> null
                error.isNotEmpty() -> throw HamQthException(error)
                else -> parseSearch(xml)
            }
        }
    }

    private fun login(user: String, password: String): String {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("u", user)
            .addQueryParameter("p", password)
            .build()
        val xml = get(url.toString())
        val id = tag(xml, "session_id")
        if (id.isEmpty()) throw HamQthException(tag(xml, "error").ifEmpty { AppStrings.get(R.string.err_hamqth_login) })
        sessionId = id
        sessionUser = user
        return id
    }

    private fun query(session: String, callsign: String): String {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", session)
            .addQueryParameter("callsign", callsign.trim().uppercase())
            .addQueryParameter("prg", "HamRadioToolkit")
            .build()
        return get(url.toString())
    }

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} z HamQTH")
            response.body?.string() ?: throw IOException(AppStrings.get(R.string.err_empty_response_from, "HamQTH"))
        }

    companion object {
        private const val BASE_URL = "https://www.hamqth.com/xml.php"

        internal fun parseSearch(xml: String): CallsignInfo? {
            val search = Regex("(?s)<search>(.*?)</search>").find(xml)?.groupValues?.get(1) ?: return null
            val name = tag(search, "adr_name").ifEmpty { tag(search, "nick") }
            return CallsignInfo(
                callsign = tag(search, "callsign").uppercase(),
                name = name,
                qth = tag(search, "qth").ifEmpty { tag(search, "adr_city") },
                gridSquare = tag(search, "grid").uppercase(),
                country = tag(search, "country"),
                dxcc = tag(search, "adif").toIntOrNull(),
                qslVia = tag(search, "qsl_via"),
                qslBureau = tag(search, "qsl"),
                qslDirect = tag(search, "qsldirect"),
                lotw = tag(search, "lotw"),
                eqsl = tag(search, "eqsl"),
                web = tag(search, "web"),
                cqZone = tag(search, "cq"),
                ituZone = tag(search, "itu"),
                continent = tag(search, "continent"),
                latitude = tag(search, "latitude").toDoubleOrNull(),
                longitude = tag(search, "longitude").toDoubleOrNull()
            )
        }

        internal fun tag(xml: String, name: String): String {
            val raw = Regex("(?s)<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)?.trim() ?: return ""
            return raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&")
        }
    }
}
