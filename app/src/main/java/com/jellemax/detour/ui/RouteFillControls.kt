package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.LoopDuration

/**
 * The route editor's "fill to a riding time" row: a minutes slider and the
 * button that asks [onFill] to stretch the route to it. The minutes are this
 * row's own screen state, since nothing else reads them. The fill itself (the
 * routing calls and what they write) stays with the editor, which owns the
 * stops being filled.
 */
@Composable
internal fun RouteFillControls(
    canFill: Boolean,
    hasFill: Boolean,
    filling: Boolean,
    error: String?,
    onFill: (minutes: Float) -> Unit,
    onClearFill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var minutes by remember { mutableFloatStateOf(DEFAULT_FILL_MINUTES) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Fill to riding time", style = MaterialTheme.typography.labelLarge)
            Text(
                formatDurationHistory(LoopDuration.targetMs(minutes)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = minutes,
            onValueChange = { minutes = it },
            valueRange = LoopDuration.MIN_MINUTES..LoopDuration.MAX_MINUTES,
            // Same 5-minute stops as the spin sheet's trip-time slider.
            steps = ((LoopDuration.MAX_MINUTES - LoopDuration.MIN_MINUTES) /
                LoopDuration.STEP_MINUTES).toInt() - 1,
        )
        Text(
            "Your stops stay put; the rest of the time is filled with detours between them. " +
                "One stop fills a loop from it and back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onFill(minutes) },
                enabled = canFill && !filling,
                modifier = Modifier.weight(1f),
            ) {
                if (filling) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (hasFill) "Fill again" else "Fill route")
            }
            if (hasFill) {
                TextButton(onClick = onClearFill, enabled = !filling) { Text("Remove fill") }
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** An hour: the ride a route with a couple of fixed stops is usually built around. */
private const val DEFAULT_FILL_MINUTES = 60f
