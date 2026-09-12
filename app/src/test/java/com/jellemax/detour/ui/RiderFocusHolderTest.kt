package com.jellemax.detour.ui

import com.jellemax.detour.data.RiderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pins [RiderFocusHolder]'s one real rule: [RiderFocusHolder.clear] only
 * clears the request it was handed, never a newer one published while
 * MapScreen was still resolving the last one (#294).
 */
class RiderFocusHolderTest {

    @Before
    fun reset() {
        RiderFocusHolder.state.value?.let { RiderFocusHolder.clear(it) }
    }

    @Test
    fun requestPublishesTheAskedForRider() {
        val alex = RiderId("alex")
        RiderFocusHolder.request(alex, "Alex")
        val published = RiderFocusHolder.state.value
        assertNotNull(published)
        assertEquals(alex, published!!.riderId)
        assertEquals("Alex", published.displayName)
    }

    @Test
    fun clearOnTheHandledRequestRemovesIt() {
        RiderFocusHolder.request(RiderId("alex"), "Alex")
        val handled = RiderFocusHolder.state.value!!
        RiderFocusHolder.clear(handled)
        assertNull(RiderFocusHolder.state.value)
    }

    @Test
    fun clearOnASupersededRequestLeavesTheNewOneAlone() {
        RiderFocusHolder.request(RiderId("alex"), "Alex")
        val stale = RiderFocusHolder.state.value!!
        // A second tap — on the same or a different rider — arrives while
        // the first request is still being resolved.
        RiderFocusHolder.request(RiderId("bo"), "Bo")
        RiderFocusHolder.clear(stale)
        val current = RiderFocusHolder.state.value
        assertNotNull(current)
        assertEquals(RiderId("bo"), current!!.riderId)
    }
}
