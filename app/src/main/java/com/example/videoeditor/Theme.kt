package com.example.videoeditor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E0F12)
val Panel = Color(0xFF16181D)
val Panel2 = Color(0xFF1F2229)
val Line = Color(0xFF2E323B)
val TextHi = Color(0xFFF2F4F7)
val TextLo = Color(0xFFA3AAB5)
val TrackVideo = Color(0xFF3B6FD4)
val TrackAudio = Color(0xFF2E9E6B)
val TrackAudio2 = Color(0xFF7C5CE0)
val TrackText = Color(0xFFD9822B)
val PlayheadColor = Color(0xFFFF4D4D)
val KeyColor = Color(0xFFFFC107)

val Accents = listOf(
    Color(0xFF5B8CFF),
    Color(0xFF9B6BFF),
    Color(0xFF2FD1A3),
    Color(0xFFFF7A59),
    Color(0xFFFF4D8D)
)

fun rvedtColors(accent: Color): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.30f),
    onPrimaryContainer = TextHi,
    secondary = accent,
    onSecondary = Color.White,
    secondaryContainer = accent.copy(alpha = 0.30f),
    onSecondaryContainer = TextHi,
    background = Bg,
    onBackground = TextHi,
    surface = Panel,
    onSurface = TextHi,
    surfaceVariant = Panel2,
    onSurfaceVariant = TextLo,
    outline = Line,
    outlineVariant = Line
)
