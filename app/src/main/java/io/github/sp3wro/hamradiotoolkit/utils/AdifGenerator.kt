package io.github.sp3wro.hamradiotoolkit.utils

import io.github.sp3wro.hamradiotoolkit.BuildConfig
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import java.text.Normalizer

object AdifGenerator {
    private const val PROGRAM_ID = "Ham Radio Toolkit"
    private const val PROGRAM_VERSION = BuildConfig.VERSION_NAME
    private const val ADIF_VERSION = "3.1.4" // POTA_REF / WWFF_REF

    // Emisje, które w ADIF są podtrybami innej emisji
    private val SUBMODES = mapOf(
        "FT4" to "MFSK",
        "JS8" to "MFSK",
        "Q65" to "MFSK",
        "FST4" to "MFSK",
        "PSK31" to "PSK",
        "PSK63" to "PSK",
        "PSK125" to "PSK",
        "USB" to "SSB",
        "LSB" to "SSB"
    )

    private val POTA_REF = Regex("^[A-Z0-9]{1,4}-\\d{4,5}$")
    private val WWFF_REF = Regex("^[A-Z0-9]{1,4}FF-\\d{4}$")

    fun generateAdif(entries: List<QsoEntry>, stationCallsign: String? = null): String {
        val sb = StringBuilder()
        sb.append(header()).append('\n')
        for (qso in entries) {
            sb.append(record(qso, stationCallsign)).append('\n')
        }
        return sb.toString()
    }

    fun header(): String {
        val sb = StringBuilder()
        sb.append("Ham Radio Toolkit ADIF Export\n")
        sb.appendField("ADIF_VER", ADIF_VERSION).append('\n')
        sb.appendField("PROGRAMID", PROGRAM_ID).append('\n')
        sb.appendField("PROGRAMVERSION", PROGRAM_VERSION).append('\n')
        sb.append("<EOH>\n")
        return sb.toString()
    }

    /** Jeden rekord zakończony <EOR> (bez nagłówka) – także do wysyłki pojedynczego QSO. */
    fun record(qso: QsoEntry, stationCallsign: String? = null): String {
        val sb = StringBuilder()
        val parts = qso.datetimeUtc.split(" ")
        val datePart = parts.getOrNull(0)?.replace("-", "") ?: ""
        val timePart = parts.getOrNull(1)?.replace(":", "") ?: ""

        sb.appendField("CALL", qso.callsign.uppercase())
        if (datePart.length == 8) sb.appendField("QSO_DATE", datePart)
        if (timePart.length >= 4) sb.appendField("TIME_ON", timePart.take(4))

        // Frequency in MHz for ADIF if available
        val khz = qso.frequency.replace(',', '.').toDoubleOrNull()
        if (khz != null && khz > 0) {
            sb.appendField("FREQ", String.format(java.util.Locale.US, "%.4f", khz / 1000.0))
        }

        sb.appendField("BAND", qso.band.lowercase())
        val mode = qso.mode.uppercase()
        val parentMode = SUBMODES[mode]
        if (parentMode != null) {
            sb.appendField("MODE", parentMode)
            sb.appendField("SUBMODE", mode)
        } else {
            sb.appendField("MODE", mode)
        }
        if (qso.rstSent.isNotEmpty()) sb.appendField("RST_SENT", qso.rstSent)
        if (qso.rstRcvd.isNotEmpty()) sb.appendField("RST_RCVD", qso.rstRcvd)
        if (qso.name.isNotEmpty()) sb.appendField("NAME", qso.name)
        if (qso.qth.isNotEmpty()) sb.appendField("QTH", qso.qth)
        if (qso.gridSquare.isNotEmpty()) sb.appendField("GRIDSQUARE", qso.gridSquare.uppercase())
        qso.dxcc?.let { sb.appendField("DXCC", it.toString()) }
        if (qso.country.isNotEmpty()) sb.appendField("COUNTRY", qso.country)
        val ref = qso.huntedRef.uppercase()
        when {
            WWFF_REF.matches(ref) -> {
                sb.appendField("WWFF_REF", ref)
                sb.appendField("SIG", "WWFF")
                sb.appendField("SIG_INFO", ref)
            }
            POTA_REF.matches(ref) -> {
                sb.appendField("POTA_REF", ref)
                sb.appendField("SIG", "POTA")
                sb.appendField("SIG_INFO", ref)
            }
        }
        if (qso.comment.isNotEmpty()) sb.appendField("COMMENT", qso.comment)
        if (!stationCallsign.isNullOrBlank() && stationCallsign != "NOCALL") {
            sb.appendField("STATION_CALLSIGN", stationCallsign.uppercase())
        }
        sb.append("<EOR>")
        return sb.toString()
    }

    // Długość pola liczona jest z wartości po konwersji, więc nie może się rozjechać z treścią
    private fun StringBuilder.appendField(name: String, value: String): StringBuilder {
        val ascii = toAscii(value)
        return append('<').append(name).append(':').append(ascii.length).append('>').append(ascii).append(' ')
    }

    // Plik .adi dopuszcza tylko ASCII (pola *_INTL istnieją wyłącznie w formacie ADX),
    // dlatego polskie znaki zamieniamy na ich odpowiedniki bez ogonków
    internal fun toAscii(value: String): String {
        val withoutL = value.replace('ł', 'l').replace('Ł', 'L')
        val decomposed = Normalizer.normalize(withoutL, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .map { if (it.code in 32..126) it else ' ' }
            .joinToString("")
    }
}
