package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.network.AprsClient
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsignsTest {

    @Test
    fun acceptsRealAmateurCallsigns() {
        listOf(
            "SP3WRO", "sp3wro", "SQ9ABC", "W1AW", "K1JT", "DL1ABC", "VK2ABC", "9A1A", "E73A", "4X1AB",
            "3Z0X", "3DA0RU", "SN100PZK", "HF0POL", "VP2EX", "SP3WRO/P", "SP3WRO/M", "DL/SP3WRO", "W1AW/4"
        ).forEach { assertTrue(it, Callsigns.isValidAmateur(it)) }
    }

    @Test
    fun rejectsEmptyPlaceholdersAndText() {
        listOf("", "  ", "NOCALL", "N0CALL", "MYCALL", "JAN", "KOWALSKI", "TEST", "12345", "SP3", "SP3WRO//P", "/SP3WRO", "SP3-WRO")
            .forEach { assertFalse(it, Callsigns.isValidAmateur(it)) }
    }

    @Test
    fun aprsUsesBaseCallOnlyWhenValid() {
        assertEquals("SP3WRO", Callsigns.aprsTransmitCall("SP3WRO/P"))
        assertEquals("SN100PZK", Callsigns.aprsTransmitCall("sn100pzk"))
        assertNull(Callsigns.aprsTransmitCall(""))
        assertNull(Callsigns.aprsTransmitCall("NOCALL"))
        assertNull(Callsigns.aprsTransmitCall("Marek"))
    }

    @Test
    fun aprsClientWithoutCallsignIsReceiveOnly() = runBlocking {
        val listener = AprsClient("", 52.4, 16.9, 50)
        assertFalse(listener.canTransmit)
        // Nawet wywołane bezpośrednio – nic nie wysyła
        assertFalse(listener.sendBeacon("-7", "/>", "test", 52.4, 16.9))
        assertFalse(AprsClient("Marek", 52.4, 16.9, 50).canTransmit)
        assertTrue(AprsClient("SP3WRO", 52.4, 16.9, 50).canTransmit)
    }
}
