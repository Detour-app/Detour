package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the speed HUD and the live trip card as `MapHud.kt` renders them
 * today: `SpeedHud`'s dial and posted-limit sign, `SectionAverageChip`'s
 * running average, and `ActiveTripCard`'s readouts, hard-event sum and
 * second-row gate.
 */
class TripHudStateTest {

    /** A stationary trip that has just started, with nothing on the second
     *  row — every test below varies one thing off this. */
    private fun state(
        speedKmh: Double = 0.0,
        limitKmh: Double? = null,
        averageKmh: Double? = null,
        averageLimitKmh: Double? = null,
        startTimeMs: Long = 0L,
        nowMs: Long = 0L,
        distanceMeters: Double = 0.0,
        topSpeedKmh: Double = 0.0,
        leanDeg: Double = 0.0,
        maxLeanDeg: Double = 0.0,
        gForce: Double = 0.0,
        hardBrakeCount: Int = 0,
        hardAccelCount: Int = 0,
        hardCornerCount: Int = 0,
        stopCount: Int = 0,
        currentlyOverLimit: Boolean = false,
        overLimitToleranceKmh: Double = 5.0,
        sep: Char = '.',
    ) = tripHudStateFrom(
        speedKmh = speedKmh,
        limitKmh = limitKmh,
        averageKmh = averageKmh,
        averageLimitKmh = averageLimitKmh,
        startTimeMs = startTimeMs,
        nowMs = nowMs,
        distanceMeters = distanceMeters,
        topSpeedKmh = topSpeedKmh,
        leanDeg = leanDeg,
        maxLeanDeg = maxLeanDeg,
        gForce = gForce,
        hardBrakeCount = hardBrakeCount,
        hardAccelCount = hardAccelCount,
        hardCornerCount = hardCornerCount,
        stopCount = stopCount,
        currentlyOverLimit = currentlyOverLimit,
        overLimitToleranceKmh = overLimitToleranceKmh,
        sep = sep,
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
        // The app owns the value (Wear cannot see :shared); this only applies
        // whatever arrives, so a caller passing 0 gets a stricter HUD.
        assertTrue(state(speedKmh = 51.0, limitKmh = 50.0, overLimitToleranceKmh = 0.0).speeding)
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

    // --- distance: the metres-to-kilometres step ---

    @Test fun distanceStaysInMetresBelowTheKilometre() {
        assertEquals("999 m", state(distanceMeters = 999.4).distanceText)
    }

    @Test fun distanceJustUnderAKilometreStillRoundsAsMetres() {
        // The branch is taken on the raw value, so 999.6 is "1000 m", not
        // "1.0 km" — the one place this reads oddly, and long-standing.
        assertEquals("1000 m", state(distanceMeters = 999.6).distanceText)
    }

    @Test fun distanceSwitchesToKilometresAtExactlyOneThousand() {
        assertEquals("1.0 km", state(distanceMeters = 1000.0).distanceText)
    }

    // --- the stopwatch ---

    @Test fun zeroDurationTripReadsZero() {
        assertEquals("0:00", state(startTimeMs = 1_000_000L, nowMs = 1_000_000L).durationText)
    }

    @Test fun durationCountsUpAndGrowsAnHoursField() {
        assertEquals("7:19", state(startTimeMs = 1_000L, nowMs = 440_000L).durationText)
        assertEquals("1:12:36", state(startTimeMs = 0L, nowMs = 4_356_000L).durationText)
    }

    @Test fun clockGoingBackwardsClampsRatherThanCountingDown() {
        assertEquals("0:00", state(startTimeMs = 5_000L, nowMs = 1_000L).durationText)
    }

    // --- the rider's decimal separator ---

    @Test fun bothSeparatorSettingsReachEveryDecimalReadout() {
        val point = state(distanceMeters = 12_400.0, gForce = 1.25, sep = '.')
        assertEquals("12.4 km", point.distanceText)
        assertEquals("1.3 g", point.gForceText)

        val comma = state(distanceMeters = 12_400.0, gForce = 1.25, sep = ',')
        assertEquals("12,4 km", comma.distanceText)
        assertEquals("1,3 g", comma.gForceText)
    }

    // --- the card's remaining readouts ---

    @Test fun speedTopAndLeanReadoutsRenderWholeNumbers() {
        val s = state(speedKmh = 112.4, topSpeedKmh = 137.6, leanDeg = 31.5, maxLeanDeg = 46.6)
        assertEquals("112", s.speedText)
        assertEquals("138 km/h", s.topSpeedText)
        assertEquals("32°", s.leanText)
        assertEquals("47°", s.maxLeanText)
    }

    // --- the second row: the sum, and the gate over it ---

    @Test fun hardEventsIsTheSumOfAllThreeKinds() {
        val s = state(hardBrakeCount = 2, hardAccelCount = 1, hardCornerCount = 4)
        assertEquals(7, s.hardEvents)
        assertTrue(s.detailsShown)
    }

    @Test fun secondRowHiddenOnAnUneventfulTrip() {
        val s = state()
        assertEquals(0, s.hardEvents)
        assertFalse(s.detailsShown)
    }

    @Test fun stopsAloneOpenTheSecondRow() {
        assertTrue(state(stopCount = 1).detailsShown)
    }

    @Test fun beingOverTheLimitAloneOpensTheSecondRow() {
        assertTrue(state(currentlyOverLimit = true).detailsShown)
    }
}
