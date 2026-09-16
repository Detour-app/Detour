package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [SpeedLimitStore]'s on-disk shape — the same split [SpeedCameraStoreTest] uses and for
 * the same reason: [SpeedLimitStore.load]/[SpeedLimitStore.save] need a real account directory
 * this module's test target does not have, so what is exercised here is the pure machinery
 * around it.
 */
class SpeedLimitStoreTest {

    private fun tile(key: String, fetchedAtMs: Long = 1_700_000_000_000L) = CachedSpeedLimitTile(
        key = key,
        fetchedAtMs = fetchedAtMs,
        ways = listOf(RoadRoulette.SpeedLimitWay(50.0, listOf(LatLon(50.85, 4.35), LatLon(50.86, 4.35)))),
    )

    @Test
    fun aSavedTileRoundTripsThroughSerialiseAndParseAll() {
        val original = tile("505:435:1500")
        val back = SpeedLimitStore.parseAll(SpeedLimitStore.serialise(listOf(original)))
        assertEquals(listOf(original), back)
    }

    @Test
    fun theSameKeyReplacesTheExistingTileRatherThanDuplicatingIt() {
        val nowMs = 1_700_000_000_000L
        val first = tile("505:435:1500", fetchedAtMs = nowMs - 1_000L)
        val second = tile("505:435:1500", fetchedAtMs = nowMs)

        val next = SpeedLimitStore.withTile(listOf(first), second, nowMs)

        assertEquals(listOf(second), next)
    }

    @Test
    fun aDifferentKeyIsKeptAlongsideTheExisting() {
        val nowMs = 1_700_000_000_000L
        val existing = tile("505:435:1500", fetchedAtMs = nowMs)
        val incoming = tile("506:436:1500", fetchedAtMs = nowMs)

        val next = SpeedLimitStore.withTile(listOf(existing), incoming, nowMs)

        assertEquals(setOf(existing, incoming), next.toSet())
    }

    @Test
    fun aTileOlderThanTheTtlReadsBackAsAMiss() {
        val nowMs = 1_700_000_000_000L
        val stale = tile("505:435:1500", fetchedAtMs = nowMs - SpeedLimitStore.TTL_MS - 1)
        assertEquals(false, SpeedLimitStore.fresh(stale, nowMs))
    }

    @Test
    fun aTileWithinTheTtlStillReadsBack() {
        val nowMs = 1_700_000_000_000L
        val stillFresh = tile("505:435:1500", fetchedAtMs = nowMs - SpeedLimitStore.TTL_MS + 1)
        assertEquals(true, SpeedLimitStore.fresh(stillFresh, nowMs))
    }

    @Test
    fun savingOneTileEvictsAnUnrelatedTileThatHasAgedPastTheTtl() {
        val nowMs = 1_700_000_000_000L
        val longExpired = tile("100:100:1500", fetchedAtMs = nowMs - SpeedLimitStore.TTL_MS - 60_000L)
        val incoming = tile("505:435:1500", fetchedAtMs = nowMs)

        val next = SpeedLimitStore.withTile(listOf(longExpired), incoming, nowMs)

        assertEquals(listOf(incoming), next)
    }

    @Test
    fun corruptJsonReadsBackAsEmptyRatherThanCrashing() {
        assertEquals(emptyList(), SpeedLimitStore.parseAll("not json at all { { ["))
    }

    @Test
    fun jsonThatIsNotAnArrayReadsBackAsEmpty() {
        assertEquals(emptyList(), SpeedLimitStore.parseAll("""{"not":"an array"}"""))
    }

    @Test
    fun aTileMissingARequiredFieldIsSkippedRatherThanFailingTheWholeFile() {
        val text = """[
            {"key":"505:435:1500","fetchedAtMs":1700000000000,"ways":[]},
            {"key":"nope"}
        ]"""
        val back = SpeedLimitStore.parseAll(text)
        assertEquals(listOf("505:435:1500"), back.map { it.key })
    }

    @Test
    fun aWayWithFewerThanTwoPointsIsDroppedRatherThanCrashing() {
        val text = """[
            {"key":"505:435:1500","fetchedAtMs":1700000000000,"ways":[
                {"kmh":50.0,"points":[[50.85,4.35]]},
                {"kmh":70.0,"points":[[50.85,4.35],[50.86,4.35]]}
            ]}
        ]"""
        val back = SpeedLimitStore.parseAll(text)
        assertEquals(1, back.single().ways.size)
        assertEquals(70.0, back.single().ways.single().kmh)
    }
}
