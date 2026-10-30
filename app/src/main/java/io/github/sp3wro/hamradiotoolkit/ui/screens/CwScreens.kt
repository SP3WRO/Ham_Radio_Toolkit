package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.sp3wro.hamradiotoolkit.cw.CwGenerator
import io.github.sp3wro.hamradiotoolkit.cw.CwPlayer
import io.github.sp3wro.hamradiotoolkit.cw.CwReceiver
import io.github.sp3wro.hamradiotoolkit.cw.CwTiming
import io.github.sp3wro.hamradiotoolkit.cw.CwTrainerScoring
import io.github.sp3wro.hamradiotoolkit.cw.Morse
import io.github.sp3wro.hamradiotoolkit.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Trener CW metodą Kocha: pełna prędkość znaku od początku, odstępy wydłużone (Farnsworth). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CwTrainerScreen(
    repository: UserPreferencesRepository,
    onSettingsClick: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val player = remember { CwPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    val savedLesson by repository.cwLesson.collectAsState(initial = 1)
    val savedCharWpm by repository.cwCharWpm.collectAsState(initial = 20)
    val savedEffWpm by repository.cwEffectiveWpm.collectAsState(initial = 12)
    val savedTone by repository.cwToneHz.collectAsState(initial = 600)

    var lesson by remember(savedLesson) { mutableIntStateOf(savedLesson) }
    var charWpm by remember(savedCharWpm) { mutableIntStateOf(savedCharWpm) }
    var effWpm by remember(savedEffWpm) { mutableIntStateOf(savedEffWpm) }
    var tone by remember(savedTone) { mutableIntStateOf(savedTone) }
    var groups by remember { mutableIntStateOf(10) }

    var expected by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Double?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    fun save() = scope.launch { repository.saveCwSettings(lesson, charWpm, effWpm, tone) }

    fun play(text: String) {
        isPlaying = true
        scope.launch {
            val pcm = withContext(Dispatchers.Default) {
                CwGenerator(toneHz = tone.toDouble()).render(text, CwTiming(charWpm, effWpm.coerceAtMost(charWpm)))
            }
            player.play(pcm) { isPlaying = false }
        }
    }

    val chars = Morse.kochCharacters(lesson)
    val newChar = chars.last()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cwt_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.cwt_lesson, lesson, Morse.KOCH_LESSONS), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.cwt_chars, chars.toList().joinToString(" ")), fontFamily = FontFamily.Monospace)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.cwt_new_char, newChar.toString(), Morse.CODES[newChar].orEmpty()), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = { play("$newChar $newChar $newChar") }, enabled = !isPlaying) { Text(stringResource(R.string.cwt_listen)) }
                    }
                    Slider(
                        value = lesson.toFloat(), onValueChange = { lesson = it.toInt() }, onValueChangeFinished = { save() },
                        valueRange = 1f..Morse.KOCH_LESSONS.toFloat(), steps = Morse.KOCH_LESSONS - 2
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.cwt_char_speed, charWpm), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = charWpm.toFloat(), onValueChange = {
                        charWpm = it.toInt()
                        if (effWpm > charWpm) effWpm = charWpm
                    }, onValueChangeFinished = { save() }, valueRange = 10f..40f, steps = 29)
                    Text(stringResource(R.string.cwt_eff_speed, effWpm), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = effWpm.toFloat(), onValueChange = { effWpm = it.toInt().coerceAtMost(charWpm) },
                        onValueChangeFinished = { save() }, valueRange = 5f..40f, steps = 34)
                    Text(stringResource(R.string.cwt_tone, tone), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = tone.toFloat(), onValueChange = { tone = (it / 50).toInt() * 50 },
                        onValueChangeFinished = { save() }, valueRange = 400f..1000f, steps = 11)
                    Text(stringResource(R.string.cwt_groups, groups), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = groups.toFloat(), onValueChange = { groups = it.toInt() }, valueRange = 5f..30f, steps = 24)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    expected = CwTrainerScoring.randomGroups(chars, groups)
                    typed = ""
                    result = null
                    play(expected)
                }, enabled = !isPlaying) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cwt_new_round))
                }
                OutlinedButton(onClick = { play(expected) }, enabled = !isPlaying && expected.isNotEmpty() && result == null) { Text(stringResource(R.string.cwt_repeat)) }
                if (isPlaying) {
                    OutlinedButton(onClick = { player.stop(); isPlaying = false }) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text(stringResource(R.string.common_stop))
                    }
                }
            }

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.uppercase() },
                label = { Text(stringResource(R.string.cwt_type_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
            )
            Button(onClick = { result = CwTrainerScoring.accuracy(expected, typed) }, enabled = expected.isNotEmpty() && !isPlaying) {
                Text(stringResource(R.string.cwt_check))
            }

            result?.let { accuracy ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.cwt_accuracy, String.format(Locale.US, "%.0f", accuracy * 100)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = if (accuracy >= 0.9) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.cwt_sent, expected), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.cwt_received, typed.trim()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        if (accuracy >= 0.9 && lesson < Morse.KOCH_LESSONS) {
                            Text(stringResource(R.string.cwt_pass))
                            Button(onClick = {
                                lesson += 1
                                save()
                                expected = ""
                                typed = ""
                                result = null
                            }) { Text(stringResource(R.string.cwt_next_lesson, lesson + 1, Morse.kochCharacters(lesson + 1).last().toString())) }
                        } else if (accuracy < 0.9) {
                            Text(stringResource(R.string.cwt_stay), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CwDecoderScreen(onSettingsClick: () -> Unit = {}) {
    val context = LocalContext.current
    val res = LocalResources.current
    val receiver = remember { CwReceiver() }
    DisposableEffect(Unit) { onDispose { receiver.stop() } }

    val text by receiver.text.collectAsState()
    val level by receiver.level.collectAsState()
    val wpm by receiver.wpm.collectAsState()
    val toneHz by receiver.toneHz.collectAsState()
    val listening by receiver.listening.collectAsState()
    var autoTone by remember { mutableStateOf(true) }

    fun start() {
        if (!receiver.start()) Toast.makeText(context, res.getString(R.string.dec_mic_failed), Toast.LENGTH_SHORT).show()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else Toast.makeText(context, res.getString(R.string.dec_mic_denied), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cwd_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    text.ifEmpty { if (listening) stringResource(R.string.cwd_listening) else stringResource(R.string.cwd_press_listen) },
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (listening && wpm > 0) "≈ $wpm WPM" else "– WPM", fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.cwd_tone, String.format(Locale.US, "%.0f", toneHz)), fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cwd_auto_tone), modifier = Modifier.weight(1f))
                Switch(checked = autoTone, onCheckedChange = {
                    autoTone = it
                    receiver.setAutoTone(it)
                })
            }
            if (!autoTone) {
                Slider(
                    value = toneHz.toFloat(),
                    onValueChange = { receiver.setTone((it / 10).toInt() * 10.0) },
                    valueRange = 300f..1200f
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (listening) receiver.stop()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Icon(if (listening) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (listening) stringResource(R.string.common_stop) else stringResource(R.string.cwd_listen))
                }
                OutlinedButton(onClick = { receiver.clear() }) { Text(stringResource(R.string.common_clear)) }
            }
            Text(
                stringResource(R.string.cwd_info),
                    
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
