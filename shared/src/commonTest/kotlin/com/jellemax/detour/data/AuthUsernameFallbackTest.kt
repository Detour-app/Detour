package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one rule: a session that cannot name its rider shows no name, rather than
 * the previous rider's. See Auth.carriedUsername.
 */
class AuthUsernameFallbackTest {

    private val riderA = "8f1c2d3e-aaaa-4444-9999-000000000001"
    private val riderB = "8f1c2d3e-bbbb-4444-9999-000000000002"

    private fun keyOf(subject: String) = AccountScope.keyFrom(subject = subject, username = "")

    @Test
    fun theStoredNameIsKeptWhenTheTokenIsForTheSameRider() {
        // The ordinary case this fallback exists for: a realm that stopped
        // sending preferred_username should not blank a signed-in rider's name.
        assertEquals(
            "ada",
            Auth.carriedUsername(subject = riderA, storedScopeKey = keyOf(riderA), stored = "ada"),
        )
    }

    @Test
    fun theStoredNameIsDroppedWhenTheTokenIsForADifferentRider() {
        // The sharp edge. Carrying "ada" here names rider B as rider A, and
        // every isMe / place.owner comparison in the app then agrees.
        assertEquals(
            "",
            Auth.carriedUsername(subject = riderB, storedScopeKey = keyOf(riderA), stored = "ada"),
        )
    }

    @Test
    fun theStoredNameIsDroppedWhenTheTokenCarriesNoSubject() {
        // No subject means no way to prove same-rider, so the safe answer is
        // the blank one — an opaque or encrypted access token lands here.
        assertEquals(
            "",
            Auth.carriedUsername(subject = "", storedScopeKey = keyOf(riderA), stored = "ada"),
        )
    }

    @Test
    fun theStoredNameIsDroppedWhenNoBucketHasBeenClaimedYet() {
        // Nothing to compare against, so nothing can be proven.
        assertEquals(
            "",
            Auth.carriedUsername(subject = riderA, storedScopeKey = "", stored = "ada"),
        )
    }
}
