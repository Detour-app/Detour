package com.jellemax.detour.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The edge enumeration #66 had to do by hand, executable.
 *
 * #66 proved its animation fix by enumerating all 23 app-level edges in a PR
 * description and reading them. That worked, and it is not repeatable: the
 * enumeration lived in prose, and the `depth` table plus the parent-guessing
 * `BackHandler` it described could drift apart afterwards with nothing failing.
 * These tests are that enumeration in a form that fails.
 *
 * `MutableList<Destination>` is the seam. Nav 3's `NavBackStack` implements
 * `MutableList`, so the production stack and the `mutableListOf` below take the
 * identical code path — there is no test double here and nothing to keep in sync.
 */
class NavActionsTest {

    private fun stack(vararg d: Destination) = mutableListOf(*d)

    private val hubDestinations = listOf(
        Destination.History,
        Destination.Badges,
        Destination.Friends,
        Destination.Circles,
        Destination.Settings,
        Destination.SavedPlaces,
        Destination.Routes,
    )

    private val settingsSpokes = listOf(
        Destination.SettingsAppearanceMap,
        Destination.SettingsTrackingVehicles,
        Destination.SettingsNavigation,
        Destination.SettingsFog,
        Destination.SettingsDisplaysMedia,
        Destination.SettingsServersSync,
        Destination.SettingsObd2,
    )

    // ---- the shape of a push and a pop -------------------------------------

    @Test
    fun `push adds the destination on top`() {
        val s = stack(Destination.Map)
        s.push(Destination.Hub)
        assertEquals(listOf(Destination.Map, Destination.Hub), s)
    }

    @Test
    fun `push onto the same destination is ignored`() {
        val s = stack(Destination.Map, Destination.Hub)
        s.push(Destination.Hub)
        assertEquals(listOf(Destination.Map, Destination.Hub), s)
    }

    @Test
    fun `push allows the same destination again lower in the stack`() {
        // Not a double tap: Hub -> Routes -> (a route's map) is a real path, and
        // Map appearing twice is legitimate. Only the top is guarded.
        val s = stack(Destination.Map, Destination.Hub, Destination.Routes)
        s.push(Destination.Map)
        assertEquals(
            listOf(Destination.Map, Destination.Hub, Destination.Routes, Destination.Map),
            s,
        )
    }

