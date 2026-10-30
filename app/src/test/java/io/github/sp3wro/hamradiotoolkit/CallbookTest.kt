package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.data.callbook.AprsHeardStations
import io.github.sp3wro.hamradiotoolkit.data.callbook.Callbook
import io.github.sp3wro.hamradiotoolkit.data.callbook.HamDbClient
import io.github.sp3wro.hamradiotoolkit.data.callbook.PskReporterClient
import io.github.sp3wro.hamradiotoolkit.data.callbook.QrzcqClient
import io.github.sp3wro.hamradiotoolkit.data.callbook.RadioIdClient
import io.github.sp3wro.hamradiotoolkit.data.callbook.UkeRegistry
import io.github.sp3wro.hamradiotoolkit.data.callbook.UkeRegistry.toTitleCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbookTest {

    @Test
    fun parsesUkeIndividuals() {
        val csv = "number;valid_to;call_sign;category;transmitter_power;station_location\n" +
            "16/I/C/2016;2026-12-20;SP4QK;Kategoria1;500;Białystok\r\n" +
            "2/I/C/2017;2027-03-06;sp4hkj;Kategoria1;500;Supraśl\n"
        val list = UkeRegistry.parseIndividuals(csv)
        assertEquals(2, list.size)
        assertEquals("SP4QK", list[0].callsign)
        assertEquals("Białystok", list[0].stationCity)
        assertEquals(500, list[0].powerW)
        assertEquals("SP4HKJ", list[1].callsign)
        assertFalse(list[0].isClub)
    }

    @Test
    fun parsesUkeClubsWithAddressAndQuotes() {
        val csv = "number;valid_to;call_sign;category;transmitter_power;applicant_name;applicant_city;applicant_street;applicant_house;applicant_number;" +
            "enduser_name;enduser_city;enduser_street;enduser_house;enduser_number;station_city;station_street;station_house;station_number;lat;lng\n" +
            "0004/K/E/2017;2027-01-28;SP2KFD;Kategoria1;500;Liga Obrony Kraju;Warszawa;ul. Chocimska;14;\"\";" +
            "\"Klub \"\"Łączność\"\"; Starogard\";Warszawa;ul. Chocimska;14;\"\";STAROGARD GDAŃSKI;Zielona;18;\"\";;\n"
        val club = UkeRegistry.parseClubs(csv).single()
        assertEquals("SP2KFD", club.callsign)
        assertEquals("Klub \"Łączność\"; Starogard", club.clubName)
        assertEquals("Starogard Gdański", club.stationCity)
        assertEquals("Zielona 18", club.stationStreet)
        assertNull(club.position)
        assertTrue(club.isClub)
    }

    @Test
    fun titleCaseKeepsSeparators() {
        assertEquals("Kędzierzyn-Koźle", "KĘDZIERZYN-KOŹLE".toTitleCase())
        assertEquals("Nowa Wieś", "NOWA WIEŚ".toTitleCase())
        assertEquals("Wronki", "Wronki".toTitleCase())
    }

    @Test
    fun parsesRadioId() {
        val json = """{"count":1,"page":1,"pages":1,"per_page":200,"results":[{"callsign":"SP3WRO","city":"Wronki","country":"Poland",
            "fname":"Mateusz","id":2600956,"name":"Mateusz","radio_id":2600956,"state":"wielkopolskie","surname":""}]}"""
        val entry = RadioIdClient.parse(json, "SP3WRO")
        assertNotNull(entry)
        assertEquals("Mateusz", entry!!.name)
        assertEquals("Wronki", entry.city)
        assertEquals("wielkopolskie", entry.state)
        assertNull(RadioIdClient.parse("""{"count":0,"results":[]}""", "SP3WRO"))
    }

    @Test
    fun parsesHamDb() {
        val json = """{ "hamdb":{ "version":"1", "callsign":{ "call":"VE3ABC", "class":"", "expires":"", "status":"",
            "grid":"FN25ci", "lat":"45.3606600", "lon":"-75.7667900", "fname":"Lawrence Carson", "mi":"", "name":"Kearns",
            "suffix":"", "addr1":"2205 BEL-AIR DRIVE", "addr2":"OTTAWA", "state":"ON", "zip":"K2C0X2", "country":"Canada" },
            "messages":{ "status":"OK" } } }"""
        val entry = HamDbClient.parse(json)!!
        assertEquals("VE3ABC", entry.callsign)
        assertEquals("FN25CI", entry.gridSquare)
        assertEquals("Lawrence Carson Kearns", entry.name)
        assertEquals("OTTAWA, ON", entry.qth)

        val notFound = """{ "hamdb":{ "version":"1", "callsign":{ "call":"NOT_FOUND", "grid":"NOT_FOUND" },
            "messages":{ "status":"NOT_FOUND" } } }"""
        assertNull(HamDbClient.parse(notFound))
    }

    @Test
    fun parsesQrzcq() {
        val xml = """<?xml version="1.0" encoding="utf-8" ?>
            <QRZCQDatabase version="1.00" xmlns="http://qrzcq.com">
              <Callsign><call>DO5SSB</call><dxcc>230</dxcc><name>Grzegorz Surmann</name>
                <qth>Buchholz, near Hamburg</qth><city>Buchholz</city><locator>JO43WH</locator>
                <latitude>53.3199308</latitude><longitude>9.8597973</longitude></Callsign>
              <Session><Key>abc</Key><Count>18232</Count></Session>
            </QRZCQDatabase>"""
        val entry = QrzcqClient.parse(xml)!!
        assertEquals("DO5SSB", entry.callsign)
        assertEquals("JO43WH", entry.gridSquare)
        assertEquals("Grzegorz Surmann", entry.name)
        assertEquals("Buchholz, near Hamburg", entry.qth)
        assertEquals("Not found: N0CALL", QrzcqClient.tag("<Session><Error>Not found: N0CALL</Error></Session>", "Error"))
        assertNull(QrzcqClient.parse("<QRZCQDatabase><Session><Error>Session Timeout</Error></Session></QRZCQDatabase>"))
    }

    @Test
    fun aprsFindsAnySsidWithinTwoHours() {
        AprsHeardStations.clear()
        val now = 10_000_000_000L
        AprsHeardStations.record("SP3ABC-9", 52.40, 16.90, now - 30 * 60_000L)
        AprsHeardStations.record("SP3ABC-7", 52.41, 16.95, now - 5 * 60_000L)
        AprsHeardStations.record("SP3ABCD", 50.0, 20.0, now)            // inny znak – nie może pasować
        AprsHeardStations.record("SP3OLD", 52.0, 17.0, now - 3 * 3600_000L)
        AprsHeardStations.record("SP3ZERO", 0.0, 0.0, now)              // brak pozycji GPS
        assertEquals("SP3ABC-7", AprsHeardStations.find("SP3ABC", now)!!.callsign)
        assertNull(AprsHeardStations.find("SP3OLD", now))
        assertNull(AprsHeardStations.find("SP3ZERO", now))
        AprsHeardStations.clear()
    }

    @Test
    fun parsesPskReporterSenderLocator() {
        val xml = """<?xml version="1.0"?>
            <receptionReports>
              <receptionReport receiverCallsign="DL1XYZ" receiverLocator="JO62qm" senderCallsign="SP3WRO" senderLocator="JO82" frequency="14075000" flowStartSeconds="1792950000" mode="FT8"/>
              <receptionReport receiverCallsign="G4ABC" receiverLocator="IO91" senderCallsign="SP3WRO" senderLocator="JO82kr" frequency="14075000" flowStartSeconds="1792940000" mode="FT8"/>
              <receptionReport receiverCallsign="SP3WRO" receiverLocator="JO82kr" senderCallsign="DL1XYZ" senderLocator="JO62qm" flowStartSeconds="1792960000" mode="FT8"/>
            </receptionReports>"""
        val spot = PskReporterClient.parse(xml, "SP3WRO")!!
        // Najnowszy raport ma tylko kwadrat JO82 – dokładniejszy JO82KR z tego samego kwadratu
        assertEquals("JO82KR", spot.locator)
        assertEquals(1792950000L, spot.timeSeconds)
        assertNull(PskReporterClient.parse("<receptionReports><lastSequenceNumber value=\"1\"/></receptionReports>", "SP3WRO"))
        // Raport bez lokatora nadawcy albo z 2-znakowym polem się nie liczy
        assertNull(PskReporterClient.parse("""<receptionReport senderCallsign="SP3WRO" senderLocator="JO" flowStartSeconds="1"/>""", "SP3WRO"))
    }

    @Test
    fun baseCallAndCompleteness() {
        assertEquals("SP3WRO", Callbook.baseCall("SP3WRO/P"))
        assertEquals("DL1ABC", Callbook.baseCall("SP/DL1ABC"))
        assertEquals("W1AW", Callbook.baseCall("W1AW/4"))
        assertTrue(Callbook.isCompleteCall("SP3WRO"))
        assertTrue(Callbook.isCompleteCall("SN100PZK"))
        assertTrue(Callbook.isCompleteCall("3Z0X"))
        assertFalse(Callbook.isCompleteCall("SP3"))
        assertFalse(Callbook.isCompleteCall("SP"))
    }
}
