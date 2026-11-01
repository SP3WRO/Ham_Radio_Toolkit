package io.github.sp3wro.hamradiotoolkit

import android.content.Context
import android.os.Bundle
import io.github.sp3wro.hamradiotoolkit.utils.AppLanguage
import io.github.sp3wro.hamradiotoolkit.data.preferences.CountryDefaultChange
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.sp3wro.hamradiotoolkit.ui.navigation.Screen
import io.github.sp3wro.hamradiotoolkit.ui.screens.ActivationsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.AprsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.DxClusterScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.HomeScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.LoggerScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.PropagationScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.CalculatorsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.CoaxCalcScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.GrayLineScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.SatelliteScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.SstvDecoderScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.VademecumScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.ToolboxScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.AboutScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.SettingsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.BandplanScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.RepeatersScreen
import io.github.sp3wro.hamradiotoolkit.data.preferences.DEFAULT_APRS_ICON
import io.github.sp3wro.hamradiotoolkit.data.preferences.DEFAULT_APRS_SSID
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import io.github.sp3wro.hamradiotoolkit.data.preferences.dataStore
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sp3wro.hamradiotoolkit.ui.screens.AprsViewModel
import io.github.sp3wro.hamradiotoolkit.ui.screens.RepeatersViewModel
import io.github.sp3wro.hamradiotoolkit.ui.screens.LocatorScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.LiveSpotsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.BeaconsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.CwTrainerScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.CwDecoderScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.LogStatsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.Ft8DecoderScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.RttyDecoderScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.HorusDecoderScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.DsstvDecoderScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.ContestCalendarScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.AwardsScreen
import io.github.sp3wro.hamradiotoolkit.ui.screens.CallsignLookupScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import android.widget.Toast
import io.github.sp3wro.hamradiotoolkit.data.geo.CountryResolver
import io.github.sp3wro.hamradiotoolkit.data.geo.IaruRegion
import io.github.sp3wro.hamradiotoolkit.ui.screens.AbroadScreen
import io.github.sp3wro.hamradiotoolkit.background.BackgroundJobs
import io.github.sp3wro.hamradiotoolkit.ui.hasLocationPermission
import io.github.sp3wro.hamradiotoolkit.ui.rememberForegroundLocation
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import io.github.sp3wro.hamradiotoolkit.ui.theme.HamRadioToolkitTheme
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices

class MainActivity : ComponentActivity() {
    // DataStore jest singletonem na poziomie Context, więc repozytorium wystarczy utworzyć raz na aktywność
    private val repository by lazy { UserPreferencesRepository(applicationContext.dataStore) }

    // Język wybrany w Ustawieniach (Android poniżej 13; od 13 ustawia go system)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appTheme by repository.appTheme.collectAsState(initial = "system")
            val systemTheme = isSystemInDarkTheme()
            val useDarkTheme = when (appTheme) {
                "light" -> false
                "dark" -> true
                else -> systemTheme
            }

            HamRadioToolkitTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HamRadioToolkitApp(repository)
                }
            }
        }
    }
}

private data class BottomTab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(Screen.Home.route, R.string.tab_home, Icons.Filled.Home),
    BottomTab(Screen.Propagation.route, R.string.tab_propagation, Icons.Filled.NetworkCell),
    BottomTab(Screen.DxCluster.route, R.string.tab_dx_cluster, Icons.AutoMirrored.Filled.List),
    BottomTab(Screen.Logger.route, R.string.tab_logger, Icons.Filled.Book),
    BottomTab(Screen.Toolbox.route, R.string.tab_toolbox, Icons.Filled.Handyman)
)

// Ekrany otwierane z Przybornika – na dolnym pasku podświetlają zakładkę "Przybornik"
private val toolboxSubRoutes = setOf(
    Screen.Calculators.route,
    Screen.CoaxCalc.route,
    Screen.Bandplan.route,
    Screen.Repeaters.route,
    Screen.Activations.route,
    Screen.Aprs.route,
    Screen.About.route,
    Screen.GrayLineCalc.route,
    Screen.Satellites.route,
    Screen.SstvDecoder.route,
    Screen.Locator.route,
    Screen.LiveSpots.route,
    Screen.Beacons.route,
    Screen.CwTrainer.route,
    Screen.CwDecoder.route,
    Screen.Ft8Decoder.route,
    Screen.RttyDecoder.route,
    Screen.HorusDecoder.route,
    Screen.DsstvDecoder.route,
    Screen.ContestCalendar.route,
    Screen.Awards.route,
    Screen.CallsignLookup.route,
    Screen.Abroad.route
)

