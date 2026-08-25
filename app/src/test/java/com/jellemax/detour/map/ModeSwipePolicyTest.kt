package com.jellemax.detour.map

import com.jellemax.detour.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [ModeSwipePolicy] - every decision the spin dock's mode swipe makes.
 * The gesture itself has no automated coverage anywhere (this repo has no
 * Robolectric and no androidTest source set), so the arithmetic and the gates
 * are pulled out here where they can be tested without a device.
 */
class ModeSwipePolicyTest {

    @Test
    fun `nothing in flight means the swipe is allowed`() {
        assertNull(ModeSwipePolicy.blockedReason(spinning = false, tracking = false))
    }

    @Test
    fun `a spin in flight blocks the swipe`() {
        assertEquals(
            "Cancel the spin to change mode",
            ModeSwipePolicy.blockedReason(spinning = true, tracking = false),
        )
    }

    @Test
    fun `a recording trip blocks the swipe`() {
        assertEquals(
            "Stop the trip to change mode",
            ModeSwipePolicy.blockedReason(spinning = false, tracking = true),
        )
    }

    /** Both at once is reachable: a spin can be started while a trip records.
     *  The spin is the one the dice button can cancel, so it is named first. */
    @Test
    fun `a spin outranks a trip when both are in flight`() {
        assertEquals(
            "Cancel the spin to change mode",
            ModeSwipePolicy.blockedReason(spinning = true, tracking = true),
        )
    }

    @Test
    fun `each mode pairs with the other one`() {
        assertEquals(TravelMode.CAR, ModeSwipePolicy.other(TravelMode.MOTO))
        assertEquals(TravelMode.MOTO, ModeSwipePolicy.other(TravelMode.CAR))
    }

    @Test
    fun `travel inside the commit distance follows the finger exactly`() {
        assertEquals(40f, ModeSwipePolicy.dragOffsetDp(40f, blocked = false), 0.01f)
        assertEquals(-40f, ModeSwipePolicy.dragOffsetDp(-40f, blocked = false), 0.01f)
        assertEquals(84f, ModeSwipePolicy.dragOffsetDp(84f, blocked = false), 0.01f)
    }

    /** Past the commit point the card keeps moving, but slower than the finger,
     *  so the threshold is felt rather than read. */
    @Test
    fun `travel past the commit distance is resisted`() {
        // 84 + (184 - 84) * 0.35 = 119
        assertEquals(119f, ModeSwipePolicy.dragOffsetDp(184f, blocked = false), 0.01f)
        assertEquals(-119f, ModeSwipePolicy.dragOffsetDp(-184f, blocked = false), 0.01f)
    }

    @Test
    fun `a blocked drag resists from a far shorter limit`() {
        assertEquals(6f, ModeSwipePolicy.dragOffsetDp(6f, blocked = true), 0.01f)
        // 8 + (108 - 8) * 0.35 = 43 ... a long blocked pull still moves, but the
        // first 8dp is the only part that tracks the finger.
        assertEquals(43f, ModeSwipePolicy.dragOffsetDp(108f, blocked = true), 0.01f)
        assertEquals(-43f, ModeSwipePolicy.dragOffsetDp(-108f, blocked = true), 0.01f)
    }

    @Test
    fun `a short slow drag does not commit`() {
        assertFalse(ModeSwipePolicy.commits(offsetDp = 30f, velocityDpPerS = 50f, blocked = false))
    }

    @Test
    fun `travel past the commit distance commits`() {
        assertTrue(ModeSwipePolicy.commits(offsetDp = 84f, velocityDpPerS = 0f, blocked = false))
        assertTrue(ModeSwipePolicy.commits(offsetDp = -90f, velocityDpPerS = 0f, blocked = false))
    }

    @Test
    fun `a fling commits even when the travel is short`() {
        assertTrue(ModeSwipePolicy.commits(offsetDp = 20f, velocityDpPerS = 600f, blocked = false))
        assertTrue(ModeSwipePolicy.commits(offsetDp = -20f, velocityDpPerS = -600f, blocked = false))
    }

    @Test
    fun `a blocked drag never commits, however far or fast`() {
        assertFalse(ModeSwipePolicy.commits(offsetDp = 200f, velocityDpPerS = 900f, blocked = true))
    }

    /** A tap that jitters a pixel is not a swipe. */
    @Test
    fun `a drag of essentially nothing does not commit`() {
        assertFalse(ModeSwipePolicy.commits(offsetDp = 0.4f, velocityDpPerS = 0f, blocked = false))
    }

    @Test
    fun `no travel means no offset`() {
        assertEquals(0f, ModeSwipePolicy.dragOffsetDp(0f, blocked = false), 0.01f)
        assertEquals(0f, ModeSwipePolicy.dragOffsetDp(0f, blocked = true), 0.01f)
    }

    /** The offset arm is pinned at its exact threshold; this does the same for
     *  the velocity arm, so a `>=` quietly becoming `>` fails here. */
    @Test
    fun `the fling threshold is inclusive`() {
        assertFalse(ModeSwipePolicy.commits(offsetDp = 50f, velocityDpPerS = 399f, blocked = false))
        assertTrue(ModeSwipePolicy.commits(offsetDp = 50f, velocityDpPerS = 400f, blocked = false))
    }

    @Test
    fun `a first-time user with an idle dock is due the hint`() {
        assertTrue(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 0L, blocked = false))
    }

    @Test
    fun `the hint fires at most once per map visit`() {
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = true, swipesUsed = 0L, blocked = false))
    }

    @Test
    fun `the hint retires once the gesture has been used three times`() {
        assertTrue(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 2L, blocked = false))
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 3L, blocked = false))
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 9L, blocked = false))
    }

    /** Demonstrating a gesture the user is not allowed to make right now is
     *  worse than not demonstrating it. */
    @Test
    fun `a blocked swipe suppresses the hint`() {
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 0L, blocked = true))
    }

    @Test
    fun `the hint variant is read from its stored name`() {
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of("nudge"))
        assertEquals(ModeSwipePolicy.HintVariant.ARROWS, ModeSwipePolicy.HintVariant.of("arrows"))
        assertEquals(ModeSwipePolicy.HintVariant.ARROWS, ModeSwipePolicy.HintVariant.of("ARROWS"))
    }

    /** The value is written by a debug broadcast, so a typo must not crash the
     *  map screen - and the whole variant disappears once one of them wins. */
    @Test
    fun `an unknown or missing variant falls back to the nudge`() {
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of(null))
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of(""))
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of("wiggle"))
    }
}
