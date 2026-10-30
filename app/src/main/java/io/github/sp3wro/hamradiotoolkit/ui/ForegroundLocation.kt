package io.github.sp3wro.hamradiotoolkit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Wysokość z GPS; [aboveSeaLevel] = false – wysokość nad elipsoidą WGS84 (starsze Androidy, bez modelu geoidy). */
data class GpsAltitude(val meters: Double, val aboveSeaLevel: Boolean)

data class GpsFix(val position: LatLon, val altitude: GpsAltitude?)

/**
 * Bieżąca pozycja dla całej aplikacji. Aktualizacje GPS działają tylko wtedy, gdy aplikacja
 * jest na ekranie (ON_START..ON_STOP) i są zawsze wyrejestrowywane – bez drenażu baterii w tle.
 * [precise] = true (pełny GPS) tylko na ekranach, które potrzebują dokładnej pozycji i wysokości;
 * pozostałym wystarcza tryb zbalansowany (sieć/Wi-Fi, ~100 m), który zużywa dużo mniej baterii.
 */
@Composable
fun rememberForegroundLocation(permissionGranted: Boolean, precise: Boolean = true): State<GpsFix?> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val fix = remember { mutableStateOf<GpsFix?>(null) }

    DisposableEffect(lifecycleOwner, permissionGranted, precise) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = if (precise) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .setMinUpdateDistanceMeters(25f)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
                .setMinUpdateIntervalMillis(30_000L)
                .setMinUpdateDistanceMeters(100f)
                .build()
        }

        // Przeliczenie na wysokość n.p.m. czyta model geoidy z dysku – poza wątkiem głównym
        fun publish(loc: Location) {
            scope.launch {
                val altitude = withContext(Dispatchers.IO) { gpsAltitude(context.applicationContext, loc) }
                fix.value = GpsFix(LatLon(loc.latitude, loc.longitude), altitude)
            }
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // Odczyt zbalansowany zwykle nie ma wysokości – zostawiamy ostatnią znaną z GPS
                if (!loc.hasAltitude() && fix.value?.altitude != null) {
                    fix.value = GpsFix(LatLon(loc.latitude, loc.longitude), fix.value?.altitude)
                } else {
                    publish(loc)
                }
            }
        }

        fun start() {
            if (!permissionGranted || !hasLocationPermission(context)) return
            try {
                client.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null && fix.value == null) publish(loc)
                }
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (_: SecurityException) {
            }
        }

        fun stop() {
            client.removeLocationUpdates(callback)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        // addObserver dostarcza od razu zdarzenia do bieżącego stanu (np. ON_START, jeśli już widoczna)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    return fix
}

// GPS podaje wysokość nad elipsoidą WGS84; w Polsce geoida leży ~30–40 m wyżej, więc bez
// przeliczenia (AltitudeConverter, Android 14+) wynik byłby zawyżony o tyle metrów
private fun gpsAltitude(context: Context, loc: Location): GpsAltitude? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (!loc.hasMslAltitude() && loc.hasAltitude()) {
            try {
                AltitudeConverter().addMslAltitudeToLocation(context, loc)
            } catch (_: Exception) {
            }
        }
        if (loc.hasMslAltitude()) return GpsAltitude(loc.mslAltitudeMeters, aboveSeaLevel = true)
    }
    return if (loc.hasAltitude()) GpsAltitude(loc.altitude, aboveSeaLevel = false) else null
}
