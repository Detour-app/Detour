package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [navEndDecision] is the "what happens to the trip when navigation ends"
 * decision pulled out of [TripTrackingService] (issues #271, #272): end it,
 * leave it to auto-detection, or leave it alone. The wiring that feeds it live
 * state and acts on the result — calling endTrip, flipping autoStarted — is
 * verified by a GPS replay, not here.
 */
class NavEndTest {

    private val window = 30_000L

    @Test fun `a nav-started trip ends when the rider has stopped`() {
        assertEquals(
            NavEndAction.END_NOW,
            navEndDecision(tripActive = true, navStarted = true, endNow = false,
                msSinceMoving = window + 1, movingWindowMs = window),
        )
    }

    @Test fun `arrival ends a nav-started trip at once, even mid-motion`() {
        // endNow wins over the movement check: the rider is decelerating into
        // the destination, moved a second ago, and the drive is still over.
        assertEquals(
            NavEndAction.END_NOW,
            navEndDecision(tripActive = true, navStarted = true, endNow = true,
                msSinceMoving = 1_000, movingWindowMs = window),
        )
    }

    @Test fun `exiting while still driving hands the drive to auto-detection`() {
        // Not split into two trips — the drive continues and auto-detection
        // owns its stop. This is #271's fix: the trip becomes stoppable again.
        assertEquals(
            NavEndAction.HAND_TO_AUTODETECT,
            navEndDecision(tripActive = true, navStarted = true, endNow = false,
                msSinceMoving = 2_000, movingWindowMs = window),
        )
    }

    @Test fun `an auto-detected drive is left alone when navigation ends`() {
        // navStarted = false: navigation was laid over an existing drive, so
        // ending navigation must neither end nor re-flag it (#272 AC).
        for (endNow in listOf(true, false)) {
            for (moving in listOf(0L, window + 1)) {
                assertEquals(
                    NavEndAction.IGNORE,
                    navEndDecision(tripActive = true, navStarted = false, endNow = endNow,
                        msSinceMoving = moving, movingWindowMs = window),
                )
            }
        }
    }

    @Test fun `no trip means nothing to end`() {
        assertEquals(
            NavEndAction.IGNORE,
            navEndDecision(tripActive = false, navStarted = true, endNow = true,
                msSinceMoving = 0, movingWindowMs = window),
        )
    }
}
