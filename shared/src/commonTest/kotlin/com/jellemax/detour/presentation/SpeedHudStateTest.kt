package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the speed HUD as `MapHud.kt` renders it today: `SpeedHud`'s dial and
 * posted-limit sign, and `SectionAverageChip`'s running average.
 */
class SpeedHudStateTest {

    /** A standstill with no sign and no section — every test below varies one
     *  thing off this. */
    private fun state(
        speedKmh: Double = 0.0,
        limitKmh: Double? = null,
        averageKmh: Double? = null,
        averageLimitKmh: Double? = null,
        overLimitToleranceKmh: Double = 5.0,
    ) = speedHudStateFrom(
        speedKmh = speedKmh,
        limitKmh = limitKmh,
        averageKmh = averageKmh,
        averageLimitKmh = averageLimitKmh,
        overLimitToleranceKmh = overLimitToleranceKmh,
    )

    // --- the +5 threshold: the colour of the number a rider glances at ---

    @Test fun notSpeedingAtTheLimitItself() {
        assertFalse(state(speedKmh = 50.0, limitKmh = 50.0).speeding)
    }

    @Test fun notSpeedingExactlyOnTheTolerance() {
        // Strictly greater than limit + 5, so 55 in a 50 is still not red.
        assertFalse(state(speedKmh = 55.0, limitKmh = 50.0).speeding)
    }

    @Test fun speedingJustPastTheTolerance() {
        assertTrue(state(speedKmh = 55.1, limitKmh = 50.0).speeding)
    }

    @Test fun notSpeedingJustUnderTheTolerance() {
        assertFalse(state(speedKmh = 54.9, limitKmh = 50.0).speeding)
    }

    @Test fun neverSpeedingWithoutAPostedLimit() {
        // No sign, nothing to be over — however fast the reading is.
        val s = state(speedKmh = 180.0, limitKmh = null)
        assertFalse(s.speeding)
        assertNull(s.limitSignText)
    }

    @Test fun toleranceIsTheCallersToPass() {
        // The app owns the value (MapCameraTuning.OVER_LIMIT_TOLERANCE_KMH,
        // which the Android Auto dial reads too); this only applies whatever
        // arrives, so a caller passing 0 gets a stricter HUD.
        assertTrue(state(speedKmh = 51.0, limitKmh = 50.0, overLimitToleranceKmh = 0.0).speeding)
    }

    // --- the dial itself ---

    @Test fun speedDialRendersAWholeNumberWithNoUnit() {
        assertEquals("112", state(speedKmh = 112.4).speedText)
    }

    // --- the posted-limit sign ---

    @Test fun limitSignCarriesTheWholeNumber() {
        assertEquals("70", state(limitKmh = 70.0).limitSignText)
    }

    // --- the trajectcontrole chip ---

    @Test fun noAverageChipOutsideASection() {
        val s = state(averageKmh = null, averageLimitKmh = 100.0)
        assertNull(s.averageText)
        assertFalse(s.averageOverLimit)
    }

    @Test fun averageChipRendersAndReddensOnTheSectionsOwnLimit() {
        val under = state(averageKmh = 98.4, averageLimitKmh = 100.0)
        assertEquals("Ø 98", under.averageText)
        assertFalse(under.averageOverLimit)
        assertTrue(state(averageKmh = 100.1, averageLimitKmh = 100.0).averageOverLimit)
    }

    @Test fun averageIsNotOverWithoutASectionLimitToBeOver() {
        assertFalse(state(averageKmh = 130.0, averageLimitKmh = null).averageOverLimit)
    }

    @Test fun averageOverLimitIsIndependentOfTheDialsOwnRed() {
        // The case the island has to render: over right now *and* over on
        // average, which is the ordinary case inside a trajectcontrole. Both
        // flags are true at once, so a renderer that shows the average's state
        // only while the dial is calm is dropping a signal, not deduplicating
        // one.
        val both = state(
            speedKmh = 130.0, limitKmh = 100.0,
            averageKmh = 104.0, averageLimitKmh = 100.0,
        )
        assertTrue(both.speeding)
        assertTrue(both.averageOverLimit)
        // And the converse: braking back under the posted limit does not clear
        // the average that the camera pair has already banked.
        val braking = state(
            speedKmh = 90.0, limitKmh = 100.0,
            averageKmh = 104.0, averageLimitKmh = 100.0,
        )
        assertFalse(braking.speeding)
        assertTrue(braking.averageOverLimit)
    }
}
