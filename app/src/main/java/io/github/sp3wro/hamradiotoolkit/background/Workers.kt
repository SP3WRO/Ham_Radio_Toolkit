package io.github.sp3wro.hamradiotoolkit.background

import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyRepository
import io.github.sp3wro.hamradiotoolkit.data.network.DxClusterApi
import io.github.sp3wro.hamradiotoolkit.data.network.HttpClients
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import io.github.sp3wro.hamradiotoolkit.data.repository.LogbookRepository
import io.github.sp3wro.hamradiotoolkit.data.model.bandForKhz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

// Stan powiadomień (co już zgłoszono), żeby nie powtarzać tych samych alertów
internal fun notificationState(context: Context) =
    context.getSharedPreferences("notification_state", Context.MODE_PRIVATE)

private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    HttpClients.default.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body?.string() ?: throw IOException(AppStrings.get(R.string.err_empty_response))
    }
}

/** Co godzinę: Kp powyżej progu i ostrzeżenia geomagnetyczne NOAA. */
class SpaceWeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext.dataStore)
        if (!prefs.notifySpaceWeather.first()) return Result.success()
        val threshold = prefs.kpThreshold.first()
        val state = notificationState(applicationContext)
        return try {
            SpaceWeather.latestKp(httpGet(SpaceWeather.KP_URL))?.let { reading ->
                if (reading.kp >= threshold && state.getString("last_kp_time", null) != reading.timeTag) {
                    AppNotifications.show(
                        applicationContext, AppNotifications.CHANNEL_SPACE_WEATHER, 3001,
                        AppStrings.get(R.string.ntf_storm), SpaceWeather.describe(reading.kp)
                    )
                    state.edit().putString("last_kp_time", reading.timeTag).apply()
                }
            }
            // Ostrzeżenia z ostatnich 12 h, każde zgłaszane raz
            val lastSeenMs = state.getLong("last_alert_ms", 0L)
            val since = maxOf(lastSeenMs + 1, System.currentTimeMillis() - 12 * 3600_000L)
            val alerts = SpaceWeather.geomagneticAlerts(httpGet(SpaceWeather.ALERTS_URL), since)
            alerts.lastOrNull()?.let { newest ->
                AppNotifications.show(
                    applicationContext, AppNotifications.CHANNEL_SPACE_WEATHER, 3002,
                    AppStrings.get(R.string.ntf_noaa), AppStrings.get(R.string.ntf_issued, newest.headline, newest.issued.take(16))
                )
                state.edit().putLong("last_alert_ms", newest.issuedMs).apply()
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Pogoda kosmiczna niedostępna", e)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "SpaceWeatherWorker"
    }
}

/** Co 15 minut: spoty DX Summit z krajów DXCC, których nie ma w logu. */
class DxAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext.dataStore)
        if (!prefs.notifyDxNewCountry.first()) return Result.success()
        val cty = CtyRepository.get(applicationContext) ?: return Result.retry()
        val logbook = LogbookRepository.get(applicationContext)
        val worked = withContext(Dispatchers.IO) { logbook.workedDxcc() }
        // Przy pustym logu każdy kraj byłby "nowy" – to nie ma sensu, czekamy na pierwsze QSO
        if (worked.isEmpty()) return Result.success()

        return try {
            val spots = DxClusterApi.create().getSpots(limit = 100)
            val state = notificationState(applicationContext)
            val now = System.currentTimeMillis()
            val fresh = mutableListOf<String>()
            val editor = state.edit()
            for (spot in spots) {
                val entity = cty.resolve(spot.dxCall) ?: continue
                if (entity.dxcc in worked) continue
                val khz = if (spot.frequency < 1000.0) spot.frequency * 1000.0 else spot.frequency
                val band = bandForKhz(khz) ?: continue
                // Ten sam kraj na tym samym paśmie zgłaszamy najwyżej raz na 3 godziny
                val key = "dx_${entity.dxcc}_$band"
                if (now - state.getLong(key, 0L) < 3 * 3600_000L) continue
                editor.putLong(key, now)
                fresh += "${entity.name}: ${spot.dxCall} ${String.format(java.util.Locale.US, "%.1f", khz)} kHz ($band)"
            }
            editor.apply()
            if (fresh.isNotEmpty()) {
                AppNotifications.show(
                    applicationContext, AppNotifications.CHANNEL_DX, 3101,
                    if (fresh.size == 1) AppStrings.get(R.string.ntf_new_country) else AppStrings.get(R.string.ntf_new_countries, fresh.size),
                    fresh.take(6).joinToString("\n")
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("DxAlertWorker", "DX Summit niedostępny", e)
            Result.retry()
        }
    }
}
