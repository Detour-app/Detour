package com.jellemax.detour.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Social sits under Hub and pops back to it — the redesign's social cluster. */
class SocialNavTest {
    @Test fun socialPushesOverHubAndPopsBackToIt() {
        val stack = mutableListOf<Destination>(Destination.Map, Destination.Hub)
        stack.push(Destination.Social)
        assertEquals(Destination.Social, stack.last())
        stack.pop()
        assertEquals(Destination.Hub, stack.last())
    }
}
