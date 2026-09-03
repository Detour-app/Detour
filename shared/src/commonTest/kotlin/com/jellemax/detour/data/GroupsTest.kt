package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
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
            put("id", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11")
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
        assertEquals("0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11", group.id)
        assertEquals("circle", group.kind)
        assertEquals(1, group.members.size)
        assertEquals("alice", group.members[0].username)
        assertTrue(!group.members[0].sharing)
    }

    @Test
    fun group_member_parses_an_id_and_a_display_handle() {
        val json = jsonObjectOf(
            """
            {
              "id": "8f14e45f-ceea-467a-9a3b-1b2c3d4e5f60",
              "name": "Sunday run",
              "status": "accepted",
              "members": [
                { "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "username": "alice", "status": "accepted", "sharing": true }
              ]
            }
            """.trimIndent()
        )

        val group = groupFromJson(json, kind = "circle")

        assertEquals(RiderId("3fa85f64-5717-4562-b3fc-2c963f66afa6"), group.members[0].id)
        assertEquals("alice", group.members[0].username)
    }

    @Test
    fun groupDefaultsSharingToTrueForAConvoyWhereTheServerOmitsIt() {
        val json = buildJsonObject {
            put("id", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c12")
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
            put("riderId", "alice")
            put("latitude", 50.8)
            put("longitude", 3.2)
            put("accuracyMeters", 12.5)
            put("timestampMs", 1_700_000_000_000L)
        }
        val fix = memberFixFromJson(json)
        assertEquals(RiderId("alice"), fix.riderId)
        assertEquals(50.8, fix.lat)
        assertEquals(3.2, fix.lon)
        assertEquals(12.5, fix.accuracyM)
        assertEquals(1_700_000_000_000L, fix.tsMs)
    }

    // --- what the map actually draws -----------------------------------------

    private fun fix(user: String, ts: Long) =
        MemberFix(riderId = RiderId(user), lat = 50.0, lon = 3.0, accuracyM = 5.0, tsMs = ts)

    @Test
    fun ownFixIsDroppedSoItDoesNotStackOnTheOwnPositionMarker() {
        val drawn = newestPerOtherMember(
            listOf(fix("me", 100L), fix("bob", 100L)), selfUsername = "me")
        assertEquals(listOf("bob"), drawn.map { it.riderId.value })
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
            put("id", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c20")
            put("placeId", 42L)
            put("placeName", "School")
            put("riderId", "carol")
            put("kind", "arrive")
            put("timestampMs", 1_700_000_001_000L)
        }
        val event = placeEventFromJson(json)
        assertEquals("0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c20", event.id)
        assertEquals(42L, event.placeId)
        assertEquals("School", event.placeName)
        assertEquals(RiderId("carol"), event.riderId)
        assertEquals("arrive", event.kind)
        assertEquals(1_700_000_001_000L, event.tsMs)
    }

    @Test
    fun placeEventDefaultsPlaceNameToEmptyWhenTheServerOmitsIt() {
        // A pre-phase-1 style response, or a placeId whose circle_places row
        // has since been deleted - the service sends "" in that case,
        // but a client parsing an older cached payload might not have the
        // key at all.
        val json = buildJsonObject {
            put("id", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c20")
            put("placeId", 1L)
            put("riderId", "carol")
            put("kind", "depart")
            put("timestampMs", 1L)
        }
        assertEquals("", placeEventFromJson(json).placeName)
    }

    // --- live relay frame parsing -----------------------------------------

    private fun validRelayFrame() = buildJsonObject {
        put("type", "place_event")
        put("groupId", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11")
        put("placeId", 42L)
        put("placeName", "School")
        put("user", "alice")
        put("kind", "arrive")
        put("ts", 1_700_000_002_000L)
    }

    @Test
    fun relayFrameParsesEveryField() {
        val parsed = placeEventFromRelayFrame(validRelayFrame())
        assertEquals("0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11", parsed?.groupId)
        val event = parsed!!.event
        assertEquals("", event.id, "a live frame carries no stored id")
        assertEquals(42L, event.placeId)
        assertEquals("School", event.placeName)
        assertEquals(RiderId("alice"), event.riderId)
        assertEquals("arrive", event.kind)
        assertEquals(1_700_000_002_000L, event.tsMs)
    }

    @Test
    fun relayFrameAcceptsACapitalisedKind() {
        // The relay push historically sent the enum's Name ("Arrive"/"Depart")
        // while the feed and the apps use the lowercase wire spelling. Parsing is
        // case-insensitive so an old relay can't silently drop every arrival.
        val json = buildJsonObject {
            put("type", "place_event")
            put("groupId", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11")
            put("placeId", 42L)
            put("user", "alice")
            put("kind", "Arrive")
            put("ts", 1L)
        }
        assertEquals("arrive", placeEventFromRelayFrame(json)?.event?.kind)
    }

    @Test
    fun relayFrameDefaultsPlaceNameToEmptyWhenAbsent() {
        val json = buildJsonObject {
            put("type", "place_event")
            put("groupId", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11")
            put("placeId", 42L)
            put("user", "alice")
            put("kind", "arrive")
            put("ts", 1L)
        }
        assertEquals("", placeEventFromRelayFrame(json)?.event?.placeName)
    }

    @Test
    fun relayFrameOfTheWrongTypeIsNull() {
        val json = buildJsonObject {
            put("type", "location")
            put("groupId", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11")
            put("lat", 50.8)
            put("lon", 3.2)
        }
        assertEquals(null, placeEventFromRelayFrame(json))
    }

    @Test
    fun relayFrameMissingARequiredFieldIsNull() {
        // Drop one required field at a time from an otherwise-valid frame.
        for (missing in listOf("groupId", "placeId", "user", "kind", "ts")) {
            val json = JsonObject(validRelayFrame().filterKeys { it != missing })
            assertEquals(null, placeEventFromRelayFrame(json), "expected null with '$missing' missing")
        }
    }

    @Test
    fun relayFrameWithAWrongTypeFieldIsNull() {
        // placeId sent as a word, not a number - a malformed frame, not one
        // worth coercing. groupId is deliberately not the example any more: a
        // group identifier is text, so a string there is the normal case.
        val json = buildJsonObject {
            put("type", "place_event")
            put("groupId", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11")
            put("placeId", "forty-two")
            put("user", "alice")
            put("kind", "arrive")
            put("ts", 1L)
        }
        assertEquals(null, placeEventFromRelayFrame(json))
    }

    @Test
    fun relayFrameWithAnUnknownKindIsNull() {
        val json = buildJsonObject {
            put("type", "place_event")
            put("groupId", "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11")
            put("placeId", 42L)
            put("user", "alice")
            put("kind", "loiter")
            put("ts", 1L)
        }
        assertEquals(null, placeEventFromRelayFrame(json))
    }

    // --- shared notification wording ---------------------------------------

    private fun event(kind: String, placeName: String) = PlaceEvent(
        id = "e1", placeId = 1L, placeName = placeName, riderId = RiderId("alice-id"), kind = kind, tsMs = 0L,
    )

    @Test
    fun notificationTextForAnArrival() {
        assertEquals("alice arrived at School", event("arrive", "School").notificationText("alice"))
    }

    @Test
    fun notificationTextForADeparture() {
        assertEquals("alice left School", event("depart", "School").notificationText("alice"))
    }

    @Test
    fun notificationTextDropsAtPlaceWhenTheNameIsBlank() {
        assertEquals("alice arrived", event("arrive", "").notificationText("alice"))
    }

    @Test
    fun catchUpSummarySingularAndPlural() {
        assertEquals("+1 more update", catchUpSummaryText(1))
        assertEquals("+4 more updates", catchUpSummaryText(4))
    }

    // --- geofence transitions -------------------------------------------------

    private fun place(id: Long, at: LatLon, radiusM: Double) = CirclePlace(
        serverId = "place-$id",
        groupId = "0193a1f0-7c31-7c9a-9a0e-2f0d5b1a4c11",
        ownerId = RiderId("alice"),
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
