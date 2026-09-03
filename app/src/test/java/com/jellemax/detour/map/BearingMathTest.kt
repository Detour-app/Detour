package com.jellemax.detour.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [bearingDelta] and [smoothBearing] — the compass-wrap arithmetic the
 * phone and car camera loops both ease headings with. Extracted from two
 * verbatim copies (one in `ui/MapCameraTuning`, one private in
 * `car/CarMapRenderer`); these assert the merged version still does what both
 * did, including the `null` current the car copy never accepted.
 */
class BearingMathTest {

    @Test
    fun `bearingDelta takes the short way across north`() {
        assertEquals(2f, bearingDelta(359f, 1f), 1e-4f)
        assertEquals(2f, bearingDelta(1f, 359f), 1e-4f)
        assertEquals(10f, bearingDelta(5f, 355f), 1e-4f)
    }

    @Test
    fun `bearingDelta is 0_180, never negative and never reflex`() {
        assertEquals(180f, bearingDelta(0f, 180f), 1e-4f)
        assertEquals(90f, bearingDelta(270f, 0f), 1e-4f)
        assertEquals(0f, bearingDelta(42f, 42f), 1e-4f)
    }

    @Test
    fun `bearingDelta tolerates one turn of over-range on either input`() {
        // Single-if corrections, so the contract is inputs within one wrap of
        // [0,360). Every caller is already in range; this pins the bound.
        assertEquals(20f, bearingDelta(-10f, 10f), 1e-4f)
        assertEquals(20f, bearingDelta(370f, 350f), 1e-4f)
    }

    @Test
    fun `smoothBearing returns the target unchanged when there is no current`() {
        assertEquals(123f, smoothBearing(null, 123f), 1e-4f)
    }

    @Test
    fun `smoothBearing interpolates by alpha`() {
        assertEquals(50f, smoothBearing(0f, 100f, alpha = 0.5f), 1e-3f)
    }

    @Test
    fun `smoothBearing eases the short way round the wrap`() {
        // 350 -> 10 is +20 the short way, not -340. Half-step lands at 0/360.
        val eased = smoothBearing(350f, 10f, alpha = 0.5f)
        assertEquals(0f, eased % 360f, 1e-3f)
    }

    @Test
    fun `smoothBearing output stays in 0_360`() {
        val eased = smoothBearing(5f, 355f, alpha = 0.5f)
        assertTrue("was $eased", eased in 0f..360f)
    }
}
