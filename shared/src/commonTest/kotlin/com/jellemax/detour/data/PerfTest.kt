package com.jellemax.detour.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the recording seam in Perf.kt — the part of #84 that lives in the core.
 *
 * Two properties matter more than the timing itself, because both are what makes
 * the seam safe to leave in a release build and on the GPS/frame paths: with no
 * sink installed nothing is recorded and the covariate is never even computed,
 * and with one installed every sample carries the sizes the call ran over.
 */
class PerfTest {

    private val recorded = ArrayList<Perf.Sample>()

    @AfterTest
    fun clearSink() {
        Perf.sink = null
    }

    @Test
    fun noSinkRecordsNothing() {
        Perf.sink = null
        val mark = Perf.start()
        Perf.end(mark, "whatever") { listOf("n" to 1) }
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun noSinkNeverEvaluatesTheCovariate() {
        Perf.sink = null
        var evaluated = false
        val mark = Perf.start()
        // The covariate is a lambda precisely so that an off seam does not pay
        // for counting a list it is not going to report.
        Perf.end(mark, "whatever") { evaluated = true; listOf("n" to 1) }
        assertFalse(evaluated)
    }

    @Test
    fun aSampleCarriesItsLabelAndEverySize() {
        Perf.sink = { recorded.add(it) }
        val mark = Perf.start()
        Perf.end(mark, "Coverage.compute") { listOf("points" to 4210, "municipalities" to 7) }
        assertEquals(1, recorded.size)
        assertEquals("Coverage.compute", recorded[0].label)
        assertEquals(listOf("points" to 4210, "municipalities" to 7), recorded[0].sizes)
    }

    @Test
    fun durationIsRecordedInMicrosecondsBecauseTheHotTargetsAreSubMillisecond() {
        Perf.sink = { recorded.add(it) }
        val mark = Perf.start()
        Perf.end(mark, "needsLookup") { emptyList() }
        // A wall-clock assertion would be flaky; that it is non-negative and in
        // microsecond units is the contract the sink formats against.
        assertTrue(recorded[0].durationUs >= 0)
    }

    @Test
    fun enabledFollowsWhetherASinkIsInstalled() {
        Perf.sink = null
        assertFalse(Perf.enabled)
        Perf.sink = { recorded.add(it) }
        assertTrue(Perf.enabled)
    }
}
