package com.jellemax.detour.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pins `choiceRowMetrics`, the sizing rule behind [ChoiceRow].
 *
 * There is no Compose test harness in this app (no Robolectric, no
 * compose-ui-test), so the layout itself is verified by compiling; the part
 * that can go wrong arithmetically is separated out and checked here.
 *
 * The label widths are Roboto advances at 14sp (labelLarge), summed from the
 * font's `hmtx` table and rounded up a little for the bold weight the selected
 * segment draws in: "Food & drink" 78.1dp regular, "North-east" 65.7dp,
 * "System" 46.4dp, "Moto" 32.8dp, "Light" 31.1dp.
 *
 * The row widths are the ones that actually ship on a 360dp screen, each
 * traced to the paddings between the row and the window:
 *
 *  - 304dp in the spin sheet: 360 − 12dp of slot inset (MapBottom.kt:158)
 *    − 16dp of card padding (SpinCards.kt:99), both sides.
 *  - 296dp in a Settings section: 360 − 16dp of scaffold padding
 *    (SettingsScreen.kt:159) − 16dp inside the section's card
 *    (SettingsScreen.kt:1259), both sides.
 *
 * The trip card's two pickers sit in an AlertDialog, whose width is the
 * platform's rather than a padding that can be read off a parent, so no case
 * here claims one; both are 3 items or fewer and so never wrap whatever it
 * turns out to be.
 */
class ChoiceRowMetricsTest {

    private val eps = 0.01f

    @Test
    fun `two-item mode row is equal width at full size`() {
        val m = choiceRowMetrics(rowWidthDp = 304f, widestLabelDp = 34.5f, count = 2)
        assertEquals(148f, m.itemWidthDp, eps)
        assertEquals(14f, m.paddingDp, eps)
        assertEquals(1f, m.fontScale, eps)
        assertFalse(m.wraps)
    }

    @Test
    fun `four-entry theme row tightens rather than wraps at fontScale 1`() {
        // Theme is SYSTEM, LIGHT, DARK, AUTO - four, not three, so it is over
        // MAX_NON_SCROLLING and stays on one line on the arithmetic alone.
        // Widest is "System", 49.4dp, in a 68dp share of the 296dp row: it
        // clears the tight padding by 2.6dp.
        val m = choiceRowMetrics(rowWidthDp = 296f, widestLabelDp = 49.4f, count = 4)
        assertEquals((296f - 24f) / 4f, m.itemWidthDp, eps)
        assertEquals(8f, m.paddingDp, eps)
        assertEquals(1f, m.fontScale, eps)
        assertFalse(m.wraps)
    }

    @Test
    fun `four-entry theme row wraps two and two at fontScale 1_3`() {
        // "System" at 1.3x is 64.2dp, and 68dp of share less 16dp of padding
        // leaves 52dp - a 0.81 label, under the 12/14 floor. Four entries are
        // over MAX_NON_SCROLLING, so the row wraps rather than clip, from
        // about fontScale 1.23 up. Three would fit a line, but balanced lines
        // are two and two rather than three and an orphan.
        val m = choiceRowMetrics(rowWidthDp = 296f, widestLabelDp = 64.2f, count = 4)
        assertTrue(m.wraps)
        assertEquals((296f - 8f) / 2f, m.itemWidthDp, eps)
        assertEquals(1f, m.fontScale, eps)
    }

    @Test
    fun `three-entry decimal separator row keeps the wide padding at fontScale 1_3`() {
        // The same "System" label, but three entries give it a 93.3dp share of
        // the same 296dp row, so it never leaves the first rung.
        val m = choiceRowMetrics(rowWidthDp = 296f, widestLabelDp = 64.2f, count = 3)
        assertEquals((296f - 16f) / 3f, m.itemWidthDp, eps)
        assertEquals(14f, m.paddingDp, eps)
        assertEquals(1f, m.fontScale, eps)
        assertFalse(m.wraps)
    }

    @Test
    fun `a label that misses by a few dp tightens the padding rather than shrinking`() {
        val m = choiceRowMetrics(rowWidthDp = 200f, widestLabelDp = 80f, count = 2)
        assertEquals(96f, m.itemWidthDp, eps)
        assertEquals(8f, m.paddingDp, eps)
        assertEquals(1f, m.fontScale, eps)
        assertFalse(m.wraps)
    }

    @Test
    fun `a label that misses tight padding shrinks toward labelMedium`() {
        val m = choiceRowMetrics(rowWidthDp = 200f, widestLabelDp = 88f, count = 2)
        assertEquals(96f, m.itemWidthDp, eps)
        assertEquals(8f, m.paddingDp, eps)
        assertEquals((96f - 16f) / 88f, m.fontScale, eps)
        assertFalse(m.wraps)
    }

    @Test
    fun `a three-item row shrinks below the floor before it wraps`() {
        // 200 / 3 less the gaps is a 61dp slot, 45dp of text area against a
        // 70dp label: 0.64 of the size, under labelMedium's 0.857 floor. A
        // four-item row would wrap here; three stay put and shrink.
        val m = choiceRowMetrics(rowWidthDp = 200f, widestLabelDp = 70f, count = 3)
        assertFalse(m.wraps)
        assertEquals((61.33f - 16f) / 70f, m.fontScale, eps)
    }

