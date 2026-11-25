package com.peterhull.shelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

// The same palette and face as the project site (docs/index.html): black nylon, one ice-blue accent.
private val Nylon = Color(0xFF141518)
private val Panel = Color(0xFF1F2125)
private val Fg = Color(0xFFE8EAEE)
private val Muted = Color(0xFF9AA0AA)
private val Ice = Color(0xFF8FD3FF)
private val StitchColor = Color(0xFF3A3D44)

private val WalletColors = darkColorScheme(
    primary = Ice,
    onPrimary = Nylon,
    secondaryContainer = Panel,
    onSecondaryContainer = Fg,
    background = Nylon,
    onBackground = Fg,
    surface = Nylon,
    onSurface = Fg,
    surfaceVariant = Panel,
    onSurfaceVariant = Muted,
    surfaceContainer = Panel,
    outline = StitchColor,
    outlineVariant = StitchColor,
    error = Color(0xFFFF9A9A),
)

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: FontWeight) =
    Font(R.font.archivo, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val Archivo = FontFamily(
    archivo(FontWeight.Normal),
    archivo(FontWeight.Medium),
    archivo(FontWeight.SemiBold),
    archivo(FontWeight.Bold),
    archivo(FontWeight.ExtraBold),
)

// Line heights as a ratio of the font size, so they grow with it: Android scales large sp sizes
// non-linearly, and a fixed sp line height ends up shorter than Archivo's glyphs at big font settings.
private fun TextStyle.archivo(weight: FontWeight? = null) = copy(
    fontFamily = Archivo,
    fontWeight = weight ?: fontWeight,
    lineHeight = (lineHeight.value / fontSize.value).em,
)

private val WalletType = Typography().run {
    copy(
        displayLarge = displayLarge.archivo(),
        displayMedium = displayMedium.archivo(),
        displaySmall = displaySmall.archivo(),
        headlineLarge = headlineLarge.archivo(FontWeight.ExtraBold),
        headlineMedium = headlineMedium.archivo(FontWeight.ExtraBold),
        headlineSmall = headlineSmall.archivo(FontWeight.Bold),
        titleLarge = titleLarge.archivo(FontWeight.Bold),
        titleMedium = titleMedium.archivo(FontWeight.SemiBold),
        titleSmall = titleSmall.archivo(FontWeight.SemiBold),
        bodyLarge = bodyLarge.archivo(),
        bodyMedium = bodyMedium.archivo(),
        bodySmall = bodySmall.archivo(),
        labelLarge = labelLarge.archivo(FontWeight.SemiBold),
        labelMedium = labelMedium.archivo(FontWeight.SemiBold),
        labelSmall = labelSmall.archivo(FontWeight.SemiBold),
    )
}

@Composable
fun CdWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WalletColors, typography = WalletType, content = content)
}

/** The wallet's stitching: a dashed rule, the one ornament shared with the site. */
@Composable
fun Stitch(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        val y = size.height / 2
        drawLine(
            color,
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
}
