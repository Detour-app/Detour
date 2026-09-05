package com.jellemax.detour.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The prototype's list/section card: 20dp corners, a low-emphasis surface
 * container, a faint outline. Not specific to any one screen — You, Social,
 * and Profile each wrap a block of content in exactly this shell.
 */
@Composable
fun ListCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    // Overridable for the one card that floats over the live map: the search
    // island passes glassCardColors() so it frosts like the pill above it
    // instead of punching an opaque hole in the map.
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = colors,
        border = BorderStroke(1.dp, borderColor),
        content = content,
    )
}

/**
 * The divider between rows inside a [ListCard] — 0.35-alpha outlineVariant,
 * the one this repo drew by hand at five identical call sites (the Hub's
 * shortcut list, the Social row pair, Saved places). One definition now.
 */
@Composable
fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}
