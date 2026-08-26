package com.jellemax.detour.data

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

    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    /** Both lists in one pass, so a screen never shows friends without their
     *  numbers or the other way round. */
    @Throws(Exception::class)
    suspend fun reload() {
        _state.value = _state.value.starting()
        _state.value = try {
            _state.value.loaded(Friends.lists(), Friends.stats())
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
        } catch (e: Exception) {
            // A missing own row is worth strictly less than the friend list it
            // sits above, so this failure is not allowed to put an error over
            // the whole screen.
            return
        }
        _state.value = _state.value.copy(own = own)
    }

    /** Returns the resulting status — "pending", or "accepted" when they had
     *  already asked us and this answered theirs. */
    @Throws(Exception::class)
    suspend fun request(username: String): String = act { Friends.request(username) }

    @Throws(Exception::class)
    suspend fun respond(username: String, accept: Boolean) {
        act { Friends.respond(username, accept) }
    }

    @Throws(Exception::class)
    suspend fun remove(username: String) {
        act { Friends.remove(username) }
    }

    /**
     * Runs a mutation, then reloads. Rethrows so a caller that wants to react
     * to the failure itself still can, while the banner is set either way.
     */
    private suspend fun <T> act(block: suspend () -> T): T {
        _state.value = _state.value.starting()
        val result = try {
            block()
        } catch (e: Exception) {
            _state.value = _state.value.failed(e)
            throw e
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
