package io.github.sp3wro.hamradiotoolkit.utils

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PathInfo(
    val shortPathKm: Double,
    val shortPathBearing: Double,
    val longPathKm: Double,
    val longPathBearing: Double
)

// Ortodroma na kuli o średnim promieniu Ziemi – dokładność ~0.5%, w zupełności wystarczająca w radiu
object GreatCircle {
    const val EARTH_RADIUS_KM = 6371.0
    private const val EARTH_CIRCUMFERENCE_KM = 2 * Math.PI * EARTH_RADIUS_KM

    fun distanceKm(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(to.lon - from.lon)
        val a = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Azymut początkowy (0° = północ, zgodnie z ruchem wskazówek zegara). */
    fun bearingDeg(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun path(from: LatLon, to: LatLon): PathInfo {
        val sp = distanceKm(from, to)
        val bearing = bearingDeg(from, to)
        return PathInfo(
            shortPathKm = sp,
            shortPathBearing = bearing,
            longPathKm = EARTH_CIRCUMFERENCE_KM - sp,
            longPathBearing = (bearing + 180.0) % 360.0
        )
    }

    /** Punkt odległy o [distanceKm] w kierunku [bearingDeg] – do rysowania ortodromy na mapie. */
    fun destination(from: LatLon, bearingDeg: Double, distanceKm: Double): LatLon {
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val brg = Math.toRadians(bearingDeg)
        val d = distanceKm / EARTH_RADIUS_KM
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg))
        val lon2 = lon1 + atan2(sin(brg) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        val lonDeg = (Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0
        return LatLon(Math.toDegrees(lat2), lonDeg)
    }

    /**
     * Rzut azymutalny równoodległościowy wokół [center]: zwraca (x, y) w km, x na wschód, y na północ.
     * Odległość od środka mapy = odległość po ortodromie, kąt = azymut. null dla punktu antypodycznego.
     */
    fun azimuthalProject(center: LatLon, point: LatLon): Pair<Double, Double>? {
        val lat0 = Math.toRadians(center.lat)
        val lon0 = Math.toRadians(center.lon)
        val lat = Math.toRadians(point.lat)
        val lon = Math.toRadians(point.lon)
        val cosC = sin(lat0) * sin(lat) + cos(lat0) * cos(lat) * cos(lon - lon0)
        val c = acos(cosC.coerceIn(-1.0, 1.0))
        if (c > Math.PI - 1e-6) return null
        val k = if (c < 1e-9) 1.0 else c / sin(c)
        val x = k * cos(lat) * sin(lon - lon0)
        val y = k * (cos(lat0) * sin(lat) - sin(lat0) * cos(lat) * cos(lon - lon0))
        return Pair(x * EARTH_RADIUS_KM, y * EARTH_RADIUS_KM)
    }

    fun compassPoint(bearing: Double): String {
        val points = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return points[((bearing + 11.25) / 22.5).toInt() % 16]
    }
}
