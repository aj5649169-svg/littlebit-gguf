package com.littlebit.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF2DD4A7)
private val InkDark = Color(0xFF101418)

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = Color(0xFF6EE7C8),
    surface = Color(0xFF14181D),
    background = InkDark,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E9F80),
    secondary = Color(0xFF0B7C66),
    surface = Color(0xFFF4F7F5),
    background = Color(0xFFFCFDFC),
)

@Composable
fun LittleBitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
