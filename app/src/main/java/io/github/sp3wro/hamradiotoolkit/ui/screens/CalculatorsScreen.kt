package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sp3wro.hamradiotoolkit.calc.AntennaMath
import io.github.sp3wro.hamradiotoolkit.calc.FeedlineMath
import io.github.sp3wro.hamradiotoolkit.data.model.bandForKhz
import io.github.sp3wro.hamradiotoolkit.utils.UiLocale
import kotlin.math.cos
import kotlin.math.sin

private enum class CalcTool(@StringRes val label: Int) {
    DIPOLE(R.string.calc_t_dipole), EFHW(R.string.calc_t_efhw), GP(R.string.calc_t_gp), JPOLE(R.string.calc_t_jpole), YAGI(R.string.calc_t_yagi), MOXON(R.string.calc_t_moxon), LOOP(R.string.calc_t_loop),
    STUB(R.string.calc_t_stub), SWR(R.string.calc_t_swr), POWER(R.string.calc_t_power), BATTERY(R.string.calc_t_battery)
}

private val ANTENNA_TOOLS = listOf(CalcTool.DIPOLE, CalcTool.EFHW, CalcTool.GP, CalcTool.JPOLE, CalcTool.YAGI, CalcTool.MOXON, CalcTool.LOOP)
private val OTHER_TOOLS = listOf(CalcTool.STUB, CalcTool.SWR, CalcTool.POWER, CalcTool.BATTERY)

