package io.github.sp3wro.hamradiotoolkit.background

import io.github.sp3wro.hamradiotoolkit.AppStrings
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sp3wro.hamradiotoolkit.MainActivity
import io.github.sp3wro.hamradiotoolkit.R

object AppNotifications {
    const val CHANNEL_SATELLITES = "satellite_passes"
    const val CHANNEL_DX = "dx_new_country"
    const val CHANNEL_SPACE_WEATHER = "space_weather"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SATELLITES, AppStrings.get(R.string.ch_sat), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = AppStrings.get(R.string.ch_sat_desc)
                },
                NotificationChannel(CHANNEL_DX, AppStrings.get(R.string.ch_dx), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = AppStrings.get(R.string.ch_dx_desc)
                },
                NotificationChannel(CHANNEL_SPACE_WEATHER, AppStrings.get(R.string.ch_space), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = AppStrings.get(R.string.ch_space_desc)
                }
            )
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_antenna)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Użytkownik cofnął zgodę na powiadomienia
        }
    }
}
