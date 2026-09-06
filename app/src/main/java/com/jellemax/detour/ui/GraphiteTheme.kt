package com.jellemax.detour.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The Graphite identity: warm graphite grounds with a single amber accent by
 * night, a cool white ground with a clear blue accent by day. Replaces Material
 * You dynamic colour so the app has its own look instead of the wallpaper's.
 * Blue on white reads cleaner over the map than amber did.
 */

// Night — warm graphite + amber.
val GraphiteDark = darkColorScheme(
    primary = Color(0xFFE8B04B),
    onPrimary = Color(0xFF241C08),
    primaryContainer = Color(0xFF4A3A16),
    onPrimaryContainer = Color(0xFFF6DFAE),
    secondary = Color(0xFFC9B58A),
    onSecondary = Color(0xFF2A2410),
    // Light enough to read as a pill against surfaceContainer (0xFF1F231A) —
    // the old 0xFF2E2A1E made the nav-bar selection nearly invisible.
    secondaryContainer = Color(0xFF3D3728),
    onSecondaryContainer = Color(0xFFEAE0C6),
    tertiary = Color(0xFFE8B04B),
    onTertiary = Color(0xFF241C08),
    tertiaryContainer = Color(0xFF3A3016),
    onTertiaryContainer = Color(0xFFF3E4BE),
    background = Color(0xFF14170F),
    onBackground = Color(0xFFEDE9DB),
    surface = Color(0xFF191C14),
    onSurface = Color(0xFFEDE9DB),
    surfaceVariant = Color(0xFF2A2E22),
    onSurfaceVariant = Color(0xFFB7AF98),
    surfaceContainerLowest = Color(0xFF101309),
    surfaceContainerLow = Color(0xFF1A1E15),
    surfaceContainer = Color(0xFF1F231A),
    surfaceContainerHigh = Color(0xFF24281D),
    surfaceContainerHighest = Color(0xFF2C3025),
    outline = Color(0xFF6E6A58),
    outlineVariant = Color(0xFF464A3A),
    error = Color(0xFFE4533A),
    onError = Color(0xFF2A0E08),
    errorContainer = Color(0xFF5A2317),
    onErrorContainer = Color(0xFFFBD8CE),
    inverseSurface = Color(0xFFEDE9DB),
    inverseOnSurface = Color(0xFF1A1E15),
    inversePrimary = Color(0xFFB87A1B),
)

// Day — cool white + a light, friendly blue.
val GraphiteLight = lightColorScheme(
    primary = Color(0xFF2F80ED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBFD),
    onPrimaryContainer = Color(0xFF0B3D82),
    secondary = Color(0xFF5A7196),
    onSecondary = Color(0xFFFFFFFF),
    // Deep enough to read as a pill against surfaceContainer (0xFFEDF3FA) —
    // the old 0xFFE1ECF9 was near-identical to the bar behind it.
    secondaryContainer = Color(0xFFD2E3F6),
    onSecondaryContainer = Color(0xFF1A2D45),
    tertiary = Color(0xFF2F80ED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCEBFD),
    onTertiaryContainer = Color(0xFF0B3D82),
    background = Color(0xFFF6F9FD),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE3EAF3),
    onSurfaceVariant = Color(0xFF56606E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F7FC),
    surfaceContainer = Color(0xFFEDF3FA),
    surfaceContainerHigh = Color(0xFFE7EEF7),
    surfaceContainerHighest = Color(0xFFE0E9F3),
    outline = Color(0xFF8A96A6),
    outlineVariant = Color(0xFFCAD5E3),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADAD5),
    onErrorContainer = Color(0xFF5A1710),
    inverseSurface = Color(0xFF2A2F36),
    inverseOnSurface = Color(0xFFEDF3FA),
    inversePrimary = Color(0xFFAACDF8),
)
