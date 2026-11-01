package io.github.sp3wro.hamradiotoolkit.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.sp3wro.hamradiotoolkit.background.AppNotifications
import io.github.sp3wro.hamradiotoolkit.background.BackgroundJobs
import io.github.sp3wro.hamradiotoolkit.data.preferences.CredentialsRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.ServiceCredentials
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsChoiceItem
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsDivider
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsItem
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsSection
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsSwitchItem
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsTextItem
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import io.github.sp3wro.hamradiotoolkit.R

@Composable
fun NotificationsSettings(repository: UserPreferencesRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val spaceWeather by repository.notifySpaceWeather.collectAsState(initial = false)
    val dxNewCountry by repository.notifyDxNewCountry.collectAsState(initial = false)
    val kpThreshold by repository.kpThreshold.collectAsState(initial = 5)
    val reminderMinutes by repository.satReminderMinutes.collectAsState(initial = 5)
    val minElevation by repository.satReminderMinElevation.collectAsState(initial = 10)
    val watched by repository.watchedSatellites.collectAsState(initial = emptySet())

    // Akcja czekająca na zgodę na powiadomienia (Android 13+)
    var pendingAction by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted && action != null) scope.launch { action() }
    }

    fun update(enable: Boolean, action: suspend () -> Unit) {
        val wrapped: suspend () -> Unit = {
            action()
            BackgroundJobs.sync(context.applicationContext, repository)
        }
        if (enable && !AppNotifications.canNotify(context)) {
            pendingAction = wrapped
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch { wrapped() }
        }
    }

    fun refreshReminders(action: suspend () -> Unit) {
        scope.launch {
            action()
            BackgroundJobs.refreshSatelliteReminders(context.applicationContext)
        }
    }

    SettingsSection(stringResource(R.string.settings_section_notifications), Icons.Filled.Notifications) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_storms),
            summary = stringResource(R.string.settings_storms_desc),
            checked = spaceWeather
        ) { enable -> update(enable) { repository.saveNotifySpaceWeather(enable) } }
        if (spaceWeather) {
            SettingsDivider()
            SettingsChoiceItem(
                title = stringResource(R.string.settings_kp_threshold),
                options = listOf(4 to stringResource(R.string.settings_kp_4), 5 to stringResource(R.string.settings_kp_5), 6 to "Kp 6 (G2)", 7 to "Kp 7 (G3)", 8 to "Kp 8 (G4)"),
                selected = kpThreshold
            ) { scope.launch { repository.saveKpThreshold(it) } }
        }
        SettingsDivider()
        SettingsSwitchItem(
            title = stringResource(R.string.settings_new_countries),
            summary = stringResource(R.string.settings_new_countries_desc),
            checked = dxNewCountry
        ) { enable -> update(enable) { repository.saveNotifyDxNewCountry(enable) } }
        SettingsDivider()
        SettingsItem(
            title = stringResource(R.string.settings_watched_sats),
            summary = if (watched.isEmpty()) stringResource(R.string.settings_watched_sats_empty)
            else watched.sorted().joinToString(", ")
        )
        SettingsDivider()
        SettingsChoiceItem(
            title = stringResource(R.string.settings_pass_reminder),
            options = listOf(2, 5, 10, 15).map { it to stringResource(R.string.settings_minutes_before_aos, it) },
            selected = reminderMinutes
        ) { refreshReminders { repository.saveSatReminder(it, minElevation) } }
        SettingsDivider()
        SettingsChoiceItem(
            title = stringResource(R.string.settings_min_elevation),
            options = listOf(0, 10, 20, 30).map { it to stringResource(R.string.settings_elevation_value, it) },
            selected = minElevation
        ) { refreshReminders { repository.saveSatReminder(reminderMinutes, it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_exact_alarms),
                summary = stringResource(R.string.settings_exact_alarms_desc),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                    )
                }
            )
        }
        SettingsDivider()
        SettingsItem(
            title = stringResource(R.string.settings_widget),
            summary = stringResource(R.string.settings_widget_desc)
        )
    }
}

@Composable
fun AccountsSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CredentialsRepository(context) }
    val credentials by repository.credentials.collectAsState(initial = ServiceCredentials())

    fun update(change: (ServiceCredentials) -> ServiceCredentials) {
        scope.launch { repository.save(change(repository.current())) }
    }

    SettingsSection(stringResource(R.string.settings_section_accounts), Icons.Filled.Key) {
        SettingsItem(
            title = stringResource(R.string.settings_security),
            summary = stringResource(R.string.settings_security_desc)
        )
        SettingsDivider()
        SettingsTextItem(title = stringResource(R.string.settings_login, "HamQTH"), value = credentials.hamQthUser,
            emptySummary = stringResource(R.string.settings_hamqth_desc)) { v -> update { it.copy(hamQthUser = v) } }
        SettingsDivider()
        SettingsTextItem(title = stringResource(R.string.settings_password, "HamQTH"), value = credentials.hamQthPassword, isSecret = true) { v ->
            update { it.copy(hamQthPassword = v) }
        }
        SettingsDivider()
        SettingsTextItem(title = stringResource(R.string.settings_login, "QRZCQ"), value = credentials.qrzcqUser,
            emptySummary = stringResource(R.string.settings_qrzcq_desc)) { v -> update { it.copy(qrzcqUser = v) } }
        SettingsDivider()
        SettingsTextItem(title = stringResource(R.string.settings_password, "QRZCQ"), value = credentials.qrzcqPassword, isSecret = true) { v ->
            update { it.copy(qrzcqPassword = v) }
        }
        SettingsDivider()
        SettingsTextItem(title = stringResource(R.string.settings_qrz_key), value = credentials.qrzLogbookKey, isSecret = true,
            emptySummary = stringResource(R.string.settings_qrz_key_desc)) { v -> update { it.copy(qrzLogbookKey = v) } }
        SettingsDivider()
        SettingsTextItem(title = stringResource(R.string.settings_login, "eQSL"), value = credentials.eqslUser, uppercase = true) { v ->
            update { it.copy(eqslUser = v) }
        }
        SettingsDivider()
        SettingsTextItem(title = stringResource(R.string.settings_password, "eQSL"), value = credentials.eqslPassword, isSecret = true) { v ->
            update { it.copy(eqslPassword = v) }
        }
    }
}
