package com.jellemax.detour.presentation

import com.jellemax.detour.data.HereTrafficClient.Fetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `runBlocking` rather than `runTest`, as in [com.jellemax.detour.data.HereTrafficClientTest]:
 * commonTest has no kotlinx-coroutines-test.
 */
class TrafficKeyPresenterTest {

    private val stored = mutableListOf<String>()

    private fun presenter(result: Fetch<*>) = TrafficKeyPresenter(probe = { result }, store = { stored += it })

    @Test
    fun aKeyHereAcceptsIsStoredAndReadsAsOk() = runBlocking {
        val p = presenter(Fetch.Ok(emptyList<Any>()))
        p.test("good-key")
        assertEquals(TrafficKeyStatus.OK, p.status.value)
        assertEquals(listOf("good-key"), stored)
    }

    @Test
    fun eachRefusalMapsToItsOwnStatusAndStoresNothing() = runBlocking {
        val cases = mapOf(
            Fetch.BadKey to TrafficKeyStatus.BAD_KEY,
            Fetch.Quota to TrafficKeyStatus.QUOTA,
            Fetch.Failed to TrafficKeyStatus.UNREACHABLE,
        )
        for ((result, status) in cases) {
            val p = presenter(result)
            p.test("some-key")
            assertEquals(status, p.status.value, "for $result")
        }
        assertTrue(stored.isEmpty(), "a failed key must not replace the stored one")
    }

    @Test
    fun aBlankKeyIsRefusedWithoutARequest() = runBlocking {
        var probed = false
        val p = TrafficKeyPresenter(probe = { probed = true; Fetch.Ok(Unit) }, store = { stored += it })
        p.test("   ")
        assertEquals(TrafficKeyStatus.BAD_KEY, p.status.value)
        assertTrue(!probed && stored.isEmpty())
    }

    @Test
    fun testingShowsWhileTheRequestIsInFlight() = runBlocking {
        val answer = CompletableDeferred<Fetch<*>>()
        val p = TrafficKeyPresenter(probe = { answer.await() }, store = { stored += it })
        val job = launch { p.test("good-key") }
        yield()
        assertEquals(TrafficKeyStatus.TESTING, p.status.value)
        answer.complete(Fetch.Ok(Unit))
        job.join()
        assertEquals(TrafficKeyStatus.OK, p.status.value)
    }

    @Test
    fun editingTheFieldClearsTheLastResult() = runBlocking {
        val p = presenter(Fetch.BadKey)
        p.test("typo")
        p.edited()
        assertEquals(TrafficKeyStatus.IDLE, p.status.value)
    }

    @Test
    fun anEditDuringATestDiscardsItsResult() = runBlocking {
        val answer = CompletableDeferred<Fetch<*>>()
        val p = TrafficKeyPresenter(probe = { answer.await() }, store = { stored += it })
        val job = launch { p.test("first") }
        yield()
        p.edited()
        answer.complete(Fetch.Ok(Unit))
        job.join()
        assertEquals(TrafficKeyStatus.IDLE, p.status.value)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aRemoveDuringATestStaysRemoved() = runBlocking {
        val answer = CompletableDeferred<Fetch<*>>()
        val p = TrafficKeyPresenter(probe = { answer.await() }, store = { stored += it })
        val job = launch { p.test("good-key") }
        yield()
        p.remove()
        answer.complete(Fetch.Ok(Unit))
        job.join()
        assertEquals(listOf(""), stored)
        assertEquals(TrafficKeyStatus.IDLE, p.status.value)
    }

    @Test
    fun removeStoresABlankKeyAndResetsTheStatus() = runBlocking {
        val p = presenter(Fetch.Ok(Unit))
        p.test("good-key")
        p.remove()
        assertEquals(listOf("good-key", ""), stored)
        assertEquals(TrafficKeyStatus.IDLE, p.status.value)
    }
}
