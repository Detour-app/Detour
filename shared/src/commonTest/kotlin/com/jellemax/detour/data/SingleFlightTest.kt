package com.jellemax.detour.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The defect this exists for: `Auth.resolveRiderId` held a plain [Mutex] across
 * the `/me` request that resolves the id, and that request goes out through
 * `Auth.bearer()`, which resolves the id. A `kotlinx` `Mutex` is not reentrant,
 * so the second entry waited on a lock its own caller was holding — a permanent
 * deadlock on the first authenticated call of every fresh sign-in, with no
 * timeout and no error, because the only writer of the id was the call that
 * hung.
 *
 * `runBlocking` rather than `runTest` for the same reason [AuthRetryTest] gives:
 * kotlinx-coroutines-test is not a dependency of this source set. The timeouts
 * are real ones, so a regression fails the run instead of hanging it.
 */
class SingleFlightTest {

    @Test
    fun aNestedCallFromInsideTheBlockDoesNotDeadlock() = runBlocking {
        val flight = SingleFlight()
        var outer = 0
        var nested = 0
        withTimeout(TIMEOUT_MS) {
            flight.runOnce {
                outer++
                flight.runOnce { nested++ }
            }
        }
        assertEquals(1, outer)
        // The nested call is skipped, not queued: it is the same work already
        // running one frame up the stack.
        assertEquals(0, nested)
    }

    @Test
    fun concurrentCallersRunTheBlockOnceEach() = runBlocking {
        // The debounce the gate was there for in the first place. Two callers
        // that do not overlap each get their turn; what must not happen is one
        // of them being lost or the pair overlapping.
        val flight = SingleFlight()
        var runs = 0
        var peak = 0
        var inside = 0
        withTimeout(TIMEOUT_MS) {
            coroutineScope {
                val jobs = List(4) {
                    async {
                        flight.runOnce {
                            runs++
                            inside++
                            peak = maxOf(peak, inside)
                            inside--
                        }
                    }
                }
                jobs.forEach { it.await() }
            }
        }
        assertTrue(runs in 1..4, "expected between one and four runs, got $runs")
        assertEquals(1, peak, "two callers were inside the block at once")
    }

    @Test
    fun aThrowingBlockLeavesTheGateUsable() = runBlocking {
        // Without the `finally`, one failed `/me` would wedge every later
        // resolve for the life of the process — the same class of bug as the
        // deadlock, one restart further away.
        val flight = SingleFlight()
        assertFailsWith<IllegalStateException> {
            flight.runOnce { error("boom") }
        }
        var ran = false
        withTimeout(TIMEOUT_MS) { flight.runOnce { ran = true } }
        assertTrue(ran)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
