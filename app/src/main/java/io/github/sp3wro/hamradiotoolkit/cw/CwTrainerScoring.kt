package io.github.sp3wro.hamradiotoolkit.cw

import java.util.Random

object CwTrainerScoring {
    /** Losowe grupy znaków z bieżącej lekcji Kocha (np. "KMKMR RKMUM"). */
    fun randomGroups(chars: String, groups: Int, groupLength: Int = 5, random: Random = Random()): String =
        (1..groups).joinToString(" ") {
            (1..groupLength).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }

    /** Skuteczność odbioru 0..1 (odległość edycyjna na znakach, spacje znormalizowane). */
    fun accuracy(expected: String, typed: String): Double {
        val a = normalize(expected)
        val b = normalize(typed)
        if (a.isEmpty()) return if (b.isEmpty()) 1.0 else 0.0
        return (1.0 - levenshtein(a, b).toDouble() / a.length).coerceIn(0.0, 1.0)
    }

    private fun normalize(text: String) = text.uppercase().trim().replace(Regex("\\s+"), " ")

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }
}
