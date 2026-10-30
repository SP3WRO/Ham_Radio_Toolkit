package io.github.sp3wro.hamradiotoolkit.utils

import java.util.Locale

/**
 * Format dat i liczb w języku interfejsu (np. polski: spacja jako separator tysięcy "7 831 km", nazwy miesięcy).
 * Domyślny Locale ustawia wybór języka ([AppLanguage]) – od Androida 13 robi to system.
 */
val UiLocale: Locale get() = Locale.getDefault()

/** Polska odmiana po liczebniku: 1 stacja, 2–4 stacje, 5+ stacji (ale 22 stacje, 12 stacji). */
fun polishPlural(count: Int, one: String, few: String, many: String): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        count == 1 -> one
        last in 2..4 && lastTwo !in 12..14 -> few
        else -> many
    }
}
