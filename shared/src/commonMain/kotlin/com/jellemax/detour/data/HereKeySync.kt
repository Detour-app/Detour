package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The rider's HERE key, synced through their Detour account (#905, the owner's
 * 1A on #802).
 *
 * Two slots in the secure bag. The device slot is the key a signed-out rider
 * typed, and stays on this phone only. The account slot is the signed-in
 * account's key, mirrored to `/api/me/here-key`, where the server keeps it
 * encrypted with a key ring its operator holds. [Settings.hereTrafficKey] is the
 * account slot while signed in and the device slot while signed out, so signing
 * out takes the account's key off the phone and signing in brings it back.
 *
 * Out of `Settings.kt` because that file sits at §8's 1,000-line limit;
 * [Settings] keeps its `hereTrafficKey` / `setHereTrafficKey` surface and
 * delegates here, so neither platform's callers changed.
 */
internal object HereKeySync {

    private var slots: HereKeySlots? = null

    /** One reconcile at a time. Not `SyncClient.syncLock`: this also runs from
     *  [Auth.exchangeCode], which never holds that lock, and a sign-in waiting
     *  behind a multi-megabyte sync would be a sign-in that looks hung. */
    private val reconcileLock = Mutex()

    private val remote = HereKeyRemote(
        get = { hereKeyAnswer(Api.request("GET", PATH)) },
        put = { key -> Api.request("PUT", PATH, buildJsonObject { put("key", key) }) },
        delete = { Api.request("DELETE", PATH) },
    )

    private const val PATH = "/me/here-key"

    /** Owned here rather than by the slots, so a collector that starts
     *  before [load] still sees the key once it is read. */
    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key.asStateFlow()

    /** From [Settings.init], with the secure bag: reads the slots and runs the
     *  one-time upgrade before anything can sign in or reconcile. */
    internal fun load(secure: Prefs, signedIn: Boolean, anyAccountBucket: () -> Boolean) {
        if (slots != null) return
        slots = HereKeySlots(secure, _key).also { it.launch(signedIn, anyAccountBucket) }
    }

    /** From [Settings.setSession]: a sign-in, sign-out or refresh. */
    internal fun onSession(signedIn: Boolean) {
        slots?.session(signedIn)
    }

    /** From [Settings.setHereTrafficKey]. */
    internal fun edit(key: String) {
        slots?.edit(key)
    }

    /** From [Auth.exchangeCode], after the new session is stored. */
    internal fun claimDeviceKey() {
        slots?.claimDeviceKey()
    }

    /** From [Auth.clear]. */
    internal fun clearAccount() {
        slots?.clearAccount()
    }

    /** Brings the account slot and the server into line. Never throws except
     *  for cancellation: a failure keeps the local key and the next sync
     *  retries. */
    suspend fun reconcile(): HereKeyOutcome {
        val s = slots ?: return HereKeyOutcome.SKIPPED
        return reconcileLock.withLock { reconcileHereKey(s, remote) { Auth.sessionEpoch.value } }
    }
}

/** What still has to reach the server. Persisted, so a kill between an edit
 *  and the next sync does not lose it. */
internal enum class HereKeyPending {
    NONE,

    /** The rider set or removed the key while signed in: ours goes up. */
    EDITED,

    /** A signed-out key moved into the account: the server's wins if it has one. */
    ADOPTED,
    ;

    companion object {
        fun decode(stored: String): HereKeyPending = entries.firstOrNull { it.name == stored } ?: NONE
    }
}

/** What `GET /api/me/here-key` answered. */
internal sealed class HereKeyAnswer {
    data class Stored(val key: String) : HereKeyAnswer()

    /** 204: the account has no key. */
    data object Empty : HereKeyAnswer()

    /** The server holds a key its key ring can no longer decrypt. */
    data object Unreadable : HereKeyAnswer()
}

