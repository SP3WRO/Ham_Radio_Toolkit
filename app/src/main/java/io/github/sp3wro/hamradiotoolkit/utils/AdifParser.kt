package io.github.sp3wro.hamradiotoolkit.utils

import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.data.model.calculateBandFromFreq
import java.util.Locale

data class AdifImportResult(val entries: List<QsoEntry>, val skipped: Int)

// Import plików .adi z innych loggerów (ADIF 2/3). Długość pola liczona w znakach.
object AdifParser {

    fun parseRecords(text: String): List<Map<String, String>> {
        // Nagłówek jest wtedy, gdy plik nie zaczyna się od '<' – kończy go <EOH>
        val eoh = Regex("<eoh>", RegexOption.IGNORE_CASE).find(text)
        var pos = if (!text.trimStart().startsWith("<") && eoh != null) eoh.range.last + 1 else 0

        val records = mutableListOf<Map<String, String>>()
        var current = HashMap<String, String>()
        while (true) {
            val open = text.indexOf('<', pos)
            if (open == -1) break
            val close = text.indexOf('>', open)
            if (close == -1) break
            val spec = text.substring(open + 1, close)
            val parts = spec.split(':')
            val name = parts[0].trim().uppercase(Locale.US)
            pos = close + 1
            when {
                name == "EOR" -> {
                    if (current.isNotEmpty()) records.add(current)
                    current = HashMap()
                }
                name == "EOH" -> current = HashMap()
                parts.size >= 2 -> {
                    val length = parts[1].trim().toIntOrNull() ?: continue
                    val end = minOf(pos + length, text.length)
                    current[name] = text.substring(pos, end)
                    pos = end
                }
            }
        }
        return records
    }

    fun parse(text: String): AdifImportResult {
        val entries = mutableListOf<QsoEntry>()
        var skipped = 0
        for (fields in parseRecords(text)) {
            val entry = toQso(fields)
            if (entry == null) skipped++ else entries.add(entry)
        }
        return AdifImportResult(entries, skipped)
    }

    private fun toQso(f: Map<String, String>): QsoEntry? {
        val call = f["CALL"]?.trim()?.uppercase(Locale.US).orEmpty()
        val date = f["QSO_DATE"]?.trim().orEmpty()
        val time = f["TIME_ON"]?.trim().orEmpty()
        if (call.isEmpty() || date.length != 8 || time.length < 4) return null

        val datetime = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)} " +
            "${time.substring(0, 2)}:${time.substring(2, 4)}"

        val freqKhz = f["FREQ"]?.trim()?.toDoubleOrNull()?.let { mhz -> formatKhz(mhz * 1000.0) }.orEmpty()
        val band = f["BAND"]?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
            ?: if (freqKhz.isNotEmpty()) calculateBandFromFreq(freqKhz) else return null

        // FT4 zapisane jako MODE=MFSK SUBMODE=FT4 pokazujemy jako FT4
        val mode = f["MODE"]?.trim()?.uppercase(Locale.US).orEmpty()
        val submode = f["SUBMODE"]?.trim()?.uppercase(Locale.US).orEmpty()
        val displayMode = when {
            mode == "SSB" -> "SSB"
            submode.isNotEmpty() -> submode
            else -> mode
        }.ifEmpty { return null }

        val ref = (f["POTA_REF"] ?: f["WWFF_REF"]
            ?: f["SIG_INFO"]?.takeIf { f["SIG"]?.uppercase(Locale.US) in setOf("POTA", "WWFF") })
            ?.trim()?.uppercase(Locale.US).orEmpty()

        return QsoEntry(
            callsign = call,
            datetimeUtc = datetime,
            frequency = freqKhz,
            band = band,
            mode = displayMode,
            rstSent = f["RST_SENT"]?.trim().orEmpty(),
            rstRcvd = f["RST_RCVD"]?.trim().orEmpty(),
            comment = (f["COMMENT"] ?: f["NOTES"])?.trim().orEmpty(),
            name = f["NAME"]?.trim().orEmpty(),
            qth = f["QTH"]?.trim().orEmpty(),
            gridSquare = f["GRIDSQUARE"]?.trim()?.uppercase(Locale.US).orEmpty(),
            dxcc = f["DXCC"]?.trim()?.toIntOrNull(),
            country = f["COUNTRY"]?.trim().orEmpty(),
            huntedRef = ref,
            qrzUploaded = f["QRZCOM_QSO_UPLOAD_STATUS"]?.trim().equals("Y", ignoreCase = true),
            eqslUploaded = f["EQSL_QSL_SENT"]?.trim().equals("Y", ignoreCase = true)
        )
    }

    private fun formatKhz(khz: Double): String {
        val text = String.format(Locale.US, "%.3f", khz)
        return text.trimEnd('0').trimEnd('.')
    }
}
