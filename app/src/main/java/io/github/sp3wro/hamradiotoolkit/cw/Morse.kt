package io.github.sp3wro.hamradiotoolkit.cw

object Morse {
    val CODES: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '/' to "-..-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '@' to ".--.-.", '\'' to ".----.", '(' to "-.--.",
        ')' to "-.--.-", ':' to "---...", '!' to "-.-.--", '&' to ".-..."
    )

    val DECODE: Map<String, Char> = CODES.entries.associate { (k, v) -> v to k }

    /** Kolejność znaków metody Kocha (jak w LCWO): 41 znaków, lekcja 1 = K i M, każda kolejna dodaje jeden znak. */
    const val KOCH_ORDER = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X"
    const val KOCH_LESSONS = 40

    fun kochCharacters(lesson: Int): String = KOCH_ORDER.take(lesson.coerceIn(1, KOCH_LESSONS) + 1)
}

/**
 * Czasy elementów w ms. Prędkość znaku [charWpm] wg wzorca PARIS (kropka = 1200 / WPM ms);
 * przy [effectiveWpm] < [charWpm] wydłużane są tylko odstępy (Farnsworth, wzór ARRL).
 */
data class CwTiming(val charWpm: Int, val effectiveWpm: Int = charWpm) {
    val ditMs: Double = 1200.0 / charWpm
    val dahMs: Double = 3 * ditMs
    val elementGapMs: Double = ditMs

    private val farnsworthUnitMs: Double =
        if (effectiveWpm >= charWpm) ditMs
        else (60.0 * charWpm - 37.2 * effectiveWpm) / (charWpm * effectiveWpm) * 1000.0 / 19.0

    val charGapMs: Double = 3 * farnsworthUnitMs
    val wordGapMs: Double = 7 * farnsworthUnitMs
}
