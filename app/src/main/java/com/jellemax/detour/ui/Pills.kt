package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/** Gap between segments, and the two horizontal paddings a row degrades
 *  through before it is allowed to shrink the label. */
private const val GAP_DP = 8f
private const val PAD_WIDE_DP = 14f
private const val PAD_TIGHT_DP = 8f

/** labelMedium (12sp) over labelLarge (14sp): the floor the label may shrink
 *  to before wrapping is the only remaining option. */
private const val SHRINK_FLOOR = 12f / 14f

/** A row this narrow has no readable answer left, so stop shrinking: below
 *  this even a two- or three-item row wraps, because the alternative is a
 *  label drawn wider than its segment and cut by the clip. Reachable — a
 *  320dp screen at font scale 2 puts the trip card's "Standard" here. */
private const val MIN_SCALE = 0.5f

/** Up to this many segments the row stays on one line: Mode (2, in the spin
 *  sheet) and the decimal separator (3, in Settings) are switchers small
 *  enough that breaking one over two lines would read as breakage rather
 *  than as a control. Both have width to spare - the decimal row's widest
 *  label, "System" at 49dp, sits in a 93dp share and still fits with the wide
 *  padding at fontScale 1.3.
 *
 *  Theme is four entries (SYSTEM, LIGHT, DARK, AUTO), so it is not covered by
 *  this rule, and at 296dp - a 360dp screen less the scaffold's 16dp
 *  (SettingsScreen.kt:159) and the section card's 16dp
 *  (SettingsScreen.kt:1259), both sides - its 68dp share takes "System" on the
 *  tighter padding at fontScale 1 with 2.6dp to spare, and wraps to two rows
 *  of two from about fontScale 1.23 up. Accepted rather than papered over: raising this to 4
 *  would sweep in the destination-type row, where "Food & drink" has a 70dp
 *  share and would have to shrink past the floor - the clipping this component
 *  exists to prevent. A wrapped grid of equal segments is the intended last
 *  rung; a clipped label is the bug. */
private const val MAX_NON_SCROLLING = 3

/** How [ChoiceRow] resolves one row: every segment [itemWidthDp] wide, its
 *  label drawn at [fontScale] of labelLarge inside [paddingDp] of horizontal
 *  padding, with the segments wrapping onto more lines only when [wraps],
 *  [perLine] to a line. */
internal data class ChoiceRowMetrics(
    val itemWidthDp: Float,
    val paddingDp: Float,
    val fontScale: Float,
    val wraps: Boolean,
    val perLine: Int,
)

/** The degradation order from the widest label that has to fit, in the order
 *  the issue fixed: equal widths at full size while the set fits, then
 *  tighter padding, then a smaller label down to labelMedium, and only then
 *  wrapping — where the segments still share one width, so a nine-option row
 *  reads as one grid rather than nine differently sized pills.
 *
 *  Wrapping replaced horizontal scrolling (#1029): a scrolled row cut its last
 *  visible label at the edge ("North-east") with nothing saying it scrolled.
 *  The wrapped segments are balanced over the lines — four go two and two, not
 *  three and one — and stretched to fill the row, so the grid has no ragged
 *  right edge.
 *
 *  [widestLabelDp] is measured at the bold weight the selected segment draws
 *  in, which is the widest any label ever gets; sizing off the regular weight
 *  would clip a label the moment it was picked.
 *
 *  Pure so the arithmetic can be tested without a Compose harness — the app
 *  has none. */
internal fun choiceRowMetrics(
    rowWidthDp: Float,
    widestLabelDp: Float,
    count: Int,
): ChoiceRowMetrics {
    // An unbounded parent (a horizontally scrolling ancestor) reports an
    // infinite maxWidth; dividing that into slots would hand Modifier.width an
    // infinite Dp and crash the layout pass.
    if (!rowWidthDp.isFinite() || count <= 0) {
        return ChoiceRowMetrics(widestLabelDp + 2 * PAD_WIDE_DP, PAD_WIDE_DP, 1f, false, count)
    }

    val slot = (rowWidthDp - GAP_DP * (count - 1)) / count
    if (widestLabelDp + 2 * PAD_WIDE_DP <= slot) {
        return ChoiceRowMetrics(slot, PAD_WIDE_DP, 1f, false, count)
    }
    if (widestLabelDp + 2 * PAD_TIGHT_DP <= slot) {
        return ChoiceRowMetrics(slot, PAD_TIGHT_DP, 1f, false, count)
    }
    // Roboto's advances scale linearly with the font size, so the fraction of
    // the label that fits is the fraction of the size that does.
    val scale = (slot - 2 * PAD_TIGHT_DP) / widestLabelDp
    // A short row keeps shrinking past the floor rather than wrap, but not
    // past MIN_SCALE: clamping there drew the label wider than its segment.
    if (scale >= SHRINK_FLOOR || (count <= MAX_NON_SCROLLING && scale >= MIN_SCALE)) {
        return ChoiceRowMetrics(slot, PAD_TIGHT_DP, scale.coerceAtMost(1f), false, count)
    }
    return wrapped(rowWidthDp, widestLabelDp, count)
}

/** The last rung of [choiceRowMetrics]: as many tight-padded segments per line
 *  as fit, rebalanced so the lines hold near-equal counts, each segment
 *  stretched to share its line's width. A label wider than the whole row (no
 *  shipped one is) keeps its content width and overflows, as it would have
 *  scrolled before. */
