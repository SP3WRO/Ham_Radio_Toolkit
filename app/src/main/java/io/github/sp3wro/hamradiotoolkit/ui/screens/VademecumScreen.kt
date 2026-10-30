package io.github.sp3wro.hamradiotoolkit.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.data.vademecum.VademecumData

val morseAlphabet = listOf(
    "A" to ".-", "B" to "-...", "C" to "-.-.", "D" to "-..", "E" to ".",
    "F" to "..-.", "G" to "--.", "H" to "....", "I" to "..", "J" to ".---",
    "K" to "-.-", "L" to ".-..", "M" to "--", "N" to "-.", "O" to "---",
    "P" to ".--.", "Q" to "--.-", "R" to ".-.", "S" to "...", "T" to "-",
    "U" to "..-", "V" to "...-", "W" to ".--", "X" to "-..-", "Y" to "-.--",
    "Z" to "--..", "1" to ".----", "2" to "..---", "3" to "...--", "4" to "....-",
    "5" to ".....", "6" to "-....", "7" to "--...", "8" to "---..", "9" to "----.",
    "0" to "-----"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VademecumScreen(
    initialTopic: String,
    onSettingsClick: () -> Unit = {}
) {
    val tabs = listOf(
        "DXCC", stringResource(R.string.vad_tab_qcodes), stringResource(R.string.vad_tab_reports), stringResource(R.string.vad_tab_areas),
        stringResource(R.string.vad_tab_slang), stringResource(R.string.vad_tab_morse), stringResource(R.string.vad_tab_phonetic)
    )
    val appContext = LocalContext.current.applicationContext
    val data = remember { VademecumData.load(appContext) }
    
    val initialPage = when(initialTopic) {
        "dxcc" -> 0
        "qcodes" -> 1
        "rst" -> 2
        "sp" -> 3
        "slang" -> 4
        "morse" -> 5
        "phonetic" -> 6
        else -> 0
    }

    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vad_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(title) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> DxccView(data.dxcc)
                    1 -> SimpleListView(data.qCodes)
                    2 -> RstView()
                    3 -> SimpleListView(data.spAreas)
                    4 -> SimpleListView(data.slang)
                    5 -> MorseListView(morseAlphabet)
                    6 -> SimpleListView(data.phonetic)
                }
            }
        }
    }
}

@Composable
fun DxccView(dxccDatabase: List<io.github.sp3wro.hamradiotoolkit.data.vademecum.DxccItem>) {
    var searchQuery by remember { mutableStateOf("") }
    
    val filtered = dxccDatabase.filter { 
        it.prefix.contains(searchQuery, ignoreCase = true) || 
        it.entity.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(stringResource(R.string.vad_search_dxcc)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true
        )
        
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { item ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(item.entity, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.vad_continent, item.continent), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(item.prefix, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleListView(data: List<Pair<String, String>>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(data) { (key, value) ->
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(key, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.3f))
                    Text(value, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun MorseListView(data: List<Pair<String, String>>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(data) { (key, value) ->
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(key, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.3f))
                    Text(value, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                }
            }
        }
    }
}

@Composable
fun RstView() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.rst_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.rst_r), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.rst_r_scale), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.rst_s), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.rst_s_scale), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.rst_s_note), style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        }
        
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.rst_t), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.rst_t_scale),








                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
