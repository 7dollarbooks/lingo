package com.livetranslate.headphones.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette (used when Material You dynamic color isn't available).
private val Indigo = Color(0xFF4C5BD4)
private val IndigoLightContainer = Color(0xFFE1E2FF)
private val IndigoDark = Color(0xFFBCC2FF)
private val Teal = Color(0xFF00A6A0)
private val Violet = Color(0xFF7C4DFF)

val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoLightContainer,
    onPrimaryContainer = Color(0xFF060B57),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB7F3EF),
    onSecondaryContainer = Color(0xFF00201E),
    tertiary = Violet,
    onTertiary = Color.White,
    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE4E2F0),
    onSurfaceVariant = Color(0xFF474657),
    outline = Color(0xFF787685),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val DarkColors = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF12206B),
    primaryContainer = Color(0xFF2E3A9E),
    onPrimaryContainer = IndigoLightContainer,
    secondary = Color(0xFF80D8D2),
    onSecondary = Color(0xFF003734),
    secondaryContainer = Color(0xFF00504C),
    onSecondaryContainer = Color(0xFFB7F3EF),
    tertiary = Color(0xFFCFBCFF),
    onTertiary = Color(0xFF2C0F72),
    background = Color(0xFF101019),
    onBackground = Color(0xFFE4E1EC),
    surface = Color(0xFF16161F),
    onSurface = Color(0xFFE4E1EC),
    surfaceVariant = Color(0xFF474657),
    onSurfaceVariant = Color(0xFFC7C4D6),
    outline = Color(0xFF918FA0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
