package com.jellemax.detour.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #905: the HERE key's two slots and their reconcile with `/api/me/here-key`.
 *
 * The slots run on an in-memory [Prefs] and the three calls are answered by
 * lambdas, so none of this needs `Settings.init()` or a network. `runBlocking`
 * for the same reason `AuthRetryTest` gives.
 */
class HereKeySyncTest {

    private class MemPrefs : Prefs {
        val map = mutableMapOf<String, Any>()
        override fun string(key: String, def: String) = map[key] as? String ?: def
        override fun bool(key: String, def: Boolean) = map[key] as? Boolean ?: def
        override fun float(key: String, def: Float) = map[key] as? Float ?: def
        override fun long(key: String, def: Long) = map[key] as? Long ?: def
        override fun put(key: String, value: String) { map[key] = value }
        override fun put(key: String, value: Boolean) { map[key] = value }
        override fun put(key: String, value: Float) { map[key] = value }
        override fun put(key: String, value: Long) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    /** A server with one key slot, recording what it was sent. */
    private class FakeServer(var stored: HereKeyAnswer = HereKeyAnswer.Empty) {
        val calls = mutableListOf<String>()
        var fail: Exception? = null
        var duringCall: () -> Unit = {}

        val remote = HereKeyRemote(
            get = { call("GET"); stored },
            put = { key -> call("PUT $key"); stored = HereKeyAnswer.Stored(key) },
            delete = { call("DELETE"); stored = HereKeyAnswer.Empty },
        )

        private fun call(what: String) {
            fail?.let { throw it }
            calls += what
            duringCall().also { duringCall = {} }
        }
    }

    private var epoch = 0

    private fun slots(bag: Prefs = MemPrefs(), signedIn: Boolean = true, anyBucket: Boolean = false) =
        HereKeySlots(bag).apply { launch(signedIn) { anyBucket } }

    private fun HereKeySlots.reconcile(server: FakeServer) =
        runBlocking { reconcileHereKey(this@reconcile, server.remote) { epoch } }

    // --- the reconcile matrix ---

    @Test
    fun anEditedKeyIsPut() {
        val s = slots().apply { edit(" abc ") }
        val server = FakeServer()
        assertEquals(HereKeyOutcome.UPLOADED, s.reconcile(server))
        assertEquals(listOf("PUT abc"), server.calls)
        assertEquals(HereKeyPending.NONE, s.pending)
    }

    @Test
    fun anEditedRemovalIsDeleted() {
        val s = slots().apply { edit("abc"); edit("") }
        val server = FakeServer(HereKeyAnswer.Stored("abc"))
        assertEquals(HereKeyOutcome.UPLOADED, s.reconcile(server))
        assertEquals(listOf("DELETE"), server.calls)
        assertEquals("", s.key.value)
    }

    @Test
    fun anAdoptedKeyLosesToTheServersKey() {
        val s = slotsWithClaimedDeviceKey("mine")
        val server = FakeServer(HereKeyAnswer.Stored("theirs"))
        assertEquals(HereKeyOutcome.TOOK_SERVER, s.reconcile(server))
        assertEquals("theirs", s.key.value)
        assertEquals(HereKeyPending.NONE, s.pending)
    }

    @Test
    fun anAdoptedKeyGoesUpWhenTheServerHasNone() {
        val s = slotsWithClaimedDeviceKey("mine")
        val server = FakeServer()
        assertEquals(HereKeyOutcome.PUT_BACK, s.reconcile(server))
        assertEquals(listOf("GET", "PUT mine"), server.calls)
        assertEquals(HereKeyPending.NONE, s.pending)
    }

    @Test
    fun anAdoptedKeyGoesUpOverAnUnreadableOne() {
        val s = slotsWithClaimedDeviceKey("mine")
        val server = FakeServer(HereKeyAnswer.Unreadable)
        assertEquals(HereKeyOutcome.PUT_BACK, s.reconcile(server))
        assertEquals(listOf("GET", "PUT mine"), server.calls)
    }

