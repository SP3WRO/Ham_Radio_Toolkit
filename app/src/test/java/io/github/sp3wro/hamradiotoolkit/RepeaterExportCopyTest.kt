package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.network.RepeaterDataSourceFetcher
import io.github.sp3wro.hamradiotoolkit.data.network.readThroughCopy
import io.github.sp3wro.hamradiotoolkit.data.network.readVersionedCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RepeaterExportCopyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val week = 7 * 86_400_000L
    private var downloads = 0

    private fun download(text: String): () -> String = {
        downloads++
        text
    }

    private fun failing(): () -> String = {
        downloads++
        throw IOException("brak sieci")
    }

    private fun decode(text: String): String {
        require(text.startsWith("ok")) { "niepoprawny plik" }
        return text
    }

    private fun copy(text: String, ageMs: Long, now: Long): File = File(folder.root, "export.json").apply {
        writeText(text)
        setLastModified(now - ageMs)
    }

    @Test
    fun firstRunDownloadsAndKeepsCopy() {
        val file = File(folder.root, "export.json")
        assertEquals("ok-1", readThroughCopy(file, week, 1_000_000L, download("ok-1"), ::decode))
        assertEquals(1, downloads)
        assertEquals("ok-1", file.readText())
    }

    @Test
    fun copyYoungerThanWeekIsUsedWithoutNetwork() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", week - 60_000L, now)
        assertEquals("ok-stara", readThroughCopy(file, week, now, download("ok-nowa"), ::decode))
        assertEquals(0, downloads)
    }

    @Test
    fun copyOlderThanWeekIsReplaced() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", week, now)
        assertEquals("ok-nowa", readThroughCopy(file, week, now, download("ok-nowa"), ::decode))
        assertEquals(1, downloads)
        assertEquals("ok-nowa", file.readText())
    }

    @Test
    fun oldCopyIsUsedWhenDownloadFails() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", 2 * week, now)
        assertEquals("ok-stara", readThroughCopy(file, week, now, failing(), ::decode))
    }

    @Test
    fun invalidDownloadDoesNotReplaceCopy() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", 2 * week, now)
        assertEquals("ok-stara", readThroughCopy(file, week, now, download("<html>błąd</html>"), ::decode))
        assertEquals("ok-stara", file.readText())
    }

    @Test
    fun errorWithoutCopyIsReported() {
        val file = File(folder.root, "export.json")
        assertThrows(IOException::class.java) { readThroughCopy(file, week, 1_000_000L, failing(), ::decode) }
    }

    // ------------------------------------------------------------------ znacznik wersji (timestamp.xml)

    private val day = 86_400_000L
    private val stampFile get() = File(folder.root, "export.timestamp")
    private var stampChecks = 0

    private fun stamp(value: String?): () -> String? = {
        stampChecks++
        value
    }

    private fun versioned(file: File, now: Long, stampValue: () -> String?, content: () -> String) =
        readVersionedCopy(file, stampFile, day, now, stampValue, content, ::decode)

    @Test
    fun copyCheckedTodayAsksForNothing() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", day - 60_000L, now)
        assertEquals("ok-stara", versioned(file, now, stamp("t2"), download("ok-nowa")))
        assertEquals(0, stampChecks)
        assertEquals(0, downloads)
    }

    @Test
    fun sameTimestampKeepsCopyForAnotherDayWithoutDownload() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", 3 * day, now)
        stampFile.writeText("t1")
        assertEquals("ok-stara", versioned(file, now, stamp("t1"), download("ok-nowa")))
        assertEquals(1, stampChecks)
        assertEquals(0, downloads)
        assertEquals("ok-stara", versioned(file, now + 60_000L, stamp("t1"), download("ok-nowa")))
        assertEquals(1, stampChecks)
    }

    @Test
    fun newTimestampDownloadsAndIsRemembered() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", 3 * day, now)
        stampFile.writeText("t1")
        assertEquals("ok-nowa", versioned(file, now, stamp("t2"), download("ok-nowa")))
        assertEquals(1, downloads)
        assertEquals("t2", stampFile.readText())
    }

    @Test
    fun failedDownloadDoesNotRememberNewTimestamp() {
        val now = System.currentTimeMillis()
        val file = copy("ok-stara", 3 * day, now)
        stampFile.writeText("t1")
        assertEquals("ok-stara", versioned(file, now, stamp("t2"), failing()))
        // Przy następnym sprawdzeniu baza ma zostać pobrana ponownie
        assertEquals("t1", stampFile.readText())
    }

    // ------------------------------------------------------------------ rpts.json

    private val fetcher = RepeaterDataSourceFetcher()

    private val rptsJson = """
        {"masks":{"status":[{"type":"status","mask":"1"}]},"repeaters":[
        {"id":"1","qra":"SR3DAB","status":"1","visible":"1","qrg_tx":439.05,"qrg_rx":431.45,"band":16384,"mode_txt":"FM+DSTAR/DV+C4FM/Fusion",
         "locator":"JO82","qth":"Zalesie","lat":"52.1","lng":"16.9","loclat":"52.0","loclng":"16.8","ctcss_rx":"71.9","ctcss_tx":"79.7","deleted":"0"},
        {"id":"2","qra":"SR3OFF","status":"2","visible":"1","qrg_tx":145.6625,"qrg_rx":145.0625,"mode_txt":"","locator":"JO91","qth":null,
         "latitude":null,"lat":null,"lng":null,"loclat":"51.5","loclng":"17.0","ctcss_rx":null,"deleted":"0"},
        {"id":"3","qra":"SR3NOPOS","status":"1","visible":"1","qrg_tx":145.6,"qrg_rx":145.0,"lat":null,"lng":null,"loclat":null,"loclng":null},
        {"id":"4","qra":"SR3DEL","status":"1","visible":"1","qrg_tx":145.6,"qrg_rx":145.0,"lat":"52.0","lng":"17.0","deleted":"1"},
        {"id":"5","qra":"SR3PLAN","status":"32","visible":"1","qrg_tx":145.6,"qrg_rx":145.0,"lat":"52.0","lng":"17.0","ctcss_rx":"0"}
        ],"generated":"2026-10-25 15:51:00"}
    """.trimIndent()

    @Test
    fun parsesPrzemiennikiNetExport() {
        val list = fetcher.parsePrzemiennikiNet(rptsJson).associateBy { it.callsign }
        assertEquals(setOf("SR3DAB", "SR3OFF", "SR3PLAN"), list.keys)

        val dab = list.getValue("SR3DAB")
        assertEquals(439.05, dab.userRx, 0.0)
        assertEquals(431.45, dab.userTx, 0.0)
        assertEquals(listOf("FM", "D-STAR", "C4FM"), dab.modes)
        assertEquals("71.9", dab.requiredCtcss)
        assertEquals("working", dab.status)
        assertEquals(52.1, dab.latitude, 0.0)

        // Bez dokładnej pozycji – środek lokatora; bez emisji – FM; wyłączony nie jest "working"
        val off = list.getValue("SR3OFF")
        assertEquals(51.5, off.latitude, 0.0)
        assertEquals("JO91", off.qth)
        assertEquals(listOf("FM"), off.modes)
        assertEquals("-", off.requiredCtcss)
        assertEquals("off", off.status)

        assertEquals("planned", list.getValue("SR3PLAN").status)
        assertEquals("-", list.getValue("SR3PLAN").requiredCtcss)
    }

    @Test
    fun readsPrzemiennikiNetTimestamp() {
        assertEquals("2026-10-25 14:43:54",
            fetcher.przemiennikiNetTimestamp("""<?xml version="1.0" encoding="UTF-8"?><timestamp>2026-10-25 14:43:54</timestamp>"""))
        assertNull(fetcher.przemiennikiNetTimestamp("<html>błąd</html>"))
    }

    @Test
    fun parsesDownloadedPrzemiennikiNetExport() {
        // Opcjonalnie: -Pintl.dataDir=<katalog z rpts.json>
        val file = System.getProperty("intl.dataDir")?.let { File(it, "rpts.json") }?.takeIf { it.exists() } ?: return
        val list = fetcher.parsePrzemiennikiNet(file.readText())
        println("przemienniki.net: ${list.size}, sprawne: ${list.count { it.status == "working" }}, statusy: ${list.groupingBy { it.status }.eachCount()}")
        println("emisje: ${list.flatMap { it.modes }.groupingBy { it }.eachCount()}, CTCSS: ${list.count { it.requiredCtcss != "-" }}")
        assertTrue(list.size > 2000)
        assertFalse(list.any { it.userRx == 0.0 || it.latitude == 0.0 })
    }
}
