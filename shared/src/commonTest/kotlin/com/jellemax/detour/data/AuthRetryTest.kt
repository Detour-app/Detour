package com.jellemax.detour.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The decision #67 was filed about: which `401`s end a session, and which are
 * only this server refusing this token.
 *
 * `Http`'s client is private with no injection seam and there is no `MockEngine`
 * in this source set, so [retryingRefusedAuth] takes its refresh and its call as
 * parameters. That is what lets these run against plain fakes with no network —
 * the part that was wrong was the decision, and the decision is what is tested.
 *
 * `runBlocking`, not `runTest`: `ConvoyRelayTest` already drives suspend code
 * that way and kotlinx-coroutines-test is not a dependency of this source set.
 * Nothing here delays, so there is no virtual clock to want.
 */
class AuthRetryTest {

    private fun refused() = HttpStatusException(401, """{"detail":"audience invalid"}""")

    @Test
    fun aSuccessfulCallNeitherRefreshesNorRetries() = runBlocking {
        var refreshes = 0
        var calls = 0
        val out = retryingRefusedAuth(
            auth = true,
            refresh = { refreshes++; true },
        ) { calls++; "ok" }
        assertEquals("ok", out)
        assertEquals(0, refreshes)
        assertEquals(1, calls)
    }

    @Test
    fun a401RefreshesOnceAndRetriesOnce() = runBlocking {
        var calls = 0
        val out = retryingRefusedAuth(
            auth = true,
            refresh = { true },
        ) {
            calls++
            if (calls == 1) throw refused() else "ok"
        }
        assertEquals("ok", out)
        assertEquals(2, calls)
    }

    @Test
    fun a401ThatSurvivesTheRefreshIsNotRetriedAgain() = runBlocking {
        // The whole point: two attempts, never three. A server that refuses the
        // token for its own reasons refuses it every time, and a loop here would
        // spend a refresh per request forever.
        var calls = 0
        assertFailsWith<HttpStatusException> {
            retryingRefusedAuth(auth = true, refresh = { true }) {
                calls++
                throw refused()
            }
        }
        assertEquals(2, calls)
    }

    @Test
    fun the401ThatSurvivesARefreshPropagatesRatherThanEndingTheSession() = runBlocking {
        // It comes back as an HttpStatusException, not an AuthException. Api
        // turns that into an IOException and leaves Auth alone; only the
        // provider rejecting the refresh token ends a session.
        val e = assertFailsWith<HttpStatusException> {
            retryingRefusedAuth(auth = true, refresh = { true }) { throw refused() }
        }
        assertEquals(401, e.code)
    }

    @Test
    fun anUnauthenticatedCallIsNeverRefreshed() = runBlocking {
        var refreshes = 0
        var calls = 0
        assertFailsWith<HttpStatusException> {
            retryingRefusedAuth(auth = false, refresh = { refreshes++; true }) {
                calls++
                throw refused()
            }
        }
        assertEquals(0, refreshes)
        assertEquals(1, calls)
    }

    @Test
    fun noSessionToRefreshMeansOneAttemptAndNoRetry() = runBlocking {
        // refresh() returning false is Auth saying "there is nothing signed in".
        var calls = 0
        assertFailsWith<HttpStatusException> {
            retryingRefusedAuth(auth = true, refresh = { false }) {
                calls++
                throw refused()
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun aProviderRejectingTheRefreshTokenPropagates() = runBlocking {
        // Auth.refresh clears the session and throws in this case. The exception
        // must reach the caller unchanged rather than being swallowed into a
        // retry, or a genuinely dead session would look like a server problem.
        var calls = 0
        val e = assertFailsWith<AuthException> {
            retryingRefusedAuth(
                auth = true,
                refresh = { throw AuthException("Session expired — sign in again [AUTH-401-ENDED]") },
            ) { calls++; throw refused() }
        }
        assertEquals(1, calls)
        assertTrue(e.message!!.contains(AuthRefusal.SESSION_ENDED), e.message!!)
    }

    @Test
    fun aNon401IsNeitherRefreshedNorRetried() = runBlocking {
        var refreshes = 0
        var calls = 0
        assertFailsWith<HttpStatusException> {
            retryingRefusedAuth(auth = true, refresh = { refreshes++; true }) {
                calls++
                throw HttpStatusException(500, "boom")
            }
        }
        assertEquals(0, refreshes)
        assertEquals(1, calls)
    }

    @Test
    fun theRefreshIsAskedForExactlyOncePerRefusal() = runBlocking {
        // Auth.refreshAfterRefusal is what dedupes concurrent callers, and it can
        // only do that if it is called once per refused request rather than once
        // per attempt. Twice here would spend two refreshes for one 401, and on a
        // rotating refresh token (ASVS 5.0.0 V10.4.5) the second invalidates the
        // first.
        var refreshes = 0
        var calls = 0
        retryingRefusedAuth(auth = true, refresh = { refreshes++; true }) {
            calls++
            if (calls == 1) throw refused() else "ok"
        }
        assertEquals(1, refreshes)
    }

    // ---- the identifiers a rider can quote --------------------------------

    @Test
    fun theIdentifierFollowsTheServersOwnMessage() {
        assertEquals(
            "audience invalid [AUTH-401-REFUSED]",
            AuthRefusal.message("audience invalid", AuthRefusal.SERVER_REFUSED),
        )
    }

    @Test
    fun theThreeIdentifiersAreDistinctAndStable() {
        // Append-only: these appear in bug reports, so a rename is a broken
        // promise to whoever quoted the old one.
        val all = listOf(
            AuthRefusal.SERVER_REFUSED,
            AuthRefusal.NO_SESSION,
            AuthRefusal.SESSION_ENDED,
        )
        assertEquals(all.size, all.toSet().size)
        assertEquals(listOf("AUTH-401-REFUSED", "AUTH-401-ANON", "AUTH-401-ENDED"), all)
    }
}
