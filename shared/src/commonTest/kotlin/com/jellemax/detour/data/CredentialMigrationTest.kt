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

    @Test
    fun firstRunCopiesEverythingAndKeepsTheOriginals() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        val outcome = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.Copied, outcome)
        assertEquals("at", secure.string("access_token", ""))
        assertEquals(1234L, secure.long("access_token_expires_at", 0L))
        // The whole point: the fallback is still there.
        assertEquals("at", plain.string("access_token", ""))
        assertEquals(1234L, plain.long("access_token_expires_at", 0L))
    }

    @Test
    fun theSecondRunDeletesTheOriginalsOnceTheMarkerReadsBack() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)
        val second = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

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

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)
        // The Keystore key is gone: every read returns the default, so the marker
        // does not read back and nothing may be deleted.
        secure.failReads = true
        val second = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.Copied, second)
        assertEquals("at", plain.string("access_token", ""))
    }

    @Test
    fun runningRepeatedlyChangesNothingOnceThePlaintextIsGone() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        repeat(4) { CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS) }
        val settled = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.NothingToDo, settled)
        assertEquals("at", secure.string("access_token", ""))
    }

    @Test
    fun aFreshInstallWithNothingToMoveStillArmsTheMarker() {
        val plain = FakePrefs()
        val secure = FakePrefs()

        val first = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals(CredentialMigration.Outcome.NothingToDo, first)
        // Armed anyway, so a later run does not mistake a fresh install for an
        // interrupted migration and start copying blanks over real values.
        assertTrue(secure.string(CredentialMigration.MARKER, "").isNotEmpty())
    }

    @Test
    fun blankValuesAreNotCopiedOverTheOnesAlreadyMoved() {
        val plain = FakePrefs().apply { put("access_token", "") }
        val secure = FakePrefs().apply { put("access_token", "already-here") }

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_KEYS)

        assertEquals("already-here", secure.string("access_token", ""))
    }

    @Test
    fun theServerKeysAreTheTwoCloudflareFields() {
        assertEquals(
            listOf("clientId", "clientSecret"),
            CredentialMigration.SERVER_KEYS.map { it.name },
        )
    }

    @Test
    fun theExpiryIsTheOnlyNumericSecret() {
        val numeric = (CredentialMigration.SESSION_KEYS + CredentialMigration.SERVER_KEYS)
            .filter { it.type == SecretType.Number }
        assertEquals(listOf("access_token_expires_at"), numeric.map { it.name })
    }
}
