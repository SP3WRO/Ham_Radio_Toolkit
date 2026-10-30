package io.github.sp3wro.hamradiotoolkit.utils

class CsvRow(private val columns: Map<String, Int>, private val values: List<String>) {
    operator fun get(column: String): String = columns[column]?.let { values.getOrNull(it) }.orEmpty()
}

// Parser CSV zgodny z RFC 4180: pola w cudzysłowach mogą zawierać przecinki i podwójne "" (escapowany cudzysłów)
object CsvParser {

    fun parseLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString().trim())
        return fields
    }

    /**
     * Nagłówkiem jest pierwsza linia zawierająca kolumnę [headerMarker] – linie przed nią
     * (np. tytuł w pliku SOTA) są pomijane. [rowFilter] działa na surowej linii, przed parsowaniem,
     * żeby przy dużych plikach (cały świat) parsować tylko potrzebne wiersze.
     */
    /** Jak [parseWithHeader], ale strumieniowo – wiersze nie są zbierane w pamięci (pliki z całego świata). */
    fun forEachRow(lines: Sequence<String>, headerMarker: String, onRow: (CsvRow) -> Unit) {
        var columns: Map<String, Int>? = null
        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val currentColumns = columns
            if (currentColumns == null) {
                val fields = parseLine(line)
                if (headerMarker in fields) columns = fields.withIndex().associate { it.value to it.index }
            } else {
                onRow(CsvRow(currentColumns, parseLine(line)))
            }
        }
    }

    fun parseWithHeader(
        text: String,
        headerMarker: String,
        rowFilter: (String) -> Boolean = { true }
    ): List<CsvRow> {
        var columns: Map<String, Int>? = null
        val rows = mutableListOf<CsvRow>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val currentColumns = columns
            if (currentColumns == null) {
                val fields = parseLine(line)
                if (headerMarker in fields) {
                    columns = fields.withIndex().associate { it.value to it.index }
                }
            } else if (rowFilter(line)) {
                rows.add(CsvRow(currentColumns, parseLine(line)))
            }
        }
        return rows
    }
}