/** A 204 arrives as an empty body. */
internal fun hereKeyAnswer(body: String): HereKeyAnswer {
    if (body.isBlank()) return HereKeyAnswer.Empty
    val o = jsonObjectOf(body)
    if (o.optBoolean("unreadable")) return HereKeyAnswer.Unreadable
    return o.optString("key").trim().takeIf { it.isNotEmpty() }?.let { HereKeyAnswer.Stored(it) } ?: HereKeyAnswer.Empty
}

/** What a reconcile does with a GET's answer. */
internal sealed class HereKeyAfterGet {
    /** The account slot becomes [key] (blank = no key). */
    data class Take(val key: String) : HereKeyAfterGet()

    /** The server has nothing usable and we do: send ours. */
    data class PutBack(val key: String) : HereKeyAfterGet()
}

/**
 * The decision, apart from the network. [pending] is never [HereKeyPending.EDITED]
 * here: an edit uploads without asking first.
 *
 * - A stored key wins, adopted or not: an account that already has a key keeps it.
 * - 204 with an adopted key: ours goes up. 204 otherwise: the key was removed on
 *   another device, so it goes here too.
 * - Unreadable (the server lost its key ring): ours goes back up if we hold one.
 */
internal fun hereKeyAfterGet(pending: HereKeyPending, answer: HereKeyAnswer, local: String): HereKeyAfterGet =
    when (answer) {
        is HereKeyAnswer.Stored -> HereKeyAfterGet.Take(answer.key)
        HereKeyAnswer.Empty ->
            if (pending == HereKeyPending.ADOPTED && local.isNotEmpty()) {
                HereKeyAfterGet.PutBack(local)
            } else {
                HereKeyAfterGet.Take("")
            }
        HereKeyAnswer.Unreadable ->
            if (local.isNotEmpty()) HereKeyAfterGet.PutBack(local) else HereKeyAfterGet.Take("")
    }

internal enum class HereKeyOutcome {
    /** Signed out, or [Settings.init] has not run: nothing to reconcile. */
    SKIPPED,

    /** Our edit went up. */
    UPLOADED,

    /** The account slot now holds what the server said. */
    TOOK_SERVER,

    /** The server had nothing usable; ours went up. */
    PUT_BACK,

    /** The answer arrived after an edit or a session change, and was not applied. */
    DROPPED,

    /** Offline, an older server (404), a 5xx: the local key stays, the next sync retries. */
    KEPT_LOCAL,
}

/** The three calls, as values so a test can answer them without a network
 *  (the seam [retryingRefusedAuth] uses for the same reason). */
internal class HereKeyRemote(
    val get: suspend () -> HereKeyAnswer,
    val put: suspend (String) -> Unit,
    val delete: suspend () -> Unit,
)

/**
 * One reconcile. Every write-back first checks that neither the session
 * ([epoch], [Auth.sessionEpoch]) nor the slots ([HereKeySlots.edits]) moved
 * while the request was out: an answer for a signed-out rider, or one older
 * than the rider's latest edit, is dropped, and the edit goes up next time.
 */
