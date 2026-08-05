package com.jellemax.detour.ui

import androidx.compose.foundation.border
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The frosted-glass look for cards that float over the live map: a translucent
 * surface so the map reads through them, plus a hairline highlight border that
 * catches the edge the way real glass does. True backdrop blur needs API 31+
 * window blur, which is unreliable over the GL map surface, so translucency +
 * border + elevation carry the effect instead. Alpha is kept high — without a
 * blur to even it out, a low alpha lets the map's bright and dark patches bleed
 * through unevenly and the card looks like two different translucencies. High
 * alpha keeps a single uniform frost that still hints at the map underneath.
 */
private const val GLASS_ALPHA = 0.92f

/** Card colours for a glass overlay: translucent surface, normal on-surface text. */
@Composable
fun glassCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = GLASS_ALPHA),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

/** Translucent container for a glass FAB, so the buttons don't punch solid holes
 *  in the map. */
@Composable
fun glassContainerColor() =
    MaterialTheme.colorScheme.surface.copy(alpha = GLASS_ALPHA)

/** The hairline edge highlight that sells the glass. Apply with the same shape
 *  the card is clipped to. */
@Composable
fun Modifier.glassBorder(shape: Shape): Modifier = border(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    shape = shape,
)