// Typowe częstotliwości robocze pasm (środek części fonicznej / popularne QRG)
private val BAND_PRESETS = listOf(
    "160" to 1.840, "80" to 3.700, "60" to 5.355, "40" to 7.100, "30" to 10.120, "20" to 14.175, "17" to 18.120,
    "15" to 21.250, "12" to 24.940, "10" to 28.500, "6" to 50.150, "2" to 145.000, "70 cm" to 435.000
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalculatorsScreen(
    onSettingsClick: () -> Unit = {}
) {
    var tool by rememberSaveableTool()
    var freqInput by remember { mutableStateOf("14.175") }
    val freq = freqInput.replace(",", ".").toDoubleOrNull()?.takeIf { it in 0.1..10_000.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calc_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ToolChips(stringResource(R.string.calc_antennas), ANTENNA_TOOLS, tool) { tool = it }
            ToolChips(stringResource(R.string.calc_matching), OTHER_TOOLS, tool) { tool = it }
            HorizontalDivider()

            if (tool in ANTENNA_TOOLS || tool == CalcTool.STUB) {
                FrequencyInput(freqInput, freq) { freqInput = it }
            }

            when (tool) {
                CalcTool.DIPOLE -> freq?.let { DipoleCalculator(it) }
                CalcTool.EFHW -> freq?.let { EfhwCalculator(it) }
                CalcTool.GP -> freq?.let { GroundPlaneCalculator(it) }
                CalcTool.JPOLE -> freq?.let { JPoleCalculator(it) }
                CalcTool.YAGI -> freq?.let { YagiCalculator(it) }
                CalcTool.MOXON -> freq?.let { MoxonCalculator(it) }
                CalcTool.LOOP -> freq?.let { LoopCalculator(it) }
                CalcTool.STUB -> freq?.let { StubCalculator(it) }
                CalcTool.SWR -> SwrCalculator()
                CalcTool.POWER -> PowerCalculator()
                CalcTool.BATTERY -> BatteryCalculator()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun rememberSaveableTool(): MutableState<CalcTool> =
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CalcTool.DIPOLE) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolChips(title: String, tools: List<CalcTool>, selected: CalcTool, onSelect: (CalcTool) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.forEach { t ->
                FilterChip(selected = selected == t, onClick = { onSelect(t) }, label = { Text(stringResource(t.label)) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrequencyInput(value: String, parsed: Double?, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.calc_freq)) },
            isError = parsed == null,
            supportingText = {
                val band = parsed?.let { bandForKhz(it * 1000) }
                Text(if (parsed == null) stringResource(R.string.calc_enter_freq) else band?.let { stringResource(R.string.calc_band, it) } ?: stringResource(R.string.calc_outside_bands))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BAND_PRESETS.forEach { (label, mhz) ->
                FilterChip(
                    selected = parsed == mhz,
                    onClick = { onChange(String.format(java.util.Locale.US, "%.3f", mhz)) },
                    label = { Text(if (label.endsWith("cm")) label else "$label m") }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- wspólne elementy

internal fun formatLength(m: Double): String =
    if (m >= 1.0) String.format(UiLocale, "%.2f m", m) else String.format(UiLocale, "%.1f cm", m * 100)

@Composable
private fun ResultCard(title: String, rows: List<Pair<String, String>>, tips: List<String> = emptyList()) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (tips.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                tips.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = value.replace(',', '.').toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

private fun String.num(): Double? = replace(',', '.').toDoubleOrNull()

/** Wspólna kartka na schemat anteny z podpisami literowymi. */
@Composable
private fun Diagram(height: Int = 180, draw: DrawScope.(DiagramStyle) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = DiagramStyle(
        measurer = measurer,
        element = MaterialTheme.colorScheme.primary,
        accent = MaterialTheme.colorScheme.tertiary,
        muted = MaterialTheme.colorScheme.outline,
        text = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    )
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height.dp).padding(12.dp)) { draw(style) }
    }
}

private class DiagramStyle(
    val measurer: TextMeasurer,
    val element: Color,
    val accent: Color,
    val muted: Color,
    val text: TextStyle
)

private fun DrawScope.label(s: DiagramStyle, text: String, center: Offset) {
    val layout = s.measurer.measure(text, s.text)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

private fun DrawScope.feed(s: DiagramStyle, at: Offset, length: Float = 40f) {
    drawLine(s.muted, at, Offset(at.x, at.y + length), strokeWidth = 4f)
    drawCircle(s.accent, radius = 7f, center = at)
}

// ---------------------------------------------------------------- anteny

@Composable
private fun ShorteningField(k: String, onChange: (String) -> Unit) {
    NumberField(stringResource(R.string.calc_k_factor), k, onChange, Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.calc_k_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DipoleCalculator(freq: Double) {
    var k by remember { mutableStateOf("0.95") }
    ShorteningField(k) { k = it }
    val factor = k.num()?.takeIf { it in 0.5..1.0 } ?: return
    val d = AntennaMath.dipole(freq, factor)

    Diagram { s ->
        val y = size.height * 0.45f
        val cx = size.width / 2
        drawLine(s.element, Offset(8f, y), Offset(cx - 10f, y), strokeWidth = 6f)
        drawLine(s.element, Offset(cx + 10f, y), Offset(size.width - 8f, y), strokeWidth = 6f)
        feed(s, Offset(cx, y), size.height * 0.4f)
        label(s, "A", Offset(cx / 2, y - 26f))
        label(s, "A", Offset(cx * 1.5f, y - 26f))
        label(s, "50 Ω", Offset(cx + 46f, y + size.height * 0.3f))
    }
    ResultCard(
        stringResource(R.string.calc_dipole_title),
        listOf(
            stringResource(R.string.calc_total_length) to formatLength(d.total),
            stringResource(R.string.calc_leg) to formatLength(d.leg),
            stringResource(R.string.calc_wavelength) to formatLength(d.wavelength)
        ),
        listOf(
            stringResource(R.string.calc_dipole_tip1),
            stringResource(R.string.calc_dipole_tip2),
            stringResource(R.string.calc_dipole_tip3)
        )
    )
}

@Composable
private fun EfhwCalculator(freq: Double) {
    var k by remember { mutableStateOf("0.95") }
    ShorteningField(k) { k = it }
    val factor = k.num()?.takeIf { it in 0.5..1.0 } ?: return
    val e = AntennaMath.efhw(freq, factor)

    Diagram { s ->
        val y = size.height * 0.3f
        drawRect(s.accent, topLeft = Offset(4f, y - 18f), size = androidx.compose.ui.geometry.Size(40f, 36f))
        drawLine(s.element, Offset(44f, y), Offset(size.width - 8f, y - size.height * 0.15f), strokeWidth = 6f)
        drawLine(s.muted, Offset(24f, y + 18f), Offset(size.width * 0.3f, size.height - 12f), strokeWidth = 4f)
        label(s, "A", Offset(size.width * 0.55f, y - size.height * 0.08f - 22f))
        label(s, "B", Offset(size.width * 0.2f, size.height * 0.75f))
        label(s, "49:1", Offset(24f, y - 34f))
    }
    val harmonics = e.harmonics.joinToString(", ") { "${it.second} (${String.format(UiLocale, "%.3f", it.first)})" }
    ResultCard(
        stringResource(R.string.calc_efhw_title),
        listOf(
            stringResource(R.string.calc_radiator) to formatLength(e.radiator),
            stringResource(R.string.calc_counterpoise) to formatLength(e.counterpoise),
            stringResource(R.string.calc_transformer) to "49:1 (2,5–3 kΩ)"
        ),
        listOfNotNull(
            harmonics.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.calc_efhw_harmonics, it) },
            stringResource(R.string.calc_efhw_tip1),
            stringResource(R.string.calc_efhw_tip2)
        )
    )
}

@Composable
private fun GroundPlaneCalculator(freq: Double) {
    var k by remember { mutableStateOf("0.95") }
    ShorteningField(k) { k = it }
    val factor = k.num()?.takeIf { it in 0.5..1.0 } ?: return
    val g = AntennaMath.groundPlane(freq, factor)

    Diagram(200) { s ->
        val base = Offset(size.width / 2, size.height * 0.62f)
        drawLine(s.element, base, Offset(base.x, 8f), strokeWidth = 6f)
        val r = size.height * 0.5f
        for (angle in listOf(200.0, 340.0, 245.0, 295.0)) {
            val rad = Math.toRadians(angle)
            val end = Offset(base.x + (r * 1.5f * cos(rad)).toFloat(), base.y - (r * 0.55f * sin(rad)).toFloat())
            drawLine(s.muted, base, end, strokeWidth = 4f)
        }
        feed(s, base, size.height * 0.35f)
        label(s, "A", Offset(base.x + 22f, size.height * 0.3f))
        label(s, "B", Offset(base.x - r * 1.1f, base.y + 6f))
    }
    ResultCard(
        stringResource(R.string.calc_gp_title),
        listOf(
            stringResource(R.string.calc_radiator) to formatLength(g.radiator),
            stringResource(R.string.calc_radials) to formatLength(g.radials)
        ),
        listOf(
            stringResource(R.string.calc_gp_tip1),
            stringResource(R.string.calc_gp_tip2),
            stringResource(R.string.calc_gp_tip3)
        )
    )
}

@Composable
private fun JPoleCalculator(freq: Double) {
    var k by remember { mutableStateOf("0.95") }
    ShorteningField(k) { k = it }
    val factor = k.num()?.takeIf { it in 0.5..1.0 } ?: return
    val j = AntennaMath.jPole(freq, factor)

    Diagram(220) { s ->
        val bottom = size.height - 10f
        val xLong = size.width * 0.42f
        val xShort = size.width * 0.58f
        val longTop = 8f
        val shortTop = bottom - (bottom - longTop) / 3f
        drawLine(s.element, Offset(xLong, bottom), Offset(xLong, longTop), strokeWidth = 6f)
        drawLine(s.element, Offset(xShort, bottom), Offset(xShort, shortTop), strokeWidth = 6f)
        drawLine(s.element, Offset(xLong, bottom), Offset(xShort, bottom), strokeWidth = 6f)
        val feedY = bottom - (bottom - shortTop) * 0.12f
        drawCircle(s.accent, 7f, Offset(xLong, feedY))
        drawCircle(s.accent, 7f, Offset(xShort, feedY))
        label(s, "A", Offset(xLong - 24f, (longTop + bottom) / 2))
        label(s, "B", Offset(xShort + 24f, (shortTop + bottom) / 2))
        label(s, "C", Offset(xShort + 26f, feedY))
        label(s, "D", Offset((xLong + xShort) / 2, bottom - 22f))
    }
    ResultCard(
        stringResource(R.string.calc_jpole_title),
        listOf(
            stringResource(R.string.calc_jpole_long) to formatLength(j.longElement),
            stringResource(R.string.calc_jpole_short) to formatLength(j.stub),
            stringResource(R.string.calc_jpole_feed) to "≈ ${formatLength(j.feedPoint)}",
            stringResource(R.string.calc_jpole_spacing) to "${formatLength(j.spacingMin)} – ${formatLength(j.spacingMax)}"
        ),
        listOf(
            stringResource(R.string.calc_jpole_tip1),
            stringResource(R.string.calc_jpole_tip2),
            stringResource(R.string.calc_jpole_tip3)
        )
    )
}

@Composable
private fun YagiCalculator(freq: Double) {
    val y = AntennaMath.yagi3(freq)
    Diagram(220) { s ->
        val cx = size.width / 2
        drawLine(s.muted, Offset(cx, 10f), Offset(cx, size.height - 10f), strokeWidth = 8f)
        val scale = (size.width * 0.8f) / y.reflector.toFloat()
        val ys = listOf(24f, 24f + (size.height - 48f) * 0.57f, size.height - 24f)
        val lens = listOf(y.reflector, y.driven, y.director)
        lens.forEachIndexed { i, len ->
            val half = (len * scale / 2).toFloat()
            drawLine(if (i == 1) s.accent else s.element, Offset(cx - half, ys[i]), Offset(cx + half, ys[i]), strokeWidth = 7f)
        }
        label(s, "R", Offset(18f, ys[0]))
        label(s, "W", Offset(18f, ys[1]))
        label(s, "D", Offset(18f, ys[2]))
        label(s, "S1", Offset(cx + 26f, (ys[0] + ys[1]) / 2))
        label(s, "S2", Offset(cx + 26f, (ys[1] + ys[2]) / 2))
    }
    ResultCard(
        stringResource(R.string.calc_yagi_title),
        listOf(
            stringResource(R.string.calc_reflector) to formatLength(y.reflector),
            stringResource(R.string.calc_driven) to formatLength(y.driven),
            stringResource(R.string.calc_director) to formatLength(y.director),
            stringResource(R.string.calc_spacing_rw) to formatLength(y.spacingReflector),
            stringResource(R.string.calc_spacing_wd) to formatLength(y.spacingDirector),
            stringResource(R.string.calc_boom) to formatLength(y.boom)
        ),
        listOf(
            stringResource(R.string.calc_yagi_tip1),
            stringResource(R.string.calc_yagi_tip2),
            stringResource(R.string.calc_yagi_tip3)
        )
    )
}

@Composable
private fun MoxonCalculator(freq: Double) {
    var wire by remember { mutableStateOf("2.0") }
    NumberField(stringResource(R.string.calc_wire_diameter), wire, { wire = it }, Modifier.fillMaxWidth(), "mm")
    val diameter = wire.num()?.takeIf { it > 0.0 && it < 200.0 } ?: return
    val m = AntennaMath.moxon(freq, diameter / 1000.0)

    Diagram(200) { s ->
        val left = size.width * 0.12f
        val right = size.width * 0.88f
        val top = 16f
        val bottom = size.height - 16f
        val gapTop = top + (bottom - top) * 0.40f
        val gapBottom = top + (bottom - top) * 0.52f
        drawPath(Path().apply {
            moveTo(left, gapTop); lineTo(left, top); lineTo(right, top); lineTo(right, gapTop)
        }, s.element, style = Stroke(6f))
        drawPath(Path().apply {
            moveTo(left, gapBottom); lineTo(left, bottom); lineTo(right, bottom); lineTo(right, gapBottom)
        }, s.muted, style = Stroke(6f))
        drawCircle(s.accent, 7f, Offset(size.width / 2, top))
        label(s, "A", Offset(size.width / 2, top + 22f))
        label(s, "B", Offset(left - 22f, (top + gapTop) / 2))
        label(s, "C", Offset(left - 22f, (gapTop + gapBottom) / 2))
        label(s, "D", Offset(left - 22f, (gapBottom + bottom) / 2))
        label(s, "E", Offset(right + 22f, (top + bottom) / 2))
    }
    ResultCard(
        stringResource(R.string.calc_moxon_title),
        listOf(
            stringResource(R.string.calc_moxon_a) to formatLength(m.a),
            stringResource(R.string.calc_moxon_b) to formatLength(m.b),
            stringResource(R.string.calc_moxon_c) to formatLength(m.c),
            stringResource(R.string.calc_moxon_d) to formatLength(m.d),
            stringResource(R.string.calc_moxon_e) to formatLength(m.e)
        ),
        listOf(
            stringResource(R.string.calc_moxon_tip1),
            stringResource(R.string.calc_moxon_tip2),
            stringResource(R.string.calc_moxon_tip3)
        )
    )
}

@Composable
private fun LoopCalculator(freq: Double) {
    val l = AntennaMath.loop(freq)
    Diagram(170) { s ->
        val h = size.height - 20f
        val sq = h
        val x0 = size.width * 0.12f
        drawRect(s.element, topLeft = Offset(x0, 10f), size = androidx.compose.ui.geometry.Size(sq, sq), style = Stroke(6f))
        drawCircle(s.accent, 7f, Offset(x0 + sq / 2, 10f + sq))
        label(s, "A", Offset(x0 - 18f, 10f + sq / 2))
        val tx = size.width * 0.62f
        drawPath(Path().apply {
            moveTo(tx, 10f + h); lineTo(tx + h * 1.15f, 10f + h); lineTo(tx + h * 0.575f, 10f); close()
        }, s.element, style = Stroke(6f))
        drawCircle(s.accent, 7f, Offset(tx + h * 0.575f, 10f + h))
        label(s, "B", Offset(tx + h * 0.2f, 10f + h * 0.45f))
    }
    ResultCard(
        stringResource(R.string.calc_loop_title),
        listOf(
            stringResource(R.string.calc_loop_perimeter) to formatLength(l.perimeter),
            stringResource(R.string.calc_loop_quad) to formatLength(l.quadSide),
            stringResource(R.string.calc_loop_delta) to formatLength(l.deltaSide),
            stringResource(R.string.calc_loop_reflector) to formatLength(l.reflectorPerimeter)
        ),
        listOf(
            stringResource(R.string.calc_loop_tip1),
            stringResource(R.string.calc_loop_tip2),
            stringResource(R.string.calc_loop_tip3)
        )
    )
}

// ---------------------------------------------------------------- linie, SWR, moc, zasilanie

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StubCalculator(freq: Double) {
    var vf by remember { mutableStateOf("0.66") }
    var z1 by remember { mutableStateOf("50") }
    var z2 by remember { mutableStateOf("100") }
    Text(stringResource(R.string.calc_vf), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("0.66" to "RG-58/213 (PE)", "0.78" to stringResource(R.string.calc_vf_foam), "0.83" to "H-155", "0.85" to "LMR-400 / Aircell 7", "0.95" to stringResource(R.string.calc_vf_ladder))
            .forEach { (v, label) -> FilterChip(selected = vf == v, onClick = { vf = v }, label = { Text("$label $v") }) }
    }
    NumberField("VF", vf, { vf = it }, Modifier.fillMaxWidth())
    val v = vf.num()?.takeIf { it in 0.4..1.0 } ?: return
    val stub = FeedlineMath.stubs(freq, v)
    ResultCard(
        stringResource(R.string.calc_stub_title),
        listOf(
            stringResource(R.string.calc_stub_quarter) to formatLength(stub.quarter),
            stringResource(R.string.calc_stub_half) to formatLength(stub.half),
            stringResource(R.string.calc_stub_full) to formatLength(stub.full)
        ),
        listOf(
            stringResource(R.string.calc_stub_tip1),
            stringResource(R.string.calc_stub_tip2)
        )
    )

    Text(stringResource(R.string.calc_qmatch), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.calc_z_source), z1, { z1 = it }, Modifier.weight(1f), "Ω")
        NumberField(stringResource(R.string.calc_z_load), z2, { z2 = it }, Modifier.weight(1f), "Ω")
    }
    val a = z1.num()
    val b = z2.num()
    if (a != null && b != null && a > 0 && b > 0) {
        ResultCard(
            stringResource(R.string.calc_q_title),
            listOf(
                stringResource(R.string.calc_q_required) to String.format(UiLocale, "%.1f Ω", FeedlineMath.quarterWaveZ0(a, b)),
                stringResource(R.string.calc_q_length) to formatLength(stub.quarter)
            ),
            listOf(stringResource(R.string.calc_q_tip))
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwrCalculator() {
    var byPower by remember { mutableStateOf(true) }
    var fwd by remember { mutableStateOf("100") }
    var ref by remember { mutableStateOf("5") }
    var r by remember { mutableStateOf("75") }
    var x by remember { mutableStateOf("0") }
    var z0 by remember { mutableStateOf("50") }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = byPower, onClick = { byPower = true }, label = { Text(stringResource(R.string.calc_by_power)) })
        FilterChip(selected = !byPower, onClick = { byPower = false }, label = { Text(stringResource(R.string.calc_by_impedance)) })
    }
    val gamma = if (byPower) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.calc_forward), fwd, { fwd = it }, Modifier.weight(1f), "W")
            NumberField(stringResource(R.string.calc_reflected), ref, { ref = it }, Modifier.weight(1f), "W")
        }
        val f = fwd.num()
        val rr = ref.num()
        if (f != null && rr != null && f > 0 && rr >= 0 && rr < f) FeedlineMath.gammaFromPower(f, rr) else null
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("R", r, { r = it }, Modifier.weight(1f), "Ω")
            NumberField("X", x, { x = it }, Modifier.weight(1f), "Ω")
            NumberField("Z₀", z0, { z0 = it }, Modifier.weight(1f), "Ω")
        }
        val rv = r.num()
        val xv = x.num()
        val zv = z0.num()
        if (rv != null && xv != null && zv != null && rv >= 0 && zv > 0) FeedlineMath.gammaFromImpedance(rv, xv, zv) else null
    }
    if (gamma == null) return
    val swr = FeedlineMath.swr(gamma)
    ResultCard(
        stringResource(R.string.calc_match_title),
        listOf(
            stringResource(R.string.calc_t_swr) to if (swr.isInfinite() || swr > 99) "> 99" else String.format(UiLocale, "%.2f : 1", swr),
            stringResource(R.string.calc_gamma) to String.format(UiLocale, "%.3f", gamma),
            stringResource(R.string.calc_reflected) to String.format(UiLocale, "%.1f %%", gamma * gamma * 100),
            stringResource(R.string.calc_return_loss) to if (gamma <= 1e-6) "∞" else String.format(UiLocale, "%.1f dB", FeedlineMath.returnLossDb(gamma)),
            stringResource(R.string.calc_mismatch_loss) to String.format(UiLocale, "%.2f dB", FeedlineMath.mismatchLossDb(gamma))
        ),
        listOf(
            stringResource(R.string.calc_swr_tip1),
            stringResource(R.string.calc_swr_tip2)
        )
    )
}

@Composable
private fun PowerCalculator() {
    var power by remember { mutableStateOf("100") }
    var loss by remember { mutableStateOf("1.5") }
    var gain by remember { mutableStateOf("2.15") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.calc_tx_power), power, { power = it }, Modifier.weight(1f), "W")
        NumberField(stringResource(R.string.calc_cable_loss), loss, { loss = it }, Modifier.weight(1f), "dB")
    }
    NumberField(stringResource(R.string.calc_antenna_gain), gain, { gain = it }, Modifier.fillMaxWidth(), "dBi")
    Text(stringResource(R.string.calc_gain_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val p = power.num()?.takeIf { it > 0 } ?: return
    val l = loss.num() ?: return
    val g = gain.num() ?: return
    val res = FeedlineMath.radiated(p, l, g)
    ResultCard(
        stringResource(R.string.calc_radiated),
        listOf(
            stringResource(R.string.calc_tx_power) to String.format(UiLocale, "%.1f dBm / %.1f dBW", res.txDbm, res.txDbm - 30),
            stringResource(R.string.calc_at_antenna) to String.format(UiLocale, "%.1f W", res.atAntennaW),
            stringResource(R.string.calc_eirp) to String.format(UiLocale, "%.1f W", res.eirpW),
            stringResource(R.string.calc_erp) to String.format(UiLocale, "%.1f W", res.erpW)
        ),
        listOf(
            stringResource(R.string.calc_power_tip1),
            stringResource(R.string.calc_power_tip2)
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatteryCalculator() {
    var capacity by remember { mutableStateOf("20") }
    var usable by remember { mutableStateOf("90") }
    var rx by remember { mutableStateOf("1.0") }
    var tx by remember { mutableStateOf("4.0") }
    var duty by remember { mutableStateOf("25") }

    Text(stringResource(R.string.calc_battery), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("90" to "LiFePO4", "85" to "Li-ion", "50" to stringResource(R.string.calc_agm)).forEach { (v, label) ->
            FilterChip(selected = usable == v, onClick = { usable = v }, label = { Text("$label ($v %)") })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.calc_capacity), capacity, { capacity = it }, Modifier.weight(1f), "Ah")
        NumberField(stringResource(R.string.calc_usable), usable, { usable = it }, Modifier.weight(1f), "%")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.calc_rx_current), rx, { rx = it }, Modifier.weight(1f), "A")
        NumberField(stringResource(R.string.calc_tx_current), tx, { tx = it }, Modifier.weight(1f), "A")
    }
    Text(stringResource(R.string.calc_tx_share), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("20" to "SSB", "40" to "CW", "50" to "FT8", "10" to stringResource(R.string.calc_duty_quiet)).forEach { (v, label) ->
            FilterChip(selected = duty == v, onClick = { duty = v }, label = { Text("$label $v %") })
        }
    }
    NumberField(stringResource(R.string.calc_tx_share_field), duty, { duty = it }, Modifier.fillMaxWidth(), "%")

    val c = capacity.num()?.takeIf { it > 0 } ?: return
    val u = usable.num()?.takeIf { it in 1.0..100.0 } ?: return
    val rxA = rx.num()?.takeIf { it >= 0 } ?: return
    val txA = tx.num()?.takeIf { it >= 0 } ?: return
    val d = duty.num()?.takeIf { it in 0.0..100.0 } ?: return
    val b = FeedlineMath.battery(c, u, rxA, txA, d)
    val hours = b.hours
    ResultCard(
        stringResource(R.string.calc_runtime),
        listOf(
            stringResource(R.string.calc_avg_current) to String.format(UiLocale, "%.2f A", b.averageA),
            stringResource(R.string.calc_avail_capacity) to String.format(UiLocale, "%.1f Ah", b.usableAh),
            stringResource(R.string.calc_est_runtime) to if (hours.isInfinite()) "∞" else String.format(UiLocale, "%d h %02d min", hours.toInt(), ((hours % 1) * 60).toInt())
        ),
        listOf(
            stringResource(R.string.calc_battery_tip1),
            stringResource(R.string.calc_battery_tip2)
        )
    )
}
