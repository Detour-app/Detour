package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers the arithmetic half of RiderTotals.kt — the stored record that stands
 * in for folding all of `trips.json` on every `BadgeStore.stats()` call (#83).
 *
 * Nothing here touches a file: the record's whole risk is that an increment
 * and a full recompute disagree, and that is a property of the arithmetic, not
 * of the storage. The file half is driven from `TripStore`, which no unit-test
 * target can reach — `appFilesDir()` needs an Android Context (see
 * Platform.android.kt), which is also why `nowMs` arrives as an argument here
 * rather than being read from the clock.
 */
class RiderTotalsTest {

    private fun trip(
        startTimeMs: Long,
        distanceMeters: Double = 1_000.0,
        topSpeedMps: Double = 20.0,
        maxLeanAngleDeg: Double = 15.0,
    ) = Trip(
        startTimeMs = startTimeMs,
        endTimeMs = startTimeMs + 600_000L,
        distanceMeters = distanceMeters,
        topSpeedMps = topSpeedMps,
        maxLeanAngleDeg = maxLeanAngleDeg,
        maxGForce = 1.1,
        destinationLat = 51.05,
        destinationLon = 3.72,
        mode = TravelMode.MOTO,
    )

    private fun history() = listOf(
        trip(1_000L, distanceMeters = 12_400.0, topSpeedMps = 31.0, maxLeanAngleDeg = 22.0),
        trip(2_000L, distanceMeters = 84_100.0, topSpeedMps = 27.5, maxLeanAngleDeg = 41.5),
        trip(3_000L, distanceMeters = 3_050.0, topSpeedMps = 48.2, maxLeanAngleDeg = 8.0),
    )

    /**
     * The acceptance criterion the whole design rests on: a record built one
     * trip at a time must be indistinguishable from one folded over the same
     * history. Every field, because a sum and a maximum fail differently and a
     * spot check on distance would pass with the maxima wired wrong.
     */
    @Test
    fun incrementingTripByTripEqualsFoldingTheWholeHistory() {
        val trips = history()
        val folded = RiderTotals.foldAll(trips, nowMs = 999L)
        val incremented = trips
            .fold(RiderTotals.EMPTY) { acc, t -> RiderTotals.including(acc, t) }
            .copy(computedAtMs = 999L)
        assertEquals(folded, incremented)
    }

    /**
     * Why `delete` invalidates rather than decrementing: the maxima cannot be
     * walked backwards. 48.2 m/s was the fastest ride; removing it has to
     * leave 31.0, and no arithmetic on the record alone can know that.
     */
    @Test
    fun droppingTheTripThatHeldTheTopSpeedLowersIt() {
        val trips = history()
        val before = RiderTotals.foldAll(trips, nowMs = 999L)
        assertEquals(48.2, before.topSpeedMps, absoluteTolerance = 1e-9)

        val after = RiderTotals.foldAll(trips.filterNot { it.startTimeMs == 3_000L }, nowMs = 999L)
        assertEquals(31.0, after.topSpeedMps, absoluteTolerance = 1e-9)
        assertEquals(2, after.tripCount)
    }

    @Test
    fun aFoldOverNoTripsIsTheEmptyRecordWithATimestamp() {
        val folded = RiderTotals.foldAll(emptyList(), nowMs = 4_242L)
        assertEquals(0.0, folded.totalDistanceMeters, absoluteTolerance = 1e-9)
        assertEquals(0, folded.tripCount)
        assertEquals(4_242L, folded.computedAtMs)
    }

    // --- what a save does to the record -----------------------------------

    /**
     * An increment must not renew the TTL. [TripTotals.computedAtMs] means
     * "last agreed with the whole history", and a save agrees with one trip —
     * so stamping it here would let a rider who rides daily go forever without
     * a single full recompute, which is the one thing the TTL exists to
     * prevent.
     */
    @Test
    fun savingATripDoesNotRenewTheTtl() {
        val record = RiderTotals.foldAll(history(), nowMs = 1_000L)
        val after = RiderTotals.afterSave(record, trip(4_000L))
        assertNotNull(after)
        assertEquals(1_000L, after.computedAtMs)
        assertEquals(4, after.tripCount)
    }

    /**
     * With no record on disk yet, `EMPTY + oneTrip` would be a *complete*
     * record claiming the rider has ridden once — wiping a lifetime of history
     * on the first trip after an install that has not folded yet. Leaving it
     * absent makes the next read do the fold.
     */
    @Test
    fun savingATripWithNoRecordYetLeavesTheFoldToTheNextRead() {
        assertNull(RiderTotals.afterSave(null, trip(4_000L)))
    }

    /** A stale record is still arithmetically consistent, so a save folds into
     *  it rather than throwing it away — it stays stale and still refreshes. */
    @Test
    fun savingATripOntoAStaleRecordKeepsItStale() {
        val stale = RiderTotals.foldAll(history(), nowMs = 1_000L)
        val after = RiderTotals.afterSave(stale, trip(4_000L))
        assertNotNull(after)
        assertEquals(
            Freshness.STALE,
            RiderTotals.freshness(after, nowMs = 1_000L + RiderTotals.TTL_MS + 1),
        )
    }

    // --- freshness --------------------------------------------------------

    @Test
    fun aRecordInsideItsTtlIsFresh() {
        val record = RiderTotals.foldAll(history(), nowMs = 1_000L)
        assertEquals(
            Freshness.FRESH,
            RiderTotals.freshness(record, nowMs = 1_000L + RiderTotals.TTL_MS - 1),
        )
    }

    /**
     * Past the TTL the record is *stale*, not *invalid*, and the difference is
     * the whole point of the design: a stale record is still served, and the
     * recompute it schedules happens off the path the rider is waiting on. Were
     * this INVALID, every TTL expiry would put a full history fold back in front
     * of a screen — the cost #83 exists to remove.
     */
    @Test
    fun aRecordPastItsTtlIsStaleAndStillServable() {
        val record = RiderTotals.foldAll(history(), nowMs = 1_000L)
        assertEquals(
            Freshness.STALE,
            RiderTotals.freshness(record, nowMs = 1_000L + RiderTotals.TTL_MS + 1),
        )
    }

    /**
     * A record written by a build that had fewer fields would otherwise be
     * served with the new ones defaulted to zero — a rider's lifetime maximum
     * lean silently reading 0° after an upgrade.
     */
    @Test
    fun aRecordFromAnotherSchemaIsInvalidHoweverRecent() {
        val record = RiderTotals.foldAll(history(), nowMs = 1_000L)
            .copy(schemaVersion = RiderTotals.SCHEMA_VERSION - 1)
        assertEquals(Freshness.INVALID, RiderTotals.freshness(record, nowMs = 1_000L))
    }

    @Test
    fun noRecordAtAllIsInvalid() {
        assertEquals(Freshness.INVALID, RiderTotals.freshness(null, nowMs = 1_000L))
    }

    // --- storage format ---------------------------------------------------

    @Test
    fun aRecordSurvivesBeingWrittenAndReadBack() {
        val record = RiderTotals.foldAll(history(), nowMs = 1_700_000_000_000L)
        assertEquals(record, RiderTotals.decode(RiderTotals.encode(record).string()))
    }

    /**
     * A truncated or hand-edited file must read as "no record" so the next read
     * recomputes, matching how every other store here treats unparseable JSON
     * (`TripStore.load`, `RelayProtocol.decode`) — never as a throw reaching a
     * screen, and never as a record of zeroes that would look like a rider who
     * has never ridden.
     */
    @Test
    fun anUnparseableRecordReadsAsNoRecord() {
        assertNull(RiderTotals.decode("{\"totalDistanceMeters\":"))
        assertNull(RiderTotals.decode(""))
    }
}
