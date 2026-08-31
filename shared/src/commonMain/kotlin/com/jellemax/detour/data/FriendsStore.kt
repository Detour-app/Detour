package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the Friends screen shows about friends, on both platforms.
 *
 * `lists` is null until the first load finishes, which is not the same as
 * "no friends" — a screen has to tell those apart to avoid claiming someone
 * has nobody when the server simply has not answered yet.
 */
data class FriendsState(
    val lists: FriendLists? = null,
    val leaderboard: List<FriendStats> = emptyList(),
    /** This device's own totals, so "me" appears in my own leaderboard. The
     *  server sends a rider's numbers to their friends and never back to
     *  them, so this is computed locally or not at all. */
    val own: FriendStats? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The friend list and the shared leaderboard, with the load/busy/error
 * bookkeeping that used to be written once per platform.
 *
 * No coroutine of its own: `commonMain` has no `Dispatchers` (see
 * CONTRIBUTING.md and Platform.kt's ceiling), so every action here is
 * `suspend` and the caller supplies the scope — `scope.launch { }` in Compose,
 * a `Task { }` in SwiftUI. The store owns the state and nothing else, which is
 * the only division of labour this module's constraints allow.
 *
 * Actions all follow one shape, and it is the shape both platforms had already
 * arrived at independently: run the mutation, then re-read the server's view
 * rather than patching the local copy, so a request that crossed with somebody
 * else's cannot leave the two disagreeing.
 */
object FriendsStore {

    /** What an exception with no message becomes. Named rather than inlined
     *  because the test for it asserts this exact string. */
    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    /** Drops everything back to [FriendsState]'s defaults — including
     *  [FriendsState.own], which [loaded] otherwise goes out of its way to
     *  preserve. Called from [Auth.clear] rather than by a screen; see that
     *  function's doc for why. */
    internal fun reset() {
        _state.update { it.cleared() }
    }

    /** Both lists in one pass, so a screen never shows friends without their
     *  numbers or the other way round. */
    @Throws(Exception::class)
    suspend fun reload() {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        // The transform is built here, once the awaits below are done, and
        // only *applied* inside the final `update { }` — not computed against
        // `_state.value` up front. Kotlin evaluates a call's receiver before
        // its arguments, so `_state.value.loaded(Friends.lists(), Friends.stats())`
        // would read `_state.value` as a snapshot taken *before* either
        // suspending call, and the plain assignment that used to follow wrote
        // that stale snapshot back wholesale — silently discarding anything
        // that changed `state` during the request, including a sign-out's
        // `reset()`. Deferring `.loaded()`/`.failed()` to run on the `it`
        // inside `update { }` below applies them to the live state instead.
        val apply: (FriendsState) -> FriendsState = try {
            val lists = Friends.lists()
            val leaderboard = Friends.stats()
            val transform: (FriendsState) -> FriendsState = { s -> s.loaded(lists, leaderboard) }
            transform
        } catch (e: CancellationException) {
            // A cancellation is the caller's own doing — a genuine Kotlin
            // `Job` cancelled, which is what happens when Android's
            // `LaunchedEffect`/`scope.launch` tears down mid-request. It is
            // NOT what iOS's `.task(id:)` teardown does to this call: an
            // exported `suspend fun` compiles to an ObjC completion-handler
            // bridge with no cancellation path, so cancelling the Swift
            // `Task` awaiting it does not cancel the coroutine underneath —
            // this catch never fires there. A sign-out mid-reload on iOS
            // instead runs to completion and is caught by the `commitIfCurrent`
            // check below, not by this branch. Either way this is not a load
            // failure, and unlike a composable-local `mutableStateOf`, `state`
            // here is a singleton other observers may be watching —
            // swallowing this would leave a phantom error banner sitting in
            // shared state with nothing left to clear it.
            throw e
        } catch (e: Exception) {
            val transform: (FriendsState) -> FriendsState = { s -> s.failed(e) }
            transform
        }
        _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, apply(it)) }
    }

    /**
     * Recomputes the rider's own row. Separate from [reload] on purpose:
     * `Coverage.compute()` reads every trace on disk, and a screen that
     * reloads after every mutation must not pay that each time.
     *
     * **Blocks on disk and CPU — must not be called from a main-thread
     * coroutine.** `Coverage.compute()` loads every trace and walks every
     * point per municipality, and `BadgeStore.stats`/`refresh` load every trip
     * and write `badges.json`; none of the three is `suspend`, because
     * `commonMain` has no `Dispatchers` to hop off of. This store cannot
     * switch dispatchers itself, so the caller must: `withContext(Dispatchers.IO)`
     * on Android, `Task.detached` (or equivalent) on iOS. See
     * `app/.../ui/BadgesScreen.kt` for the Android-side reasoning this mirrors.
     */
    @Throws(Exception::class)
    suspend fun refreshOwn(username: String) {
        // Belt and braces: nothing above this function checks `Auth.signedIn`
        // on its behalf (unlike `reload`/`act`, whose callers only ever run
        // while signed in), so this makes the contract honest on its own
        // rather than trusting every future call site to remember it. See
        // [Auth.sessionEpoch]'s doc for the reachable case this closes one
        // more layer of: a call already in flight when the epoch guard below
        // captures it is unaffected by this line, same as ever — it is only
        // a call that has not started yet that this stops from beginning
        // ungated.
        if (!Auth.signedIn) return
        // A blank handle is not a rider. Reachable in one narrow window on iOS,
        // where `username` is a mirrored @Published copy fed by a second
        // watcher that can lag the token's by a tick after a sign-in — the
        // mirror now clears rather than freezing, so what arrives here is "" and
        // not the departed rider's handle. Committing it would put a nameless
        // row in the leaderboard until the next reload.
        if (username.isBlank()) return
        val epoch = Auth.sessionEpoch.value
        val own = try {
            val coverage = Coverage.compute()
            val riderStats = BadgeStore.stats(coverage)
            val badgeIds = BadgeStore.refresh(riderStats).states
                .filter { it.earned }.map { it.def.id }
            FriendStats(username, riderStats, badgeIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing own row is worth strictly less than the friend list it
            // sits above, so this failure is not allowed to put an error over
            // the whole screen.
            return
        }
        // Same guard as `reload`, and just as needed: there is no suspension
        // between `own` being computed and the write below, so a cancellation
        // cannot interpose either — but the caller of this function switches
        // dispatcher/thread to get off the main one (`withContext(Dispatchers.IO)`
        // on Android, `Task.detached` on iOS), and `Auth.clear()` can run
        // concurrently on that other thread while this is still computing.
        // Without this check, a sign-out mid-`refreshOwn` writes the departed
        // rider's own-stats row into the next rider's freshly reset state.
        _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, it.copy(own = own)) }
    }

    /** True on success; false leaves the failure in [state]'s `error`. */
    // No `request` action here on purpose. Both platforms' add-friend dialogs
    // call `Friends.request` directly, so that a refused handle reports inside
    // the dialog the rider is looking at rather than also lighting the banner
    // over the list behind it. Routing it through this store would set both.
    // The cost is that a sent request does not appear under Outgoing on its
    // own: Android's `AddFriendDialog` really does wait for the next reload,
    // but iOS's `sendRequest()` (FriendsScreen.swift) reloads right after a
    // successful send, so its pending row shows up immediately instead.
    @Throws(Exception::class)
    suspend fun respond(username: String, accept: Boolean): Boolean =
        act { Friends.respond(username, accept) } != null

    /**
     * Runs a mutation, then reloads. Never throws for an ordinary failure:
     * the per-platform helpers this replaces never rethrew to their callers
     * either, and every Android call site is a `scope.launch { }` with no
     * handler, where an escaping exception crashes the app. Instead this
     * reports through `state.error` — the whole reason the store exists —
     * and returns null so a caller that needs to react locally (e.g. not
     * clearing a text field on failure) still can, without racing a read of
     * `state` back after the `await`. A [CancellationException] is not an
     * ordinary failure and still propagates, per [reload]'s comment above.
     */
    private suspend fun <T> act(block: suspend () -> T): T? {
        // Same epoch capture as [reload]: a mutation that fails after the rider
        // signed out must not write its banner onto the next rider's freshly
        // reset store. Lower stakes than a leaked friend list — an error is not
        // data — but it is the same shape, and leaving one instance of it is how
        // it comes back.
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, it.failed(e)) }
            return null
        }
        reload()
        return result
    }
}

