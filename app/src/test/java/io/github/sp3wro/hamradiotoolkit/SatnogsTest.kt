package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.network.SatnogsTransmitter
import io.github.sp3wro.hamradiotoolkit.data.network.activeNoradIds
import io.github.sp3wro.hamradiotoolkit.data.network.extractNoradId
import org.junit.Assert.assertEquals
import org.junit.Test

class SatnogsTest {

    @Test
    fun activeNoradIds_keepsOnlyAliveActiveTransmitters() {
        val transmitters = listOf(
            SatnogsTransmitter(norad_cat_id = 25544, alive = true, status = "active"),
            SatnogsTransmitter(norad_cat_id = 25544, alive = false, status = "inactive"),
            SatnogsTransmitter(norad_cat_id = 27607, alive = true, status = "inactive"),
            SatnogsTransmitter(norad_cat_id = 43017, alive = false, status = "active"),
            SatnogsTransmitter(norad_cat_id = null, alive = true, status = "active")
        )
        assertEquals(setOf(25544L), activeNoradIds(transmitters))
    }

    @Test
    fun extractNoradId_readsCatalogNumberFromTleLine2() {
        val line2 = "2 25544  51.6412 128.9321 0005432  32.1234 328.0987 15.49812345445678"
        assertEquals("25544", extractNoradId(line2))
        assertEquals(25544L, extractNoradId(line2).toLongOrNull())
        assertEquals("", extractNoradId("2"))
    }
}
