package com.itantra.edgespeech.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Modern Clean Light Palette
val TechBluePrimary = Color(0xFF2563EB)
val TechBlueDark = Color(0xFF1D4ED8)
val EmeraldConnected = Color(0xFF10B981)
val AmberSearching = Color(0xFFF59E0B)
val CrimsonAlert = Color(0xFFDC2626)

val BackgroundLight = Color(0xFFF8FAFC)
val SurfaceCard = Color(0xFFFFFFFF)
val SurfaceCardAlt = Color(0xFFF1F5F9)
val BorderSubtle = Color(0xFFE2E8F0)

val TextPrimaryDark = Color(0xFF0F172A)
val TextSecondaryMuted = Color(0xFF64748B)

val BubbleSent = Color(0xFFEFF6FF)
val BubbleReceived = Color(0xFFFFFFFF)

private val LightColorScheme = lightColorScheme(
    primary = TechBluePrimary,
    onPrimary = Color.White,
    secondary = EmeraldConnected,
    onSecondary = Color.White,
    background = BackgroundLight,
    surface = SurfaceCard,
    error = CrimsonAlert,
    onError = Color.White,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark
)

@Composable
fun ITantraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
