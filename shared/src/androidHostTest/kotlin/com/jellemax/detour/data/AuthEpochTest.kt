package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Covers Auth.kt's session epoch: that establishing a session bumps it (not
 * only [Auth.clear] tearing one down), that a routine access-token refresh
 * does not, and that a store action captured before a new session begins is
 * rejected by [FriendsState.commitIfCurrent] once it does.
 *
 * Android-only for the same reason `RouteStoreLoadOrderTest` is: this test
 * target has no Android Context (see Platform.android.kt), so
 * `Settings.init()` was never called and any write through
 * `Settings.setSession()` throws "initSharedCore(context) has not been
 * called". That failure is what makes the ordering observable here —
 * [Auth.store] bumps the epoch as a plain field write, before it ever
 * reaches [Settings], so the bump has already happened by the time that
 * exception is thrown partway through.
 */
class AuthEpochTest {

    private fun tokenResponse(accessToken: String) =
        """{"access_token":"$accessToken","refresh_token":"rt","expires_in":900}"""

    @Test
    fun establishingASessionBumpsTheEpochEvenThoughSettingsThenFails() {
        val before = Auth.sessionEpoch.value

        assertFailsWith<IllegalStateException> {
            Auth.store(tokenResponse("at1"), establishesSession = true)
        }

        assertEquals(before + 1, Auth.sessionEpoch.value)
    }

    @Test
    fun aRoutineRefreshDoesNotBumpTheEpoch() {
        // Guards the other half of the same fix: a background access-token
        // refresh continues the same session rather than starting a new one,
        // so it must not discard a store action that legitimately spans it —
        // see Auth.store's own doc. Establish first so this isn't trivially
        // true against a from-scratch epoch that has never moved.
        assertFailsWith<IllegalStateException> {
            Auth.store(tokenResponse("at2"), establishesSession = true)
        }
        val before = Auth.sessionEpoch.value

        assertFailsWith<IllegalStateException> {
            Auth.store(tokenResponse("at3"), establishesSession = false)
        }

        assertEquals(before, Auth.sessionEpoch.value)
    }

    /**
     * The other side of the same discipline: [Auth.clear] used to bump the
     * epoch *after* writing [Settings] — the opposite order from [store]'s
     * establish path above. Reusing this file's own technique proves the fix
     * the same way: [Settings.setSession] throws for lack of a Context in
     * this test target, so if the bump happened first (as it now does) it
     * survives that throw; if it happened last (the old order) the throw
     * would have pre-empted it and this assertion would see the epoch
     * unmoved.
     */
    @Test
    fun clearingASessionBumpsTheEpochEvenThoughSettingsThenFails() {
        val before = Auth.sessionEpoch.value

        assertFailsWith<IllegalStateException> {
            Auth.clear()
        }

        assertEquals(before + 1, Auth.sessionEpoch.value)
    }

    @Test
    fun aStoreActionCapturedBeforeANewSessionIsEstablishedDoesNotCommitUnderIt() {
        // The reachable instance from the spec: an action with no `signedIn`
        // guard of its own captures the epoch, keeps running across a
        // sign-in, and must not land under the new rider's state.
        val epoch = Auth.sessionEpoch.value

        assertFailsWith<IllegalStateException> {
            Auth.store(tokenResponse("at4"), establishesSession = true)
        }

        val postEstablish = FriendsState()
        val staleResult = postEstablish.copy(
            own = FriendStats(
                rider = RiderRef(RiderId("previous-rider-id"), "previous-rider"),
                stats = RiderStats(),
                badgeIds = emptyList(),
            ),
        )
        assertSame(
            postEstablish,
            postEstablish.commitIfCurrent(epoch, Auth.sessionEpoch.value, staleResult),
        )
    }
}
