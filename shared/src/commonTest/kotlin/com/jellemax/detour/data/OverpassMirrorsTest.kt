package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mirror fallthrough in [RoadRoulette.rawQuery] — the one place every
 * Overpass call in the app goes through.
 *
 * The symptom it was written for: with the first mirror down but not refusing,
 * speed cameras, average-speed sections and the posted-limit sign silently
 * never loaded, because the primary was handed the entire client budget and the
 * second mirror was reached only after it had run out — and because a mirror
 * that answers 200 with an HTML "runtime error" page counted as an answer, so
 * the healthy mirror was never asked at all.
 *
 * Deliberately no network: the fetch itself needs a Ktor engine, so what is
 * pinned here is the two decisions around it — which mirror is next, and
 * whether what came back is an answer at all.
 */
class OverpassMirrorsTest {

    /** The client budget for one whole `rawQuery`, mirrors included. */
    private val budgetMs = 12_000L

    /**
     * The **default** slice, which is what a mirror gets unless the caller says
     * otherwise. Three callers do say otherwise — `overpassWays`, `PoiRoulette`
     * and `SpeedCameras.near` pass [RoadRoulette.QUERY_BUDGET_MS] as `timeoutMs`
     * and so hand the whole window to each mirror in turn; see that constant's
     * KDoc for why their queries are heavy enough to need it. So read this
     * test's name as being about the default, not about every call the app makes.
     */
    @Test
    fun noSingleMirrorCanSpendTheWholeBudget() {
        // The bug exactly: 12 s each, sequentially, so the second mirror only
        // ever started once the window the caller cared about was gone.
        assertTrue(
            RoadRoulette.MIRROR_TIMEOUT_MS < budgetMs,
            "a mirror may not have the whole ${budgetMs}ms to itself",
        )
        assertEquals(
            budgetMs,
            RoadRoulette.MIRROR_TIMEOUT_MS * RoadRoulette.ENDPOINTS.size,
            "the mirrors between them should spend the budget, no more and no less",
        )
    }

    @Test
    fun theServerSideHintNeverOutlastsWhatTheClientWillWaitFor() {
        // A server still grinding on a query we have already abandoned holds
        // the rate-limit slot the retry needs.
        assertTrue(
            RoadRoulette.SERVER_TIMEOUT_S * 1000 <= RoadRoulette.MIRROR_TIMEOUT_MS,
            "[timeout:${RoadRoulette.SERVER_TIMEOUT_S}] outlasts the " +
                "${RoadRoulette.MIRROR_TIMEOUT_MS}ms the client waits",
        )
    }

    @Test
    fun everyMirrorIsTriedExactlyOnce() {
        val order = RoadRoulette.mirrorOrder(0)
        assertEquals(RoadRoulette.ENDPOINTS, order)
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun theOffsetRotatesTheOrderRatherThanShorteningIt() {
        val size = RoadRoulette.ENDPOINTS.size
        for (offset in listOf(1, size, size + 1, -1)) {
            val order = RoadRoulette.mirrorOrder(offset)
            assertEquals(
                RoadRoulette.ENDPOINTS.toSet(), order.toSet(),
                "offset $offset dropped a mirror",
            )
            assertEquals(size, order.size, "offset $offset repeated a mirror")
        }
        assertEquals(RoadRoulette.ENDPOINTS[1 % size], RoadRoulette.mirrorOrder(1).first())
    }

    @Test
    fun anOverloadedMirrorsErrorPageIsNotAnAnswer() {
        // What a busy Overpass actually sends, with a 200 on it.
        assertFalse(
            RoadRoulette.isOverpassAnswer(
                "<html><body><p>Error: runtime error: Query timed out</p></body></html>",
            ),
        )
        assertFalse(RoadRoulette.isOverpassAnswer("Error: rate_limited"))
        assertFalse(RoadRoulette.isOverpassAnswer(""))
        // Truncated mid-download: shaped like an answer, is not one.
        assertFalse(RoadRoulette.isOverpassAnswer("""{"version":0.6,"elements":[{"type":"""))
    }

    @Test
    fun aTimedOutQueryIsNotAnEmptyArea() {
        // What `[out:json]` sends when the server-side timeout fires: a
        // well-formed envelope, no elements, and the reason in `remark`. It
        // parses, so passing it on reads as "no cameras around here" — the
        // prefetch clears its backoff, marks the area held and never asks
        // again, which is the silence #197 is about.
        assertFalse(
            RoadRoulette.isOverpassAnswer(
                """
                {
                  "version": 0.6,
                  "generator": "Overpass API 0.7.62.1 084b4234",
                  "osm3s": { "timestamp_osm_base": "2026-09-06T10:14:21Z" },
                  "elements": [],
                  "remark": "runtime error: Query timed out in \"query\" at line 2 after 6 seconds."
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun aWayMappedWithARemarkTagIsStillAnAnswer() {
        // `remark` is an OSM tag as well as Overpass's error channel, and
        // `out tags` prints the ones mappers wrote. A substring test for it
        // would throw this answer away and then the other mirror's copy of it.
        assertTrue(
            RoadRoulette.isOverpassAnswer(
                """
                {"version":0.6,"elements":[
                  {"type":"way","id":7,"tags":{"maxspeed":"50","remark":"access for residents"}}
                ]}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun anOverpassAnswerIsAnAnswerEvenWithNothingInIt() {
        assertTrue(RoadRoulette.isOverpassAnswer("""{"version":0.6,"elements":[]}"""))
        // Servers are free to pretty-print, and one of these does.
        assertTrue(RoadRoulette.isOverpassAnswer("\n  {\n  \"elements\": []\n}"))
    }
}
