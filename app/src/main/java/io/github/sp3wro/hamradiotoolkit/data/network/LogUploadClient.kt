package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.utils.AdifGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed class UploadOutcome {
    object Uploaded : UploadOutcome()
    object AlreadyThere : UploadOutcome() // dubel po stronie serwisu – QSO już tam jest
    data class Rejected(val reason: String) : UploadOutcome() // ten rekord odrzucony, kolejne można próbować
    data class Fatal(val reason: String) : UploadOutcome() // np. złe hasło – nie ma sensu wysyłać dalej
}

/** Wysyłka QSO do QRZ Logbook (klucz API logbooka) i eQSL.cc (login/hasło) – po jednym QSO na zapytanie. */
class LogUploadClient(private val client: OkHttpClient = HttpClients.default) {

    // https://www.qrz.com/docs/logbook/QRZLogbookAPI.html – INSERT wymaga subskrypcji QRZ
    suspend fun uploadToQrz(apiKey: String, qso: QsoEntry, stationCallsign: String?): UploadOutcome = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("KEY", apiKey)
            .add("ACTION", "INSERT")
            .add("ADIF", AdifGenerator.record(qso, stationCallsign))
            .build()
        val request = Request.Builder().url("https://logbook.qrz.com/api").post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext UploadOutcome.Fatal(AppStrings.get(R.string.err_http_from, response.code, "QRZ"))
                parseQrzResponse(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            UploadOutcome.Fatal(AppStrings.get(R.string.up_no_connection, "QRZ", e.message.orEmpty()))
        }
    }

    // https://www.eqsl.cc/qslcard/ImportADIF.txt – parametr ADIFData, jedno QSO na zapytanie.
    // POST zamiast GET: login i hasło nie trafiają do adresu URL (a przez to do logów serwerów po drodze)
    suspend fun uploadToEqsl(user: String, password: String, qso: QsoEntry, stationCallsign: String?): UploadOutcome =
        withContext(Dispatchers.IO) {
            val adif = "<ADIF_VER:5>3.1.4 <PROGRAMID:17>Ham Radio Toolkit <EOH> " +
                AdifGenerator.record(qso, stationCallsign)
            val body = FormBody.Builder()
                .add("ADIFData", adif)
                .add("EQSL_USER", user)
                .add("EQSL_PSWD", password)
                .build()
            val request = Request.Builder().url("https://www.eqsl.cc/qslcard/importADIF.cfm").post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext UploadOutcome.Fatal(AppStrings.get(R.string.err_http_from, response.code, "eQSL"))
                    parseEqslResponse(response.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                UploadOutcome.Fatal(AppStrings.get(R.string.up_no_connection, "eQSL", e.message.orEmpty()))
            }
        }

    companion object {
        internal fun parseQrzResponse(body: String): UploadOutcome {
            val fields = body.trim().split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i > 0) it.substring(0, i).uppercase() to it.substring(i + 1) else null
            }.toMap()
            val result = fields["RESULT"]?.uppercase()
            val reason = fields["REASON"].orEmpty()
            return when {
                result == "OK" || result == "REPLACE" -> UploadOutcome.Uploaded
                reason.contains("duplicate", ignoreCase = true) -> UploadOutcome.AlreadyThere
                result == "AUTH" -> UploadOutcome.Fatal(AppStrings.get(R.string.up_qrz_auth))
                reason.contains("invalid api key", ignoreCase = true) || reason.contains("access key", ignoreCase = true) ->
                    UploadOutcome.Fatal("QRZ: $reason")
                result == "FAIL" -> UploadOutcome.Rejected(if (reason.isEmpty()) AppStrings.get(R.string.up_qrz_rejected) else "QRZ: $reason")
                else -> UploadOutcome.Fatal(AppStrings.get(R.string.up_qrz_unexpected))
            }
        }

        internal fun parseEqslResponse(html: String): UploadOutcome {
            val text = html.replace(Regex("<[^>]+>"), "\n")
            val added = Regex("Result:\\s*(\\d+)\\s+out of\\s+(\\d+)\\s+records added", RegexOption.IGNORE_CASE).find(text)
            val error = Regex("Error:\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
            val warning = Regex("Warning:\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
            return when {
                error != null -> UploadOutcome.Fatal("eQSL: $error")
                added != null && added.groupValues[1].toInt() > 0 -> UploadOutcome.Uploaded
                warning != null && warning.contains("Duplicate", ignoreCase = true) -> UploadOutcome.AlreadyThere
                warning != null -> UploadOutcome.Rejected("eQSL: $warning")
                else -> UploadOutcome.Rejected(AppStrings.get(R.string.up_eqsl_rejected))
            }
        }
    }
}
