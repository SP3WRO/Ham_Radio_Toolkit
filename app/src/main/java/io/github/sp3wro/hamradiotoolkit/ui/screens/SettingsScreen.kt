package io.github.sp3wro.hamradiotoolkit.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.data.network.RepeaterSources
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsChoiceItem
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsDivider
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsSection
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsSliderItem
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsSwitchItem
import io.github.sp3wro.hamradiotoolkit.ui.components.SettingsTextItem
import io.github.sp3wro.hamradiotoolkit.utils.AppLanguage
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import kotlinx.coroutines.launch

private val APRS_SSID = Regex("^-?(\\d|1[0-5])$")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val appTheme by repository.appTheme.collectAsState(initial = "system")
    val callsign by repository.userCallsign.collectAsState(initial = "")
    val locator by repository.userLocator.collectAsState(initial = "")
    val refreshInterval by repository.refreshInterval.collectAsState(initial = 0)
    val tropocastArea by repository.tropocastArea.collectAsState(initial = "poland")
    val repeaterSource by repository.repeaterDataSource.collectAsState(initial = "przemienniki_net")
    val repeaterRadius by repository.repeaterRadius.collectAsState(initial = 50)
    val aprsSsid by repository.aprsSsid.collectAsState(initial = "-7")
    val aprsIcon by repository.aprsIcon.collectAsState(initial = "/>")
    val aprsComment by repository.aprsComment.collectAsState(initial = "")
    val aprsRadius by repository.aprsRadius.collectAsState(initial = 50)
    val activePrograms by repository.activePrograms.collectAsState(initial = emptySet())
    val language = remember { AppLanguage.current(context) }

    fun save(action: suspend () -> Unit) {
        scope.launch { action() }
    }

    val callsignInvalid = stringResource(R.string.settings_callsign_invalid)
    val locatorInvalid = stringResource(R.string.settings_locator_invalid)
    val ssidInvalid = stringResource(R.string.settings_aprs_ssid_invalid)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSection(stringResource(R.string.settings_language), Icons.Filled.Language) {
                val systemLabel = stringResource(R.string.settings_language_system)
                SettingsChoiceItem(
                    title = stringResource(R.string.settings_language),
                    options = AppLanguage.values().map { it to if (it == AppLanguage.SYSTEM) systemLabel else it.nativeName },
                    selected = language
                ) { chosen -> context.findActivity()?.let { AppLanguage.apply(it, chosen) } }
                Text(
                    stringResource(R.string.settings_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            SettingsSection(stringResource(R.string.settings_section_appearance), Icons.Filled.Palette) {
                SettingsChoiceItem(
                    title = stringResource(R.string.settings_theme),
                    options = listOf(
                        "system" to stringResource(R.string.settings_theme_system),
                        "light" to stringResource(R.string.settings_theme_light),
                        "dark" to stringResource(R.string.settings_theme_dark)
                    ),
                    selected = appTheme
                ) { save { repository.saveAppTheme(it) } }
            }

            SettingsSection(stringResource(R.string.settings_section_station), Icons.Filled.Person) {
                SettingsTextItem(
                    title = stringResource(R.string.settings_callsign),
                    value = callsign.takeIf { it != "NOCALL" }.orEmpty(),
                    emptySummary = stringResource(R.string.settings_callsign_empty),
                    placeholder = stringResource(R.string.settings_callsign_placeholder),
                    uppercase = true,
                    // Puste pole jest dozwolone: bez znaku aplikacja tylko odbiera
                    validate = { if (it.isBlank() || Callsigns.isValidAmateur(it)) null else callsignInvalid }
                ) { save { repository.saveCallsign(it.trim().uppercase()) } }
                SettingsDivider()
                SettingsTextItem(
                    title = stringResource(R.string.settings_locator),
                    value = locator,
                    emptySummary = stringResource(R.string.settings_locator_empty),
                    placeholder = stringResource(R.string.settings_locator_placeholder),
                    uppercase = true,
                    validate = { if (it.isEmpty() || Maidenhead.isValid(it)) null else locatorInvalid }
                ) { save { repository.saveUserLocator(it) } }
            }

            SettingsSection(stringResource(R.string.settings_section_dx), Icons.Filled.Public) {
                SettingsChoiceItem(
                    title = stringResource(R.string.settings_dx_refresh),
                    options = listOf(
                        0 to stringResource(R.string.settings_dx_refresh_manual),
                        1 to stringResource(R.string.settings_dx_refresh_1),
                        5 to stringResource(R.string.settings_dx_refresh_5)
                    ),
                    selected = refreshInterval
                ) { save { repository.saveRefreshInterval(it) } }
                SettingsDivider()
                SettingsChoiceItem(
                    title = stringResource(R.string.settings_tropocast_area),
                    options = listOf(
                        "europe" to stringResource(R.string.settings_area_europe),
                        "poland" to stringResource(R.string.settings_area_poland),
                        "united-kingdom" to stringResource(R.string.settings_area_uk),
                        "germany" to stringResource(R.string.settings_area_germany),
                        "france" to stringResource(R.string.settings_area_france),
                        "spain" to stringResource(R.string.settings_area_spain),
                        "italy" to stringResource(R.string.settings_area_italy),
                        "czechia" to stringResource(R.string.settings_area_czechia),
                        "croatia" to stringResource(R.string.settings_area_croatia)
                    ),
                    selected = tropocastArea
                ) { save { repository.saveTropocastArea(it) } }
            }

            SettingsSection(stringResource(R.string.settings_section_repeaters), Icons.Filled.CellTower) {
                SettingsChoiceItem(
                    title = stringResource(R.string.settings_repeater_source),
                    options = listOf(
                        RepeaterSources.PRZEMIENNIKI_NET to stringResource(R.string.settings_source_poland, "przemienniki.net"),
                        RepeaterSources.PRZEMIENNIKI_EU to stringResource(R.string.settings_source_poland, "przemienniki.eu"),
                        RepeaterSources.WORLD to stringResource(R.string.settings_source_world, "HearHam + repeatermap.de"),
                        RepeaterSources.HEARHAM to stringResource(R.string.settings_source_world, "HearHam"),
                        RepeaterSources.REPEATERMAP to stringResource(R.string.settings_source_europe, "repeatermap.de"),
                        RepeaterSources.REPEATERBOOK to stringResource(R.string.settings_source_token, "RepeaterBook")
                    ),
                    selected = repeaterSource
                ) { save { repository.saveRepeaterDataSource(it) } }
                Text(
                    stringResource(R.string.settings_repeater_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SettingsDivider()
                val kmFormat = stringResource(R.string.common_km)
                SettingsSliderItem(
                    title = stringResource(R.string.settings_search_radius),
                    value = repeaterRadius,
                    range = 10..200,
                    step = 10,
                    format = { kmFormat.format(it.toString()) }
                ) { save { repository.saveRepeaterRadius(it) } }
            }

            SettingsSection("APRS-IS", Icons.Filled.Radar) {
                SettingsTextItem(
                    title = "SSID",
                    value = aprsSsid,
                    placeholder = stringResource(R.string.settings_aprs_ssid_placeholder),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    validate = { if (it.isEmpty() || APRS_SSID.matches(it)) null else ssidInvalid }
                ) { ssid ->
                    val normalized = if (ssid.isEmpty() || ssid.startsWith("-")) ssid else "-$ssid"
                    save { repository.saveAprsSettings(normalized, aprsIcon, aprsComment) }
                }
                SettingsDivider()
                SettingsChoiceItem(
                    title = stringResource(R.string.settings_aprs_icon),
                    options = listOf(
                        "/>" to stringResource(R.string.settings_aprs_icon_car),
                        "/<" to stringResource(R.string.settings_aprs_icon_motorcycle),
                        "/v" to stringResource(R.string.settings_aprs_icon_van),
                        "/[" to stringResource(R.string.settings_aprs_icon_walker),
                        "/-" to stringResource(R.string.settings_aprs_icon_home),
                        "/;" to stringResource(R.string.settings_aprs_icon_tent)
                    ),
                    selected = aprsIcon
                ) { save { repository.saveAprsSettings(aprsSsid, it, aprsComment) } }
                SettingsDivider()
                SettingsTextItem(
                    title = stringResource(R.string.settings_aprs_comment),
                    value = aprsComment,
                    emptySummary = stringResource(R.string.settings_none)
                ) { save { repository.saveAprsSettings(aprsSsid, aprsIcon, it) } }
                SettingsDivider()
                val kmFormat = stringResource(R.string.common_km)
                SettingsSliderItem(
                    title = stringResource(R.string.settings_aprs_radius),
                    value = aprsRadius,
                    range = 10..200,
                    step = 10,
                    format = { kmFormat.format(it.toString()) }
                ) { save { repository.saveAprsRadius(it) } }
            }

            SettingsSection(stringResource(R.string.settings_section_programs), Icons.Filled.Hiking) {
                listOf(
                    "POTA" to "Parks on the Air",
                    "SOTA" to "Summits on the Air",
                    "WWFF" to stringResource(R.string.settings_wwff_desc)
                ).forEachIndexed { index, (program, description) ->
                    if (index > 0) SettingsDivider()
                    SettingsSwitchItem(
                        title = program,
                        summary = description,
                        checked = program in activePrograms
                    ) { checked ->
                        save { repository.saveActivePrograms(if (checked) activePrograms + program else activePrograms - program) }
                    }
                }
            }

            NotificationsSettings(repository)

            AccountsSettings()

            Spacer(Modifier.height(16.dp))
        }
    }
}
