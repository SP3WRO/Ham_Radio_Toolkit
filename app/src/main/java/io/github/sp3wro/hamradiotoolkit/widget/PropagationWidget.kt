package io.github.sp3wro.hamradiotoolkit.widget

import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sp3wro.hamradiotoolkit.MainActivity
import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.background.BackgroundJobs
import io.github.sp3wro.hamradiotoolkit.background.SatellitePasses
import io.github.sp3wro.hamradiotoolkit.background.TleStore
import io.github.sp3wro.hamradiotoolkit.data.model.BandCondition
import io.github.sp3wro.hamradiotoolkit.data.network.SolarApi
import io.github.sp3wro.hamradiotoolkit.data.network.SolarXmlParser
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import kotlinx.coroutines.flow.first
import uk.me.g4dpz.satellite.GroundStationPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PropagationWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        BackgroundJobs.scheduleWidget(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        BackgroundJobs.scheduleWidget(context)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget_update_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        )
    }

    override fun onDisabled(context: Context) {
        BackgroundJobs.cancelWidget(context)
    }
}

class WidgetUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, PropagationWidgetProvider::class.java))
        if (ids.isEmpty()) return Result.success()

        val views = RemoteViews(applicationContext.packageName, R.layout.widget_propagation)
        val openApp = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)

        val solarOk = try {
            val solar = SolarXmlParser.parse(SolarApi.create().getSolarDataRaw())
            views.setTextViewText(R.id.widget_indices, "SFI ${solar.solarFlux} • A ${solar.aIndex} • K ${solar.kIndex}")
            views.setTextViewText(R.id.widget_bands, formatBands(solar.hfConditions))
            true
        } catch (e: Exception) {
            Log.w("WidgetUpdateWorker", "Brak danych hamqsl.com", e)
            false
        }
        views.setTextViewText(R.id.widget_satellite, nextPassText())
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        views.setTextViewText(R.id.widget_updated, if (solarOk) AppStrings.get(R.string.wdg_updated, time) else AppStrings.get(R.string.wdg_offline, time))
        manager.updateAppWidget(ids, views)
        return Result.success()
    }

    // Najbliższy przelot spośród obserwowanych satelitów (a bez obserwowanych – ISS)
    private suspend fun nextPassText(): String {
        val prefs = UserPreferencesRepository(applicationContext.dataStore)
        val station = prefs.stationPosition.first() ?: return ""
        val watched = prefs.watchedSatellites.first()
        val tles = TleStore.load(applicationContext)
        val candidates = if (watched.isNotEmpty()) tles.filter { it.name in watched }
        else tles.filter { it.name.startsWith("ISS") }
        val now = System.currentTimeMillis()
        val site = GroundStationPosition(station.lat, station.lon, 100.0)
        val next = candidates.flatMap { SatellitePasses.upcoming(it, site, now, now + 24 * 3600_000L, 5.0).take(1) }
            .minByOrNull { it.aosMs } ?: return ""
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val prefix = if (next.aosMs <= now) AppStrings.get(R.string.wdg_now) else "AOS ${time.format(Date(next.aosMs))}"
        return AppStrings.get(R.string.wdg_sat_line, next.satellite, prefix, String.format(Locale.US, "%.0f", next.maxElevation))
    }

    companion object {
        private val CONDITIONS get() = mapOf("Good" to AppStrings.get(R.string.wdg_good), "Fair" to AppStrings.get(R.string.wdg_fair), "Poor" to AppStrings.get(R.string.wdg_poor))

        internal fun formatBands(conditions: List<BandCondition>): String {
            if (conditions.isEmpty()) return AppStrings.get(R.string.wdg_no_bands)
            return conditions.groupBy { it.name }.entries.joinToString("\n") { (band, items) ->
                val day = items.firstOrNull { it.time == "day" }?.condition
                val night = items.firstOrNull { it.time == "night" }?.condition
                AppStrings.get(R.string.wdg_band_line, band, CONDITIONS[day] ?: day ?: "–", CONDITIONS[night] ?: night ?: "–")
            }
        }
    }
}
