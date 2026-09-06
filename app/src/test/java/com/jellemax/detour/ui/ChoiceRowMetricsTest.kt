package com.jellemax.detour.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
 * "System" 46.4dp, "Moto" 32.8dp, "Light" 31.1dp. The row widths are the real
 * ones: 304dp for the spin sheet (360dp screen − 12dp sheet inset − 16dp card
 * padding, both sides) and 328dp for a Settings section (360dp − 16dp).
 */
class ChoiceRowMetricsTest {

    private val eps = 0.01f

    @Test
    fun `two-item mode row is equal width at full size`() {
        val m = choiceRowMetrics(rowWidthDp = 304f, widestLabelDp = 34.5f, count = 2)
        assertEquals(148f, m.itemWidthDp, eps)
        assertEquals(14f, m.paddingDp, eps)
        assertEquals(1f, m.fontScale, eps)
        assertFalse(m.scrolls)
    }

    @Test
    fun `three-item theme row still fits at fontScale 1_3`() {
        // "Light" at 1.3x = 42.5dp; a Settings section is 328dp wide.
        val m = choiceRowMetrics(rowWidthDp = 328f, widestLabelDp = 42.5f, count = 3)
        assertEquals((328f - 16f) / 3f, m.itemWidthDp, eps)
        assertEquals(14f, m.paddingDp, eps)
        assertEquals(1f, m.fontScale, eps)
        assertFalse(m.scrolls)
    }

    @Test
    fun `a label that misses by a few dp tightens the padding rather than shrinking`() {
        val m = choiceRowMetrics(rowWidthDp = 200f, widestLabelDp = 80f, count = 2)
        assertEquals(96f, m.itemWidthDp, eps)
        assertEquals(8f, m.paddingDp, eps)
        assertEquals(1f, m.fontScale, eps)
        assertFalse(m.scrolls)
    }

    @Test
    fun `a label that misses tight padding shrinks toward labelMedium`() {
        val m = choiceRowMetrics(rowWidthDp = 200f, widestLabelDp = 88f, count = 2)
        assertEquals(96f, m.itemWidthDp, eps)
        assertEquals(8f, m.paddingDp, eps)
        assertEquals((96f - 16f) / 88f, m.fontScale, eps)
        assertFalse(m.scrolls)
    }

    @Test
    fun `a three-item row never scrolls, however badly the label fits`() {
        val m = choiceRowMetrics(rowWidthDp = 120f, widestLabelDp = 88f, count = 3)
        assertFalse(m.scrolls)
        assertTrue(m.fontScale >= 0.5f)
    }

    @Test
    fun `the nine-option direction row scrolls with one shared width`() {
        val m = choiceRowMetrics(rowWidthDp = 304f, widestLabelDp = 69f, count = 9)
        assertTrue(m.scrolls)
        // Content-sized off the widest label, and every segment gets that same
        // width - a scrolling row is still one control, not nine sizes.
        assertEquals(69f + 28f, m.itemWidthDp, eps)
        assertEquals(1f, m.fontScale, eps)
    }

    @Test
    fun `the four-item destination row keeps Food and drink whole by scrolling`() {
        // 4 x ("Food & drink" at the labelMedium floor + the tightest padding)
        // is 355dp against 304dp of row, so no equal-width layout fits; the
        // segment is then sized to the label instead of the label to the
        // segment, which is the clipping bug this replaced.
        val m = choiceRowMetrics(rowWidthDp = 304f, widestLabelDp = 82f, count = 4)
        assertTrue(m.scrolls)
        assertEquals(82f + 28f, m.itemWidthDp, eps)
        assertEquals(1f, m.fontScale, eps)
    }

    @Test
    fun `an unbounded parent falls back to content width instead of an infinite one`() {
        val m = choiceRowMetrics(rowWidthDp = Float.POSITIVE_INFINITY, widestLabelDp = 82f, count = 4)
        assertFalse(m.scrolls)
        assertEquals(82f + 28f, m.itemWidthDp, eps)
    }
}
