package io.github.sp3wro.hamradiotoolkit.data.vademecum

import android.content.Context
import java.util.Locale

data class DxccItem(val prefix: String, val entity: String, val continent: String)

/**
 * Treść ściąg z plików TSV w assets/vademecum: każdy wiersz to jedna pozycja, a kolumny – języki
 * (en, pl, de, cs, sk). Bierzemy kolumnę języka interfejsu, a gdy jej brak – angielską.
 */
class VademecumData private constructor(
    val dxcc: List<DxccItem>,
    val qCodes: List<Pair<String, String>>,
    val slang: List<Pair<String, String>>,
    val spAreas: List<Pair<String, String>>,
    val phonetic: List<Pair<String, String>>
) {
    companion object {
        @Volatile private var cached: Pair<String, VademecumData>? = null

        fun load(context: Context, language: String = Locale.getDefault().language): VademecumData {
            cached?.let { (lang, data) -> if (lang == language) return data }
            val data = VademecumData(
                dxcc = table(context, "dxcc.tsv").map { row -> DxccItem(row.getValue("prefix"), row.text(language), row.getValue("continent")) },
                qCodes = table(context, "qcodes.tsv").map { it.getValue("code") to it.text(language) },
                slang = table(context, "slang.tsv").map { it.getValue("code") to it.text(language) },
                spAreas = table(context, "sp_areas.tsv").map { it.getValue("code") to it.text(language) },
                phonetic = table(context, "phonetic.tsv").map { it.getValue("letter") to it.text(language) }
            )
            cached = language to data
            return data
        }

        private fun Map<String, String>.text(language: String): String =
            this[language]?.takeIf { it.isNotBlank() } ?: getValue("en")

        private fun table(context: Context, name: String): List<Map<String, String>> {
            val lines = context.assets.open("vademecum/$name").bufferedReader(Charsets.UTF_8).use { it.readLines() }
                .filter { it.isNotBlank() }
            val header = lines.first().split('\t')
            return lines.drop(1).map { line -> header.zip(line.split('\t')).toMap() }
        }
    }
}
