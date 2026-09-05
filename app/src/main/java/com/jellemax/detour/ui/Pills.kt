package com.jellemax.detour.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One pill in a [ScrollingPillRow]: rounded, filled when selected. No new
 *  dependency — built on Surface rather than SegmentedButton so the row can
 *  scroll horizontally rather than compress its pills to fit. */
@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** A horizontally scrolling row of pills — the direction picker (9 options)
 *  and the destination-type row.
 *
 *  The destination-type row used to be its own equal-width variant, which
 *  divided the width evenly and so sized every pill to 1/n regardless of its
 *  label. "Food & drink" and "Viewpoint" did not fit their quarter and were
 *  cut mid-word, because [Pill] is `maxLines = 1` and the default overflow is
 *  `Clip` — no ellipsis, so the label simply ended. Sizing each pill to its
 *  own content fixes that, and scrolling absorbs the overflow on a narrow
 *  screen instead of truncating. */
@Composable
internal fun ScrollingPillRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            Pill(label, i == selectedIndex, { onSelect(i) })
        }
    }
}