    @Test
    fun `a three-item row wraps once even the smallest label would not fit`() {
        // A 120dp row: 33dp slots, 17dp of text against an 88dp label. This
        // used to clamp the scale at 0.5 and hand the clip a 44dp label in a
        // 17dp area — the bug the component exists to prevent, on the trip
        // card's layout picker at 320dp and font scale 2.
        val m = choiceRowMetrics(rowWidthDp = 120f, widestLabelDp = 88f, count = 3)
        assertTrue(m.wraps)
        // 88 + 16 = 104dp fits once per line, so one segment a line, full width.
        assertEquals(120f, m.itemWidthDp, eps)
    }

    @Test
    fun `no row draws a label wider than its text area or a line wider than the row`() {
        // The invariant every branch has to hold, swept over the shipped row
        // widths, the measured labels and every count from none to nine —
        // including 0 and 1, which the early returns handle.
        val rows = listOf(120f, 200f, 296f, 304f, 360f)
        val labels = listOf(20f, 46.4f, 65.7f, 82f, 100f)
        val rowLabels = rows.flatMap { row -> labels.map { row to it } }
        val cases = (0..9).flatMap { count -> rowLabels.map { Triple(count, it.first, it.second) } }
        cases.forEach { (count, row, label) ->
            val m = choiceRowMetrics(row, label, count)
            assertTrue(
                "row=$row label=$label count=$count",
                m.itemWidthDp - 2 * m.paddingDp + eps >= label * m.fontScale,
            )
            if (!m.wraps) return@forEach
            // A wrapped segment is never wider than the row it wraps in.
            val perLine = floor((row + 8f + eps) / (m.itemWidthDp + 8f)).toInt()
            assertTrue("row=$row label=$label count=$count", perLine >= 1)
        }
    }

    @Test
    fun `the nine-option direction row wraps into a three by three grid`() {
        // "North-east" (69dp) plus tight padding is 85dp, so three fit a 304dp
        // line; nine make three full lines, each segment a third of the row.
        // This row used to scroll and cut "North-east" at the edge (#1029).
        val m = choiceRowMetrics(rowWidthDp = 304f, widestLabelDp = 69f, count = 9)
        assertTrue(m.wraps)
        assertEquals(3, m.perLine)
        assertEquals((304f - 16f) / 3f, m.itemWidthDp, eps)
        assertEquals(1f, m.fontScale, eps)
    }

    @Test
    fun `the four-item destination row keeps Food and drink whole by wrapping`() {
        // 4 x ("Food & drink" at the labelMedium floor + the tightest padding)
        // is 355dp against 304dp of row, so no one-line layout fits; the row
        // wraps two and two at full size rather than clip the label, which
        // is the bug this component replaced.
        val m = choiceRowMetrics(rowWidthDp = 304f, widestLabelDp = 82f, count = 4)
        assertTrue(m.wraps)
        assertEquals((304f - 8f) / 2f, m.itemWidthDp, eps)
        assertEquals(1f, m.fontScale, eps)
    }

    @Test
    fun `a wrapped line of whole-pixel segments fits its row at 2_625x`() {
        // The review's case: the 304dp spin-sheet row at 2.625x is 798px,
        // the 8dp gap 21px. 148dp segments are 388.5px, which Modifier.width
        // rounds to 389: two plus the gap made 799px and FlowRow pushed the
        // second segment onto its own line, so 2x2 drew as 4x1.
        val m = choiceRowMetrics(rowWidthDp = 304f, widestLabelDp = 82f, count = 4)
        assertEquals(2, m.perLine)
        val px = wrappedSegmentPx(rowWidthPx = 798, gapPx = 21, perLine = m.perLine, metricsPx = 389)
        assertEquals(388, px)
    }

    @Test
    fun `wrapped segments never overrun the row in pixels at any shipped density`() {
        val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)
        val rows = listOf(120f, 200f, 296f, 304f, 360f)
        val labels = listOf(46.4f, 65.7f, 69f, 82f, 100f)
        val cases = densities.flatMap { d -> rows.flatMap { row -> labels.map { Triple(d, row, it) } } }
            .flatMap { c -> (4..9).map { c to it } }
        cases.forEach { (c, count) ->
            val (d, row, label) = c
            val m = choiceRowMetrics(row, label, count)
            if (!m.wraps || m.perLine <= 1) return@forEach
            val rowPx = floor(row * d).toInt()
            val gapPx = (8f * d).roundToInt()
            val px = wrappedSegmentPx(rowPx, gapPx, m.perLine, ceil(m.itemWidthDp * d).toInt())
            assertTrue(
                "d=$d row=$row label=$label count=$count",
                px * m.perLine + gapPx * (m.perLine - 1) <= rowPx,
            )
        }
    }

    @Test
    fun `an unbounded parent falls back to content width instead of an infinite one`() {
        val m = choiceRowMetrics(rowWidthDp = Float.POSITIVE_INFINITY, widestLabelDp = 82f, count = 4)
        assertFalse(m.wraps)
        assertEquals(82f + 28f, m.itemWidthDp, eps)
    }
}
