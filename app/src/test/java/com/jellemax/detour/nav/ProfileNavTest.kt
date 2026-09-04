package com.jellemax.detour.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Profile sits under Hub. Sign-out returns to the map root (no dangling stack). */
class ProfileNavTest {
    @Test fun profilePushesOverHubAndPopsBackToIt() {
        val stack = mutableListOf<Destination>(Destination.Map, Destination.Hub)
        stack.push(Destination.Profile)
        assertEquals(Destination.Profile, stack.last())
        stack.pop()
        assertEquals(Destination.Hub, stack.last())
    }

    @Test fun returnToMapClearsProfileAndHub() {
        val stack = mutableListOf<Destination>(Destination.Map, Destination.Hub, Destination.Profile)
        stack.returnToMap()
        assertEquals(1, stack.size)
        assertEquals(Destination.Map, stack.last())
    }
}
