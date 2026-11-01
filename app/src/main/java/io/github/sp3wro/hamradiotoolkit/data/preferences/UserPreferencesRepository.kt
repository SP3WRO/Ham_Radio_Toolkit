package io.github.sp3wro.hamradiotoolkit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.sp3wro.hamradiotoolkit.data.network.RepeaterSources
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import io.github.sp3wro.hamradiotoolkit.utils.Maidenhead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Utworzenie pojedynczej instancji DataStore dla całej aplikacji (właściwość rozszerzająca Context)
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

// Domyślne ustawienia APRS: -5 to SSID telefonu, "/[" to piechur
const val DEFAULT_APRS_SSID = "-5"
const val DEFAULT_APRS_ICON = "/["

class UserPreferencesRepository(private val dataStore: DataStore<Preferences>) {
    
    private val callsignKey = stringPreferencesKey("callsign")
    private val refreshIntervalKey = intPreferencesKey("refresh_interval")
    private val tropocastAreaKey = stringPreferencesKey("tropocast_area")
    private val activeProgramsKey = stringSetPreferencesKey("active_programs")

    private val appThemeKey = stringPreferencesKey("app_theme")

    private val aprsSsidKey = stringPreferencesKey("aprs_ssid")
    private val aprsIconKey = stringPreferencesKey("aprs_icon")
    private val aprsCommentKey = stringPreferencesKey("aprs_comment")
    private val aprsRadiusKey = intPreferencesKey("aprs_radius_km")

    private val repeaterRadiusKey = intPreferencesKey("repeater_radius_km")
    private val repeaterDataSourceKey = stringPreferencesKey("repeater_data_source")
    // Automatyczna zmiana źródła poza Polską: ostatni wybór krajowy i czy obecny ustawiła aplikacja
    private val repeaterSourceAutoKey = booleanPreferencesKey("repeater_source_auto")
    private val repeaterPolishSourceKey = stringPreferencesKey("repeater_polish_source")
    private val tropocastAreaAutoKey = booleanPreferencesKey("tropocast_area_auto")
    private val tropocastManualAreaKey = stringPreferencesKey("tropocast_manual_area")

    private val userLocatorKey = stringPreferencesKey("user_locator")
    private val lastLatKey = doublePreferencesKey("last_known_lat")
    private val lastLonKey = doublePreferencesKey("last_known_lon")

    private val notifyDxNewCountryKey = booleanPreferencesKey("notify_dx_new_country")
    private val notifySpaceWeatherKey = booleanPreferencesKey("notify_space_weather")
    private val locationRationaleShownKey = booleanPreferencesKey("location_rationale_shown")
    private val kpThresholdKey = intPreferencesKey("kp_threshold")
    private val satReminderMinutesKey = intPreferencesKey("sat_reminder_minutes")
    private val satReminderMinElevationKey = intPreferencesKey("sat_reminder_min_elevation")
    private val watchedSatellitesKey = stringSetPreferencesKey("watched_satellites")

    private val cwLessonKey = intPreferencesKey("cw_koch_lesson")
    private val cwCharWpmKey = intPreferencesKey("cw_char_wpm")
    private val cwEffectiveWpmKey = intPreferencesKey("cw_effective_wpm")
    private val cwToneHzKey = intPreferencesKey("cw_tone_hz")

    private val contestsShowRussianKey = booleanPreferencesKey("contests_show_russian_regional")
    private val followedAwardsKey = stringSetPreferencesKey("followed_awards")

    // Ręcznie wpisany lokator ma pierwszeństwo przed GPS (np. gdy telefon jest w domu, a stacja na działce)
    val userLocator: Flow<String> = dataStore.data.map { it[userLocatorKey] ?: "" }

    val stationPosition: Flow<LatLon?> = dataStore.data.map { preferences ->
        val locator = preferences[userLocatorKey].orEmpty()
        Maidenhead.toLatLon(locator) ?: run {
            val lat = preferences[lastLatKey]
            val lon = preferences[lastLonKey]
            if (lat != null && lon != null) LatLon(lat, lon) else null
        }
    }

