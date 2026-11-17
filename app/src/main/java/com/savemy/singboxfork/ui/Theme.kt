package com.savemy.singboxfork.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.savemy.singboxfork.data.Settings

/** Палитра OriginOS/Фарватер: глубокий фон, атомарные карточки, циан для VPN, изумруд для пинга. */
object Palette {
    val Ink = Color(0xFF0B0D12)
    val Surface = Color(0xFF12151C)
    val SurfaceHigh = Color(0xFF1A1F2A)
    val Cyan = Color(0xFF38BDF8)
    val Emerald = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)
    val Rose = Color(0xFFFB7185)
    val TextPrimary = Color(0xFFE8EDF6)
    val TextMuted = Color(0xFF8A93A6)
    /** Полупрозрачная заливка карточки: белый 6 %. */
    val CardFill = Color(0x0FFFFFFF)
    /** Обводка карточки: белый 8 %. */
    val CardStroke = Color(0x14FFFFFF)
    val CardShape = RoundedCornerShape(24.dp)
}

/** Единые формы: «атомарные» карточки 24.dp, мелкие элементы 12.dp. */
private val BoxShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = Palette.CardShape,
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable fun BoxTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "light" -> false; "dark", "halloween", "newyear" -> true; else -> isSystemInDarkTheme() }
    val accent = Color(settings.accent)
    val colors = if (dark) darkColorScheme(
        primary = accent, onPrimary = Palette.Ink,
        secondary = Palette.Cyan, onSecondary = Palette.Ink,
        tertiary = Palette.Emerald, onTertiary = Palette.Ink,
        background = Palette.Ink, onBackground = Palette.TextPrimary,
        surface = Palette.Surface, onSurface = Palette.TextPrimary,
        surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.TextMuted,
        outline = Palette.CardStroke, error = Palette.Rose
    ) else lightColorScheme(
        primary = accent.copy(red = accent.red * .48f, green = accent.green * .48f, blue = accent.blue * .48f),
        secondary = Palette.Cyan, tertiary = Palette.Emerald,
        background = Color(0xFFF4F7FB), onBackground = Color(0xFF111821),
        surface = Color.White, onSurface = Color(0xFF111821),
        surfaceVariant = Color(0xFFE8EEF6), onSurfaceVariant = Color(0xFF56617A)
    )
    MaterialTheme(colorScheme = colors, shapes = BoxShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                content()
                if (settings.theme == "newyear") Snow()
            }
        }
    }
}

@Composable private fun Snow() {
    val context = LocalContext.current
    val enabled = remember { AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0 }
    if (!enabled) return
    val transition = rememberInfiniteTransition(label = "Снег")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "Снежинки")
    // Canvas не перехватывает касания и не содержит текста для скринридера.
    Canvas(Modifier.fillMaxSize()) {
        repeat(28) { i ->
            val x = ((i * 0.618034f) % 1f) * size.width
            val y = ((i * 0.173f + progress * (.6f + i % 3 * .2f)) % 1f) * size.height
            drawCircle(Color.White.copy(alpha = .17f), 1.8f + (i % 3), Offset(x, y))
        }
    }
}
