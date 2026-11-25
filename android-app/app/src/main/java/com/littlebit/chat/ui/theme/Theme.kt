package com.littlebit.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.littlebit.chat.data.SettingsStore

val LocalHapticsEnabled = compositionLocalOf { true }

@Composable
fun LittleBitTheme(settings: SettingsStore, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        "System" -> isSystemInDarkTheme()
        "Light" -> false
        else -> true
    }
    val accents = when (settings.accent) {
        "Ocean Blue" -> 0xFF84CAFF to 0xFF005E98
        "Violet" -> 0xFFD3B5FF to 0xFF684397
        "Amber" -> 0xFFFFBD54 to 0xFF825100
        "Rose" -> 0xFFFFB1C5 to 0xFFA02D54
        else -> 0xFF63DEB3 to 0xFF006B51
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val black = settings.theme == "AMOLED Pure Black"
    val accent = Color(if (dark) accents.first else accents.second)
    val paper = if (dark) Color(0xFF111615) else Color(0xFFF8FAF7)
    val card = if (dark) Color(0xFF202725) else Color(0xFFE9EFEB)
    val colors = base.copy(primary = accent, secondary = accent, tertiary = accent,
        onPrimary = if (dark) Color.Black else Color.White,
        background = if (black) Color.Black else paper,
        surface = if (black) Color.Black else paper,
        surfaceContainer = if (black) Color(0xFF0D0D0F) else card,
        surfaceContainerHighest = if (black) Color(0xFF0D0D0F) else card,
        surfaceVariant = if (black) Color(0xFF0D0D0F) else card)
    val typography = Typography()
    CompositionLocalProvider(LocalHapticsEnabled provides settings.haptics) {
        MaterialTheme(colorScheme = colors,
            typography = typography.copy(bodyLarge = typography.bodyLarge.copy(fontSize = settings.font.sp, lineHeight = (settings.font + 8).sp)),
            content = content)
    }
}
