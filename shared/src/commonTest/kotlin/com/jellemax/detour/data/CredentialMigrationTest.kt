package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [CredentialMigration] — moving six credential values out of plaintext
 * preferences without a window in which they exist in neither store.
 *
 * The shape under test is deliberate: the plaintext is kept until a marker written
 * on an earlier run reads back, because that is the only evidence that this device
 * can actually decrypt what was written. Testable at all only because `Prefs` is an
 * interface, which is what lets [FakePrefs] exist.
 */
class CredentialMigrationTest {

    /** An in-memory [Prefs]. `failReads` models a Keystore that has lost its key. */
    private class FakePrefs(var failReads: Boolean = false) : Prefs {
        val map = mutableMapOf<String, Any>()
        override fun string(key: String, def: String): String =
            if (failReads) def else map[key] as? String ?: def
        override fun bool(key: String, def: Boolean): Boolean =
            if (failReads) def else map[key] as? Boolean ?: def
        override fun float(key: String, def: Float): Float =
            if (failReads) def else map[key] as? Float ?: def
        override fun long(key: String, def: Long): Long =
            if (failReads) def else map[key] as? Long ?: def
        override fun put(key: String, value: String) { map[key] = value }
        override fun put(key: String, value: Boolean) { map[key] = value }
        override fun put(key: String, value: Float) { map[key] = value }
        override fun put(key: String, value: Long) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    private fun plainWithSession() = FakePrefs().apply {
        put("access_token", "at")
        put("refresh_token", "rt")
        put("access_token_expires_at", 1234L)
        put("auth_username", "andre")
    }

    private fun plainWithServer() = FakePrefs().apply {
        put("clientId", "cid")
        put("clientSecret", "csecret")
    }

    @Test
    fun firstRunCopiesEverythingAndKeepsTheOriginals() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        val outcome = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals(CredentialMigration.Outcome.Copied, outcome)
        assertEquals("at", secure.string("access_token", ""))
        assertEquals(1234L, secure.long("access_token_expires_at", 0L))
        // The whole point: the fallback is still there.
        assertEquals("at", plain.string("access_token", ""))
        assertEquals(1234L, plain.long("access_token_expires_at", 0L))
    }

    // Named for calls, not runs: step() only sees whether the marker is armed, not
    // whether this is a second call in the same process or a call on a later run.
    // Telling those apart — actually waiting for a later run — is the caller's job,
    // enforced by the once-per-process guards at the two call sites, not by step()
    // itself. Two calls in a row is exactly what the guards exist to prevent in
    // production, but it is what this function does when nothing stops it.
    @Test
    fun aLaterCallDeletesTheOriginalsOnceTheMarkerReadsBack() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)
        val second = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals(CredentialMigration.Outcome.Verified, second)
        assertEquals("", plain.string("access_token", ""))
        assertEquals(0L, plain.long("access_token_expires_at", 0L))
        // And the values are still readable from where they moved to.
        assertEquals("at", secure.string("access_token", ""))
    }

    @Test
    fun anUnreadableSecureStoreKeepsThePlaintextAndRetriesTheCopy() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)
        // The Keystore key is gone: every read returns the default, so the marker
        // does not read back and nothing may be deleted.
        secure.failReads = true
        val second = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals(CredentialMigration.Outcome.Copied, second)
        assertEquals("at", plain.string("access_token", ""))
    }

    @Test
    fun runningRepeatedlyChangesNothingOnceThePlaintextIsGone() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        repeat(4) { CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP) }
        val settled = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals(CredentialMigration.Outcome.NothingToDo, settled)
        assertEquals("at", secure.string("access_token", ""))
    }

    @Test
    fun aFreshInstallWithNothingToMoveStillArmsTheMarker() {
        val plain = FakePrefs()
        val secure = FakePrefs()

        val first = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals(CredentialMigration.Outcome.NothingToDo, first)
        // Armed anyway, so a later run does not mistake a fresh install for an
        // interrupted migration and start copying blanks over real values.
        assertTrue(secure.string(CredentialMigration.SESSION_GROUP.marker, "").isNotEmpty())
    }

    @Test
    fun blankValuesAreNotCopiedOverTheOnesAlreadyMoved() {
        val plain = FakePrefs().apply { put("access_token", "") }
        val secure = FakePrefs().apply { put("access_token", "already-here") }

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals("already-here", secure.string("access_token", ""))
    }

    @Test
    fun theServerKeysAreTheTwoCloudflareFields() {
        assertEquals(
            listOf("clientId", "clientSecret"),
            CredentialMigration.SERVER_GROUP.keys.map { it.name },
        )
    }

    @Test
    fun theExpiryIsTheOnlyNumericSecret() {
        val numeric = (CredentialMigration.SESSION_GROUP.keys + CredentialMigration.SERVER_GROUP.keys)
            .filter { it.type == SecretType.Number }
        assertEquals(listOf("access_token_expires_at"), numeric.map { it.name })
    }

    // The defect this guards against: both groups migrate into the same secure store, so
    // a single shared marker would let the session group's first run arm it, and the
    // server group's very next call would read that marker back as its own and delete
    // plaintext it had never copied. Per-group markers ([SecretGroup.marker]) are what
    // keeps one group's run from arming the other.
    @Test
    fun oneGroupsMigrationDoesNotArmAnothers() {
        val sessionPlain = plainWithSession()
        val serverPlain = plainWithServer()
        val secure = FakePrefs() // one shared secure store, exactly as the real code uses

        CredentialMigration.step(sessionPlain, secure, CredentialMigration.SESSION_GROUP)
        CredentialMigration.step(serverPlain, secure, CredentialMigration.SERVER_GROUP)

        // First pass: the server keys must have been copied, not deleted sight unseen.
        assertEquals("cid", serverPlain.string("clientId", ""))
        assertEquals("csecret", serverPlain.string("clientSecret", ""))
        assertEquals("cid", secure.string("clientId", ""))
        assertEquals("csecret", secure.string("clientSecret", ""))

        CredentialMigration.step(sessionPlain, secure, CredentialMigration.SESSION_GROUP)
        CredentialMigration.step(serverPlain, secure, CredentialMigration.SERVER_GROUP)

        // Second pass: now both markers have genuinely read back, so both plaintexts go.
        assertEquals("", sessionPlain.string("access_token", ""))
        assertEquals("", serverPlain.string("clientId", ""))
        assertEquals("", serverPlain.string("clientSecret", ""))
        assertEquals("at", secure.string("access_token", ""))
        assertEquals("cid", secure.string("clientId", ""))
        assertEquals("csecret", secure.string("clientSecret", ""))
    }
}
