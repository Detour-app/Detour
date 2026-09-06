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
 * whether what came back is an answer.
 */
class OverpassMirrorsTest {

    /** The client budget for one whole `rawQuery`, mirrors included. */
    private val budgetMs = 12_000L

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
            RoadRoulette.looksLikeJson(
                "<html><body><p>Error: runtime error: Query timed out</p></body></html>",
            ),
        )
        assertFalse(RoadRoulette.looksLikeJson("Error: rate_limited"))
        assertFalse(RoadRoulette.looksLikeJson(""))
    }

    @Test
    fun anOverpassAnswerIsAnAnswerEvenWithNothingInIt() {
        assertTrue(RoadRoulette.looksLikeJson("""{"version":0.6,"elements":[]}"""))
        // Servers are free to pretty-print, and one of these does.
        assertTrue(RoadRoulette.looksLikeJson("\n  {\n  \"elements\": []\n}"))
    }
}
