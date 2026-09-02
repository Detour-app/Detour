package com.jellemax.detour.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [cappedFixDtSec] is the shared "gap since the last fix, in seconds, or drop
 * it" rule the fuel integrator and secondsOverLimit both need: a gap outside
 * 1..15 s (a tunnel, a Doze window, a BT dropout) is discarded so the next
 * real fix's own Δt spans it, rather than saturating at 15 s of invented fuel
 * or over-limit time.
 */
class CappedFixDtSecTest {

    @Test fun unsetPreviousStampGivesNull() {
        assertNull(cappedFixDtSec(nowMs = 10_000L, lastMs = 0L))
    }

    @Test fun sameInstantGivesNull() {
        assertNull(cappedFixDtSec(nowMs = 10_000L, lastMs = 10_000L))
    }

    @Test fun aOneSecondGapGivesOneSecond() {
        assertEquals(1.0, cappedFixDtSec(nowMs = 11_000L, lastMs = 10_000L)!!, 1e-9)
    }

    @Test fun aGapAtTheFifteenSecondCeilingIsKept() {
        assertEquals(15.0, cappedFixDtSec(nowMs = 25_000L, lastMs = 10_000L)!!, 1e-9)
    }

    @Test fun aGapPastTheCeilingIsDropped() {
        assertNull(cappedFixDtSec(nowMs = 25_001L, lastMs = 10_000L))
    }

    @Test fun aNegativeGapFromAClockStepIsDropped() {
        assertNull(cappedFixDtSec(nowMs = 9_000L, lastMs = 10_000L))
    }
}
