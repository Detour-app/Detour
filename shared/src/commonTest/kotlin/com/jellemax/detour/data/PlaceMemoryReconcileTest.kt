package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of #273 that keeps the gate safe. The confirmed-inside memory is
 * durable, so it outlives the process — and a rider whose app was force-stopped
 * can leave a place with no depart ever announced. Left alone, the memory would
 * then claim "inside" forever, the gate would swallow that rider's next real
 * arrival, and the circle would show them parked there permanently.
 *
 * Geometry is the authority, not the evaluator: after the force-stop that
 * causes the drift, the evaluator's `inside` is false and has no opinion.
 */
class PlaceMemoryReconcileTest {

    private val c = "11111111-2222-3333-4444-555555555555"
    private val now = 1_700_000_000_000L

    private fun place(id: Long, lat: Double, lon: Double, radiusM: Double = 100.0) = CirclePlace(
        serverId = "s$id",
        groupId = c,
        ownerId = RiderId("owner"),
        radiusM = radiusM,
        createdMs = 0L,
        place = SavedPlace(id = id, name = "p$id", location = LatLon(lat, lon)),
    )

    @Test
    fun aClaimedPlaceTheRiderIsNoLongerNearYieldsAMissedDeparture() {
        val drift = reconcilePlaceMemory(
            confirmed = setOf("$c:7"),
            circleId = c,
            places = listOf(place(7L, 50.85, 4.35)),
            lat = 50.90, lon = 4.35, // ~5.5 km north, far outside 100 m * 1.3
            nowMs = now,
        )
        assertEquals(listOf(GeofenceTransition(7L, GeofenceKind.DEPART, now)), drift.missedDepartures)
        assertTrue(drift.staleKeys.isEmpty())
    }

    @Test
    fun aClaimedPlaceTheRiderIsStillInsideIsLeftAlone() {
        val drift = reconcilePlaceMemory(
            confirmed = setOf("$c:7"),
            circleId = c,
            places = listOf(place(7L, 50.85, 4.35)),
            lat = 50.85, lon = 4.35,
            nowMs = now,
        )
        assertTrue(drift.missedDepartures.isEmpty())
        assertTrue(drift.staleKeys.isEmpty())
    }

    /** Inside the exit ring but outside the entry radius: still "inside" as far
     *  as a depart is concerned, which is what the 1.3x factor is for. */
    @Test
    fun theExitRingIsTheThresholdNotTheEntryRadius() {
        // 120 m north of a 100 m place: outside the radius, inside 130 m.
        val drift = reconcilePlaceMemory(
            confirmed = setOf("$c:7"),
            circleId = c,
            places = listOf(place(7L, 50.85, 4.35)),
            lat = 50.85 + 120.0 / 111_320.0, lon = 4.35,
            nowMs = now,
        )
        assertTrue(drift.missedDepartures.isEmpty())
    }

    @Test
    fun aClaimForAPlaceThatIsGoneIsStaleRatherThanADeparture() {
        val drift = reconcilePlaceMemory(
            confirmed = setOf("$c:7"),
            circleId = c,
            places = emptyList(),
            lat = 50.85, lon = 4.35,
            nowMs = now,
        )
        assertTrue(drift.missedDepartures.isEmpty())
        assertEquals(setOf("$c:7"), drift.staleKeys)
    }

    @Test
    fun anotherCirclesClaimsAreNotTouched() {
        val drift = reconcilePlaceMemory(
            confirmed = setOf("other:7", "$c:7"),
            circleId = c,
            places = listOf(place(7L, 50.85, 4.35)),
            lat = 50.85, lon = 4.35,
            nowMs = now,
        )
        assertTrue(drift.missedDepartures.isEmpty())
        assertTrue(drift.staleKeys.isEmpty())
    }

    @Test
    fun aPlaceWithNoClaimIsLeftToTheDwellPath() {
        val drift = reconcilePlaceMemory(
            confirmed = emptySet(),
            circleId = c,
            places = listOf(place(7L, 50.85, 4.35)),
            lat = 50.85, lon = 4.35,
            nowMs = now,
        )
        assertTrue(drift.missedDepartures.isEmpty())
        assertTrue(drift.staleKeys.isEmpty())
    }
}
