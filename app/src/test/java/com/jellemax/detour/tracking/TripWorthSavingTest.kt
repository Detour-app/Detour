package com.jellemax.detour.tracking

import com.jellemax.detour.tracking.TripSession.Companion.worthSaving
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripWorthSavingTest {

    private val minMeters = TripTrackingService.MIN_TRIP_METERS
    private val minMs = TripTrackingService.MIN_TRIP_DURATION_MS

    @Test fun `a real drive clears both floors`() {
        assertTrue(worthSaving(minMeters, minMs, wasAuto = false, looksLikeAWalk = false))
        assertTrue(worthSaving(minMeters, minMs, wasAuto = true, looksLikeAWalk = false))
    }

    @Test fun `an accidental manual trip too short in time is dropped`() {
        // The old gate only required durationMs > 0, so this used to be saved.
        assertFalse(worthSaving(minMeters, minMs - 1, wasAuto = false, looksLikeAWalk = false))
    }

    @Test fun `an accidental manual trip too short in distance is dropped`() {
        assertFalse(worthSaving(minMeters - 1, minMs, wasAuto = false, looksLikeAWalk = false))
    }

    @Test fun `the same floors apply to auto trips`() {
        assertFalse(worthSaving(minMeters - 1, minMs, wasAuto = true, looksLikeAWalk = false))
        assertFalse(worthSaving(minMeters, minMs - 1, wasAuto = true, looksLikeAWalk = false))
    }

    @Test fun `an auto trip that looks like a walk is dropped even past the floors`() {
        assertFalse(worthSaving(minMeters, minMs, wasAuto = true, looksLikeAWalk = true))
    }

    @Test fun `the walk filter does not apply to a manually ended trip`() {
        assertTrue(worthSaving(minMeters, minMs, wasAuto = false, looksLikeAWalk = true))
    }
}