// Ekrany, na których GPS pracuje w trybie najwyższej dokładności (reszta – tryb zbalansowany)
private val preciseLocationRoutes = setOf(Screen.Home.route, Screen.Aprs.route, Screen.Satellites.route, Screen.Locator.route)

// Ekrany otwierane z Loggera
private val loggerSubRoutes = setOf(Screen.LogStats.route)

private fun isSubRouteOf(tab: BottomTab, route: String?): Boolean = route != null && when (tab.route) {
    Screen.Toolbox.route -> route in toolboxSubRoutes || route.startsWith("vademecum")
    Screen.Logger.route -> route in loggerSubRoutes
    else -> false
}

private fun belongsToTab(route: String?, tab: BottomTab): Boolean =
    route == tab.route || isSubRouteOf(tab, route)

private fun NavHostController.openTab(tab: BottomTab, currentRoute: String?) {
    if (tab.route == Screen.Home.route) {
        // Home jest zawsze na dnie stosu – zwykłe cofnięcie. navigate() z restoreState mógłby
        // przywrócić zapisany stos (np. APRS otwarty z Home) zamiast pokazać Home.
        popBackStack(Screen.Home.route, inclusive = false)
        return
    }
    if (currentRoute == Screen.Settings.route) {
        popBackStack(Screen.Home.route, inclusive = false)
    }
    // Jesteśmy w pod-okienku zakładki -> wracamy do jej głównego widoku. Jeśli pod-okienko otwarto
    // spoza zakładki (np. APRS z Home), widoku zakładki nie ma na stosie – wtedy zwykła nawigacja.
    if (isSubRouteOf(tab, currentRoute) && popBackStack(tab.route, inclusive = false)) return
    navigate(tab.route) {
        popUpTo(Screen.Home.route) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun HamRadioToolkitApp(repository: UserPreferencesRepository) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val aprsViewModel: AprsViewModel = viewModel()
    // Jedna baza przemienników dla strony głównej i ekranu Przemienniki (wcześniej pobierana dwa razy)
    val repeatersViewModel: RepeatersViewModel = viewModel()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Obserwacja znaku i ustawień
    val callsign by repository.userCallsign.collectAsState(initial = "NOCALL")
    val refreshInterval by repository.refreshInterval.collectAsState(initial = 0)
    val tropocastArea by repository.tropocastArea.collectAsState(initial = "poland")
    val activePrograms by repository.activePrograms.collectAsState(initial = setOf("POTA", "SOTA", "WWFF", "PGA", "ZAMEK"))
    val aprsSsid by repository.aprsSsid.collectAsState(initial = DEFAULT_APRS_SSID)
    val aprsIcon by repository.aprsIcon.collectAsState(initial = DEFAULT_APRS_ICON)
    val aprsComment by repository.aprsComment.collectAsState(initial = "HamRadioToolkit")
    val aprsRadius by repository.aprsRadius.collectAsState(initial = 50)
    val repeaterRadius by repository.repeaterRadius.collectAsState(initial = 50)
    val repeaterDataSource by repository.repeaterDataSource.collectAsState(initial = "przemienniki_net")
    val stationPosition by repository.stationPosition.collectAsState(initial = null)

    val context = LocalContext.current
    val res = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Uprawnienie do lokalizacji pytane raz, przy starcie aplikacji (a nie przez pojedyncze ekrany)
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> locationGranted = result.values.any { it } }
    val requestLocationPermission = {
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Przy pierwszym uruchomieniu najpierw wyjaśnienie, po co lokalizacja – dopiero potem systemowe pytanie.
    // Później już nie pytamy sami; zgodę można dać z karty "Lokator QTH" na stronie głównej.
    var showLocationRationale by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        BackgroundJobs.sync(context.applicationContext, repository)
        if (!locationGranted && !repository.locationRationaleShown.first()) showLocationRationale = true
    }
    if (showLocationRationale) {
        val dismissRationale = { granted: Boolean ->
            showLocationRationale = false
            coroutineScope.launch { repository.setLocationRationaleShown() }
            if (granted) requestLocationPermission()
        }
        AlertDialog(
            onDismissRequest = { dismissRationale(false) },
            title = { Text(stringResource(R.string.location_rationale_title)) },
            text = { Text(stringResource(R.string.location_rationale_text)) },
            confirmButton = { TextButton(onClick = { dismissRationale(true) }) { Text(stringResource(R.string.common_next)) } },
            dismissButton = { TextButton(onClick = { dismissRationale(false) }) { Text(stringResource(R.string.common_not_now)) } }
        )
    }

    // Bieżąca pozycja (GPS działa tylko, gdy aplikacja jest na ekranie). Pełna dokładność tylko tam,
    // gdzie jest potrzebna: wysokość i lokator na stronie głównej, nadawanie pozycji APRS, satelity.
    val preciseLocation = currentRoute == null || currentRoute in preciseLocationRoutes
    val liveFix by rememberForegroundLocation(locationGranted, preciseLocation)
    val livePosition = liveFix?.position

    // Zapis ostatniej pozycji (co ≥ 200 m) – dla powiadomień w tle i ekranów korzystających z QTH
    var lastSavedPosition by remember { mutableStateOf<LatLon?>(null) }
    LaunchedEffect(livePosition) {
        val position = livePosition ?: return@LaunchedEffect
        val last = lastSavedPosition
        if (last == null || GreatCircle.distanceKm(last, position) > 0.2) {
            lastSavedPosition = position
            repository.saveLastKnownPosition(position.lat, position.lon)
        }
    }

    // APRS-IS: nasłuch przez cały czas, gdy aplikacja jest na ekranie – niezależnie od otwartej karty.
    // Znak i promień brane dopiero po wczytaniu ustawień, żeby nie łączyć się najpierw jako NOCALL.
    val aprsSettings by produceState<Pair<String, Int>?>(initialValue = null) {
        combine(repository.userCallsign, repository.aprsRadius) { call, radius -> call to radius }
            .collect { value = it }
    }
    LaunchedEffect(livePosition, aprsSettings) {
        val position = livePosition ?: return@LaunchedEffect
        val (aprsCall, radiusKm) = aprsSettings ?: return@LaunchedEffect
        aprsViewModel.updateStation(aprsCall, position, radiusKm)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> aprsViewModel.setActive(true)
                Lifecycle.Event.ON_STOP -> aprsViewModel.setActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Kraj pobytu: z GPS (albo zapisanej pozycji). Przy pierwszym ustaleniu po starcie aplikacji
    // dopasowujemy źródło przemienników i mapę Tropocast (za granicą – zagraniczne, w kraju – poprzednie)
    var country by remember { mutableStateOf(CountryResolver.cached()) }
    var countryDefaultsApplied by remember { mutableStateOf(false) }
    val countryPosition = livePosition ?: stationPosition
    val countryKey = countryPosition?.let { Math.round(it.lat * 4) to Math.round(it.lon * 4) } // ~25 km
    LaunchedEffect(countryKey) {
        val pos = countryPosition ?: return@LaunchedEffect
        val resolved = CountryResolver.resolve(context, pos) ?: return@LaunchedEffect
        country = resolved
        if (!countryDefaultsApplied) {
            countryDefaultsApplied = true
            val changes = repository.applyCountryDefaults(resolved.isoCode)
            if (changes.isNotEmpty()) {
                val where = if (resolved.isPoland) res.getString(R.string.country_welcome_poland)
                else res.getString(R.string.country_you_are_in, resolved.namePl)
                val what = changes.joinToString("; ") { change ->
                    when (change) {
                        is CountryDefaultChange.RepeaterSource -> res.getString(R.string.country_change_repeaters, change.label)
                        CountryDefaultChange.TropocastArea -> res.getString(R.string.country_change_tropocast)
                    }
                }
                Toast.makeText(context, res.getString(R.string.country_changes_toast, where, what), Toast.LENGTH_LONG).show()
            }
        }
    }
    val region = country?.let { IaruRegion.forCountry(it.isoCode) } ?: countryPosition?.let { IaruRegion.forPosition(it) }

    val openSettings = {
        navController.navigate(Screen.Settings.route) {
            launchSingleTop = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomTabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                        label = { Text(stringResource(tab.label)) },
                        selected = currentDestination?.hierarchy?.any { belongsToTab(it.route, tab) } == true,
                        onClick = { navController.openTab(tab, currentDestination?.route) },
                        // Zaznaczenie w kolorze głównym (domyślnie Material bierze tu kolor akcentu)
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    callsign = callsign,
                    position = livePosition,
                    altitude = liveFix?.altitude,
                    locationGranted = locationGranted,
                    onRequestLocation = requestLocationPermission,
                    repeaterDataSource = repeaterDataSource,
                    aprsRadius = aprsRadius,
                    onSettingsClick = openSettings,
                    onAprsClick = { navController.navigate(Screen.Aprs.route) },
                    country = country,
                    onAbroadClick = { navController.navigate(Screen.Abroad.route) },
                    onRepeatersClick = { navController.navigate(Screen.Repeaters.route) },
                    repeatersViewModel = repeatersViewModel,
                    aprsViewModel = aprsViewModel
                )
            }
            composable(Screen.Propagation.route) {
                PropagationScreen(
                    tropocastArea = tropocastArea,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Activations.route) {
                ActivationsScreen(
                    activePrograms = activePrograms,
                    position = livePosition ?: stationPosition,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.DxCluster.route) {
                DxClusterScreen(
                    spotterCallsign = callsign,
                    refreshInterval = refreshInterval,
                    stationPosition = stationPosition,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Logger.route) {
                LoggerScreen(
                    stationCallsign = callsign,
                    stationPosition = stationPosition,
                    onSettingsClick = openSettings,
                    onStatsClick = { navController.navigate(Screen.LogStats.route) }
                )
            }
            composable(Screen.LogStats.route) {
                LogStatsScreen(onSettingsClick = openSettings)
            }
            composable(Screen.Locator.route) {
                LocatorScreen(repository = repository, onSettingsClick = openSettings)
            }
            composable(Screen.LiveSpots.route) {
                LiveSpotsScreen(stationPosition = stationPosition, onSettingsClick = openSettings)
            }
            composable(Screen.Beacons.route) {
                BeaconsScreen(stationPosition = stationPosition, onSettingsClick = openSettings)
            }
            composable(Screen.CwTrainer.route) {
                CwTrainerScreen(repository = repository, onSettingsClick = openSettings)
            }
            composable(Screen.CwDecoder.route) {
                CwDecoderScreen(onSettingsClick = openSettings)
            }
            composable(Screen.Ft8Decoder.route) {
                Ft8DecoderScreen(
                    onCallsignClick = { call -> navController.navigate(Screen.CallsignLookup.createRoute(call)) },
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.RttyDecoder.route) {
                RttyDecoderScreen(onSettingsClick = openSettings)
            }
            composable(Screen.HorusDecoder.route) {
                HorusDecoderScreen(stationPosition = stationPosition, onSettingsClick = openSettings)
            }
            composable(Screen.DsstvDecoder.route) {
                DsstvDecoderScreen(onSettingsClick = openSettings)
            }
            composable(Screen.ContestCalendar.route) {
                ContestCalendarScreen(countryIso = country?.isoCode, onSettingsClick = openSettings)
            }
            composable(Screen.Awards.route) {
                AwardsScreen()
            }
            composable(
                Screen.CallsignLookup.route,
                arguments = listOf(navArgument("call") { type = NavType.StringType; defaultValue = "" })
            ) { backStackEntry ->
                CallsignLookupScreen(
                    initialCall = backStackEntry.arguments?.getString("call").orEmpty(),
                    stationPosition = stationPosition,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Aprs.route) {
                AprsScreen(
                    callsign = callsign,
                    ssid = aprsSsid,
                    icon = aprsIcon,
                    comment = aprsComment,
                    aprsRadiusKm = aprsRadius,
                    position = livePosition,
                    onSettingsClick = openSettings,
                    viewModel = aprsViewModel
                )
            }
            composable(Screen.Bandplan.route) {
                BandplanScreen(
                    detectedRegion = region,
                    countryIso = country?.isoCode,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Abroad.route) {
                AbroadScreen(
                    callsign = callsign,
                    detectedCountry = country,
                    onOpenRepeaters = { navController.navigate(Screen.Repeaters.route) },
                    onOpenBandplan = { navController.navigate(Screen.Bandplan.route) },
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Repeaters.route) {
                RepeatersScreen(
                    repeaterRadiusKm = repeaterRadius,
                    repeaterDataSource = repeaterDataSource,
                    position = livePosition ?: stationPosition,
                    onSettingsClick = openSettings,
                    viewModel = repeatersViewModel
                )
            }
            composable(Screen.Toolbox.route) {
                ToolboxScreen(
                    navController = navController,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Calculators.route) {
                CalculatorsScreen(
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.CoaxCalc.route) {
                CoaxCalcScreen(
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.GrayLineCalc.route) {
                GrayLineScreen(
                    position = livePosition ?: stationPosition,
                    onRequestLocation = requestLocationPermission,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Satellites.route) {
                SatelliteScreen(
                    position = livePosition ?: stationPosition,
                    altitudeM = liveFix?.altitude?.meters,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.SstvDecoder.route) {
                SstvDecoderScreen(
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Vademecum.route) { backStackEntry ->
                val topic = backStackEntry.arguments?.getString("topic") ?: "dxcc"
                VademecumScreen(
                    initialTopic = topic,
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.About.route) {
                AboutScreen(
                    onSettingsClick = openSettings
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    repository = repository,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
