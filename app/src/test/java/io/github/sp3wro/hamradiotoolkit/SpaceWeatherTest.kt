package io.github.sp3wro.hamradiotoolkit

import io.github.sp3wro.hamradiotoolkit.background.SpaceWeather
import io.github.sp3wro.hamradiotoolkit.data.model.BandCondition
import io.github.sp3wro.hamradiotoolkit.widget.WidgetUpdateWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceWeatherTest {

    @Test
    fun latestKp_currentObjectFormat() {
        val body = """[{"time_tag":"2026-10-13T00:00:00","Kp":3.33,"a_running":18,"station_count":8},
            {"time_tag":"2026-10-20T18:00:00","Kp":5.67,"a_running":48,"station_count":8},
            {"time_tag":"2026-10-20T15:00:00","Kp":4.00,"a_running":27,"station_count":8}]"""
        val latest = SpaceWeather.latestKp(body)!!
        assertEquals("2026-10-20T18:00:00", latest.timeTag)
        assertEquals(5.67, latest.kp, 1e-9)
    }

    @Test
    fun latestKp_legacyArrayFormat() {
        val body = """[["time_tag","Kp","a_running","station_count"],
            ["2026-10-20 15:00:00.000","4.00","27","8"],["2026-10-20 18:00:00.000","6.33","80","8"]]"""
        assertEquals(6.33, SpaceWeather.latestKp(body)!!.kp, 1e-9)
    }

    @Test
    fun geomagneticAlerts_filtersByTypeAndTime() {
        val body = """[
          {"product_id":"A20F","issue_datetime":"2026-10-20 19:12:44.190","message":"Space Weather Message Code: WATA20\r\nSerial Number: 1132\r\n\r\nWATCH: Geomagnetic Storm Category G1 Predicted \nHighest Storm Level"},
          {"product_id":"EF3A","issue_datetime":"2026-10-20 19:00:00.000","message":"Space Weather Message Code: ALTEF3\r\nALERT: Electron 2MeV Integral Flux exceeded 1000pfu"},
          {"product_id":"K05W","issue_datetime":"2026-10-19 01:00:00.000","message":"WARNING: Geomagnetic K-index of 5 expected"}
        ]"""
        val since = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse("2026-10-20 00:00")!!.time
        val alerts = SpaceWeather.geomagneticAlerts(body, since)
        assertEquals(1, alerts.size)
        assertEquals("WATCH: Geomagnetic Storm Category G1 Predicted", alerts.single().headline)
    }

    @Test
    fun gScaleAndDescription() {
        assertEquals(0, SpaceWeather.gScale(4.67))
        assertEquals(1, SpaceWeather.gScale(5.0))
        assertEquals(3, SpaceWeather.gScale(7.33))
        // Opis z zasobów (w testach JVM bez kontekstu – identyfikatory zasobów)
        assertTrue(SpaceWeather.describe(7.0).startsWith("Kp = 7.0"))
        assertTrue(SpaceWeather.describe(7.0).contains(AppStrings.get(R.string.sw_kp7)))
    }

    @Test
    fun widgetBandSummary() {
        val text = WidgetUpdateWorker.formatBands(
            listOf(
                BandCondition("80m-40m", "day", "Fair"), BandCondition("80m-40m", "night", "Good"),
                BandCondition("12m-10m", "day", "Poor"), BandCondition("12m-10m", "night", "Poor")
            )
        )
        // Bez kontekstu AppStrings zwraca identyfikator zasobu – sprawdzamy kolejność pasm i liczbę wierszy
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertEquals(AppStrings.get(R.string.wdg_band_line, "80m-40m", AppStrings.get(R.string.wdg_fair), AppStrings.get(R.string.wdg_good)), lines[0])
    }
}