private fun wrapped(rowWidthDp: Float, widestLabelDp: Float, count: Int): ChoiceRowMetrics {
    val content = widestLabelDp + 2 * PAD_TIGHT_DP
    val fits = floor((rowWidthDp + GAP_DP) / (content + GAP_DP)).toInt().coerceIn(1, count)
    val lines = (count + fits - 1) / fits
    val perLine = (count + lines - 1) / lines
    val width = (rowWidthDp - GAP_DP * (perLine - 1)) / perLine
    return ChoiceRowMetrics(maxOf(width, content), PAD_TIGHT_DP, 1f, true, perLine)
}

/** A wrapped segment's width in whole pixels: the row's pixels, less the
 *  gaps, split [perLine] ways and rounded down. `Modifier.width` rounds a
 *  fractional dp to the nearest pixel, so [ChoiceRowMetrics.itemWidthDp]
 *  could come out half a pixel wide per segment; a line of them then ran a
 *  pixel past the row and FlowRow pushed its last segment onto the next line
 *  (304dp at 2.625x: 388.5dp-px segments, two plus a gap 799px in 798). A
 *  one-to-a-line row keeps its metrics width - there is no sibling to push. */
internal fun wrappedSegmentPx(rowWidthPx: Int, gapPx: Int, perLine: Int, metricsPx: Int): Int =
    if (perLine <= 1) metricsPx else minOf(metricsPx, (rowWidthPx - gapPx * (perLine - 1)) / perLine)

/** One segment of a [ChoiceRow]: rounded, filled with the primary colour when
 *  selected, and exactly
 *  as wide as every one of its siblings. `softWrap = false` with
 *  `maxLines = 1` is what makes the no-wrap guarantee structural rather than
 *  a hope about widths — [choiceRowMetrics] then guarantees it never has to
 *  overflow either. */
@Composable
private fun ChoiceItem(
    label: String,
    selected: Boolean,
    metrics: ChoiceRowMetrics,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.labelLarge
    Surface(
        modifier = modifier
            // A pill is ~36dp tall, so it needs the 48dp target reserved
            // around it - Surface(onClick =) used to do this for free and a
            // plain Surface does not. Before the width, per the ordering
            // SearchIsland.kt:341-347 documents: outside it, the fixed size
            // clamps the target back to the pill.
            .minimumInteractiveComponentSize()
            .width(width)
            // Clip before selectable so the ripple stays inside the pill.
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = CircleShape,
        // Primary, not secondaryContainer: on the dark theme that sat a few
        // shades off surfaceContainerHigh and the picked side was hard to
        // tell from the other at a glance (#1029).
        color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = metrics.paddingDp.dp, vertical = 8.dp),
            style = style,
            fontSize = style.fontSize * metrics.fontScale,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/** The app's one pick-one row: Mode, destination type, direction, theme,
 *  decimal separator, fuel type and the trip card's two pickers.
 *
 *  It exists because the app had two of these and both went ugly on a long
 *  label, in opposite ways. Content-sized pills kept every label whole but
 *  made a row of two ("Car" next to "Moto") look broken, and they were
 *  themselves the fix for an equal-width variant that divided the width
 *  evenly and cut "Food & drink" mid-word — `maxLines = 1` with the default
 *  `Clip` overflow ends a label with no ellipsis to admit it. Material's
 *  `SingleChoiceSegmentedButtonRow` had the opposite failure: equal widths,
 *  but a long label wraps to a second line inside its segment while its
 *  neighbours stay on one, and the row grows taller.
 *
 *  So the width is measured rather than assumed: the widest label decides,
 *  through [choiceRowMetrics], what every segment gets. Equal widths hold
 *  whenever the set fits at all, and the label is one line by construction.
 *
 *  Not `BasicText`'s `autoSize`, which foundation 1.10 does have: it sizes
 *  each `Text` on its own, so a row would end up with "Auto" large next to a
 *  shrunken "Food & drink". One row is one type size, decided once. */
@Composable
internal fun ChoiceRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val boldLabel = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widest = remember(options, boldLabel, density) {
            options.maxOfOrNull { ceil(measurer.measure(it, boldLabel).size.width / density.density) } ?: 0f
        }
        val metrics = remember(widest, maxWidth, options.size) {
            choiceRowMetrics(maxWidth.value, widest, options.size)
        }
        val width = with(density) {
            if (!metrics.wraps) metrics.itemWidthDp.dp else wrappedSegmentPx(
                rowWidthPx = constraints.maxWidth,
                gapPx = GAP_DP.dp.roundToPx(),
                perLine = metrics.perLine,
                metricsPx = ceil(metrics.itemWidthDp * density.density).toInt(),
            ).toDp()
        }
        val items = @Composable {
            options.forEachIndexed { i, label ->
                ChoiceItem(label, i == selectedIndex, metrics, width, { onSelect(i) })
            }
        }
        if (metrics.wraps) {
            // No vertical gap: each pill already reserves a 48dp touch target
            // around its ~36dp body, which spaces the lines on its own.
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
                // The balanced count, as a guard: the width above already
                // fits exactly this many, so one more never squeezes in.
                maxItemsInEachRow = metrics.perLine,
            ) { items() }
        } else {
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            ) { items() }
        }
    }
}
