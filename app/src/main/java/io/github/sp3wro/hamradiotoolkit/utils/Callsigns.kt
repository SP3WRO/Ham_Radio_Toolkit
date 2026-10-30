package io.github.sp3wro.hamradiotoolkit.utils

object Callsigns {
    /** Znak bez dopisków portable/mobile: SP3ABC/P -> SP3ABC, DL/SP3ABC -> SP3ABC. */
    fun baseCall(callsign: String): String {
        val parts = callsign.trim().uppercase().split('/').filter { it.isNotEmpty() }
        return parts.maxByOrNull { it.length }.orEmpty()
    }

    // Prefiks (W, SP, 9A, E7, 3DA) + cyfra(y) okręgu + sufiks z liter: SP3ABC, W1AW, 3Z0X, SN100PZK, 3DA0RU
    private val AMATEUR = Regex("^(?:[A-Z]{1,2}|[A-Z][0-9]|[0-9][A-Z]{1,2})[0-9]{1,4}[A-Z]{1,4}$")
    private val EXTRA_PART = Regex("^[A-Z0-9]{1,4}$")
    // Znaki-wypełniacze z programów i przykładów – nie należą do nikogo
    private val PLACEHOLDERS = setOf("N0CALL", "N0CAL", "MYCALL")

    /**
     * Czy to wygląda na prawdziwy znak krótkofalarski (z ewentualnym /P, /M, DL/…).
     * Puste pole, "NOCALL" albo dowolny tekst (np. imię) – nie.
     */
    fun isValidAmateur(callsign: String): Boolean {
        val call = callsign.trim().uppercase()
        if (call.isEmpty() || call.length > 16) return false
        val parts = call.split('/')
        if (parts.any { it.isEmpty() }) return false
        val baseIndex = parts.indices.maxByOrNull { parts[it].length } ?: return false
        val base = parts[baseIndex]
        if (base in PLACEHOLDERS || !AMATEUR.matches(base)) return false
        return parts.filterIndexed { i, _ -> i != baseIndex }.all { EXTRA_PART.matches(it) }
    }

    /**
     * Znak, którym wolno nadawać do APRS-IS, albo null – wtedy aplikacja tylko słucha.
     * APRS używa znaku bez /P (SSID dodaje się osobno); APRS-IS przyjmuje najwyżej 9 znaków.
     */
    fun aprsTransmitCall(callsign: String): String? {
        if (!isValidAmateur(callsign)) return null
        return baseCall(callsign).takeIf { it.length <= 9 }
    }
}
