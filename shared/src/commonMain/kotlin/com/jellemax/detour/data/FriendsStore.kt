package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** `internal`, not `private`: [StoresTest] drives it directly to pin
     *  [reset], which — being an unconditional overwrite rather than a
     *  transition on a state the caller already has — has nothing else to
     *  assert against. */
    internal val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    /** Drops everything back to [FriendsState]'s defaults — including
     *  [FriendsState.own], which [loaded] otherwise goes out of its way to
     *  preserve. Called from [Auth.clear] rather than by a screen; see that
     *  function's doc for why. */
    internal fun reset() {
        _state.value = FriendsState()
    }

    /** Both lists in one pass, so a screen never shows friends without their
     *  numbers or the other way round. */
    @Throws(Exception::class)
    suspend fun reload() {
        _state.value = _state.value.starting()
        _state.value = try {
            _state.value.loaded(Friends.lists(), Friends.stats())
        } catch (e: CancellationException) {
            // A cancellation is the caller's own doing (a `.task`/
            // `LaunchedEffect` torn down by, say, a sign-out mid-request),
            // not a load failure — and unlike a composable-local
            // `mutableStateOf`, `state` here is a singleton other observers
            // may be watching. Swallowing this would leave a phantom error
            // banner sitting in shared state with nothing left to clear it.
            throw e
        } catch (e: Exception) {
            _state.value.failed(e)
        }
    }

    /**
     * Recomputes the rider's own row. Separate from [reload] on purpose:
     * `Coverage.compute()` reads every trace on disk, and a screen that
     * reloads after every mutation must not pay that each time.
     */
    @Throws(Exception::class)
    suspend fun refreshOwn(username: String) {
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
        _state.value = _state.value.copy(own = own)
    }

    /** True on success; false leaves the failure in [state]'s `error`. */
    // No `request` action here on purpose. Both platforms' add-friend dialogs
    // call `Friends.request` directly, so that a refused handle reports inside
    // the dialog the rider is looking at rather than also lighting the banner
    // over the list behind it. Routing it through this store would set both.
    // The cost is that a sent request does not appear under Outgoing until the
    // next reload, which is how both screens already behaved.
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
        _state.value = _state.value.starting()
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.failed(e)
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
