package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.model.Repeater
import io.github.sp3wro.hamradiotoolkit.data.model.RepeaterGroup
import io.github.sp3wro.hamradiotoolkit.data.network.RepeaterDataSourceFetcher
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeaterGroupTest {

    private fun rep(call: String, rx: Double, lat: Double, lon: Double, km: Double) =
        Repeater(call, "", listOf("FM"), rx, rx - 0.6, "-", "working", lat, lon, km)

    @Test
    fun sameCallAndPlace_isOneGroupWithChannelsSortedByFrequency() {
        val groups = RepeaterGroup.of(
            listOf(
                rep("SR3WR", 438.825, 52.40, 16.92, 3.0),
                rep("SR3PO", 145.600, 52.10, 16.50, 5.0),
                rep("SR3WR", 145.6875, 52.401, 16.921, 3.1)
            )
        )
        assertEquals(2, groups.size)
        // Kolejność grup jak na wejściu (posortowanym po odległości)
        assertEquals("SR3WR", groups[0].callsign)
        assertEquals(listOf(145.6875, 438.825), groups[0].channels.map { it.userRx })
        assertEquals(3.0, groups[0].distanceKm, 1e-9)
    }

    @Test
    fun combinedModeText_keepsEveryMode() {
        val fetcher = RepeaterDataSourceFetcher()
        // SR3DOS na przemienniki.net: wcześniej wychodziło samo "C4FM"
        assertEquals(listOf("D-STAR", "C4FM"), fetcher.normalizeModes(listOf("DSTAR/DV+C4FM/Fusion")))
        assertEquals(listOf("FM", "DMR"), fetcher.normalizeModes(listOf("FM", "DMR")))
        assertEquals(listOf("C4FM", "FM"), fetcher.normalizeModes(listOf("C4FM", "FM")))
        assertEquals(listOf("C4FM"), fetcher.normalizeModes(listOf("DV")))
        assertEquals(listOf("FM"), fetcher.normalizeModes(listOf("NFM")))
    }

    @Test
    fun sameCallFarApart_staysSeparate() {
        // Ten sam znak w dwóch miejscach (np. przemiennik i jego link) – osobne karty
        val groups = RepeaterGroup.of(listOf(rep("SR3XX", 145.6, 52.4, 16.9, 1.0), rep("SR3XX", 439.0, 52.9, 17.5, 60.0)))
        assertEquals(2, groups.size)
    }
}