    @Test
    fun withNothingPendingTheServersKeyReplacesOurs() {
        val s = settledSlots("old")
        assertEquals(HereKeyOutcome.TOOK_SERVER, s.reconcile(FakeServer(HereKeyAnswer.Stored("new"))))
        assertEquals("new", s.key.value)
    }

    @Test
    fun withNothingPendingA204RemovesOurs() {
        // Remove on phone B turns A's traffic off after A's next sync.
        val s = settledSlots("old")
        assertEquals(HereKeyOutcome.TOOK_SERVER, s.reconcile(FakeServer()))
        assertEquals("", s.key.value)
    }

    @Test
    fun withNothingPendingAnUnreadableKeyIsPutBackFromOurCache() {
        val s = settledSlots("cached")
        val server = FakeServer(HereKeyAnswer.Unreadable)
        assertEquals(HereKeyOutcome.PUT_BACK, s.reconcile(server))
        assertEquals(listOf("GET", "PUT cached"), server.calls)
        assertEquals("cached", s.key.value)
    }

    @Test
    fun withNothingPendingAnUnreadableKeyAndNoCacheLeavesItBlank() {
        val s = slots()
        val server = FakeServer(HereKeyAnswer.Unreadable)
        assertEquals(HereKeyOutcome.TOOK_SERVER, s.reconcile(server))
        assertEquals(listOf("GET"), server.calls)
        assertEquals("", s.key.value)
    }

    @Test
    fun aMissingEndpointKeepsTheLocalKeyAndItsPending() {
        val s = slots().apply { edit("abc") }
        val server = FakeServer().apply { fail = HttpStatusException(404, "") }
        assertEquals(HereKeyOutcome.KEPT_LOCAL, s.reconcile(server))
        assertEquals("abc", s.key.value)
        assertEquals(HereKeyPending.EDITED, s.pending)
    }

    @Test
    fun signedOutThereIsNothingToReconcile() {
        val s = slots(signedIn = false)
        val server = FakeServer()
        assertEquals(HereKeyOutcome.SKIPPED, s.reconcile(server))
        assertTrue(server.calls.isEmpty())
    }

    // --- races ---

    @Test
    fun anEditDuringAnUploadIsStillUploaded() {
        val s = slots().apply { edit("first") }
        val server = FakeServer().apply { duringCall = { s.edit("second") } }
        s.reconcile(server)
        assertEquals(HereKeyPending.EDITED, s.pending)
        s.reconcile(server)
        assertEquals(listOf("PUT first", "PUT second"), server.calls)
        assertEquals(HereKeyPending.NONE, s.pending)
    }

    @Test
    fun aGetLandingAfterAnEditIsDropped() {
        val s = settledSlots("old")
        val server = FakeServer(HereKeyAnswer.Stored("server")).apply { duringCall = { s.edit("typed") } }
        assertEquals(HereKeyOutcome.DROPPED, s.reconcile(server))
        assertEquals("typed", s.key.value)
        assertEquals(HereKeyPending.EDITED, s.pending)
    }

    @Test
    fun aGetLandingAfterSignOutIsDropped() {
        val s = settledSlots("old")
        val server = FakeServer(HereKeyAnswer.Stored("server")).apply {
            duringCall = {
                epoch++
                s.session(false)
                s.clearAccount()
            }
        }
        assertEquals(HereKeyOutcome.DROPPED, s.reconcile(server))
        assertEquals("", s.account)
    }

    // --- sign-in, sign-out, upgrade ---

    @Test
    fun theFirstSignInOnAPhoneWithNoRidesTakesTheDeviceKey() {
        val s = slots(signedIn = false, anyBucket = false).apply { edit("typed") }
        s.session(true)
        s.startAccount()
        assertEquals("typed", s.key.value)
        assertEquals(HereKeyPending.ADOPTED, s.pending)
        assertEquals("", s.device)
    }

