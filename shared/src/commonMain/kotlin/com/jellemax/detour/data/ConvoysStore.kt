package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConvoysState(
    val convoys: List<Group> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Convoy membership: creating, listing, inviting, responding, leaving.
 *
 * Membership only. A convoy's live location and push-to-talk ride a WebSocket
 * that is still platform code (`app/net/ConvoyLiveClient.kt` and its Swift
 * twin), so whether this device is *connected* is not in this state — the
 * screens read that from the client itself, which is what keeps the button
 * honest about whether the service is actually running.
 *
 * Same no-scope rule as [FriendsStore]: actions are `suspend`, the caller
 * supplies the coroutine. Same never-throws-for-an-ordinary-failure contract
 * too — see `FriendsStore`'s private `act` for the full reasoning, since its
 * own `act` below is not part of this store's public surface either.
 */
object ConvoysStore {

    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(ConvoysState())
    val state: StateFlow<ConvoysState> = _state.asStateFlow()

    /** Drops everything back to [ConvoysState]'s defaults. Called from
     *  [Auth.clear] rather than by a screen; see that function's doc for why. */
    internal fun reset() {
        _state.update { it.cleared() }
    }

    @Throws(Exception::class)
    suspend fun reload() {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        // See FriendsStore.reload's comment: the transform is built from the
        // awaits' results and only applied to the live `it` inside the final
        // `update { }` below, not to a `_state.value` snapshot taken before
        // either suspending call.
        val apply: (ConvoysState) -> ConvoysState = try {
            val convoys = Groups.list(KIND)
            val transform: (ConvoysState) -> ConvoysState = { s -> s.loaded(convoys) }
            transform
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transform: (ConvoysState) -> ConvoysState = { s -> s.failed(e) }
            transform
        }
        _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, apply(it)) }
    }

    /** True on success; false leaves the failure in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun create(name: String): Boolean =
        act { Groups.create(KIND, name) } != null

    /** Returns the resulting status, e.g. "invited" — or null if the invite
     *  failed, with the failure left in [state]'s `error`. Only accepted
     *  friends can be invited; the server enforces that and this surfaces
     *  its refusal. */
    @Throws(Exception::class)
    suspend fun invite(groupId: String, username: String): String? =
        act { Groups.invite(groupId, username) }

    /** True on success; false leaves the failure in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun respond(groupId: String, accept: Boolean): Boolean =
        act { Groups.respond(groupId, accept) } != null

    /** True on success; false leaves the failure in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun leave(groupId: String): Boolean =
        act { Groups.leave(groupId) } != null

    private suspend fun <T> act(block: suspend () -> T): T? {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Epoch-guarded for the same reason the commit in [reload] is: a
            // mutation failing after a sign-out must not put its banner on the
            // next rider's store. See FriendsStore.act.
            _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, it.failed(e)) }
            return null
        }
        reload()
        return result
    }

    /** The discriminator [Groups] routes on. "convoy" here, "circle" in
     *  [CirclesStore]; one entity on the server, two kinds. */
    private const val KIND = "convoy"
}

internal fun ConvoysState.starting() = copy(busy = true, error = null)

internal fun ConvoysState.loaded(convoys: List<Group>) =
    copy(convoys = convoys, busy = false, error = null)

internal fun ConvoysState.failed(e: Exception) =
    copy(busy = false, error = e.message?.ifBlank { null } ?: ConvoysStore.FALLBACK_ERROR)

/** [result] if [epoch] still names the session an in-flight [ConvoysStore.reload]
 *  started under, checked against [currentEpoch] read fresh at commit time —
 *  or this state untouched otherwise. Same guard as [FriendsStore]'s
 *  matching function; see its doc for the full reasoning. */
internal fun ConvoysState.commitIfCurrent(epoch: Int, currentEpoch: Int, result: ConvoysState): ConvoysState =
    if (epoch == currentEpoch) result else this

/** Every field back to [ConvoysState]'s defaults. `internal` and pure,
 *  called from [ConvoysStore.reset] and asserted directly — see
 *  [FriendsState.cleared]'s doc for why. */
internal fun ConvoysState.cleared() = ConvoysState()
