package io.github.sp3wro.hamradiotoolkit.ui.screens

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KukiEasterEggScreen(onDismiss: () -> Unit) {
    val colors = listOf(Color.Green, Color.Cyan, Color.Magenta, Color.Yellow, Color.Red, Color.White)
    var currentColor by remember { mutableStateOf(Color.Green) }

    var x by remember { mutableStateOf(100f) }
    var y by remember { mutableStateOf(100f) }
    var vx by remember { mutableStateOf(250f) } 
    var vy by remember { mutableStateOf(200f) }

    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        while (true) {
            withFrameNanos { time ->
                val dt = (time - lastTime) / 1_000_000_000f
                lastTime = time

                if (dt > 0 && dt < 0.1f) {
                    x += vx * dt
                    y += vy * dt
                }
            }
        }
    }

    val density = LocalDensity.current
    val elementWidth = with(density) { 180.dp.toPx() }
    val elementHeight = with(density) { 120.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() }
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        LaunchedEffect(x, y, screenWidthPx, screenHeightPx) {
            if (screenWidthPx > 0 && screenHeightPx > 0) {
                var hit = false
                if (x <= 0f) {
                    x = 0f
                    vx = -vx
                    hit = true
                } else if (x >= screenWidthPx - elementWidth) {
                    x = screenWidthPx - elementWidth
                    vx = -vx
                    hit = true
                }

                if (y <= 0f) {
                    y = 0f
                    vy = -vy
                    hit = true
                } else if (y >= screenHeightPx - elementHeight) {
                    y = screenHeightPx - elementHeight
                    vy = -vy
                    hit = true
                }

                if (hit) {
                    currentColor = colors.filter { it != currentColor }.random()
                }
            }
        }

        val xDp = with(density) { x.toDp() }
        val yDp = with(density) { y.toDp() }

        Box(
            modifier = Modifier
                .offset(x = xDp, y = yDp)
                .size(width = 180.dp, height = 120.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🦜",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "KUKI",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = currentColor
                )
            }
        }
    }
}