    @Test
    fun `pop removes exactly one entry`() {
        val s = stack(Destination.Map, Destination.Hub, Destination.History)
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub), s)
    }

    @Test
    fun `pop at the root does nothing`() {
        // Back on the map falls through to the system, which is how the app exits.
        val s = stack(Destination.Map)
        s.pop()
        assertEquals(listOf(Destination.Map), s)
    }

    // ---- every app-level edge #66 enumerated -------------------------------

    @Test
    fun `map to hub and back`() {
        val s = stack(Destination.Map)
        s.push(Destination.Hub)
        s.pop()
        assertEquals(listOf(Destination.Map), s)
    }

    @Test
    fun `map to search and back`() {
        // Search pushes straight off the map, not off the Hub — same shape as
        // Hub itself, and unlike every destination in hubDestinations below.
        val s = stack(Destination.Map)
        s.push(Destination.Search)
        s.pop()
        assertEquals(listOf(Destination.Map), s)
    }

    @Test
    fun `every hub destination steps back to hub`() {
        // The old `else -> Screen.HUB` branch, which was right for seven of the
        // twelve screens and had to be spelled out for the rest.
        for (d in hubDestinations) {
            val s = stack(Destination.Map, Destination.Hub)
            s.push(d)
            s.pop()
            assertEquals("back from $d", listOf(Destination.Map, Destination.Hub), s)
        }
    }

    @Test
    fun `trip detail steps back to history not to hub`() {
        // One of the three special cases the old BackHandler had to name.
        val s = stack(Destination.Map, Destination.Hub, Destination.History)
        s.push(Destination.TripDetail(1_786_449_800_000))
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub, Destination.History), s)
    }

    @Test
    fun `route editor steps back to routes not to hub`() {
        val s = stack(Destination.Map, Destination.Hub, Destination.Routes)
        s.push(Destination.RouteEditor(null))
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub, Destination.Routes), s)
    }

    @Test
    fun `coverage map steps back to badges not to hub`() {
        val s = stack(Destination.Map, Destination.Hub, Destination.Badges)
        s.push(Destination.CoverageMap)
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub, Destination.Badges), s)
    }

    // ---- the edge the depth inference could not express --------------------

    @Test
    fun `riding a saved route returns to the map, not to hub`() {
        // RoutesScreen.onNavigate. Depth 2 -> 0 animated as a pop and looked
        // right, but a single pop from Routes lands on Hub.
        val s = stack(Destination.Map, Destination.Hub, Destination.Routes)
        s.returnToMap()
        assertEquals(listOf(Destination.Map), s)
    }

    @Test
    fun `returning to the map from the editor clears both entries`() {
        val s = stack(
            Destination.Map,
            Destination.Hub,
            Destination.Routes,
            Destination.RouteEditor(1L),
        )
        s.returnToMap()
        assertEquals(listOf(Destination.Map), s)
    }

    @Test
    fun `returning to the map when already there does nothing`() {
        val s = stack(Destination.Map)
        s.returnToMap()
        assertEquals(listOf(Destination.Map), s)
    }

    // ---- Settings: the second navigation model, now folded in --------------

    @Test
    fun `every settings spoke steps back to the settings root`() {
        assertEquals("spoke count", 7, settingsSpokes.size)
        for (d in settingsSpokes) {
            val s = stack(Destination.Map, Destination.Hub, Destination.Settings)
            s.push(d)
            s.pop()
            assertEquals(
                "back from $d",
                listOf(Destination.Map, Destination.Hub, Destination.Settings),
                s,
            )
        }
    }

    @Test
    fun `back out of the settings root reaches hub`() {
        // The old inner BackHandler had to be disabled at the root so the outer
        // one could take over. One stack needs no such handoff.
        val s = stack(Destination.Map, Destination.Hub, Destination.Settings)
        s.push(Destination.SettingsFog)
        s.pop()
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub), s)
    }

    // ---- Circles: the third navigation state, per Jonohas on #68 -----------

    @Test
    fun `a circle steps back to the circles list, not out of circles`() {
        // Was CirclesStore.selectedId plus a BackHandler in the screen's own top
        // bar that de-selected before allowing onBack() to leave.
        val s = stack(Destination.Map, Destination.Hub, Destination.Circles)
        s.push(Destination.CircleDetail("c1"))
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub, Destination.Circles), s)
    }

    @Test
    fun `back out of the circles list reaches hub`() {
        val s = stack(Destination.Map, Destination.Hub, Destination.Circles)
        s.push(Destination.CircleDetail("c1"))
        s.pop()
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub), s)
    }

    // ---- keys carry identity, so two subjects are two destinations ---------

    @Test
    fun `two trips are two distinct destinations`() {
        // The old stateKeyOf folded startTimeMs into the saved-state key by hand
        // for exactly this reason: keyed on the enum alone, opening trip A, going
        // back, then opening trip B handed B's screen A's saved scroll offset.
        assertEquals(Destination.TripDetail(1L), Destination.TripDetail(1L))
        org.junit.Assert.assertNotEquals(
            Destination.TripDetail(1L),
            Destination.TripDetail(2L),
        )
    }

    @Test
    fun `opening a second trip pushes rather than being swallowed as a double tap`() {
        val s = stack(Destination.Map, Destination.Hub, Destination.History)
        s.push(Destination.TripDetail(1L))
        s.pop()
        s.push(Destination.TripDetail(2L))
        assertEquals(
            listOf(
                Destination.Map,
                Destination.Hub,
                Destination.History,
                Destination.TripDetail(2L),
            ),
            s,
        )
    }

    @Test
    fun `a new route and an edited route are distinct destinations`() {
        org.junit.Assert.assertNotEquals(
            Destination.RouteEditor(null),
            Destination.RouteEditor(1L),
        )
    }

    // ---- deep links -------------------------------------------------------

    @Test
    fun `a tapped trip notification lands on the trip with a way back out`() {
        assertEquals(
            listOf(
                Destination.Map,
                Destination.Hub,
                Destination.History,
                Destination.TripDetail(99L),
            ),
            tripNotificationStack(99L),
        )
    }

    @Test
    fun `a trip notification for a missing trip lands on history`() {
        assertEquals(
            listOf(Destination.Map, Destination.Hub, Destination.History),
            tripNotificationStack(null),
        )
    }

    @Test
    fun `backing out of a deep-linked trip walks history then hub then exits`() {
        val s = tripNotificationStack(99L).toMutableList()
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub, Destination.History), s)
        s.pop()
        assertEquals(listOf(Destination.Map, Destination.Hub), s)
        s.pop()
        assertEquals(listOf(Destination.Map), s)
        s.pop()
        assertEquals(listOf(Destination.Map), s)
    }

    @Test
    fun `a tapped circle notification lands on the circle`() {
        assertEquals(
            listOf(
                Destination.Map,
                Destination.Hub,
                Destination.Circles,
                Destination.CircleDetail("c9"),
            ),
            circleNotificationStack("c9"),
        )
    }

    @Test
    fun `a circle notification without an id lands on the circles list`() {
        assertEquals(
            listOf(Destination.Map, Destination.Hub, Destination.Circles),
            circleNotificationStack(null),
        )
    }

    @Test
    fun `every deep-link stack is rooted at the map`() {
        // So back never exits the app straight from a deep-linked screen.
        assertEquals(Destination.Map, tripNotificationStack(1L).first())
        assertEquals(Destination.Map, tripNotificationStack(null).first())
        assertEquals(Destination.Map, circleNotificationStack("c").first())
        assertEquals(Destination.Map, circleNotificationStack(null).first())
    }
}