    val notifyDxNewCountry: Flow<Boolean> = dataStore.data.map { it[notifyDxNewCountryKey] ?: false }
    val notifySpaceWeather: Flow<Boolean> = dataStore.data.map { it[notifySpaceWeatherKey] ?: false }
    val kpThreshold: Flow<Int> = dataStore.data.map { it[kpThresholdKey] ?: 5 }
    val satReminderMinutes: Flow<Int> = dataStore.data.map { it[satReminderMinutesKey] ?: 5 }
    val satReminderMinElevation: Flow<Int> = dataStore.data.map { it[satReminderMinElevationKey] ?: 10 }
    val watchedSatellites: Flow<Set<String>> = dataStore.data.map { it[watchedSatellitesKey] ?: emptySet() }

    val cwLesson: Flow<Int> = dataStore.data.map { it[cwLessonKey] ?: 1 }
    val cwCharWpm: Flow<Int> = dataStore.data.map { it[cwCharWpmKey] ?: 20 }
    val cwEffectiveWpm: Flow<Int> = dataStore.data.map { it[cwEffectiveWpmKey] ?: 12 }
    val cwToneHz: Flow<Int> = dataStore.data.map { it[cwToneHzKey] ?: 600 }

    /** Kalendarz zawodów: czy pokazywać rosyjskie zawody krajowe i regionalne z QRZ.RU. */
    val contestsShowRussianRegional: Flow<Boolean> = dataStore.data.map { it[contestsShowRussianKey] ?: false }

    suspend fun saveContestsShowRussianRegional(show: Boolean) {
        dataStore.edit { it[contestsShowRussianKey] = show }
    }

    /** Obserwowane akcje dyplomowe radiodyplom.pl (numery akcji) – ich stacje są oznaczane w DX Clusterze. */
    val followedAwards: Flow<Set<String>> = dataStore.data.map { it[followedAwardsKey] ?: emptySet() }

    suspend fun setAwardFollowed(eventId: String, followed: Boolean) {
        dataStore.edit {
            val current = it[followedAwardsKey] ?: emptySet()
            it[followedAwardsKey] = if (followed) current + eventId else current - eventId
        }
    }

    suspend fun saveUserLocator(locator: String) {
        dataStore.edit { it[userLocatorKey] = locator.trim().uppercase() }
    }

    /** Czy pokazano już wyjaśnienie, po co aplikacji lokalizacja (pytamy o nią tylko raz sami z siebie). */
    val locationRationaleShown: Flow<Boolean> = dataStore.data.map { it[locationRationaleShownKey] ?: false }

    suspend fun setLocationRationaleShown() {
        dataStore.edit { it[locationRationaleShownKey] = true }
    }

    suspend fun saveLastKnownPosition(lat: Double, lon: Double) {
        dataStore.edit {
            it[lastLatKey] = lat
            it[lastLonKey] = lon
        }
    }

    suspend fun saveNotifyDxNewCountry(enabled: Boolean) {
        dataStore.edit { it[notifyDxNewCountryKey] = enabled }
    }

    suspend fun saveNotifySpaceWeather(enabled: Boolean) {
        dataStore.edit { it[notifySpaceWeatherKey] = enabled }
    }

    suspend fun saveKpThreshold(kp: Int) {
        dataStore.edit { it[kpThresholdKey] = kp }
    }

    suspend fun saveSatReminder(minutesBefore: Int, minElevation: Int) {
        dataStore.edit {
            it[satReminderMinutesKey] = minutesBefore
            it[satReminderMinElevationKey] = minElevation
        }
    }

    suspend fun setSatelliteWatched(name: String, watched: Boolean) {
        dataStore.edit {
            val current = it[watchedSatellitesKey] ?: emptySet()
            it[watchedSatellitesKey] = if (watched) current + name else current - name
        }
    }

