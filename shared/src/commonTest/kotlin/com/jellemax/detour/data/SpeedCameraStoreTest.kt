package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [SpeedCameraStore]'s on-disk shape. [SpeedCameraStore.load]/[SpeedCameraStore.save]
 * themselves need a real account directory ([accountFile] resolves through a platform
 * `Context`), which this module's test target does not have — the same reason
 * [MunicipalityStore]'s own disk I/O has no test either. What is exercised here is the pure
 * machinery around it: [SpeedCameraStore.parseAll]/[SpeedCameraStore.serialise] (the format),
 * and [SpeedCameraStore.withTile] (the dedup-and-prune [SpeedCameraStore.save] actually
 * performs before it writes), the same split [SpeedCameras.parseCamerasResponse] uses for the
 * same reason.
 */
class SpeedCameraStoreTest {

    private fun tile(key: String, fetchedAtMs: Long = 1_700_000_000_000L) = CachedTile(
        key = key,
        fetchedAtMs = fetchedAtMs,
        result = SpeedCameras.Result(
            cameras = listOf(SpeedCameras.Camera(LatLon(50.85, 4.35), maxspeedKmh = 50.0)),
            sections = listOf(SpeedCameras.Section(listOf(LatLon(50.85, 4.35)), listOf(LatLon(50.86, 4.35)), 111.0, 90.0)),
        ),
    )

    // --- (i) round trip -----------------------------------------------------

    @Test
    fun aSavedTileRoundTripsThroughSerialiseAndParseAll() {
        val original = tile("505:435:4000")
        val back = SpeedCameraStore.parseAll(SpeedCameraStore.serialise(listOf(original)))
        assertEquals(listOf(original), back)
    }

    // --- (ii) same key overwrites rather than duplicates ---------------------

    @Test
    fun theSameKeyReplacesTheExistingTileRatherThanDuplicatingIt() {
        val nowMs = 1_700_000_000_000L
        val first = tile("505:435:4000", fetchedAtMs = nowMs - 1_000L)
        val second = tile("505:435:4000", fetchedAtMs = nowMs)

        val next = SpeedCameraStore.withTile(listOf(first), second, nowMs)

        assertEquals(listOf(second), next)
    }

    @Test
    fun aDifferentKeyIsKeptAlongsideTheExisting() {
        val nowMs = 1_700_000_000_000L
        val existing = tile("505:435:4000", fetchedAtMs = nowMs)
        val incoming = tile("506:436:4000", fetchedAtMs = nowMs)

        val next = SpeedCameraStore.withTile(listOf(existing), incoming, nowMs)

        assertEquals(setOf(existing, incoming), next.toSet())
    }

    // --- (iii) a tile older than the TTL reads back as a miss ----------------

    @Test
    fun aTileOlderThanTheTtlReadsBackAsAMiss() {
        val nowMs = 1_700_000_000_000L
        val stale = tile("505:435:4000", fetchedAtMs = nowMs - SpeedCameraStore.TTL_MS - 1)
        val fresh = stale.result.takeIf { nowMs - stale.fetchedAtMs <= SpeedCameraStore.TTL_MS }
        assertEquals(null, fresh)
    }

    @Test
    fun aTileWithinTheTtlStillReadsBack() {
        val nowMs = 1_700_000_000_000L
        val stillFresh = tile("505:435:4000", fetchedAtMs = nowMs - SpeedCameraStore.TTL_MS + 1)
        val result = stillFresh.result.takeIf { nowMs - stillFresh.fetchedAtMs <= SpeedCameraStore.TTL_MS }
        assertEquals(stillFresh.result, result)
    }

    // --- unbounded disk growth: withTile also prunes every other expired tile ---

    @Test
    fun savingOneTileEvictsAnUnrelatedTileThatHasAgedPastTheTtl() {
        // The bug #4c fixes: a long drive refetches roughly every 3 km (CameraPrefetch's
        // edge-of-area margin), and nothing ever dropped an old tile — this is what proves
        // the next save() prunes it rather than growing the file forever.
        val nowMs = 1_700_000_000_000L
        val longExpired = tile("100:100:4000", fetchedAtMs = nowMs - SpeedCameraStore.TTL_MS - 60_000L)
        val incoming = tile("505:435:4000", fetchedAtMs = nowMs)

        val next = SpeedCameraStore.withTile(listOf(longExpired), incoming, nowMs)

        assertEquals(listOf(incoming), next)
    }

    // --- (iv) corrupt/unparseable JSON reads back as empty -------------------

    @Test
    fun corruptJsonReadsBackAsEmptyRatherThanCrashing() {
        assertEquals(emptyList(), SpeedCameraStore.parseAll("not json at all { { ["))
    }

    @Test
    fun jsonThatIsNotAnArrayReadsBackAsEmpty() {
        assertEquals(emptyList(), SpeedCameraStore.parseAll("""{"not":"an array"}"""))
    }

    @Test
    fun aTileMissingARequiredFieldIsSkippedRatherThanFailingTheWholeFile() {
        // One malformed entry among otherwise-good ones must not take every other cached
        // tile down with it - same "one bad row, not the whole run" rule the camera importer
        // follows for the same reason.
        val text = """[
            {"key":"505:435:4000","fetchedAtMs":1700000000000,"cameras":[],"sections":[]},
            {"key":"nope"}
        ]"""
        val back = SpeedCameraStore.parseAll(text)
        assertEquals(listOf("505:435:4000"), back.map { it.key })
    }
}
