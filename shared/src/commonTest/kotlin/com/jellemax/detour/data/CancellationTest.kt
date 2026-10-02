package com.jellemax.detour.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `runBlocking` rather than `runTest`: kotlinx-coroutines-test is not a
 * dependency of this source set (see [SingleFlightTest]).
 */
class CancellationTest {

    @Test
    fun aValueIsASuccess() {
        assertEquals(Result.success(1), catchingCancellable { 1 })
    }

    @Test
    fun anOrdinaryFailureIsCaught() {
        val result = catchingCancellable { error("offline") }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun cancellationIsRethrown() {
        assertFailsWith<CancellationException> {
            catchingCancellable { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun aCancelledLoopStopsAtTheCatch() = runBlocking {
        // CarMapRenderer's poll-loop shape, bounded so a regression to plain
        // runCatching fails on the count instead of spinning forever.
        var attempts = 0
        val job = launch {
            repeat(2) {
                attempts++
                catchingCancellable { awaitCancellation() }
            }
        }
        yield()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }
}
