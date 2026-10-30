package io.github.sp3wro.hamradiotoolkit.data.dxcc

import io.github.sp3wro.hamradiotoolkit.utils.LatLon

data class DxccEntity(
    val name: String,
    val dxcc: Int,
    val primaryPrefix: String,
    val continent: String,
    val cqZone: Int,
    val ituZone: Int,
    val lat: Double,
    val lon: Double,
    /** Przesunięcie czasu lokalnego względem UTC w godzinach (bez czasu letniego), np. +1.0 dla SP. */
    val utcOffsetHours: Double = 0.0
) {
    val position: LatLon get() = LatLon(lat, lon)
}

/**
 * Baza krajów DXCC w formacie cty.csv (AD1C, country-files.com):
 * prefiks,nazwa,nr DXCC,kontynent,CQ,ITU,szer.,dł.(dodatnia na ZACHÓD),strefa czasowa,prefiksy;
 * Prefiksy mogą mieć nadpisania: (CQ) [ITU] <szer/dł> {kontynent} ~czas~, "=" oznacza pełny znak.
 */
class CtyDatabase private constructor(
    private val byPrefix: Map<String, DxccEntity>,
    private val byExactCall: Map<String, DxccEntity>,
    val entities: List<DxccEntity>
) {
    private val maxPrefixLength = byPrefix.keys.maxOfOrNull { it.length } ?: 0

    fun resolve(callsign: String): DxccEntity? {
        val call = callsign.trim().uppercase()
        if (call.isEmpty()) return null
        byExactCall[call]?.let { return it }

        val parts = call.split('/').filter { it.isNotEmpty() }
        val meaningful = parts.filterNot { it in IGNORED_SUFFIXES }
        if (parts.any { it == "MM" || it == "AM" }) return null // morski / lotniczy mobilny – poza DXCC

        val lookup = when {
            meaningful.isEmpty() -> return null
            meaningful.size == 1 -> {
                byExactCall[meaningful[0]]?.let { return it }
                meaningful[0]
            }
            else -> {
                val (a, b) = meaningful[0] to meaningful[1]
                when {
                    // W1AW/4 -> W4AW: zmiana okręgu wywoławczego
                    b.length == 1 && b[0].isDigit() -> replaceAreaDigit(a, b[0])
                    a.length == 1 && a[0].isDigit() -> replaceAreaDigit(b, a[0])
                    // SP/DL1ABC, DL1ABC/SP, VP2E/W1AW – prefiksem jest krótsza część
                    b.length < a.length -> b
                    else -> a
                }
            }
        }
        return longestPrefixMatch(lookup)
    }

    private fun longestPrefixMatch(text: String): DxccEntity? {
        for (len in minOf(text.length, maxPrefixLength) downTo 1) {
            byPrefix[text.substring(0, len)]?.let { return it }
        }
        return null
    }

    private fun replaceAreaDigit(call: String, digit: Char): String {
        val index = call.indexOfFirst { it.isDigit() }
        return if (index == -1) call + digit else call.substring(0, index) + digit + call.substring(index + 1)
    }

    companion object {
        private val IGNORED_SUFFIXES = setOf("P", "M", "QRP", "QRPP", "A", "B", "LH", "R", "T", "J", "LGT")
        // "]" i "}" muszą być poprzedzone "\" – Java na PC to wybacza, ale silnik ICU w Androidzie nie
        private val OVERRIDE = Regex("""\((\d+)\)|\[(\d+)\]|<([-\d.]+)/([-\d.]+)>|\{(\w+)\}|~([-\d.]+)~""")

        fun parse(csv: String): CtyDatabase {
            val byPrefix = HashMap<String, DxccEntity>()
            val byExact = HashMap<String, DxccEntity>()
            val entities = ArrayList<DxccEntity>()

            for (rawLine in csv.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val fields = line.split(',', limit = 10)
                if (fields.size < 10) continue
                // Wpisy z "*" to dodatkowe obszary listy WAE, nie osobne kraje DXCC
                if (fields[0].startsWith("*")) continue

                val base = DxccEntity(
                    name = fields[1].trim(),
                    dxcc = fields[2].trim().toIntOrNull() ?: continue,
                    primaryPrefix = fields[0].trim(),
                    continent = fields[3].trim(),
                    cqZone = fields[4].trim().toIntOrNull() ?: 0,
                    ituZone = fields[5].trim().toIntOrNull() ?: 0,
                    lat = fields[6].trim().toDoubleOrNull() ?: 0.0,
                    lon = -(fields[7].trim().toDoubleOrNull() ?: 0.0),
                    // W cty.csv strefa ma znak odwrotny (dodatnia na zachód), jak długość geograficzna
                    utcOffsetHours = -(fields[8].trim().toDoubleOrNull() ?: 0.0)
                )
                entities.add(base)

                for (token in fields[9].trimEnd(';').split(' ')) {
                    if (token.isBlank()) continue
                    val entity = applyOverrides(base, token)
                    val key = OVERRIDE.replace(token, "").trim()
                    if (key.startsWith("=")) {
                        byExact[key.substring(1)] = entity
                    } else if (key.isNotEmpty()) {
                        byPrefix[key] = entity
                    }
                }
            }
            return CtyDatabase(byPrefix, byExact, entities)
        }

        private fun applyOverrides(base: DxccEntity, token: String): DxccEntity {
            var entity = base
            for (m in OVERRIDE.findAll(token)) {
                val g = m.groupValues
                entity = when {
                    g[1].isNotEmpty() -> entity.copy(cqZone = g[1].toInt())
                    g[2].isNotEmpty() -> entity.copy(ituZone = g[2].toInt())
                    g[3].isNotEmpty() -> entity.copy(lat = g[3].toDouble(), lon = -g[4].toDouble())
                    g[5].isNotEmpty() -> entity.copy(continent = g[5])
                    g[6].isNotEmpty() -> entity.copy(utcOffsetHours = -g[6].toDouble())
                    else -> entity
                }
            }
            return entity
        }
    }
}
