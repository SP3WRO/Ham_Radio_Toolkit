package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.utils.AdifGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdifGeneratorTest {

    private val qso = QsoEntry(
        id = "1",
        callsign = "sp3abc",
        datetimeUtc = "2026-10-20 18:45",
        frequency = "14195",
        band = "20m",
        mode = "ssb",
        rstSent = "59",
        rstRcvd = "57",
        comment = "Łódź, żółw"
    )

    // Każde pole <NAZWA:n>wartość musi mieć długość zgodną z wartością
    private fun assertFieldLengthsMatch(adif: String) {
        val field = Regex("<(\\w+):(\\d+)>")
        for (match in field.findAll(adif)) {
            val length = match.groupValues[2].toInt()
            val valueStart = match.range.last + 1
            val value = adif.substring(valueStart, valueStart + length)
            val next = adif.getOrNull(valueStart + length)
            assertTrue("Pole ${match.groupValues[1]} ma złą długość: '$value'", next == ' ' || next == '\n')
        }
    }

    @Test
    fun header_hasCorrectProgramIdLength() {
        val adif = AdifGenerator.generateAdif(emptyList())
        assertTrue(adif.contains("<PROGRAMID:17>Ham Radio Toolkit"))
        assertTrue(adif.contains("<EOH>"))
        assertFieldLengthsMatch(adif)
    }

    @Test
    fun record_containsAllFieldsWithMatchingLengths() {
        val adif = AdifGenerator.generateAdif(listOf(qso))

        assertTrue(adif.contains("<CALL:6>SP3ABC "))
        assertTrue(adif.contains("<QSO_DATE:8>20261020 "))
        assertTrue(adif.contains("<TIME_ON:4>1845 "))
        assertTrue(adif.contains("<FREQ:7>14.1950 "))
        assertTrue(adif.contains("<BAND:3>20m "))
        assertTrue(adif.contains("<MODE:3>SSB "))
        assertTrue(adif.contains("<COMMENT:10>Lodz, zolw "))
        assertTrue(adif.trimEnd().endsWith("<EOR>"))
        assertFieldLengthsMatch(adif)
    }

    @Test
    fun output_isPureAscii() {
        val adif = AdifGenerator.generateAdif(listOf(qso.copy(comment = "ąćęłńóśźż ĄĆĘŁŃÓŚŹŻ ü\nnowa linia")))
        assertTrue(adif.all { it.code in 32..126 || it == '\n' })
        assertTrue(adif.contains("acelnoszz ACELNOSZZ u nowa linia"))
        assertFieldLengthsMatch(adif)
    }
}
