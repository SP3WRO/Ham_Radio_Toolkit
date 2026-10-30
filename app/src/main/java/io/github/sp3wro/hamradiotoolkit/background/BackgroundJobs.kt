package io.github.sp3wro.hamradiotoolkit.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.widget.WidgetUpdateWorker
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Włącza/wyłącza zadania w tle zgodnie z ustawieniami – wyłączone nie zużywają baterii ani transferu. */
object BackgroundJobs {
    private const val SPACE_WEATHER = "space_weather"
    private const val DX_ALERTS = "dx_alerts"
    private const val SAT_PASSES = "satellite_passes"
    private const val SAT_PASSES_NOW = "satellite_passes_now"
    const val WIDGET = "widget_update"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    suspend fun sync(context: Context, prefs: UserPreferencesRepository) {
        val wm = WorkManager.getInstance(context)
        AppNotifications.createChannels(context)

        if (prefs.notifySpaceWeather.first()) {
            wm.enqueueUniquePeriodicWork(
                SPACE_WEATHER, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SpaceWeatherWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
            )
        } else {
            wm.cancelUniqueWork(SPACE_WEATHER)
        }

        if (prefs.notifyDxNewCountry.first()) {
            wm.enqueueUniquePeriodicWork(
                DX_ALERTS, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DxAlertWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            )
        } else {
            wm.cancelUniqueWork(DX_ALERTS)
        }

        if (prefs.watchedSatellites.first().isNotEmpty()) {
            wm.enqueueUniquePeriodicWork(
                SAT_PASSES, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SatellitePassWorker>(3, TimeUnit.HOURS).build()
            )
        } else {
            wm.cancelUniqueWork(SAT_PASSES)
        }
    }

    /** Natychmiastowe przeliczenie przypomnień – po zmianie listy obserwowanych satelitów. */
    fun refreshSatelliteReminders(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SAT_PASSES_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SatellitePassWorker>().build()
        )
    }

    fun scheduleWidget(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WIDGET, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetUpdateWorker>(30, TimeUnit.MINUTES).setConstraints(online).build()
        )
    }

    fun cancelWidget(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET)
    }
}
