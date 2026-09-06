package com.jellemax.detour.tracking

import com.jellemax.detour.data.CirclePresence.GateCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceGeofenceGateTest {

    @Test fun `a fence id round trips through encode and decode`() {
        val id = placeFenceId("circle-1", 42L, PlaceFenceKind.ENTER)
        assertEquals(Triple("circle-1", 42L, PlaceFenceKind.ENTER), parsePlaceFenceId(id))
    }

    @Test fun `decode rejects a malformed id`() {
        assertNull(parsePlaceFenceId("not-a-fence-id"))
        assertNull(parsePlaceFenceId("circle-1:notanumber:enter"))
        assertNull(parsePlaceFenceId("circle-1:42:sideways"))
    }

    @Test fun `diff finds ids to add and ids to remove`() {
        val diff = diffFenceIds(
            registered = setOf("a:1:enter", "a:1:exit", "b:2:enter"),
            target = setOf("a:1:enter", "a:1:exit", "c:3:enter"),
        )
        assertEquals(setOf("c:3:enter"), diff.toAdd)
        assertEquals(setOf("b:2:enter"), diff.toRemove)
    }

    @Test fun `an unchanged set diffs to nothing`() {
        val ids = setOf("a:1:enter", "a:1:exit")
        val diff = diffFenceIds(registered = ids, target = ids)
        assertEquals(emptySet<String>(), diff.toAdd)
        assertEquals(emptySet<String>(), diff.toRemove)
    }

    @Test fun `target fence ids are two per candidate`() {
        val candidates = listOf(
            GateCandidate(circleId = "a", placeId = 1L, lat = 0.0, lon = 0.0, radiusM = 50.0, distanceM = 10.0),
        )
        val target = targetFenceIds(candidates)
        assertEquals(setOf("a:1:enter", "a:1:exit"), target)
    }
}
