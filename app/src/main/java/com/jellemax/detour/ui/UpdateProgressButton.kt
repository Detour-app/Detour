package com.jellemax.detour.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Material's own minimum touch target; a filled button is normally 40dp tall
 *  and borrows the rest from an invisible margin, which a full-width control
 *  in a list card has no room to do. */
private const val BUTTON_MIN_HEIGHT_DP = 48

/** Long enough that a fill stepping 1% at a time glides, short enough that it
 *  has caught up before the next progress callback lands. */
private const val FILL_EASE_MS = 250

/** The indeterminate sweep: a band this wide crossing the button in this long.
 *  Slower than Material's indeterminate bar on purpose — the band re-colours
 *  the label as it passes, and a fast strobe over words is unpleasant. */
private const val SWEEP_WIDTH = 0.4f
private const val SWEEP_PERIOD_MS = 1400

/**
 * A filled button whose fill *is* the progress bar.
 *
 * The alternative — a button with a [androidx.compose.material3.LinearProgressIndicator]
 * under it — puts two controls on a settings row to describe one download, and
 * the row is already carrying a title and a subtitle. One control that changes
 * in place says the same thing in the space there is.
 *
 * [fraction] follows `UpdateStatus.Downloading`'s convention rather than
 * flattening it: `null` is a plain button with nothing to report, `>= 0f` is a
 * determinate fill, and **negative** means the server sent no length, which
 * draws a travelling band instead of a percentage nobody knows.
 *
 * The label is drawn twice — once in the track's content colour, once in the
 * fill's, clipped to exactly the filled region. A single colour cannot be read
 * against both `primary` and `primaryContainer` in either theme, and a label
 * that goes unreadable at 50% is worse than the extra `Text`.
 */
@Composable
fun UpdateProgressButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = BUTTON_MIN_HEIGHT_DP.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                // TalkBack reads the label from the Text below; this adds the
                // part a sighted rider gets from the fill and it does not.
                if (fraction != null) {
                    progressBarRangeInfo = if (fraction >= 0f) {
                        ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                    } else {
                        ProgressBarRangeInfo.Indeterminate
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(label, MaterialTheme.colorScheme.onPrimaryContainer)
        if (fraction != null) ProgressFill(label, fraction)
    }
}

/** The filled part, drawn over the track and clipped to it: the same label in
 *  the same place, so the two layers register exactly and only the colour
 *  changes where the fill has reached. */
@Composable
private fun BoxScope.ProgressFill(label: String, fraction: Float) {
    val window = if (fraction >= 0f) 0f to determinateEdge(fraction) else sweepWindow()
    Box(
        Modifier
            .matchParentSize()
            .drawWithContent {
                clipRect(left = window.first * size.width, right = window.second * size.width) {
                    this@drawWithContent.drawContent()
                }
            }
            // Without this the same words are in the tree twice and TalkBack
            // says them twice; the layer is paint, not content.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary))
        ButtonLabel(label, MaterialTheme.colorScheme.onPrimary)
    }
}

/** Where the determinate fill's right edge is, eased so a jump between two
 *  progress callbacks glides instead of stepping. */
@Composable
private fun determinateEdge(fraction: Float): Float {
    val eased by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = FILL_EASE_MS, easing = LinearEasing),
        label = "updateFill",
    )
    return eased
}

/** The travelling band's left and right edges, as fractions of the width. It
 *  starts and ends off the button — the edges are allowed outside `0f..1f`
 *  because `clipRect` takes the intersection with the layer's own bounds. */
@Composable
private fun sweepWindow(): Pair<Float, Float> {
    val transition = rememberInfiniteTransition(label = "updateSweep")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f + SWEEP_WIDTH,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = SWEEP_PERIOD_MS, easing = LinearEasing),
        ),
        label = "updateSweepHead",
    )
    return head - SWEEP_WIDTH to head
}

@Composable
private fun ButtonLabel(label: String, color: Color) {
    Text(
        label,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}
