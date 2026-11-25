package com.littlebit.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.compositeOver
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
    val paper = when { black -> Color.Black; dark -> Color(0xFF0F1115); else -> Color(0xFFF8F9FA) }
    val card = when { black -> Color(0xFF0E0E11); dark -> Color(0xFF171A21); else -> Color.White }
    val pill = when { black -> Color(0xFF17171C); dark -> Color(0xFF20242D); else -> Color(0xFFEEF0F3) }
    val edge = when { black -> Color(0xFF24242B); dark -> Color(0xFF323641); else -> Color(0xFFE2E5EA) }
    val ink = if (dark) Color(0xFFF0F0F3) else Color(0xFF21252B)
    val muted = if (dark) Color(0xFFB9BDC7) else Color(0xFF535B66)
    val tint = accent.copy(alpha = .14f).compositeOver(card)
    val colors = base.copy(primary = accent, secondary = accent, tertiary = accent,
        onPrimary = if (dark) Color.Black else Color.White,
        primaryContainer = tint, onPrimaryContainer = ink, secondaryContainer = tint, onSecondaryContainer = ink,
        background = paper, onBackground = ink, surface = paper, onSurface = ink, onSurfaceVariant = muted,
        surfaceContainerLowest = paper, surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = card, surfaceContainerHighest = card, surfaceVariant = pill, outlineVariant = edge)
    val typography = Typography()
    CompositionLocalProvider(LocalHapticsEnabled provides settings.haptics) {
        MaterialTheme(colorScheme = colors,
            typography = typography.copy(headlineSmall = typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                labelSmall = typography.labelSmall.copy(letterSpacing = .45.sp), bodyLarge = typography.bodyLarge.copy(fontSize = settings.font.sp, lineHeight = (settings.font * 1.5f).sp)),
            content = content)
    }
}
