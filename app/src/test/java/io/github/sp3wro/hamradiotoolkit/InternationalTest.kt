package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.geo.CountryInfo
import io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion
import io.github.sp3wro.hamradiotoolkit.data.geo.PolandBorder
import io.github.sp3wro.hamradiotoolkit.data.intl.CeptLicence
import io.github.sp3wro.hamradiotoolkit.data.model.BandplanProvider
import io.github.sp3wro.hamradiotoolkit.data.network.GeoBox
import io.github.sp3wro.hamradiotoolkit.data.network.InternationalRepeaters
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InternationalTest {

    // ---------------------------------------------------------------- przemienniki

    // Fragment prawdziwej odpowiedzi hearham.com/api/repeaters/v1
    private val hearHamJson = """
        [{"id":681,"callsign":"GB3DV","latitude":53.424857,"longitude":-1.19,"city":"Rotherham, SY England","group":"IRLP",
          "internet_node":"5130","mode":"FM","encode":"71.90","decode":"71.90","frequency":433025000,"offset":1600000,
          "description":"Internet-connected IRLP node owned by G0EPX.\nhttps://qsl.net/gb3dv/","power":"unknown","operational":1,"restriction":""},
         {"id":9001,"callsign":"DB0XYZ","latitude":52.5,"longitude":13.4,"city":"Berlin","group":"DMR","mode":"DMR    ",
          "encode":"CC1","decode":"CC1","frequency":439562500,"offset":-7600000,"description":"","operational":1},
         {"id":9002,"callsign":"OE1ABC","latitude":48.2,"longitude":16.4,"city":"Wien","group":"","mode":"YSF/FM",
          "encode":"0","decode":"0","frequency":145650000,"offset":-600000,"description":"","operational":0},
         {"id":9003,"callsign":"","latitude":1.0,"longitude":1.0,"mode":"FM","frequency":145000000,"offset":0,"operational":1}]
    """.trimIndent()

    @Test
    fun parsesHearHam() {
        val list = InternationalRepeaters.parseHearHam(hearHamJson)
        assertEquals(3, list.size) // bez znaku – pomijany
        val gb = list[0]
        assertEquals(433.025, gb.userRx, 1e-9)
        assertEquals(434.625, gb.userTx, 1e-9) // UK: wejście +1,6 MHz
        assertEquals("71.9", gb.requiredCtcss)
        assertTrue(gb.note.startsWith("IRLP • Internet-connected"))
        val dmr = list[1]
        assertEquals(listOf("DMR"), dmr.modes)
        assertEquals("-", dmr.requiredCtcss)
        assertEquals("1", dmr.dmrColorCode)
        assertEquals(431.9625, dmr.userTx, 1e-9)
        val oe = list[2]
        assertEquals(listOf("C4FM", "FM"), oe.modes)
        assertEquals("off", oe.status)
    }

    // Fragment prawdziwej odpowiedzi repeatermap.de/api/v1/map.php (DB0LDS, Wildau)
    private val repeatermapJson = """
        {"bounds":{},"locations":[{"qth":"Wildau","position":{"lon":13.61973,"lat":52.31613},"relais":[
          {"id":3623,"call":"DB0LDS","locator":"JO62TH","mode":"FM","rx":144.8,"tx":144.8,"remarks":"APRS Digi","qth":"Wildau","url":"http://www.db0lds.de","has_events":false},
          {"id":3604,"call":"DB0LDS","locator":"JO62TH","mode":"DMR","rx":431.9625,"tx":439.5625,"remarks":"CC:1, Brandmeister","qth":"Wildau","url":"http://www.db0lds.de/","has_events":false},
          {"id":3603,"call":"DB0LDS","locator":"JO62TH","mode":"FM","rx":431.9625,"tx":439.5625,"remarks":"CTCSS 67,0 (RX+TX),FM-N narrow, schmal,","qth":"Wildau","url":"http://www.db0lds.de/","has_events":false}
        ]}],"mode_detail_defs":{}}
    """.trimIndent()

    @Test
    fun parsesRepeatermapAndMergesModes() {
        val list = InternationalRepeaters.parseRepeatermap(repeatermapJson)
        assertEquals(3, list.size)
        val dmr = list[1]
        assertEquals(439.5625, dmr.userRx, 1e-9) // tx przemiennika = nasz odbiór
        assertEquals(431.9625, dmr.userTx, 1e-9)
        assertEquals("1", dmr.dmrColorCode)
        assertEquals("67.0", list[2].requiredCtcss)

        // FM i DMR na tych samych częstotliwościach to jeden przemiennik
        val merged = InternationalRepeaters.merge(list)
        assertEquals(2, merged.size)
        val ldsUhf = merged.single { it.userRx > 400 }
        assertEquals(setOf("DMR", "FM"), ldsUhf.modes.toSet())
        assertEquals("67.0", ldsUhf.requiredCtcss)
        assertEquals("1", ldsUhf.dmrColorCode)
    }

    @Test
    fun repeatermapDuplicatesGetUniqueListKeys() {
        // Rzeczywisty kształt odpowiedzi: FM i DMR na tej samej parze częstotliwości to osobne wpisy,
        // mode_details bywa obiektem albo pustą tablicą, url/remarks bywają null
        val text = """{"bounds":[52,10,50,8],"locations":[
            {"qth":"Bovenden","position":{"lon":9.95603,"lat":51.58294},"relais":[
              {"id":492,"call":"DB0GOE","mode":"FM","rx":431.1,"tx":438.7,"remarks":null,"qth":"Bovenden","url":null,
               "mode_details":{"ctcss_rx":"88.5","open_1750":false}},
              {"id":1193,"call":"DB0GOE","mode":"DMR","rx":431.1,"tx":438.7,"remarks":"BM 262","qth":"Bovenden","url":null,
               "mode_details":[]}]},
            {"qth":"Bovenden","position":{"lon":9.95603,"lat":51.58294},"relais":[
              {"id":492,"call":"DB0GOE","mode":"FM","rx":431.1,"tx":438.7,"remarks":null,"qth":"Bovenden","url":null}]}
        ]}"""
        val raw = InternationalRepeaters.parseRepeatermap(text)
        assertEquals(3, raw.size)
        val merged = InternationalRepeaters.merge(raw)
        assertEquals(1, merged.size)
        assertEquals(listOf("FM", "DMR"), merged[0].modes)
        assertEquals("88.5", merged[0].requiredCtcss)
        assertEquals(merged.size, merged.map { it.listKey }.toSet().size)
    }

    @Test
    fun mergeAcrossSourcesKeepsFirstPositionAndCombinesSources() {
        val a = InternationalRepeaters.parseRepeatermap(repeatermapJson)
        val b = a.map { it.copy(source = "HearHam", latitude = 0.5, requiredCtcss = "-", note = "Allstar") }
        val merged = InternationalRepeaters.merge(a, b)
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.source == "repeatermap.de + HearHam" })
        assertTrue(merged.all { it.latitude > 50 })
        assertTrue(merged.all { "Allstar" in it.note })
    }

    @Test
    fun parsesRadioIdDetails() {
        val text = """{"count":1,"results":[{"callsign":"SR7LDA","city":"Lodz","color_code":1,"country":"Poland",
            "frequency":"438.45000","ipsc_network":"Brandmeister","offset":"-7.600","status":"on-air",
            "talkgroups":[{"description":"Poland","talkgroup":260,"timeslot":1},{"description":"Lodz","talkgroup":26071,"timeslot":2}],
            "trustee":["SQ7SCC"]}]}"""
        val (freq, details) = InternationalRepeaters.parseRadioId(text).single()
        assertEquals(438.45, freq, 1e-9)
        assertEquals("1", details.colorCode)
        assertEquals("Brandmeister", details.network)
        assertEquals(listOf("TS1: 260 (Poland)", "TS2: 26071 (Lodz)"), details.talkgroups)
        assertEquals("SQ7SCC", details.trustee)
    }

    @Test
    fun parsesRepeaterBookExport() {
        // Nazwy pól wg dokumentacji eksportu RepeaterBook
        val text = """{"count":1,"results":[{"Callsign":"HB9F","Frequency":"438.6250","Input Freq":"431.0250","PL":"71.9",
            "TSQ":"","Lat":"46.95","Long":"7.44","Nearest City":"Bern","Landmark":"Gurten","Operational Status":"On-air",
            "FM Analog":"Yes","DMR":"No","D-Star":"No","System Fusion":"Yes","Notes":"Linked"}]}"""
        val r = InternationalRepeaters.parseRepeaterBook(text).single()
        assertEquals("HB9F", r.callsign)
        assertEquals(431.025, r.userTx, 1e-9)
        assertEquals("71.9", r.requiredCtcss)
        assertEquals(listOf("FM", "C4FM"), r.modes)
        assertEquals("Bern, Gurten", r.qth)
    }

    @Test
    fun geoBoxTilesCoverArea() {
        val box = GeoBox.around(52.4, 16.9, 210.0)
        val tiles = box.tiles(2.0)
        assertTrue(tiles.all { it.north - it.south <= 2.0 + 1e-9 && it.east - it.west <= 2.0 + 1e-9 })
        // Każdy punkt prostokąta leży w jakimś kafelku
        for (lat in listOf(box.south, 52.4, box.north - 1e-6)) for (lon in listOf(box.west, 16.9, box.east - 1e-6)) {
            assertTrue(tiles.any { it.contains(lat, lon) })
        }
    }

    @Test
    fun parsesFullDownloadedDataIfAvailable() {
        // Opcjonalnie: -Pintl.dataDir=<katalog z hearham.json i repeatermap_*.json>
        val dir = System.getProperty("intl.dataDir")?.let(::File) ?: return
        val hearHam = InternationalRepeaters.parseHearHam(File(dir, "hearham.json").readText())
        println("HearHam: ${hearHam.size} przemienników, działające: ${hearHam.count { it.status == "working" }}")
        assertTrue(hearHam.size > 20_000)
        dir.listFiles { f -> f.name.startsWith("repeatermap_") }?.forEach { f ->
            val list = InternationalRepeaters.parseRepeatermap(f.readText())
            println("${f.name}: ${list.size} → po scaleniu ${InternationalRepeaters.merge(list).size}")
            assertTrue(list.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------- kraj, region, CEPT

    @Test
    fun polandBorder() {
        assertTrue(PolandBorder.contains(LatLon(52.41, 16.93)))   // Poznań
        assertTrue(PolandBorder.contains(LatLon(54.35, 18.65)))   // Gdańsk
        assertTrue(PolandBorder.contains(LatLon(50.06, 19.94)))   // Kraków
        assertTrue(PolandBorder.contains(LatLon(53.13, 23.16)))   // Białystok
        assertFalse(PolandBorder.contains(LatLon(52.52, 13.40)))  // Berlin
        assertFalse(PolandBorder.contains(LatLon(50.08, 14.43)))  // Praga
        assertFalse(PolandBorder.contains(LatLon(54.69, 25.28)))  // Wilno
        assertFalse(PolandBorder.contains(LatLon(49.84, 24.03)))  // Lwów
    }

    @Test
    fun iaruRegions() {
        assertEquals(IaruRegion.R1, IaruRegion.forCountry("DE"))
        assertEquals(IaruRegion.R1, IaruRegion.forCountry("RU"))
        assertEquals(IaruRegion.R2, IaruRegion.forCountry("US"))
        assertEquals(IaruRegion.R2, IaruRegion.forCountry("BR"))
        assertEquals(IaruRegion.R3, IaruRegion.forCountry("JP"))
        assertEquals(IaruRegion.R3, IaruRegion.forCountry("IR"))
        assertEquals(IaruRegion.R1, IaruRegion.forPosition(LatLon(55.75, 37.6)))   // Moskwa
        assertEquals(IaruRegion.R1, IaruRegion.forPosition(LatLon(62.0, 129.7)))   // Jakuck
        assertEquals(IaruRegion.R1, IaruRegion.forPosition(LatLon(41.3, 69.2)))    // Taszkent
        assertEquals(IaruRegion.R2, IaruRegion.forPosition(LatLon(40.7, -74.0)))   // Nowy Jork
        assertEquals(IaruRegion.R3, IaruRegion.forPosition(LatLon(35.7, 139.7)))   // Tokio
        assertEquals(IaruRegion.R3, IaruRegion.forPosition(LatLon(-33.9, 151.2)))  // Sydney
        assertEquals(IaruRegion.R3, IaruRegion.forPosition(LatLon(-17.5, -149.6))) // Tahiti
    }

    @Test
    fun ceptPrefixes() {
        fun call(iso: String, area: String = "", pos: LatLon? = null) =
            CeptLicence.advice(CountryInfo(iso, area, pos), "sp3wro").callsign

        assertEquals("DL/SP3WRO", call("DE"))
        assertEquals("OE/SP3WRO", call("AT"))
        assertEquals("HB9/SP3WRO", call("CH"))
        assertEquals("M/SP3WRO", call("GB", "England"))
        assertEquals("MM/SP3WRO", call("GB", "Scotland"))
        assertEquals("MD/SP3WRO", call("IM"))
        assertEquals("W6/SP3WRO", call("US", "California"))
        assertEquals("KH6/SP3WRO", call("US", "Hawaii"))
        assertEquals("KP4/SP3WRO", call("PR"))
        assertEquals("VE7/SP3WRO", call("CA", "British Columbia"))
        assertEquals("CT8/SP3WRO", call("PT", pos = LatLon(37.74, -25.67)))  // Azory
        assertEquals("CT9/SP3WRO", call("PT", pos = LatLon(32.65, -16.91)))  // Madera
        assertEquals("CT7/SP3WRO", call("PT", pos = LatLon(38.72, -9.14)))   // Lizbona
        assertEquals("TK/SP3WRO", call("FR", pos = LatLon(41.92, 8.74)))     // Ajaccio
        assertEquals("OH0/SP3WRO", call("AX"))
        assertEquals("SP3WRO/OA", call("PE"))                                // Peru – przyrostek
        assertEquals("SP3WRO", call("PL"))
        assertNull(call("RU"))   // spoza T/R 61-01
        assertNull(call("JP"))
        assertNull(CeptLicence.advice(CountryInfo("DE"), "NOCALL").callsign)
        assertEquals(R.string.cept_note_au, CeptLicence.find("AU")!!.note)
        assertFalse(CeptLicence.find("US")!!.cept)
    }

    @Test
    fun regionalBandplansAreConsistent() {
        for (region in IaruRegion.values()) {
            val bands = BandplanProvider.bandsFor(region)
            assertTrue(bands.size >= 12)
            for (band in bands) {
                // Segmenty rosnąco i bez nakładania (wejścia/wyjścia mogą się stykać)
                val starts = band.segments.map { it.range.substringBefore(" ").toDouble() }
                assertEquals("${region}: ${band.name}", starts.sorted(), starts)
            }
        }
        val r2forty = BandplanProvider.bandsFor(IaruRegion.R2).single { it.name == "40m" }
        assertEquals("7.000 - 7.300 MHz", r2forty.range)
        assertTrue(BandplanProvider.bandsFor(IaruRegion.R2).single { it.name == "2m" }.segments.any { "146.520" in it.description })
    }
}
