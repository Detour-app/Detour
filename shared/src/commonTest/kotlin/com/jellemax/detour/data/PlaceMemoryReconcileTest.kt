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
        assertEquals(
            listOf(GeofenceTransition(7L, GeofenceKind.DEPART, now)),
            drift.missedDepartures,
            "a claim the rider has drifted away from must synthesize a missed departure",
        )
        assertTrue(drift.staleKeys.isEmpty(), "a place that still exists must not be reported as stale")
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
        assertTrue(drift.missedDepartures.isEmpty(), "a still-inside claim must not synthesize a departure")
        assertTrue(drift.staleKeys.isEmpty(), "a still-inside claim must not be dropped as stale")
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
        assertTrue(
            drift.missedDepartures.isEmpty(),
            "a claim inside the exit ring but outside the entry radius must not synthesize a departure",
        )
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
        assertTrue(drift.missedDepartures.isEmpty(), "a claim for a vanished place must not synthesize a departure")
        assertEquals(setOf("$c:7"), drift.staleKeys, "a claim for a vanished place must be reported as stale")
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
        assertTrue(drift.missedDepartures.isEmpty(), "a claim keyed to a different circle must not be reconciled here")
        assertTrue(drift.staleKeys.isEmpty(), "a claim keyed to a different circle must not be reported as stale here")
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
        assertTrue(drift.missedDepartures.isEmpty(), "a place with no existing claim must not synthesize a departure")
        assertTrue(drift.staleKeys.isEmpty(), "a place with no existing claim must not be reported as stale")
    }

    @Test
    fun aConfirmedKeyWhoseSuffixIsNotALongIsSkippedEntirely() {
        // toLongOrNull() ?: continue - a key this malformed cannot name a
        // real place, so the loop must neither try to synthesize a
        // departure for it nor report it as stale; it simply isn't looked at.
        val drift = reconcilePlaceMemory(
            confirmed = setOf("$c:not-a-long"),
            circleId = c,
            places = listOf(place(7L, 50.85, 4.35)),
            lat = 50.85, lon = 4.35,
            nowMs = now,
        )
        assertTrue(
            drift.missedDepartures.isEmpty(),
            "a key with an unparseable place-id suffix must not synthesize a departure",
        )
        assertTrue(
            drift.staleKeys.isEmpty(),
            "a key with an unparseable place-id suffix must not be reported as stale either",
        )
    }

    @Test
    fun oneCallCanYieldAllThreeOutcomesAtOnce() {
        // The shape the loop actually has to handle: one circle, three
        // claims, three different fates in a single reconcilePlaceMemory
        // call - a drifted claim (place 1, still exists but the rider is far
        // away), a claim for a place gone from the circle (place 2, absent
        // from `places` below), and a claim the rider is still inside
        // (place 3, at the rider's own position). Nothing today pins that
        // the loop keeps these three straight when they arrive together
        // rather than one at a time.
        val drift = reconcilePlaceMemory(
            confirmed = setOf("$c:1", "$c:2", "$c:3"),
            circleId = c,
            places = listOf(
                place(1L, 50.85, 4.35),
                place(3L, 50.90, 4.35),
            ),
            lat = 50.90, lon = 4.35, // far from place 1, exactly at place 3
            nowMs = now,
        )
        assertEquals(
            listOf(GeofenceTransition(1L, GeofenceKind.DEPART, now)),
            drift.missedDepartures,
            "only the drifted claim (place 1) should synthesize a departure, and place 3 must not leak in",
        )
        assertEquals(
            setOf("$c:2"),
            drift.staleKeys,
            "only the claim for the vanished place (place 2) should be reported as stale",
        )
    }

    /**
     * [reconcilePlaceMemory] can only synthesize a missed DEPART past the
     * exit ring (`radiusM * EXIT_HYSTERESIS_FACTOR`), while [GeofenceEvaluator]
     * can only raise a fresh ARRIVE within the entry `radiusM` itself. With
     * the factor at 1.3 those ranges cannot overlap, so the two can never
     * both fire for the same place on the same fix — today, only by
     * inspection across two files. This pins it so a later change to either
     * formula (a per-place hysteresis override, say) fails a test instead of
     * silently reopening the contradiction.
     */
    @Test
    fun reconciliationsDepartAndTheEvaluatorsArriveNeverBothFireForTheSamePlace() {
        val radiusM = 100.0
        val p = place(7L, 50.85, 4.35, radiusM)
        val metersPerDegreeLat = 111_320.0

        // Sweep 0m to 200m in 10m steps: inside the entry radius (<=100m),
        // the dead zone the hysteresis factor creates (100m-130m), and past
        // the exit ring (>130m).
        var distanceM = 0.0
        while (distanceM <= 200.0) {
            val lat = 50.85 + distanceM / metersPerDegreeLat

            val evaluator = GeofenceEvaluator.withDefaults()
            // Establish the dwell candidate, then let it elapse at the same
            // fix, so the only thing left deciding an arrive is the distance
            // check itself.
            evaluator.evaluate(lat, 4.35, now, listOf(p))
            val arrived = evaluator.evaluate(
                lat, 4.35, now + GeofenceEvaluator.MIN_DWELL_MS + 1, listOf(p),
            ).any { it.placeId == 7L && it.kind == GeofenceKind.ARRIVE }

            val drift = reconcilePlaceMemory(
                confirmed = setOf("$c:7"),
                circleId = c,
                places = listOf(p),
                lat = lat, lon = 4.35,
                nowMs = now,
            )
            val departed = drift.missedDepartures.any { it.placeId == 7L }

            assertTrue(
                !(arrived && departed),
                "at ${distanceM}m the evaluator raised an arrive and reconciliation raised a missed " +
                    "depart for the same place on the same fix — the two detectors contradicted each other",
            )
            distanceM += 10.0
        }
    }
}