    suspend fun saveCwSettings(lesson: Int, charWpm: Int, effectiveWpm: Int, toneHz: Int) {
        dataStore.edit {
            it[cwLessonKey] = lesson
            it[cwCharWpmKey] = charWpm
            it[cwEffectiveWpmKey] = effectiveWpm
            it[cwToneHzKey] = toneHz
        }
    }

    val userCallsign: Flow<String> = dataStore.data.map { preferences ->
        preferences[callsignKey] ?: "NOCALL" // Domyślna wartość
    }

    val refreshInterval: Flow<Int> = dataStore.data.map { preferences ->
        preferences[refreshIntervalKey] ?: 0 // Domyślna wartość: 0 (ręcznie)
    }

    val tropocastArea: Flow<String> = dataStore.data.map { preferences ->
        preferences[tropocastAreaKey] ?: "poland" // Domyślna wartość
    }

    val activePrograms: Flow<Set<String>> = dataStore.data.map { preferences ->
        // "SPFF" z wcześniejszych wersji to obecnie WWFF na całym świecie
        (preferences[activeProgramsKey] ?: setOf("POTA", "SOTA", "WWFF")).map { if (it == "SPFF") "WWFF" else it }.toSet()
    }

    val appTheme: Flow<String> = dataStore.data.map { preferences ->
        preferences[appThemeKey] ?: "system"
    }

    val aprsSsid: Flow<String> = dataStore.data.map { preferences ->
        preferences[aprsSsidKey] ?: DEFAULT_APRS_SSID
    }

    val aprsIcon: Flow<String> = dataStore.data.map { preferences ->
        preferences[aprsIconKey] ?: DEFAULT_APRS_ICON
    }

    val aprsComment: Flow<String> = dataStore.data.map { preferences ->
        preferences[aprsCommentKey] ?: "HamRadioToolkit"
    }

    val aprsRadius: Flow<Int> = dataStore.data.map { preferences ->
        preferences[aprsRadiusKey] ?: 50
    }

    val repeaterRadius: Flow<Int> = dataStore.data.map { preferences ->
        preferences[repeaterRadiusKey] ?: 50
    }

    val repeaterDataSource: Flow<String> = dataStore.data.map { preferences ->
        when (val source = preferences[repeaterDataSourceKey]) {
            // Starsze wersje oferowały te źródła, ale w praktyce pobierały dane z baz polskich
            "repeaterbook" -> "przemienniki_net"
            "radioreference" -> "przemienniki_eu"
            // Źródło RepeaterBook usunięte z aplikacji
            "repeaterbook_api" -> "international"
            null -> "przemienniki_net"
            else -> source
        }
    }

    suspend fun saveCallsign(callsign: String) {
        dataStore.edit { preferences ->
            preferences[callsignKey] = callsign
        }
    }

    suspend fun saveRefreshInterval(interval: Int) {
        dataStore.edit { preferences ->
            preferences[refreshIntervalKey] = interval
        }
    }

    suspend fun saveTropocastArea(area: String) {
        dataStore.edit { preferences ->
            preferences[tropocastAreaKey] = area
            preferences[tropocastAreaAutoKey] = false
        }
    }

    suspend fun saveActivePrograms(programs: Set<String>) {
        dataStore.edit { preferences ->
            preferences[activeProgramsKey] = programs
        }
    }

    suspend fun saveAppTheme(theme: String) {
        dataStore.edit { preferences ->
            preferences[appThemeKey] = theme
        }
    }

    suspend fun saveAprsSettings(ssid: String, icon: String, comment: String) {
        dataStore.edit { preferences ->
            preferences[aprsSsidKey] = ssid
            preferences[aprsIconKey] = icon
            preferences[aprsCommentKey] = comment
        }
    }

    suspend fun saveAprsRadius(radius: Int) {
        dataStore.edit { preferences ->
            preferences[aprsRadiusKey] = radius
        }
    }

    suspend fun saveRepeaterRadius(radius: Int) {
        dataStore.edit { preferences ->
            preferences[repeaterRadiusKey] = radius
        }
    }

