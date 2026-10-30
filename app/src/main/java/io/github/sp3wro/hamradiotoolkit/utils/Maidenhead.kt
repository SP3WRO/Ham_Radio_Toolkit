package io.github.sp3wro.hamradiotoolkit.utils

import kotlin.math.floor

data class LatLon(val lat: Double, val lon: Double)

// Lokator QTH (Maidenhead): pola AA-RR, kwadraty 00-99, podkwadraty aa-xx, rozszerzenie 00-99
object Maidenhead {

    private val VALID = Regex("^[A-R]{2}([0-9]{2}([A-X]{2}([0-9]{2})?)?)?$", RegexOption.IGNORE_CASE)

    fun isValid(locator: String): Boolean = VALID.matches(locator.trim())

    /** Lokator 4, 6 lub 8 znaków dla podanej pozycji. */
    fun fromLatLon(lat: Double, lon: Double, precision: Int = 6): String {
        require(precision == 4 || precision == 6 || precision == 8) { "Precyzja 4, 6 lub 8" }
        // Krawędź 180°/90° należy do ostatniego pola
        var x = (lon + 180.0).coerceIn(0.0, 359.9999999)
        var y = (lat + 90.0).coerceIn(0.0, 179.9999999)
        val sb = StringBuilder()
        sb.append('A' + floor(x / 20).toInt()).append('A' + floor(y / 10).toInt())
        x %= 20.0; y %= 10.0
        sb.append('0' + floor(x / 2).toInt()).append('0' + floor(y).toInt())
        if (precision >= 6) {
            x %= 2.0; y %= 1.0
            sb.append('a' + floor(x * 12).toInt()).append('a' + floor(y * 24).toInt())
        }
        if (precision == 8) {
            x %= (2.0 / 24); y %= (1.0 / 24)
            sb.append('0' + floor(x * 120).toInt()).append('0' + floor(y * 240).toInt())
        }
        return sb.toString()
    }

    /** Środek pola/kwadratu opisanego lokatorem albo null dla błędnego lokatora. */
    fun toLatLon(locator: String): LatLon? {
        val loc = locator.trim().uppercase()
        if (!isValid(loc)) return null
        var lon = (loc[0] - 'A') * 20.0 - 180.0
        var lat = (loc[1] - 'A') * 10.0 - 90.0
        var lonSize = 20.0
        var latSize = 10.0
        if (loc.length >= 4) {
            lon += (loc[2] - '0') * 2.0
            lat += (loc[3] - '0') * 1.0
            lonSize = 2.0; latSize = 1.0
        }
        if (loc.length >= 6) {
            lon += (loc[4] - 'A') * (2.0 / 24)
            lat += (loc[5] - 'A') * (1.0 / 24)
            lonSize = 2.0 / 24; latSize = 1.0 / 24
        }
        if (loc.length == 8) {
            lon += (loc[6] - '0') * (2.0 / 240)
            lat += (loc[7] - '0') * (1.0 / 240)
            lonSize = 2.0 / 240; latSize = 1.0 / 240
        }
        return LatLon(lat + latSize / 2, lon + lonSize / 2)
    }
}
