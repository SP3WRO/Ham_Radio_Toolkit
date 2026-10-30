package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

data class SkyTrackPoint(
    val azimuthDeg: Double,
    val elevationDeg: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteCompassWidget(
    satName: String?,
    azimuthDeg: Double?,
    elevationDeg: Double?,
    isPassingNow: Boolean,
    trajectoryPoints: List<SkyTrackPoint> = emptyList(),
    qthLocator: String?,
    latitude: Double?,
    longitude: Double?,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val activeColor = MaterialTheme.colorScheme.tertiary // Zielony dla widocznych

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sat_skyplot),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
                if (qthLocator != null) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("LOC: $qthLocator", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Rysunek tarczy kompasu w Canvas
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val center = Offset(width / 2f, height / 2f)
                    val maxRadius = (minOf(width, height) / 2f) * 0.82f

                    // 1. Tło nieboskłonu
                    drawCircle(
                        color = gridColor.copy(alpha = 0.2f),
                        radius = maxRadius,
                        center = center
                    )

                    // 2. Pierścienie elewacji (0°, 30°, 60°)
                    val rings = listOf(0.0, 30.0, 60.0)
                    for (el in rings) {
                        val r = (90.0 - el) / 90.0 * maxRadius
                        drawCircle(
                            color = gridColor,
                            radius = r.toFloat(),
                            center = center,
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = if (el > 0) PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f) else null
                            )
                        )
                    }

                    // 3. Linie osiowe N-S oraz E-W
                    drawLine(
                        color = gridColor,
                        start = Offset(center.x, center.y - maxRadius),
                        end = Offset(center.x, center.y + maxRadius),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(center.x - maxRadius, center.y),
                        end = Offset(center.x + maxRadius, center.y),
                        strokeWidth = 1.dp.toPx()
                    )

                    // 4. Etykiety kierunków świata (N, E, S, W)
                    val labelOffset = maxRadius + 14.dp.toPx()
                    
                    val nText = textMeasurer.measure("N", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = primaryColor))
                    drawText(nText, topLeft = Offset(center.x - nText.size.width / 2f, center.y - labelOffset - nText.size.height / 2f))

                    val sText = textMeasurer.measure("S", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = onSurfaceColor))
                    drawText(sText, topLeft = Offset(center.x - sText.size.width / 2f, center.y + labelOffset - sText.size.height / 2f))

                    val eText = textMeasurer.measure("E", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = onSurfaceColor))
                    drawText(eText, topLeft = Offset(center.x + labelOffset - eText.size.width / 2f, center.y - eText.size.height / 2f))

                    val wText = textMeasurer.measure("W", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = onSurfaceColor))
                    drawText(wText, topLeft = Offset(center.x - labelOffset - wText.size.width / 2f, center.y - wText.size.height / 2f))

                    // 5. Trajektoria przelotu satelity (Ścieżka na niebie)
                    if (trajectoryPoints.isNotEmpty()) {
                        val path = Path()
                        var first = true

                        for (pt in trajectoryPoints) {
                            val r = ((90.0 - maxOf(0.0, pt.elevationDeg)) / 90.0 * maxRadius).toFloat()
                            val angleRad = Math.toRadians(pt.azimuthDeg - 90.0)
                            val px = center.x + r * cos(angleRad).toFloat()
                            val py = center.y + r * sin(angleRad).toFloat()

                            if (first) {
                                path.moveTo(px, py)
                                first = false
                            } else {
                                path.lineTo(px, py)
                            }
                        }

                        drawPath(
                            path = path,
                            color = secondaryColor.copy(alpha = 0.8f),
                            style = Stroke(width = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f))
                        )
                    }

                    // 6. Znacznik pozycji satelity na żywo
                    if (azimuthDeg != null && elevationDeg != null && elevationDeg >= -5.0) {
                        val currentEl = maxOf(0.0, elevationDeg)
                        val r = ((90.0 - currentEl) / 90.0 * maxRadius).toFloat()
                        val angleRad = Math.toRadians(azimuthDeg - 90.0)
                        val satX = center.x + r * cos(angleRad).toFloat()
                        val satY = center.y + r * sin(angleRad).toFloat()

                        val markerColor = if (isPassingNow || elevationDeg > 0) activeColor else primaryColor

                        // Aura / Poświata wokół znacznika
                        drawCircle(
                            color = markerColor.copy(alpha = 0.3f),
                            radius = 12.dp.toPx(),
                            center = Offset(satX, satY)
                        )
                        // Punkt centralny
                        drawCircle(
                            color = markerColor,
                            radius = 6.dp.toPx(),
                            center = Offset(satX, satY)
                        )

                        // Etykieta przy kropce satelity
                        if (!satName.isNullOrBlank()) {
                            val tagText = textMeasurer.measure(
                                text = satName,
                                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = markerColor)
                            )
                            drawText(
                                tagText,
                                topLeft = Offset(satX + 8.dp.toPx(), satY - tagText.size.height / 2f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Informacje numeryczne o pozycji Azymut / Elewacja
            if (!satName.isNullOrBlank() && azimuthDeg != null && elevationDeg != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sat_satellite), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(satName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = primaryColor)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sat_azimuth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${String.format(Locale.US, "%.1f", azimuthDeg)}°", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sat_elevation), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${String.format(Locale.US, "%.1f", elevationDeg)}°",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (elevationDeg > 0) activeColor else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.sat_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
