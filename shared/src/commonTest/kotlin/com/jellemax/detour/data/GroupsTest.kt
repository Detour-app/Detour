package com.jellemax.detour.data

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the client-side pieces of docs/CIRCLES_AND_CONVOYS.md that don't
 * need a server: parsing the wire shapes for groups/fixes/events, and the
 * on-device geofence transition logic that keeps the position stream that
 * drives arrivals off the network entirely (section 8).
 */
class GroupsTest {

    // --- JSON parsing -------------------------------------------------------

    @Test
    fun groupParsesMembersWithSharingForACircle() {
        val json = buildJsonObject {
            put("id", 7)
            put("name", "Family")
            put("status", "accepted")
            putJsonArray("members") {
                addJsonObject {
                    put("username", "alice")
                    put("status", "accepted")
                    put("sharing", false)
                }
            }
        }
        val group = groupFromJson(json, "circle")
        assertEquals(7, group.id)
        assertEquals("circle", group.kind)
        assertEquals(1, group.members.size)
        assertEquals("alice", group.members[0].username)
        assertTrue(!group.members[0].sharing)
    }

    @Test
    fun groupDefaultsSharingToTrueForAConvoyWhereTheServerOmitsIt() {
        val json = buildJsonObject {
            put("id", 3)
            put("name", "Ride")
            put("status", "accepted")
            putJsonArray("members") {
                addJsonObject { put("username", "bob"); put("status", "accepted") }
            }
        }
        val group = groupFromJson(json, "convoy")
        assertTrue(group.members[0].sharing)
    }

    @Test
    fun memberFixParsesEveryField() {
        val json = buildJsonObject {
            put("username", "alice")
            put("lat", 50.8)
            put("lon", 3.2)
            put("accuracyM", 12.5)
            put("ts", 1_700_000_000_000L)
        }
        val fix = memberFixFromJson(json)
        assertEquals("alice", fix.username)
        assertEquals(50.8, fix.lat)
        assertEquals(3.2, fix.lon)
        assertEquals(12.5, fix.accuracyM)
        assertEquals(1_700_000_000_000L, fix.tsMs)
    }

    // --- what the map actually draws -----------------------------------------

    private fun fix(user: String, ts: Long) =
        MemberFix(username = user, lat = 50.0, lon = 3.0, accuracyM = 5.0, tsMs = ts)

    @Test
    fun ownFixIsDroppedSoItDoesNotStackOnTheOwnPositionMarker() {
        val drawn = newestPerOtherMember(
            listOf(fix("me", 100L), fix("bob", 100L)), selfUsername = "me")
        assertEquals(listOf("bob"), drawn.map { it.username })
    }

    @Test
    fun someoneInTwoOfYourCirclesIsDrawnOnceAtTheirNewestFix() {
        val drawn = newestPerOtherMember(
            listOf(fix("bob", 100L), fix("bob", 300L), fix("bob", 200L)), selfUsername = "me")
        assertEquals(1, drawn.size)
        assertEquals(300L, drawn[0].tsMs)
    }

    @Test
    fun placeEventParsesEveryField() {
        val json = buildJsonObject {
            put("id", 99L)
            put("placeId", 42L)
            put("username", "carol")
            put("kind", "arrive")
            put("tsMs", 1_700_000_001_000L)
        }
        val event = placeEventFromJson(json)
        assertEquals(99L, event.id)
        assertEquals(42L, event.placeId)
        assertEquals("carol", event.username)
        assertEquals("arrive", event.kind)
        assertEquals(1_700_000_001_000L, event.tsMs)
    }

    // --- geofence transitions -------------------------------------------------

    private fun place(id: Long, at: LatLon, radiusM: Double) = CirclePlace(
        serverId = id,
        groupId = 1,
        owner = "alice",
        radiusM = radiusM,
        createdMs = 0L,
        place = SavedPlace(id = id, name = "Home", location = at),
    )

    private val home = LatLon(50.8503, 4.3517) // Brussels
    private val farAway = LatLon(51.5074, -0.1278) // London — well outside any radius here

