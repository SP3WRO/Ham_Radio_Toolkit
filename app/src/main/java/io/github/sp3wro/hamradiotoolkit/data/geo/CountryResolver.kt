package io.github.sp3wro.hamradiotoolkit.data.geo

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Kraj, w którym jesteśmy.
 * [isoCode] – kod ISO 3166-1 alfa-2 (np. "DE"); [adminArea] – region/stan/prowincja po angielsku (np. "California").
 */
data class CountryInfo(
    val isoCode: String,
    val adminArea: String = "",
    val position: LatLon? = null
) {
    val isPoland: Boolean get() = isoCode == "PL"

    /** Nazwa kraju po polsku (z systemu), np. "Niemcy". */
    val namePl: String get() = Locale.Builder().setRegion(isoCode).build().getDisplayCountry(UiLocale).ifBlank { isoCode }

    /** Flaga z kodu ISO (dwa symbole "regional indicator"). */
    val flag: String get() = isoCode.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
}

/**
 * Ustalanie kraju z pozycji: systemowy Geocoder (zwykle wymaga internetu), a bez niego –
 * przybliżony obrys Polski, żeby przynajmniej wiedzieć "w kraju czy za granicą".
 */
object CountryResolver {
    private const val TAG = "CountryResolver"

    @Volatile private var cache: Pair<LatLon, CountryInfo>? = null

    /** Ostatnio ustalony kraj (bez czekania) albo null. */
    fun cached(): CountryInfo? = cache?.second

    suspend fun resolve(context: Context, position: LatLon): CountryInfo? {
        cache?.let { (pos, info) ->
            // Ten sam kraj w promieniu ~5 km – nie pytamy ponownie
            if (kotlin.math.abs(pos.lat - position.lat) < 0.05 && kotlin.math.abs(pos.lon - position.lon) < 0.05) return info
        }
        val fromGeocoder = geocode(context, position)
        val info = fromGeocoder
            ?: if (PolandBorder.contains(position)) CountryInfo("PL", position = position) else null
        if (info != null) cache = position to info
        return info
    }

    private suspend fun geocode(context: Context, position: LatLon): CountryInfo? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context.applicationContext, Locale.ENGLISH)
        return try {
            val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(position.lat, position.lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(position.lat, position.lon, 1)?.firstOrNull()
                }
            }
            val iso = address?.countryCode?.uppercase()?.takeIf { it.length == 2 } ?: return null
            CountryInfo(iso, address.adminArea.orEmpty(), position)
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder niedostępny", e)
            null
        }
    }
}

/** Uproszczony obrys granic Polski (ok. 5–10 km dokładności) – wystarcza do decyzji "w kraju / za granicą". */
object PolandBorder {
    private val polygon = listOf(
        53.92 to 14.22, 54.18 to 15.57, 54.76 to 17.55, 54.83 to 18.33, 54.60 to 18.80, 54.36 to 19.62,
        54.42 to 21.0, 54.36 to 22.79, 54.0 to 23.40, 53.90 to 23.51, 53.10 to 23.90, 52.70 to 23.94,
        52.08 to 23.65, 51.60 to 23.62, 50.85 to 24.10, 50.40 to 24.05, 49.80 to 22.70, 49.00 to 22.89,
        49.42 to 21.0, 49.22 to 20.10, 49.40 to 19.50, 49.52 to 18.85, 49.95 to 18.30, 50.30 to 17.70,
        50.20 to 16.90, 50.62 to 16.20, 50.87 to 14.82, 51.40 to 14.97, 51.90 to 14.70, 52.60 to 14.55,
        53.00 to 14.40, 53.40 to 14.40
    )

    fun contains(p: LatLon): Boolean {
        // Ray casting (lon = x, lat = y)
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (yi, xi) = polygon[i]
            val (yj, xj) = polygon[j]
            if ((yi > p.lat) != (yj > p.lat) && p.lon < (xj - xi) * (p.lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

/** Region IARU (= region ITU) – decyduje o bandplanie i częstotliwościach APRS. */
enum class IaruRegion(val number: Int, private val labelRes: Int) {
    R1(1, R.string.iaru_r1),
    R2(2, R.string.iaru_r2),
    R3(3, R.string.iaru_r3);

    val label: String get() = AppStrings.get(labelRes)

    companion object {
        // Ameryki, Grenlandia i Hawaje
        private val REGION2 = setOf(
            "US", "CA", "MX", "GL", "PM", "BM", "GT", "BZ", "SV", "HN", "NI", "CR", "PA", "CU", "JM", "HT", "DO",
            "PR", "VI", "VG", "AI", "KN", "AG", "MS", "GP", "DM", "MQ", "LC", "VC", "BB", "GD", "TT", "AW", "CW",
            "BQ", "SX", "MF", "BL", "KY", "TC", "BS", "CO", "VE", "GY", "SR", "GF", "EC", "PE", "BO", "BR", "PY",
            "UY", "AR", "CL", "FK", "GS"
        )
        // Azja Południowa, Wschodnia i Południowo-Wschodnia, Oceania (Iran i Afganistan też należą do R3)
        private val REGION3 = setOf(
            "AU", "NZ", "JP", "CN", "TW", "HK", "MO", "KR", "KP", "IN", "PK", "BD", "LK", "NP", "BT", "MV", "AF",
            "IR", "ID", "MY", "SG", "TH", "VN", "LA", "KH", "MM", "PH", "BN", "TL", "PG", "FJ", "SB", "VU", "NC",
            "PF", "WS", "AS", "TO", "KI", "TV", "NR", "FM", "MH", "PW", "GU", "MP", "CK", "NU", "WF", "NF", "IO"
        )

        fun forCountry(iso: String?): IaruRegion? = when {
            iso == null -> null
            iso in REGION2 -> R2
            iso in REGION3 -> R3
            else -> R1
        }

        /** Gdy kraju nie znamy – zgrubnie z położenia (Ameryki na zachód od Atlantyku, R3 na wschód od Iranu). */
        fun forPosition(p: LatLon): IaruRegion = when {
            // Wyspy Pacyfiku na wschód od linii zmiany daty (Samoa, Polinezja) – R3
            p.lon < -140 && p.lat < 0 -> R3
            p.lon < -30 -> R2
            // Rosja i Azja Środkowa należą do R1
            p.lat >= 50 -> R1
            p.lat > 35 && p.lon < 75 -> R1
            p.lon > 60 -> R3
            else -> R1
        }
    }
}