/** Busy, and without the previous attempt's error under the new spinner. */
internal fun FriendsState.starting() = copy(busy = true, error = null)

/** Note what is *not* touched: [FriendsState.own]. It is expensive to compute
 *  and unrelated to the server's answer, so a reload keeps it. */
internal fun FriendsState.loaded(lists: FriendLists, leaderboard: List<FriendStats>) =
    copy(lists = lists, leaderboard = leaderboard, busy = false, error = null)

/** Keeps every data field. An error is a banner over the last known good
 *  screen, never a reason to blank it. */
internal fun FriendsState.failed(e: Exception) =
    copy(busy = false, error = e.message?.ifBlank { null } ?: FriendsStore.FALLBACK_ERROR)

/**
 * [result] if [epoch] still names the session an in-flight action started
 * under — checked against [currentEpoch], read fresh at commit time — or
 * this state untouched otherwise. The guard that stops a reload's or
 * refreshOwn's response for a rider who has since signed out (or signed back
 * in, even as themselves) from landing after [Auth.clear] has already reset
 * this store.
 *
 * `internal` and pure so it can be asserted directly, mirroring how
 * [CirclesState.commitIfViewing] guards a stale detail response — the race
 * this closes needs two overlapping coroutines to reproduce, which this
 * module's test style (plain kotlin.test, no coroutine test dispatcher)
 * cannot stage.
 */
internal fun FriendsState.commitIfCurrent(epoch: Int, currentEpoch: Int, result: FriendsState): FriendsState =
    if (epoch == currentEpoch) result else this

/** Every field back to [FriendsState]'s defaults — [FriendsState.own]
 *  included, which [loaded] otherwise goes out of its way to preserve. A
 *  sign-out is a different rider, not a failed refresh, so nothing here
 *  survives the round trip. `internal` and pure, called from [FriendsStore.reset]
 *  and asserted directly, rather than driving the singleton through
 *  `_state` the way [reset]'s own test used to. */
internal fun FriendsState.cleared() = FriendsState()
