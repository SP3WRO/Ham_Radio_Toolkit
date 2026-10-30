package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import io.github.sp3wro.hamradiotoolkit.utils.polishPlural
import org.junit.Assert.assertEquals
import org.junit.Test

class PolishTest {
    @Test
    fun plural() {
        fun s(n: Int) = "$n " + polishPlural(n, "stacja", "stacje", "stacji")
        assertEquals("1 stacja", s(1))
        assertEquals("3 stacje", s(3))
        assertEquals("5 stacji", s(5))
        assertEquals("12 stacji", s(12))
        assertEquals("22 stacje", s(22))
        assertEquals("0 stacji", s(0))
    }

    @Test
    fun thousandsSeparatorIsNotComma() {
        val text = String.format(UiLocale, "%,.0f km", 7831.0)
        assertEquals("7831", text.filter { it.isDigit() })
        assertEquals(false, text.contains(','))
    }
}