    @Test
    fun aSecondAccountDoesNotTakeTheDeviceKey() {
        val s = slots(signedIn = false).apply {
            session(true)
            startAccount()
            session(false)
            clearAccount()
            edit("second rider's")
            session(true)
            startAccount()
        }
        assertEquals("", s.key.value)
        assertEquals("second rider's", s.device)
    }

    @Test
    fun aSignInWipesWhatAnEarlierSessionLeftInTheAccountSlot() {
        // A kill between setSession("") and clearAccount(): account A's key and
        // its pending upload are still in the bag when B signs in.
        val bag = MemPrefs()
        bag.put(HereKeySlots.MIGRATED, true)
        bag.put(HereKeySlots.CLAIMED, true)
        bag.put(HereKeySlots.ACCOUNT, "rider A's")
        bag.put(HereKeySlots.PENDING, HereKeyPending.EDITED.name)
        val s = HereKeySlots(bag).apply { launch(false) { true } }
        s.session(true)
        s.startAccount()
        assertEquals("", s.key.value)
        assertEquals(HereKeyPending.NONE, s.pending)
        val server = FakeServer(HereKeyAnswer.Stored("rider B's"))
        assertEquals(HereKeyOutcome.TOOK_SERVER, s.reconcile(server))
        assertEquals(listOf("GET"), server.calls)
        assertEquals("rider B's", s.key.value)
    }

    @Test
    fun aTakeInFlightAcrossASignInIsDropped() {
        val s = settledSlots("A's")
        val server = FakeServer(HereKeyAnswer.Stored("A's server")).apply { duringCall = { s.startAccount() } }
        assertEquals(HereKeyOutcome.DROPPED, s.reconcile(server))
        assertEquals("", s.account)
    }

    @Test
    fun clearBlanksTheAccountSlotAndLeavesTheDeviceSlot() {
        val bag = MemPrefs()
        bag.put(HereKeySlots.DEVICE, "device")
        bag.put(HereKeySlots.MIGRATED, true)
        bag.put(HereKeySlots.CLAIMED, true)
        val s = HereKeySlots(bag).apply { launch(true) { true }; edit("account") }
        s.session(false)
        s.clearAccount()
        assertEquals("device", s.key.value)
        assertEquals("", s.account)
        assertEquals(HereKeyPending.NONE, s.pending)
    }

    @Test
    fun theUpgradeMovesASignedInKeyOnce() {
        val bag = MemPrefs().apply { put(HereKeySlots.DEVICE, "old build's") }
        val first = HereKeySlots(bag).apply { launch(true) { true } }
        assertEquals("old build's", first.key.value)
        assertEquals(HereKeyPending.ADOPTED, first.pending)

        // A later launch, with a key typed signed out since: it stays put.
        bag.put(HereKeySlots.DEVICE, "typed signed out")
        val second = HereKeySlots(bag).apply { launch(true) { true } }
        assertEquals("typed signed out", second.device)
        assertEquals("old build's", second.account)
    }

    @Test
    fun theUpgradeMarksThePhoneClaimedWhenAnAccountOwnsData() {
        val bag = MemPrefs().apply { put(HereKeySlots.DEVICE, "k") }
        val s = HereKeySlots(bag).apply { launch(false) { true } }
        assertTrue(s.claimed)
        s.session(true)
        s.startAccount()
        assertEquals("", s.key.value)
    }

    @Test
    fun aFreshInstallIsUnclaimed() {
        assertFalse(slots(signedIn = false, anyBucket = false).claimed)
    }

    // --- the wire ---

    @Test
    fun answersParse() {
        assertEquals(HereKeyAnswer.Empty, hereKeyAnswer(""))
        assertEquals(HereKeyAnswer.Stored("k"), hereKeyAnswer("""{"key":"k","unreadable":false}"""))
        assertEquals(HereKeyAnswer.Unreadable, hereKeyAnswer("""{"key":null,"unreadable":true}"""))
    }

    private fun slotsWithClaimedDeviceKey(key: String) =
        slots(signedIn = false).apply {
            edit(key)
            session(true)
            startAccount()
        }

    private fun settledSlots(key: String) = slots().apply {
        edit(key)
        settle()
    }
}
