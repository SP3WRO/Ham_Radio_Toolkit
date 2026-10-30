package io.github.sp3wro.hamradiotoolkit.ui.components

import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.utils.GreatCircle
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class MapMarker(val position: LatLon, val label: String, val color: Color)

// Kontury lądów z res/raw/world_path.txt (ścieżka SVG w rzucie walcowym 1000×500)
object WorldOutlines {
    @Volatile private var cached: List<List<LatLon>>? = null

    fun load(context: Context): List<List<LatLon>> {
        cached?.let { return it }
        val text = context.resources.openRawResource(R.raw.world_path).bufferedReader().use { it.readText() }
        return parse(text).also { cached = it }
    }

    fun parse(svgPath: String): List<List<LatLon>> {
        val polygons = mutableListOf<List<LatLon>>()
        var current = mutableListOf<LatLon>()
        val token = Regex("""([MLZ])\s*(?:(-?[\d.]+),(-?[\d.]+))?""")
        for (m in token.findAll(svgPath)) {
            when (m.groupValues[1]) {
                "M" -> {
                    if (current.size > 2) polygons.add(current)
                    current = mutableListOf()
                }
                "Z" -> {
                    if (current.size > 2) polygons.add(current)
                    current = mutableListOf()
                    continue
                }
            }
            val x = m.groupValues[2].toDoubleOrNull() ?: continue
            val y = m.groupValues[3].toDoubleOrNull() ?: continue
            current.add(LatLon(lat = 90.0 - y / 500.0 * 180.0, lon = x / 1000.0 * 360.0 - 180.0))
        }
        if (current.size > 2) polygons.add(current)
        return polygons
    }
}

private const val MAP_RADIUS_KM = Math.PI * GreatCircle.EARTH_RADIUS_KM

private const val MAX_ZOOM = 25f

/**
 * Mapa azymutalna równoodległościowa wycentrowana na QTH: odległość od środka = odległość po ortodromie,
 * kierunek od środka = azymut anteny. Ortodroma do [target] jest prostą; dłuższa droga (LP) – jej przedłużeniem.
 * [zoom] i [pan] (px) powiększają i przesuwają rysunek – grubość linii i napisy zostają stałe.
 */
@Composable
fun AzimuthalMap(
    center: LatLon,
    modifier: Modifier = Modifier,
    target: LatLon? = null,
    markers: List<MapMarker> = emptyList(),
    zoom: Float = 1f,
    pan: Offset = Offset.Zero
) {
    val context = LocalContext.current
    var outlines by remember { mutableStateOf<List<List<LatLon>>>(emptyList()) }
    LaunchedEffect(Unit) {
        outlines = withContext(Dispatchers.IO) { WorldOutlines.load(context) }
    }

    // Rzut liczony tylko przy zmianie środka; rysowanie przelicza już tylko skalę
    var projected by remember { mutableStateOf<List<ProjectedPolygon>>(emptyList()) }
    LaunchedEffect(center, outlines) {
        projected = withContext(Dispatchers.Default) { projectOutlines(center, outlines) }
    }

    val textMeasurer = rememberTextMeasurer()
    val ocean = Color(0xFF0D3B66)
    val land = Color(0xFF4C8C4A)
    val grid = Color.White.copy(alpha = 0.35f)
    val pathColor = Color(0xFFFFD166)
    val longPathColor = Color(0xFFEF476F)
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp)
    val primary = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) { clipRect {
        val radius = size.minDimension / 2f * zoom
        val c = Offset(size.width / 2f, size.height / 2f) + pan
        val scale = radius / MAP_RADIUS_KM.toFloat()
        fun toScreen(xKm: Double, yKm: Double) = Offset(c.x + (xKm * scale).toFloat(), c.y - (yKm * scale).toFloat())

        drawCircle(ocean, radius, c)

        for (polygon in projected) {
            val path = Path()
            var started = false
            for (p in polygon.points) {
                if (p == null) {
                    started = false
                    continue
                }
                val o = toScreen(p.first, p.second)
                if (!started) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                started = true
            }
            if (polygon.fillable) {
                path.close()
                drawPath(path, land, style = Fill)
            } else {
                drawPath(path, land, style = Stroke(width = 1.5f))
            }
        }

        // Okręgi odległości (gęstsze po powiększeniu) i promienie co 30°
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        val ringStepKm = when {
            zoom >= 12f -> 250
            zoom >= 6f -> 500
            zoom >= 3f -> 1000
            zoom >= 1.6f -> 2500
            else -> 5000
        }
        var ringKm = ringStepKm
        while (ringKm < MAP_RADIUS_KM) {
            drawCircle(grid, ringKm * scale, c, style = Stroke(width = 1f, pathEffect = dash))
            val ringLabel = if (ringKm % 1000 == 0) "${ringKm / 1000} tys. km" else "$ringKm km"
            drawText(textMeasurer, ringLabel, Offset(c.x + 4f, c.y - ringKm * scale), labelStyle)
            ringKm += ringStepKm
        }
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            drawLine(grid, c, Offset(c.x + (radius * sin(rad)).toFloat(), c.y - (radius * cos(rad)).toFloat()), strokeWidth = 1f)
        }
        drawCircle(Color.White.copy(alpha = 0.8f), radius, c, style = Stroke(width = 2f))
        for ((label, deg) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
            val rad = Math.toRadians(deg)
            val layout = textMeasurer.measure(label, labelStyle.copy(fontSize = 14.sp))
            val pos = Offset(
                c.x + ((radius - 16f) * sin(rad)).toFloat() - layout.size.width / 2f,
                c.y - ((radius - 16f) * cos(rad)).toFloat() - layout.size.height / 2f
            )
            drawText(layout, topLeft = pos)
        }

        if (target != null) {
            val path = GreatCircle.path(center, target)
            val sp = Math.toRadians(path.shortPathBearing)
            val lp = Math.toRadians(path.longPathBearing)
            val end = GreatCircle.azimuthalProject(center, target)
            if (end != null) {
                drawLine(pathColor, c, toScreen(end.first, end.second), strokeWidth = 4f)
                drawCircle(pathColor, 8f, toScreen(end.first, end.second))
            }
            // Dłuższa droga: przeciwny kierunek aż do krawędzi mapy (antypody)
            drawLine(
                longPathColor, c,
                Offset(c.x + (radius * sin(lp)).toFloat(), c.y - (radius * cos(lp)).toFloat()),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            )
            // Znacznik kierunku krótkiej drogi na obwodzie
            drawCircle(pathColor, 6f, Offset(c.x + (radius * sin(sp)).toFloat(), c.y - (radius * cos(sp)).toFloat()))
        }

        for (marker in markers) {
            val p = GreatCircle.azimuthalProject(center, marker.position) ?: continue
            val o = toScreen(p.first, p.second)
            if (hypot((o.x - c.x).toDouble(), (o.y - c.y).toDouble()) > radius) continue
            drawCircle(marker.color, 6f, o)
            if (marker.label.isNotEmpty()) drawText(textMeasurer, marker.label, o + Offset(8f, -8f), labelStyle)
        }

        drawCircle(primary, 7f, c)
        drawCircle(Color.White, 7f, c, style = Stroke(width = 2f))
    } }
}

