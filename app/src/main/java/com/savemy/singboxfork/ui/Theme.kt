package com.savemy.singboxfork.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings as AndroidSettings
import com.savemy.singboxfork.data.Settings

@Composable fun BoxTheme(settings: Settings,content: @Composable () -> Unit) {
    val dark=when(settings.theme) { "light" -> false; "dark","halloween","newyear" -> true; else -> isSystemInDarkTheme() }
    val accent=Color(settings.accent)
    val colors=if(dark) darkColorScheme(primary=accent,onPrimary=Color(0xFF06231F),background=Color(0xFF0C141B),surface=Color(0xFF14212B),surfaceVariant=Color(0xFF1E303A))
        else lightColorScheme(primary=accent.copy(red=accent.red*.48f,green=accent.green*.48f,blue=accent.blue*.48f),background=Color(0xFFF1F6F6),surface=Color.White)
    MaterialTheme(colorScheme=colors) {
        Surface(Modifier.fillMaxSize()) {
            Box {
                content()
                if(settings.theme=="newyear") Snow()
            }
        }
    }
}
@Composable private fun Snow() {
    val context=LocalContext.current
    val enabled=remember { AndroidSettings.Global.getFloat(context.contentResolver,AndroidSettings.Global.ANIMATOR_DURATION_SCALE,1f)>0 }
    if(!enabled) return
    val transition=rememberInfiniteTransition(label="Снег")
    val progress by transition.animateFloat(0f,1f,infiniteRepeatable(tween(18000,easing=LinearEasing)),label="Снежинки")
    // Canvas не перехватывает касания и не содержит текста для скринридера.
    Canvas(Modifier.fillMaxSize()) {
        repeat(28) { i ->
            val x=((i*0.618034f)%1f)*size.width
            val y=((i*0.173f+progress*(.6f+i%3*.2f))%1f)*size.height
            drawCircle(Color.White.copy(alpha=.17f),1.8f+(i%3),Offset(x,y))
        }
    }
}
