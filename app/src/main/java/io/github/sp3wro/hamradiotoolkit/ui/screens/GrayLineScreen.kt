package io.github.sp3wro.hamradiotoolkit.ui.screens

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sp3wro.hamradiotoolkit.R
import io.github.sp3wro.hamradiotoolkit.utils.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrayLineScreen(
    position: LatLon? = null,
    onRequestLocation: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    // Pozycja wspólna dla całej aplikacji (GPS na żywo albo ostatnia zapisana)
    val currentLat = position?.lat
    val currentLon = position?.lon

    var calendar by remember { mutableStateOf(Calendar.getInstance(TimeZone.getTimeZone("UTC"))) }
    var worldPath by remember { mutableStateOf<Path?>(null) }

    LaunchedEffect(Unit) {
        launch(Dispatchers.Default) {
            try {
                val pathStr = context.resources.openRawResource(R.raw.world_path).bufferedReader().use { it.readText() }
                // Publiczny parser Compose (PathParser z androidx.core to API wewnętrzne biblioteki)
                val parsed = PathParser().parsePathString(pathStr).toPath()
                withContext(Dispatchers.Main) {
                    worldPath = parsed
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        while (true) {
            calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            delay(1000L)
        }
    }

    val lat = currentLat ?: 52.23
    val lon = currentLon ?: 21.01

    val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val second = calendar.get(Calendar.SECOND)

    val fractionalYear = 2.0 * PI * (dayOfYear - 1 + (hour - 12) / 24.0) / 365.25

    val solarDeclination = 0.006918 - 0.399912 * cos(fractionalYear) + 0.070257 * sin(fractionalYear) -
            0.006758 * cos(2 * fractionalYear) + 0.000907 * sin(2 * fractionalYear) -
            0.002697 * cos(3 * fractionalYear) + 0.00148 * sin(3 * fractionalYear)
    val solarDeclinationDeg = Math.toDegrees(solarDeclination)

    val eqTime = 229.18 * (0.000075 + 0.001868 * cos(fractionalYear) - 0.032077 * sin(fractionalYear) -
            0.014615 * cos(2 * fractionalYear) - 0.040849 * sin(2 * fractionalYear))

    val utcDecimalHour = hour + minute / 60.0 + second / 3600.0
    val solarLng = -((utcDecimalHour - 12.0) * 15.0 + eqTime / 4.0)
    val normalizedSolarLng = ((solarLng + 180) % 360 + 360) % 360 - 180

    val twilightAngle = -6.0
    val latRad = Math.toRadians(lat)
    val declRad = solarDeclination
    val zenithRad = Math.toRadians(90.0 - twilightAngle)
    val cosH = (cos(zenithRad) - sin(latRad) * sin(declRad)) / (cos(latRad) * cos(declRad))

    val hasEvent = cosH in -1.0..1.0
    val hAngle = if (hasEvent) Math.toDegrees(acos(cosH)) else 0.0

    val solarNoonUtc = 12.0 - (lon / 15.0) - (eqTime / 60.0)
    val sunriseUtc = solarNoonUtc - (hAngle / 15.0)
    val sunsetUtc = solarNoonUtc + (hAngle / 15.0)

    fun formatDecimalHour(decimalHour: Double): String {
        val h = (decimalHour % 24 + 24) % 24
        val hh = h.toInt()
        val mm = ((h - hh) * 60).toInt()
        return String.format(Locale.US, "%02d:%02d UTC", hh, abs(mm))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gl_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = String.format(Locale.US, "%02d:%02d:%02d UTC", hour, minute, second),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                if (worldPath == null) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D47A1)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else {
                    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF0D47A1))) {
                        val w = size.width
                        val h = size.height

                        val scaleX = w / 1000f
                        val scaleY = h / 500f

                        withTransform({
                            scale(scaleX, scaleY, Offset.Zero)
                        }) {
                            drawPath(
                                path = worldPath!!,
                                color = Color(0xFF4CAF50),
                                style = Fill
                            )
                        }

                        val sunLonRad = Math.toRadians(normalizedSolarLng)
                        
                        val nightPath = Path()
                        val terminatorPath = Path()

                        val step = 2f
                        var firstPoint = true

                        for (px in 0..w.toInt() step step.toInt()) {
                            val currLon = (px / w) * 360.0 - 180.0
                            val currLonRad = Math.toRadians(currLon)

                            val safeDeclRad = if (abs(declRad) < 0.0001) 0.0001 * sign(declRad + 0.000001) else declRad
                            val tanPhi = -(1.0 / tan(safeDeclRad)) * cos(currLonRad - sunLonRad)
                            val phiRad = atan(tanPhi)
                            val terminatorLat = Math.toDegrees(phiRad)

                            val py = (h * (1.0 - (terminatorLat + 90.0) / 180.0)).toFloat()

                            if (firstPoint) {
                                nightPath.moveTo(px.toFloat(), py)
                                terminatorPath.moveTo(px.toFloat(), py)
                                firstPoint = false
                            } else {
                                nightPath.lineTo(px.toFloat(), py)
                                terminatorPath.lineTo(px.toFloat(), py)
                            }
                        }

                        if (solarDeclinationDeg >= 0) {
                            nightPath.lineTo(w, h)
                            nightPath.lineTo(0f, h)
                        } else {
                            nightPath.lineTo(w, 0f)
                            nightPath.lineTo(0f, 0f)
                        }
                        nightPath.close()

                        drawPath(
                            path = nightPath,
                            color = Color.Black.copy(alpha = 0.5f),
                            style = Fill
                        )

                        drawPath(
                            path = terminatorPath,
                            color = Color.Yellow,
                            style = Stroke(width = 3f)
                        )

                        val sunX = w * ((normalizedSolarLng + 180f) / 360f).toFloat()
                        val sunY = h * (1.0f - (solarDeclinationDeg + 90f) / 180f).toFloat()
                        drawCircle(color = Color.Yellow, radius = 10f, center = Offset(sunX, sunY))

                        val qthX = w * ((lon + 180f) / 360f).toFloat()
                        val qthY = h * (1.0f - (lat + 90f) / 180f).toFloat()
                        drawCircle(color = Color.Red, radius = 8f, center = Offset(qthX, qthY))
                        drawCircle(color = Color.White, radius = 8f, center = Offset(qthX, qthY), style = Stroke(width = 2f))
                    }
                }
            }
            Text(stringResource(R.string.gl_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.gl_your_station), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        // Pozycja odświeża się sama; przycisk tylko wtedy, gdy jej brak (np. bez zgody na lokalizację)
                        if (position == null) {
                            OutlinedButton(
                                onClick = onRequestLocation,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.gl_allow_gps), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (currentLat != null && currentLon != null) {
                        Text("Lat: ${String.format(Locale.US, "%.4f", abs(currentLat))}°${if (currentLat >= 0) "N" else "S"}", style = MaterialTheme.typography.bodyMedium)
                        Text("Lon: ${String.format(Locale.US, "%.4f", abs(currentLon))}°${if (currentLon >= 0) "E" else "W"}", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.gl_waiting_gps), color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (hasEvent) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.gl_sunrise), fontWeight = FontWeight.Bold)
                            Text(formatDecimalHour(sunriseUtc))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.gl_sunset), fontWeight = FontWeight.Bold)
                            Text(formatDecimalHour(sunsetUtc))
                        }
                    } else {
                        Text(stringResource(R.string.gl_polar), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.gl_about), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.gl_about_text),
                        
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
