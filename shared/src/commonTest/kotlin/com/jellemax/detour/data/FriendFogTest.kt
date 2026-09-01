package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Covers FriendFog.kt's `commitIfCurrent` guard — the epoch check that stops
 * an in-flight `refresh()` from writing a departed rider's friends' traces
 * back over the reset `Auth.clear()` already performed. Same shape as the
 * three feature stores' own `commitIfCurrent` (see StoresTest's doc on the
 * matching function), and not reproducible as a concurrency test in this
 * module's test style either — no coroutine test dispatcher — so the commit
 * decision is asserted directly on the pure function instead.
 */
class FriendFogTest {

    private fun traces(vararg lat: Double) = lat.map { listOf(LatLon(it, 0.0)) }

    @Test
    fun aRefreshWhoseIdentityChangedMidFlightDoesNotCommit() {
        // Auth.clear() ran (sign-out, a 401, a server switch, or a
        // sign-back-in as the very same rider) while this refresh's request
        // was in flight. The traces it fetched belong to a session that is no
        // longer current, so they must not overwrite whatever the reset (or
        // the new session) left behind.
        val postReset = emptyList<List<LatLon>>()
        val staleResult = traces(51.0, 52.0)
        assertSame(postReset, postReset.commitIfCurrent(epoch = 1, currentEpoch = 2, result = staleResult))
    }

    @Test
    fun aRefreshWhoseIdentityIsUnchangedDoesCommit() {
        val current = emptyList<List<LatLon>>()
        val freshResult = traces(51.0, 52.0)
        assertSame(freshResult, current.commitIfCurrent(epoch = 1, currentEpoch = 1, result = freshResult))
    }
}
