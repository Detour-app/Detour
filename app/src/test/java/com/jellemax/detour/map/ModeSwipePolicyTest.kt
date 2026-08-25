package com.jellemax.detour.map

import com.jellemax.detour.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
