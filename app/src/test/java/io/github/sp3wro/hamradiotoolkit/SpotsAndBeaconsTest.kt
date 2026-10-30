package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.beacons.NcdxfBeacons
import io.github.sp3wro.hamradiotoolkit.data.model.bandForKhz
import io.github.sp3wro.hamradiotoolkit.data.model.calculateBandFromFreq
import io.github.sp3wro.hamradiotoolkit.data.network.LiveSpotsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotsAndBeaconsTest {

    // Harmonogram z ncdxf.org/beacon/beaconschedule.html (pierwsza transmisja w godzinie, mm:ss)
    @Test
    fun ncdxfSchedule_matchesOfficialTable() {
        fun at(mm: Int, ss: Int) = NcdxfBeacons.nowTransmitting((mm * 60 + ss).toLong()).map { it.beacon.call }
        assertEquals(listOf("4U1UN", "YV5B", "OA4B", "LU4AA", "CS3B"), at(0, 0))
        assertEquals(listOf("VE8AT", "4U1UN", "YV5B", "OA4B", "LU4AA"), at(0, 10))
        assertEquals(listOf("OH2B", "4X6TU", "5Z4B", "ZS6DN", "4S7B"), at(2, 10))
        assertEquals(listOf("YV5B", "OA4B", "LU4AA", "CS3B", "OH2B"), at(2, 50))
        // Cykl 3-minutowy powtarza się przez całą dobę
        assertEquals(at(0, 0), NcdxfBeacons.nowTransmitting(13L * 3600 + 3 * 60).map { it.beacon.call })
        assertEquals(10, NcdxfBeacons.nowTransmitting(0).first().secondsLeft)
        assertEquals(1, NcdxfBeacons.nowTransmitting(9).first().secondsLeft)
    }

    @Test
    fun ncdxfSecondsUntil() {
        val w6wx = NcdxfBeacons.BEACONS.first { it.call == "W6WX" }
        // W6WX: 14.100 o 00:20, 28.200 o 01:00
        assertEquals(20, NcdxfBeacons.secondsUntil(w6wx, 0, 0))
        assertEquals(0, NcdxfBeacons.secondsUntil(w6wx, 0, 25))
        assertEquals(170, NcdxfBeacons.secondsUntil(w6wx, 0, 30)) // następny cykl: 20 + 180 - 30
        assertEquals(60, NcdxfBeacons.secondsUntil(w6wx, 4, 0))
    }

    @Test
    fun bands() {
        assertEquals("20m", bandForKhz(14074.0))
        assertEquals("60m", bandForKhz(5357.0))
        assertNull(bandForKhz(11000.0))
        assertEquals("20m", calculateBandFromFreq("abc")) // dotychczasowe zachowanie loggera
    }

    @Test
    fun liveSpotParsing() {
        val client = LiveSpotsClient()
        val pota = client.parsePota(
            """[{"spotId": 1, "activator": "w0ss", "frequency": "14074", "mode": "FT8", "reference": "US-12398",
                "parkName": null, "spotTime": "2026-10-20T20:22:17", "spotter": "VE7CC-#", "comments": "RBN -7 dB",
                "name": "Olathe Prairie", "latitude": 38.8834, "longitude": -94.8905, "extra": 9}]"""
        ).single()
        assertEquals("W0SS", pota.activator)
        assertEquals(14074.0, pota.frequencyKhz, 0.0)
        assertEquals("US-12398", pota.reference)
        assertEquals("Olathe Prairie", pota.referenceName)
        assertEquals(1792527737L, pota.timeEpochSec) // 2026-10-20 20:22:17 UTC

        val wwff = client.parseWwff(
            """[{"id": 159230, "activator": "W7B", "frequency_khz": 21063, "mode": "CW", "reference": "KFF-1572",
                "reference_name": "Franklin Point", "remarks": "Re-spotted via RBN", "spotter": "N7OR",
                "latitude": 38.81392, "longitude": -76.51221, "spot_time": 1792528013}]"""
        ).single()
        assertEquals("WWFF", wwff.program)
        assertEquals(21063.0, wwff.frequencyKhz, 0.0)
        assertEquals(1792528013L, wwff.timeEpochSec)
    }
}
