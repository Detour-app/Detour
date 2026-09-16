package com.jellemax.detour.drive

import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [RoadTypeStore]'s on-disk shape — the same split `SpeedLimitStoreTest` uses and for the
 * same reason: [RoadTypeStore.load]/[RoadTypeStore.save] need a real account directory this
 * module's test target does not have, so what is exercised here is the pure machinery around it.
 */
class RoadTypeStoreTest {

    private fun tile(key: String, fetchedAtMs: Long = 1_700_000_000_000L) = CachedRoadTypeTile(
        key = key,
        fetchedAtMs = fetchedAtMs,
        ways = listOf(RoadTypeTracker.ClassifiedWay(HighwayClass.ARTERIAL, listOf(LatLon(50.85, 4.35), LatLon(50.86, 4.35)))),
    )

    @Test
    fun aSavedTileRoundTripsThroughSerialiseAndParseAll() {
        val original = tile("505:435:1500")
        val back = RoadTypeStore.parseAll(RoadTypeStore.serialise(listOf(original)))
        assertEquals(listOf(original), back)
    }

    @Test
    fun theSameKeyReplacesTheExistingTileRatherThanDuplicatingIt() {
        val nowMs = 1_700_000_000_000L
        val first = tile("505:435:1500", fetchedAtMs = nowMs - 1_000L)
        val second = tile("505:435:1500", fetchedAtMs = nowMs)

        val next = RoadTypeStore.withTile(listOf(first), second, nowMs)

        assertEquals(listOf(second), next)
    }

    @Test
    fun aDifferentKeyIsKeptAlongsideTheExisting() {
        val nowMs = 1_700_000_000_000L
        val existing = tile("505:435:1500", fetchedAtMs = nowMs)
        val incoming = tile("506:436:1500", fetchedAtMs = nowMs)

        val next = RoadTypeStore.withTile(listOf(existing), incoming, nowMs)

        assertEquals(setOf(existing, incoming), next.toSet())
    }

    @Test
    fun aTileOlderThanTheTtlReadsBackAsAMiss() {
        val nowMs = 1_700_000_000_000L
        val stale = tile("505:435:1500", fetchedAtMs = nowMs - RoadTypeStore.TTL_MS - 1)
        assertEquals(false, RoadTypeStore.fresh(stale, nowMs))
    }

    @Test
    fun aTileWithinTheTtlStillReadsBack() {
        val nowMs = 1_700_000_000_000L
        val stillFresh = tile("505:435:1500", fetchedAtMs = nowMs - RoadTypeStore.TTL_MS + 1)
        assertEquals(true, RoadTypeStore.fresh(stillFresh, nowMs))
    }

    @Test
    fun savingOneTileEvictsAnUnrelatedTileThatHasAgedPastTheTtl() {
        val nowMs = 1_700_000_000_000L
        val longExpired = tile("100:100:1500", fetchedAtMs = nowMs - RoadTypeStore.TTL_MS - 60_000L)
        val incoming = tile("505:435:1500", fetchedAtMs = nowMs)

        val next = RoadTypeStore.withTile(listOf(longExpired), incoming, nowMs)

        assertEquals(listOf(incoming), next)
    }

    @Test
    fun corruptJsonReadsBackAsEmptyRatherThanCrashing() {
        assertEquals(emptyList(), RoadTypeStore.parseAll("not json at all { { ["))
    }

    @Test
    fun jsonThatIsNotAnArrayReadsBackAsEmpty() {
        assertEquals(emptyList(), RoadTypeStore.parseAll("""{"not":"an array"}"""))
    }

    @Test
    fun aTileMissingARequiredFieldIsSkippedRatherThanFailingTheWholeFile() {
        val text = """[
            {"key":"505:435:1500","fetchedAtMs":1700000000000,"ways":[]},
            {"key":"nope"}
        ]"""
        val back = RoadTypeStore.parseAll(text)
        assertEquals(listOf("505:435:1500"), back.map { it.key })
    }

    @Test
    fun aWayWithFewerThanTwoPointsIsDroppedRatherThanCrashing() {
        val text = """[
            {"key":"505:435:1500","fetchedAtMs":1700000000000,"ways":[
                {"cls":"ARTERIAL","points":[[50.85,4.35]]},
                {"cls":"LOCAL","points":[[50.85,4.35],[50.86,4.35]]}
            ]}
        ]"""
        val back = RoadTypeStore.parseAll(text)
        assertEquals(1, back.single().ways.size)
        assertEquals(HighwayClass.LOCAL, back.single().ways.single().highwayClass)
    }

    @Test
    fun aWayWithAnUnrecognisedHighwayClassIsDroppedRatherThanCrashing() {
        val text = """[
            {"key":"505:435:1500","fetchedAtMs":1700000000000,"ways":[
                {"cls":"NOT_A_REAL_CLASS","points":[[50.85,4.35],[50.86,4.35]]}
            ]}
        ]"""
        assertEquals(emptyList(), RoadTypeStore.parseAll(text).single().ways)
    }
}
