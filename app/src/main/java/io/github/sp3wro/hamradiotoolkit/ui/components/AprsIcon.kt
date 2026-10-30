package io.github.sp3wro.hamradiotoolkit.ui.components

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.sp3wro.hamradiotoolkit.R

/**
 * Arkusze symboli dekodowane raz na cały proces – wcześniej każdy wiersz listy dekodował
 * cały arkusz (2048×768, dodatkowo skalowany do gęstości ekranu), co zacinało przewijanie.
 */
private object AprsSprites {
    private val cache = HashMap<Int, ImageBitmap?>()

    fun get(context: Context, resourceId: Int): ImageBitmap? = synchronized(cache) {
        cache.getOrPut(resourceId) {
            val options = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(context.resources, resourceId, options)?.asImageBitmap()
        }
    }
}

/**
 * Symbol APRS jak na aprs.fi: '/' – tabela podstawowa, '\' – alternatywna.
 * Nakładka (cyfra/litera z pola tabeli) jest nadrukowana na środku symbolu alternatywnego.
 */
@Composable
fun AprsIcon(table: Char, symbol: Char, overlay: Char? = null, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val context = LocalContext.current

    val resourceId = if (table == '/') R.drawable.aprs_primary else R.drawable.aprs_secondary

    val painter: BitmapPainter? = remember(resourceId, symbol) {
        val fullSprite = AprsSprites.get(context.applicationContext, resourceId)
        val code = symbol.code
        if (fullSprite == null || code !in 33..126) return@remember null
        val offsetCode = code - 33
        val column = offsetCode % 16
        val row = offsetCode / 16

        val cellWidth = fullSprite.width / 16
        val cellHeight = fullSprite.height / 6

        BitmapPainter(fullSprite, IntOffset(column * cellWidth, row * cellHeight), IntSize(cellWidth, cellHeight))
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = stringResource(R.string.aprs_icon_desc, "$table$symbol"),
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (overlay != null && (overlay in '0'..'9' || overlay in 'A'..'Z')) {
            // Rozmiar w dp, nie sp – nakładka ma się skalować z ikoną, nie z systemową czcionką
            val density = LocalDensity.current
            val fontSize = with(density) { (size * 0.5f).toSp() }
            val outlinePx = with(density) { (size * 0.09f).toPx() }
            val base = TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
            )
            // Biały znak z czarnym obrysem – czytelny na każdym kolorze symbolu
            Text(
                overlay.toString(),
                style = base.copy(color = Color.Black, drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round))
            )
            Text(overlay.toString(), style = base.copy(color = Color.White))
        }
    }
}
