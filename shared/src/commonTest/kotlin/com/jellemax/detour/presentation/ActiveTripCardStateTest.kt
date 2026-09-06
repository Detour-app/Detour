package com.jellemax.detour.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the live trip card as `MapHud.kt` renders it today: `ActiveTripCard`'s
 * readouts and its second-row gate.
 */
class ActiveTripCardStateTest {

    /** A trip that has just started and got nowhere, with nothing on the
     *  second row — every test below varies one thing off this. */
    private fun state(
        startTimeMs: Long = 0L,
        nowMs: Long = 0L,
        distanceMeters: Double = 0.0,
        topSpeedKmh: Double = 0.0,
        leanDeg: Double = 0.0,
        maxLeanDeg: Double = 0.0,
        maxGForce: Double = 0.0,
        hardEvents: Int = 0,
        stopCount: Int = 0,
        currentlyOverLimit: Boolean = false,
        sep: Char = '.',
    ) = activeTripCardStateFrom(
        startTimeMs = startTimeMs,
        nowMs = nowMs,
        distanceMeters = distanceMeters,
        topSpeedKmh = topSpeedKmh,
        leanDeg = leanDeg,
        maxLeanDeg = maxLeanDeg,
        maxGForce = maxGForce,
        hardEvents = hardEvents,
        stopCount = stopCount,
        currentlyOverLimit = currentlyOverLimit,
        sep = sep,
    )

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
        val point = state(distanceMeters = 12_400.0, maxGForce = 1.25, sep = '.')
        assertEquals("12.4 km", point.distanceText)
        assertEquals("1.3 g", point.maxGForceText)

        val comma = state(distanceMeters = 12_400.0, maxGForce = 1.25, sep = ',')
        assertEquals("12,4 km", comma.distanceText)
        assertEquals("1,3 g", comma.maxGForceText)
    }

    // --- the card's remaining readouts ---

    @Test fun topSpeedAndLeanReadoutsRenderWholeNumbers() {
        val s = state(topSpeedKmh = 137.6, leanDeg = 31.5, maxLeanDeg = 46.6)
        assertEquals("138 km/h", s.topSpeedText)
        assertEquals("32°", s.leanText)
        assertEquals("47°", s.maxLeanText)
    }

    // --- the second row's gate: three ways in, one way out ---

    @Test fun hardEventsAloneOpenTheSecondRow() {
        // The sum arrives as one number and is not published back: the card
        // renders the three counts itself and only the gate reads the total.
        assertTrue(state(hardEvents = 7).detailsShown)
    }

    @Test fun secondRowHiddenOnAnUneventfulTrip() {
        assertFalse(state().detailsShown)
    }

    @Test fun stopsAloneOpenTheSecondRow() {
        assertTrue(state(stopCount = 1).detailsShown)
    }

    @Test fun beingOverTheLimitAloneOpensTheSecondRow() {
        assertTrue(state(currentlyOverLimit = true).detailsShown)
    }
}
