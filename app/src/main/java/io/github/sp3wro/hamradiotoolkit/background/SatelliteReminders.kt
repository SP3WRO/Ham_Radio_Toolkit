package io.github.sp3wro.hamradiotoolkit.background

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.sp3wro.hamradiotoolkit.data.network.SatelliteTle
import io.github.sp3wro.hamradiotoolkit.data.network.parseTleData
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uk.me.g4dpz.satellite.GroundStationPosition
import uk.me.g4dpz.satellite.PassPredictor
import uk.me.g4dpz.satellite.TLE
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UpcomingPass(val satellite: String, val aosMs: Long, val losMs: Long, val maxElevation: Double)

// Ostatnio pobrane TLE (zapisuje ekran Satelity) – z nich liczone są przeloty w tle
object TleStore {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "amateur_tles.txt")

    fun save(context: Context, rawTle: String) {
        if (parseTleData(rawTle).isEmpty()) return
        file(context).writeText(rawTle)
    }

    fun load(context: Context): List<SatelliteTle> =
        file(context).takeIf { it.exists() }?.let { parseTleData(it.readText()) } ?: emptyList()

    /** Wiek zapisanych TLE w ms (Long.MAX_VALUE, gdy ich brak). */
    fun ageMs(context: Context): Long =
        file(context).takeIf { it.exists() }?.let { System.currentTimeMillis() - it.lastModified() } ?: Long.MAX_VALUE
}

object SatellitePasses {
    /** Przeloty satelity od [fromMs] do [untilMs] z maksymalną elewacją co najmniej [minElevation]. */
    fun upcoming(sat: SatelliteTle, site: GroundStationPosition, fromMs: Long, untilMs: Long, minElevation: Double): List<UpcomingPass> {
        val result = mutableListOf<UpcomingPass>()
        try {
            val predictor = PassPredictor(TLE(arrayOf(sat.name, sat.line1, sat.line2)), site)
            var from = Date(fromMs)
            repeat(12) {
                val pass = predictor.nextSatPass(from) ?: return result
                if (pass.startTime.time > untilMs) return result
                if (pass.maxEl >= minElevation && pass.endTime.time > fromMs) {
                    result += UpcomingPass(sat.name, pass.startTime.time, pass.endTime.time, pass.maxEl)
                }
                from = Date(pass.endTime.time + 60_000L)
            }
        } catch (e: Exception) {
            Log.w("SatellitePasses", "Nie udało się policzyć przelotów ${sat.name}", e)
        }
        return result
    }
}

/** Planuje alarmy przed przelotami obserwowanych satelitów (najbliższe 6 h). */
class SatellitePassWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext.dataStore)
        val watched = prefs.watchedSatellites.first()
        if (watched.isEmpty()) return Result.success()
        val station = prefs.stationPosition.first() ?: return Result.success()
        val minutesBefore = prefs.satReminderMinutes.first()
        val minElevation = prefs.satReminderMinElevation.first().toDouble()

        val site = GroundStationPosition(station.lat, station.lon, 100.0)
        val now = System.currentTimeMillis()
        val tles = TleStore.load(applicationContext).filter { it.name in watched }
        for (sat in tles) {
            for (pass in SatellitePasses.upcoming(sat, site, now, now + 6 * 3600_000L, minElevation)) {
                val triggerAt = pass.aosMs - minutesBefore * 60_000L
                if (triggerAt > now) SatellitePassReceiver.schedule(applicationContext, pass, triggerAt)
            }
        }
        return Result.success()
    }
}

class SatellitePassReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val aos = intent.getLongExtra(EXTRA_AOS, 0L)
        val los = intent.getLongExtra(EXTRA_LOS, 0L)
        val maxEl = intent.getDoubleExtra(EXTRA_MAX_EL, 0.0)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Satelita mógł zostać usunięty z obserwowanych po zaplanowaniu alarmu
                val watched = UserPreferencesRepository(context.dataStore).watchedSatellites.first()
                if (name in watched) {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val minutes = ((aos - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    AppNotifications.show(
                        context, AppNotifications.CHANNEL_SATELLITES, requestCode(name, aos),
                        AppStrings.get(R.string.ntf_pass_in, name, minutes.toInt()),
                        AppStrings.get(R.string.ntf_pass_details, time.format(Date(aos)), time.format(Date(los)), String.format(Locale.US, "%.0f", maxEl))
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_AOS = "aos"
        private const val EXTRA_LOS = "los"
        private const val EXTRA_MAX_EL = "max_el"

        private fun requestCode(name: String, aos: Long) = (name.hashCode() * 31 + (aos / 60_000L).toInt())

        fun schedule(context: Context, pass: UpcomingPass, triggerAtMs: Long) {
            val intent = Intent(context, SatellitePassReceiver::class.java).apply {
                putExtra(EXTRA_NAME, pass.satellite)
                putExtra(EXTRA_AOS, pass.aosMs)
                putExtra(EXTRA_LOS, pass.losMs)
                putExtra(EXTRA_MAX_EL, pass.maxElevation)
            }
            val pending = PendingIntent.getBroadcast(
                context, requestCode(pass.satellite, pass.aosMs), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarms = context.getSystemService(AlarmManager::class.java)
            // Dokładny alarm tylko za zgodą użytkownika (Android 12+); bez niej system może go opóźnić o kilka minut
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exactAllowed) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
            }
        }
    }
}
