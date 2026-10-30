package io.github.sp3wro.hamradiotoolkit.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Propagation : Screen("propagation")
    object Activations : Screen("activations")
    object DxCluster : Screen("dx_cluster")
    object Logger : Screen("logger")
    object Aprs : Screen("aprs")
    object Bandplan : Screen("bandplan")
    object Repeaters : Screen("repeaters")
    object Toolbox : Screen("toolbox")
    object Calculators : Screen("calculators")
    object CoaxCalc : Screen("coax_calc")
    object GrayLineCalc : Screen("gray_line_calc")
    object Satellites : Screen("satellites")
    object SstvDecoder : Screen("sstv_decoder")
    object Vademecum : Screen("vademecum/{topic}") {
        fun createRoute(topic: String) = "vademecum/$topic"
    }
    object About : Screen("about")
    object Settings : Screen("settings")
    object Locator : Screen("locator")
    object LogStats : Screen("log_stats")
    object LiveSpots : Screen("live_spots")
    object Beacons : Screen("beacons")
    object CwTrainer : Screen("cw_trainer")
    object CwDecoder : Screen("cw_decoder")
    object Ft8Decoder : Screen("ft8_decoder")
    object RttyDecoder : Screen("rtty_decoder")
    object HorusDecoder : Screen("horus_decoder")
    object DsstvDecoder : Screen("dsstv_decoder")
    object ContestCalendar : Screen("contest_calendar")
    object Awards : Screen("awards")
    object Abroad : Screen("abroad")
    object CallsignLookup : Screen("callsign_lookup?call={call}") {
        fun createRoute(call: String = "") = "callsign_lookup?call=$call"
    }
}
