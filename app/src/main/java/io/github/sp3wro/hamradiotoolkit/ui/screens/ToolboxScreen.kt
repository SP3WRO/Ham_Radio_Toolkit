package io.github.sp3wro.hamradiotoolkit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.ui.navigation.Screen

data class ToolboxItem(
    @StringRes val title: Int,
    val icon: ImageVector,
    val route: String
)

private data class ToolboxGroup(@StringRes val title: Int, val items: List<ToolboxItem>)

private val toolboxGroups = listOf(
    ToolboxGroup(
        R.string.toolbox_group_on_air, listOf(
            ToolboxItem(R.string.tool_callsign_lookup, Icons.Filled.PersonSearch, Screen.CallsignLookup.createRoute()),
            ToolboxItem(R.string.tool_live_spots, Icons.Filled.Park, Screen.LiveSpots.route),
            ToolboxItem(R.string.tool_aprs, Icons.Filled.Radar, Screen.Aprs.route),
            ToolboxItem(R.string.tool_repeaters, Icons.Filled.CellTower, Screen.Repeaters.route),
            ToolboxItem(R.string.tool_satellites, Icons.Filled.SatelliteAlt, Screen.Satellites.route),
            ToolboxItem(R.string.tool_beacons, Icons.Filled.WifiTethering, Screen.Beacons.route)
        )
    ),
    ToolboxGroup(
        R.string.toolbox_group_digital, listOf(
            ToolboxItem(R.string.tool_ft8, Icons.Filled.Waves, Screen.Ft8Decoder.route),
            ToolboxItem(R.string.tool_rtty, Icons.Filled.Keyboard, Screen.RttyDecoder.route),
            ToolboxItem(R.string.tool_horus, Icons.Filled.Air, Screen.HorusDecoder.route),
            ToolboxItem(R.string.tool_sstv, Icons.Filled.ImageSearch, Screen.SstvDecoder.route),
            ToolboxItem(R.string.tool_dsstv, Icons.Filled.Image, Screen.DsstvDecoder.route),
            ToolboxItem(R.string.tool_cw_decoder, Icons.Filled.GraphicEq, Screen.CwDecoder.route),
            ToolboxItem(R.string.tool_cw_trainer, Icons.Filled.School, Screen.CwTrainer.route)
        )
    ),
    ToolboxGroup(
        R.string.toolbox_group_planning, listOf(
            ToolboxItem(R.string.tool_contests, Icons.Filled.EmojiEvents, Screen.ContestCalendar.route),
            ToolboxItem(R.string.tool_awards, Icons.Filled.WorkspacePremium, Screen.Awards.route),
            ToolboxItem(R.string.tool_abroad, Icons.Filled.Flight, Screen.Abroad.route),
            ToolboxItem(R.string.tool_locator, Icons.Filled.Explore, Screen.Locator.route),
            ToolboxItem(R.string.tool_grayline, Icons.Filled.WbSunny, Screen.GrayLineCalc.route),
            ToolboxItem(R.string.tool_bandplan, Icons.Filled.TableChart, Screen.Bandplan.route),
            ToolboxItem(R.string.tool_activations, Icons.Filled.LocationOn, Screen.Activations.route)
        )
    ),
    ToolboxGroup(
        R.string.toolbox_group_calculators, listOf(
            ToolboxItem(R.string.tool_calculators, Icons.Filled.Calculate, Screen.Calculators.route),
            ToolboxItem(R.string.tool_coax, Icons.Filled.Cable, Screen.CoaxCalc.route)
        )
    ),
    ToolboxGroup(
        R.string.toolbox_group_cheatsheets, listOf(
            ToolboxItem(R.string.tool_dxcc, Icons.Filled.Public, Screen.Vademecum.createRoute("dxcc")),
            ToolboxItem(R.string.tool_qcodes, Icons.AutoMirrored.Filled.MenuBook, Screen.Vademecum.createRoute("qcodes")),
            ToolboxItem(R.string.tool_rst, Icons.Filled.SignalCellularAlt, Screen.Vademecum.createRoute("rst")),
            ToolboxItem(R.string.tool_sp_districts, Icons.Filled.Map, Screen.Vademecum.createRoute("sp")),
            ToolboxItem(R.string.tool_slang, Icons.Filled.Forum, Screen.Vademecum.createRoute("slang")),
            ToolboxItem(R.string.tool_morse, Icons.Filled.MoreHoriz, Screen.Vademecum.createRoute("morse")),
            ToolboxItem(R.string.tool_phonetic, Icons.Filled.Translate, Screen.Vademecum.createRoute("phonetic"))
        )
    ),
    ToolboxGroup(
        R.string.toolbox_group_app, listOf(
            ToolboxItem(R.string.tool_about, Icons.Filled.Info, Screen.About.route)
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxScreen(
    navController: NavController,
    onSettingsClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.toolbox_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            toolboxGroups.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header_${group.title}") {
                    Text(
                        stringResource(group.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(group.items, key = { it.route }) { item ->
                    ToolboxTile(item) { navController.navigate(item.route) }
                }
            }
        }
    }
}

@Composable
private fun ToolboxTile(item: ToolboxItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(item.title),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