    suspend fun saveRepeaterDataSource(source: String) {
        dataStore.edit { preferences ->
            preferences[repeaterDataSourceKey] = source
            // Wybór ręczny – aplikacja nie zmieni go już sama
            preferences[repeaterSourceAutoKey] = false
        }
    }

    /**
     * Dopasowanie ustawień do kraju pobytu (wołane przy starcie aplikacji, gdy znamy pozycję):
     *  - za granicą polskie źródło przemienników zamieniane jest na światowe, a obszar Tropocast na kraj pobytu,
     *  - po powrocie do Polski przywracany jest poprzedni wybór – ale tylko, jeśli zmieniła go aplikacja.
     * Zwraca opisy zmian do pokazania użytkownikowi.
     */
    suspend fun applyCountryDefaults(isoCode: String): List<CountryDefaultChange> {
        val changes = ArrayList<CountryDefaultChange>()
        val inPoland = isoCode == "PL"
        dataStore.edit { p ->
            val source = p[repeaterDataSourceKey] ?: RepeaterSources.PRZEMIENNIKI_NET
            val sourceAuto = p[repeaterSourceAutoKey] ?: false
            if (!inPoland && RepeaterSources.isPolish(source)) {
                p[repeaterPolishSourceKey] = source
                p[repeaterDataSourceKey] = RepeaterSources.WORLD
                p[repeaterSourceAutoKey] = true
                changes += CountryDefaultChange.RepeaterSource(RepeaterSources.label(RepeaterSources.WORLD))
            } else if (inPoland && sourceAuto && !RepeaterSources.isPolish(source)) {
                val polish = p[repeaterPolishSourceKey] ?: RepeaterSources.PRZEMIENNIKI_NET
                p[repeaterDataSourceKey] = polish
                p[repeaterSourceAutoKey] = false
                changes += CountryDefaultChange.RepeaterSource(RepeaterSources.label(polish))
            }

            val area = p[tropocastAreaKey] ?: "poland"
            val areaAuto = p[tropocastAreaAutoKey] ?: false
            val target = TROPOCAST_AREAS[isoCode]
            if (!inPoland && target != null && target != area && (area == "poland" || areaAuto)) {
                if (!areaAuto) p[tropocastManualAreaKey] = area
                p[tropocastAreaKey] = target
                p[tropocastAreaAutoKey] = true
                changes += CountryDefaultChange.TropocastArea
            } else if (inPoland && areaAuto) {
                p[tropocastAreaKey] = p[tropocastManualAreaKey] ?: "poland"
                p[tropocastAreaAutoKey] = false
            }
        }
        return changes
    }

    private companion object {
        // Obszary map Tropocast (ICON-EU) – poza nimi zostaje ogólna mapa Europy
        val TROPOCAST_AREAS = mapOf(
            "GB" to "united-kingdom", "IE" to "united-kingdom", "DE" to "germany", "FR" to "france", "ES" to "spain",
            "PT" to "spain", "IT" to "italy", "CZ" to "czechia", "SK" to "czechia", "HR" to "croatia", "SI" to "croatia",
            "AT" to "europe", "CH" to "europe", "NL" to "europe", "BE" to "europe", "LU" to "europe", "DK" to "europe",
            "SE" to "europe", "NO" to "europe", "FI" to "europe", "LT" to "europe", "LV" to "europe", "EE" to "europe",
            "HU" to "europe", "RO" to "europe", "BG" to "europe", "GR" to "europe", "RS" to "europe", "BA" to "europe",
            "ME" to "europe", "MK" to "europe", "AL" to "europe", "UA" to "europe", "BY" to "europe", "MD" to "europe"
        )
    }
}

/** Zmiana ustawień po wykryciu kraju pobytu – opis składa UI w swoim języku. */
sealed class CountryDefaultChange {
    data class RepeaterSource(val label: String) : CountryDefaultChange()
    object TropocastArea : CountryDefaultChange()
}