/**
 * [AzimuthalMap] z gestami: szczypanie powiększa (wokół punktu między palcami), przeciąganie przesuwa,
 * podwójne stuknięcie powiększa w danym miejscu albo wraca do całej mapy.
 */
@Composable
fun ZoomableAzimuthalMap(
    center: LatLon,
    modifier: Modifier = Modifier,
    target: LatLon? = null,
    markers: List<MapMarker> = emptyList()
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(modifier) {
        AzimuthalMap(
            center = center, target = target, markers = markers, zoom = zoom, pan = pan,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    fun zoomAround(focus: Offset, newZoom: Float, extraPan: Offset = Offset.Zero) {
                        val z = newZoom.coerceIn(1f, MAX_ZOOM)
                        val rel = focus - Offset(size.width / 2f, size.height / 2f)
                        // Punkt mapy pod [focus] zostaje w tym samym miejscu ekranu
                        val moved = (pan - rel) * (z / zoom) + rel + extraPan
                        val limit = minOf(size.width, size.height) / 2f * (z - 1f)
                        pan = Offset(moved.x.coerceIn(-limit, limit), moved.y.coerceIn(-limit, limit))
                        zoom = z
                    }
                    coroutineScope {
                        launch {
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                zoomAround(centroid, zoom * zoomChange, panChange)
                            }
                        }
                        launch {
                            detectTapGestures(onDoubleTap = { tap ->
                                if (zoom > 1.05f) {
                                    zoom = 1f
                                    pan = Offset.Zero
                                } else {
                                    zoomAround(tap, 3f)
                                }
                            })
                        }
                    }
                }
        )
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(onClick = {
                val z = (zoom * 2f).coerceAtMost(MAX_ZOOM)
                pan *= z / zoom
                zoom = z
            }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.map_zoom_in)) }
            SmallFloatingActionButton(onClick = {
                val z = (zoom / 2f).coerceAtLeast(1f)
                pan = if (z == 1f) Offset.Zero else pan * (z / zoom)
                zoom = z
            }) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.map_zoom_out)) }
            SmallFloatingActionButton(onClick = {
                zoom = 1f
                pan = Offset.Zero
            }) { Icon(Icons.Filled.CenterFocusStrong, contentDescription = stringResource(R.string.map_whole)) }
        }
    }
}

/** Mapa azymutalna na cały ekran, z powiększaniem. [header] – np. opis celu i dane krótkiej/długiej drogi. */
@Composable
fun FullScreenAzimuthalMapDialog(
    center: LatLon,
    target: LatLon?,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF06192B), contentColor = Color.White) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        header()
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close)) }
                }
                ZoomableAzimuthalMap(center = center, target = target, modifier = Modifier.fillMaxWidth().weight(1f))
                Text(
                    stringResource(R.string.map_hint),
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private class ProjectedPolygon(val points: List<Pair<Double, Double>?>, val fillable: Boolean)

// Przy antypodach rzut rozciąga lądy na całą krawędź mapy – takie fragmenty rysujemy tylko konturem,
// a skoki między kolejnymi punktami (przejście przez antypody) przerywają linię
private fun projectOutlines(center: LatLon, outlines: List<List<LatLon>>): List<ProjectedPolygon> {
    val jumpKm = 4000.0
    val nearAntipodeKm = MAP_RADIUS_KM * 0.85
    return outlines.map { polygon ->
        var fillable = true
        var previous: Pair<Double, Double>? = null
        val points = ArrayList<Pair<Double, Double>?>(polygon.size)
        for (vertex in polygon) {
            val p = GreatCircle.azimuthalProject(center, vertex)
            if (p == null || hypot(p.first, p.second) > nearAntipodeKm) fillable = false
            val prev = previous
            if (p != null && prev != null && hypot(p.first - prev.first, p.second - prev.second) > jumpKm) {
                fillable = false
                points.add(null)
            }
            points.add(p)
            previous = p
        }
        ProjectedPolygon(points, fillable)
    }
}