internal suspend fun reconcileHereKey(
    slots: HereKeySlots,
    remote: HereKeyRemote,
    epoch: () -> Int,
): HereKeyOutcome {
    if (!slots.signedIn) return HereKeyOutcome.SKIPPED
    val epochAtStart = epoch()
    val editsAtStart = slots.edits
    val pending = slots.pending
    val local = slots.account
    fun current() = epoch() == epochAtStart && slots.edits == editsAtStart
    return try {
        if (pending == HereKeyPending.EDITED) {
            if (local.isEmpty()) remote.delete() else remote.put(local)
            if (current()) slots.settle()
            return HereKeyOutcome.UPLOADED
        }
        val answer = remote.get()
        if (!current()) return HereKeyOutcome.DROPPED
        when (val step = hereKeyAfterGet(pending, answer, local)) {
            is HereKeyAfterGet.Take -> {
                if (!slots.take(step.key, editsAtStart)) return HereKeyOutcome.DROPPED
                HereKeyOutcome.TOOK_SERVER
            }
            is HereKeyAfterGet.PutBack -> {
                remote.put(step.key)
                if (current()) slots.settle()
                HereKeyOutcome.PUT_BACK
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Any failure, deliberately broad: this runs at the tail of a sign-in
        // and of every sync, and neither may fail because of it.
        HereKeyOutcome.KEPT_LOCAL
    }
}

/**
 * The two slots and their bookkeeping, on the secure bag.
 *
 * "First account on this phone" is the persisted [CLAIMED] flag rather than
 * [AccountFiles.adoptAndActivate]'s return: that is false on a phone with no
 * rides yet, and a first rider who typed a key before recording anything would
 * leave it behind.
 */
internal class HereKeySlots(
    private val bag: Prefs,
    private val live: MutableStateFlow<String> = MutableStateFlow(""),
) {

    /** The live slot: [account] signed in, [device] signed out. */
    val key: StateFlow<String> = live.asStateFlow()

    /** Bumped by every edit and by [clearAccount]. In memory only: it guards a
     *  round trip, which does not outlive the process. */
    @Volatile
    var edits = 0
        private set

    @Volatile
    var signedIn = false
        private set

    val device: String get() = bag.string(DEVICE)
    val account: String get() = bag.string(ACCOUNT)
    val pending: HereKeyPending get() = HereKeyPending.decode(bag.string(PENDING))
    val claimed: Boolean get() = bag.bool(CLAIMED, true)

    /**
     * At launch. Once per install, behind [MIGRATED]: the claim flag starts as
     * "signed in, or some account already owns data here", and a key typed
     * before this build while signed in moves into the account. Never again —
     * on a later launch a second account would take an earlier rider's key.
     */
    fun launch(signedIn: Boolean, anyAccountBucket: () -> Boolean) {
        if (!bag.bool(MIGRATED, false)) {
            bag.put(CLAIMED, signedIn || anyAccountBucket())
            if (signedIn) moveDeviceKeyToAccount()
            bag.put(MIGRATED, true)
        }
        session(signedIn)
    }

    fun session(signedIn: Boolean) {
        this.signedIn = signedIn
        refresh()
    }

    /** Stores [key] trimmed in whichever slot is live; blank removes it. */
    fun edit(key: String) {
        val trimmed = key.trim()
        if (signedIn) {
            write(ACCOUNT, trimmed)
            bag.put(PENDING, HereKeyPending.EDITED.name)
            edits++
        } else {
            write(DEVICE, trimmed)
        }
        refresh()
    }

    /** On an establishing sign-in: the first account on this phone takes the
     *  signed-out key; every later one leaves it where it is. */
    fun claimDeviceKey() {
        if (claimed) return
        moveDeviceKeyToAccount()
        bag.put(CLAIMED, true)
        refresh()
    }

    /** On sign-out: the account's key leaves the phone, the device's stays. */
    fun clearAccount() {
        bag.remove(ACCOUNT)
        bag.remove(PENDING)
        edits++
        refresh()
    }

    /** The server's answer into the account slot, unless an edit landed since
     *  [editsAtStart]. Returns whether it was written. */
    fun take(key: String, editsAtStart: Int): Boolean {
        if (edits != editsAtStart) return false
        write(ACCOUNT, key)
        bag.remove(PENDING)
        refresh()
        return true
    }

    /** What was pending reached the server. */
    fun settle() {
        bag.remove(PENDING)
    }

    private fun moveDeviceKeyToAccount() {
        val key = device
        if (key.isEmpty()) return
        bag.put(ACCOUNT, key)
        bag.put(PENDING, HereKeyPending.ADOPTED.name)
        bag.remove(DEVICE)
    }

    private fun write(slot: String, key: String) {
        if (key.isEmpty()) bag.remove(slot) else bag.put(slot, key)
    }

    private fun refresh() {
        live.value = if (signedIn) account else device
    }

    companion object {
        /** The pre-#905 key, now the signed-out one. Same name, so an upgrade reads it. */
        const val DEVICE = "here_traffic_key"
        const val ACCOUNT = "here_traffic_key_account"
        const val PENDING = "here_key_pending"
        const val CLAIMED = "here_key_device_claimed"
        const val MIGRATED = "here_key_slots_migrated"
    }
}
