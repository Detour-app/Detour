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

    /**
     * An in-memory [Prefs]. `failReads` models a Keystore that has lost its key
     * entirely. `unreadableKeys` models the narrower case: the alias was
     * regenerated *mid-loop*, so only the keys sealed before that point are
     * unreadable — the marker and any keys sealed after it read back fine.
     */
    private class FakePrefs(
        var failReads: Boolean = false,
        val unreadableKeys: MutableSet<String> = mutableSetOf(),
    ) : Prefs {
        val map = mutableMapOf<String, Any>()
        override fun string(key: String, def: String): String =
            if (failReads || key in unreadableKeys) def else map[key] as? String ?: def
        override fun bool(key: String, def: Boolean): Boolean =
            if (failReads || key in unreadableKeys) def else map[key] as? Boolean ?: def
        override fun float(key: String, def: Float): Float =
            if (failReads || key in unreadableKeys) def else map[key] as? Float ?: def
        override fun long(key: String, def: Long): Long =
            if (failReads || key in unreadableKeys) def else map[key] as? Long ?: def
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

    // The defect this guards against: the delete branch used to check only whether
    // the marker read back, then delete every plaintext key on the strength of that
    // one check. A key whose ciphertext failed to seal (or whose alias was
    // regenerated mid-loop, after that key but before the marker) would still get
    // deleted, because nothing ever asked whether *that key* read back from secure.
    // The fix re-reads each key from secure before deleting it, and re-copies
    // instead of deleting when it doesn't match.
    @Test
    fun aKeyThatDoesNotReadBackFromSecureIsKeptWhileTheOthersAreDeleted() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)
        // access_token was sealed in the first run but cannot be read back now —
        // the marker and every other key are unaffected.
        secure.unreadableKeys += "access_token"
        val second = CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals(CredentialMigration.Outcome.Verified, second)
        // The key that failed its round-trip is kept in plaintext...
        assertEquals("at", plain.string("access_token", ""))
        // ...while the keys that did verify are gone.
        assertEquals("", plain.string("refresh_token", ""))
        assertEquals("", plain.string("auth_username", ""))
        assertEquals(0L, plain.long("access_token_expires_at", 0L))
    }

    // The defect this guards against: phase 1 copied plaintext into secure
    // unconditionally. If the marker write itself failed to seal on an earlier
    // run, a later run repeats phase 1 — and if the user had signed in again (or
    // saved a new Cloudflare token) in the meantime, the stale plaintext would
    // clobber the newer secure value. The fix only copies into a slot that is
    // still empty.
    @Test
    fun phaseOneDoesNotOverwriteASecureValueThatIsNewerThanTheStalePlaintext() {
        val plain = FakePrefs().apply { put("access_token", "stale-token") }
        val secure = FakePrefs().apply { put("access_token", "newer-token") }
        // No marker: as if the marker write itself never sealed on the run that
        // produced "newer-token", so this run repeats phase 1 from scratch.

        CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP)

        assertEquals("newer-token", secure.string("access_token", ""))
    }

    // The regression this guards against: step() always touches the
    // Keystore-backed secure store, even to discover there is nothing to do —
    // reading the marker back is how it tells "nothing to migrate" from
    // "verification pending" apart. groupHasPlaintext is what lets migrateGroup
    // skip that call, and skip the Keystore round trip with it, once a group's
    // migration is actually done (see CredentialMigration.kt's migrateGroup doc
    // for the measured cost that motivated this).
    @Test
    fun groupHasPlaintextIsFalseOnceEveryKeyIsEmpty() {
        val plain = plainWithSession()
        val secure = FakePrefs()

        assertTrue(CredentialMigration.groupHasPlaintext(plain, CredentialMigration.SESSION_GROUP))
        repeat(2) { CredentialMigration.step(plain, secure, CredentialMigration.SESSION_GROUP) }

        assertEquals(
            false,
            CredentialMigration.groupHasPlaintext(plain, CredentialMigration.SESSION_GROUP),
        )
    }

    @Test
    fun groupHasPlaintextIsFalseOnAFreshInstallThatNeverHadLegacyCredentials() {
        assertEquals(
            false,
            CredentialMigration.groupHasPlaintext(FakePrefs(), CredentialMigration.SESSION_GROUP),
        )
    }

    @Test
    fun groupHasPlaintextCatchesTheNumericKeyEvenWhenEveryTextKeyIsEmpty() {
        val plain = FakePrefs().apply { put("access_token_expires_at", 1234L) }

        assertTrue(CredentialMigration.groupHasPlaintext(plain, CredentialMigration.SESSION_GROUP))
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

    // The two markers that are already written to real devices' secure stores. A
    // marker that changed would read back blank on an install whose migration is
    // done, which puts that install back on the copy phase for credentials whose
    // plaintext was deleted runs ago. Asserted as literals rather than as
    // "__migration_" + name so that deriving the marker differently one day fails
    // here instead of on somebody's phone.
    @Test
    fun theTwoShippedMarkersKeepTheirExactStrings() {
        assertEquals("__migration_session", CredentialMigration.SESSION_GROUP.marker)
        assertEquals("__migration_server", CredentialMigration.SERVER_GROUP.marker)
    }

    // The copy-paste defect, which the two real groups can no longer express: a third
    // group added by copying an existing one and editing only the keys would, with a
    // hand-written marker, arm on the first group's run and take the delete branch
    // immediately — destroying plaintext it had never copied. Deriving the marker from
    // the name makes distinct groups distinct by construction, so this test uses two
    // groups that exist nowhere else to prove the construction and not the two
    // declarations.
    @Test
    fun twoAdHocGroupsAgainstOneStoreDoNotArmEachOther() {
        val alpha = SecretGroup("alpha", listOf(SecretKey("alpha_token", SecretType.Text)))
        val beta = SecretGroup("beta", listOf(SecretKey("beta_token", SecretType.Text)))
        assertTrue(alpha.marker != beta.marker, "distinct names must give distinct markers")

        val alphaPlain = FakePrefs().apply { put("alpha_token", "a") }
        val betaPlain = FakePrefs().apply { put("beta_token", "b") }
        val secure = FakePrefs() // one shared secure store, exactly as the real code uses

        // Alpha goes first and arms its own marker. Beta must not read that as its own.
        assertEquals(CredentialMigration.Outcome.Copied, CredentialMigration.step(alphaPlain, secure, alpha))
        assertEquals(CredentialMigration.Outcome.Copied, CredentialMigration.step(betaPlain, secure, beta))

        // Copied, not deleted: beta's plaintext survives its first run.
        assertEquals("b", betaPlain.string("beta_token", ""))
        assertEquals("b", secure.string("beta_token", ""))

        // Only on a later run, once its own marker has read back, does beta's go.
        assertEquals(CredentialMigration.Outcome.Verified, CredentialMigration.step(betaPlain, secure, beta))
        assertEquals("", betaPlain.string("beta_token", ""))
        assertEquals("b", secure.string("beta_token", ""))
    }
}
