package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the pure half of OverpassCache.kt: the key builder, the freshness
 * check and eviction. Nothing here touches a file — [OverpassCache.fetch]
 * itself is driven from `deviceFile`, which no unit-test target can reach
 * (`appFilesDir()` needs an Android Context, same constraint RiderTotalsTest
 * documents) — so `cacheKey`, `isFresh` and `evict` take their inputs as
 * plain arguments instead of reading global state, which is what makes them
 * testable at all.
 */
class OverpassCacheTest {

    // --- cacheKey -----------------------------------------------------------

    @Test
    fun twoCentersInTheSameGridCellShareAKey() {
        val a = cacheKey("roads", LatLon(51.0500, 3.7200), 1500.0)
        // A few metres away — well inside one 250 m cell.
        val b = cacheKey("roads", LatLon(51.05002, 3.72002), 1500.0)
        assertEquals(a, b)
    }

    @Test
    fun twoCentersInDifferentGridCellsDoNotShareAKey() {
        val a = cacheKey("roads", LatLon(51.0500, 3.7200), 1500.0)
        // ~1 km away — several cells over.
        val b = cacheKey("roads", LatLon(51.0590, 3.7200), 1500.0)
        assertNotEquals(a, b)
    }

    @Test
    fun differentTagsForTheSameSpotProduceDifferentKeys() {
        val center = LatLon(51.05, 3.72)
        val roads = cacheKey("roads:motorway", center, 1500.0)
        val cameras = cacheKey("cameras", center, 1500.0)
        assertNotEquals(roads, cameras)
    }

    @Test
    fun differentHighwayRegexesProduceDifferentKeys() {
        val center = LatLon(51.05, 3.72)
        val a = cacheKey("roads:motorway", center, 1500.0)
        val b = cacheKey("roads:residential", center, 1500.0)
        assertNotEquals(a, b)
    }

    @Test
    fun differentRadiiForTheSameCenterProduceDifferentKeys() {
        val center = LatLon(51.05, 3.72)
        val a = cacheKey("cameras", center, 1500.0)
        val b = cacheKey("cameras", center, 4000.0)
        assertNotEquals(a, b)
    }

    // --- isFresh --------------------------------------------------------------

    @Test
    fun anEntryWellInsideTheTtlIsFresh() {
        val entry = Entry(body = "{}", fetchedAtMs = 1_000L)
        assertTrue(isFresh(entry, nowMs = 1_000L + 1000L, ttlMs = 30 * 24 * 60 * 60 * 1000L))
    }

    @Test
    fun anEntryPastTheTtlIsNotFresh() {
        val entry = Entry(body = "{}", fetchedAtMs = 1_000L)
        assertEquals(false, isFresh(entry, nowMs = 1_000L + 10_001L, ttlMs = 10_000L))
    }

    /** Exactly at the boundary counts as fresh — same `in 0L..ttlMs` idiom
     *  `RiderTotals.freshness` uses, and for the same reason: an inclusive
     *  bound is a deliberate choice, not an off-by-one worth flipping. */
    @Test
    fun anEntryExactlyAtTheTtlIsStillFresh() {
        val entry = Entry(body = "{}", fetchedAtMs = 1_000L)
        assertTrue(isFresh(entry, nowMs = 1_000L + 10_000L, ttlMs = 10_000L))
    }

    @Test
    fun anEntryOneMillisecondPastTheTtlIsNotFresh() {
        val entry = Entry(body = "{}", fetchedAtMs = 1_000L)
        assertEquals(false, isFresh(entry, nowMs = 1_000L + 10_001L, ttlMs = 10_000L))
    }

    // --- evict ------------------------------------------------------------

    @Test
    fun evictIsANoOpUnderTheLimit() {
        val entries = (1..5).associate { "k$it" to Entry("{}", it.toLong()) }
        assertEquals(entries, evict(entries, maxEntries = 10))
    }

    @Test
    fun evictIsANoOpExactlyAtTheLimit() {
        val entries = (1..5).associate { "k$it" to Entry("{}", it.toLong()) }
        assertEquals(entries, evict(entries, maxEntries = 5))
    }

    @Test
    fun evictKeepsTheMostRecentlyFetchedAndDropsTheOldest() {
        val entries = mapOf(
            "oldest" to Entry("{}", fetchedAtMs = 1_000L),
            "middle" to Entry("{}", fetchedAtMs = 2_000L),
            "newest" to Entry("{}", fetchedAtMs = 3_000L),
        )
        val kept = evict(entries, maxEntries = 2)
        assertEquals(setOf("middle", "newest"), kept.keys)
    }
}