    @Test
    fun arrivesOnlyAfterTheMinimumDwell() {
        val eval = GeofenceEvaluator(minDwellMs = 60_000L)
        val places = listOf(place(1L, home, radiusM = 100.0))

        // Inside the radius, but not long enough yet.
        assertTrue(eval.evaluate(home.lat, home.lon, tsMs = 0L, places).isEmpty())
        assertTrue(eval.evaluate(home.lat, home.lon, tsMs = 30_000L, places).isEmpty())

        // Dwell satisfied.
        val transitions = eval.evaluate(home.lat, home.lon, tsMs = 60_000L, places)
        assertEquals(1, transitions.size)
        assertEquals(GeofenceKind.ARRIVE, transitions[0].kind)
        assertEquals(1L, transitions[0].placeId)
    }

    @Test
    fun leavingBeforeDwellCompletesResetsTheCandidateWindow() {
        val eval = GeofenceEvaluator(minDwellMs = 60_000L)
        val places = listOf(place(1L, home, radiusM = 100.0))

        eval.evaluate(home.lat, home.lon, tsMs = 0L, places)
        // Steps out before the dwell timer would have fired.
        assertTrue(eval.evaluate(farAway.lat, farAway.lon, tsMs = 30_000L, places).isEmpty())
        // Back inside at t=100_000 — the dwell clock must have restarted from
        // here, not carried over the 30_000 already spent before stepping out.
        assertTrue(eval.evaluate(home.lat, home.lon, tsMs = 100_000L, places).isEmpty())
        assertTrue(eval.evaluate(home.lat, home.lon, tsMs = 130_000L, places).isEmpty()) // only 30s since restart
        val transitions = eval.evaluate(home.lat, home.lon, tsMs = 160_000L, places) // 60s since restart
        assertEquals(1, transitions.size)
        assertEquals(GeofenceKind.ARRIVE, transitions[0].kind)
    }

    @Test
    fun departsOnlyPastTheHysteresisRadiusNotTheEntryRadius() {
        val eval = GeofenceEvaluator(minDwellMs = 0L, exitHysteresisFactor = 1.5)
        val radiusM = 100.0
        val places = listOf(place(1L, home, radiusM))

        val arrived = eval.evaluate(home.lat, home.lon, tsMs = 0L, places)
        assertEquals(GeofenceKind.ARRIVE, arrived[0].kind)

        // A point ~120m from home: past the 100m entry radius but inside the
        // 150m (100 * 1.5) exit radius — must not flap back to "departed".
        val justOutsideEntry = LatLon(home.lat + 0.0011, home.lon)
        assertTrue(eval.evaluate(justOutsideEntry.lat, justOutsideEntry.lon, tsMs = 1_000L, places).isEmpty())

        // Genuinely far away clears the hysteresis radius and departs.
        val departed = eval.evaluate(farAway.lat, farAway.lon, tsMs = 2_000L, places)
        assertEquals(1, departed.size)
        assertEquals(GeofenceKind.DEPART, departed[0].kind)
    }

    @Test
    fun noFlappingRightAtTheEntryBoundary() {
        val eval = GeofenceEvaluator(minDwellMs = 0L, exitHysteresisFactor = 1.3)
        val radiusM = 100.0
        val places = listOf(place(1L, home, radiusM))

        eval.evaluate(home.lat, home.lon, tsMs = 0L, places) // arrive
        // A few metres past the 100m entry radius — inside the 130m exit
        // radius, so hysteresis must keep this as "still inside".
        val jitter = LatLon(home.lat + 0.0010, home.lon) // ~111m north
        val result = eval.evaluate(jitter.lat, jitter.lon, tsMs = 1_000L, places)
        assertTrue(result.isEmpty(), "expected no transition from GPS jitter, got $result")
    }

    @Test
    fun removingAPlaceDropsItsState() {
        val eval = GeofenceEvaluator(minDwellMs = 0L)
        val places = listOf(place(1L, home, radiusM = 100.0))
        eval.evaluate(home.lat, home.lon, tsMs = 0L, places) // arrives and marks "inside"

        // The place is gone from the circle; feeding an empty list must not
        // throw or leak state into a later place reusing the same id.
        assertTrue(eval.evaluate(home.lat, home.lon, tsMs = 1_000L, emptyList()).isEmpty())
        val readded = eval.evaluate(home.lat, home.lon, tsMs = 2_000L, places)
        // Treated as a fresh arrival, not a no-op from stale "inside" state.
        assertEquals(1, readded.size)
        assertEquals(GeofenceKind.ARRIVE, readded[0].kind)
    }
}
