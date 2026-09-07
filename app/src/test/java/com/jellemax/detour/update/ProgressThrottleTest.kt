package com.jellemax.detour.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the throttle exists for: a 46 MB transfer calls back per 64 KiB,
 * and a `notify()` for every one of those is hundreds of binder round trips
 * for a bar that only moves in whole percent.
 */
class ProgressThrottleTest {

    @Test fun theFirstCallbackAlwaysPosts() {
        assertTrue(ProgressThrottle().shouldPost(0L, 0f))
    }

    @Test fun chunksInsideTheSamePercentAreDropped() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldPost(1_000L, 0.5f))
        assertFalse(throttle.shouldPost(1_100L, 0.501f))
        assertFalse(throttle.shouldPost(1_400L, 0.509f))
    }

    @Test fun aWholePercentChangePostsImmediately() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldPost(1_000L, 0.500f))
        // 0.515f rather than 0.51f: the nearest float to 0.51 is a hair under
        // it, so truncating 0.51f * 100 still lands on 50.
        assertTrue(throttle.shouldPost(1_001L, 0.515f))
    }

    /** Without this an indeterminate transfer — no percentage to change —
     *  would post once and then look stalled forever. */
    @Test fun theIntervalPostsEvenWhenNothingChanged() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldPost(1_000L, -1f))
        assertFalse(throttle.shouldPost(1_400L, -1f))
        assertTrue(throttle.shouldPost(1_500L, -1f))
    }

    /** The interval restarts from the post, not from the last callback, so a
     *  steady stream of chunks cannot slide the deadline forever. */
    @Test fun theIntervalIsMeasuredFromTheLastPost() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldPost(0L, 0.5f))
        assertFalse(throttle.shouldPost(400L, 0.5f))
        assertTrue(throttle.shouldPost(500L, 0.5f))
        assertFalse(throttle.shouldPost(900L, 0.5f))
    }

    /** A negative fraction is "length unknown", not 0 % — the two must not be
     *  the same bucket, or the switch from a sweep to a real bar would be
     *  swallowed as "nothing changed". */
    @Test fun unknownLengthIsItsOwnBucket() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldPost(0L, -1f))
        assertTrue(throttle.shouldPost(10L, 0.0f))
    }

    @Test fun resetForgetsThePreviousTransfer() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldPost(1_000L, 0.5f))
        assertFalse(throttle.shouldPost(1_100L, 0.5f))
        throttle.reset()
        assertTrue(throttle.shouldPost(1_100L, 0.5f))
    }
}
