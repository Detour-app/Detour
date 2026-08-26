package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    @Throws(Exception::class)
    suspend fun reload() {
        _state.value = _state.value.starting()
        _state.value = try {
            _state.value.loaded(Groups.list(KIND))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value.failed(e)
        }
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

    /** The discriminator [Groups] routes on. "convoy" here, "circle" in
     *  [CirclesStore]; one entity on the server, two kinds. */
    private const val KIND = "convoy"
}

internal fun ConvoysState.starting() = copy(busy = true, error = null)

internal fun ConvoysState.loaded(convoys: List<Group>) =
    copy(convoys = convoys, busy = false, error = null)

internal fun ConvoysState.failed(e: Exception) =
    copy(busy = false, error = e.message?.ifBlank { null } ?: ConvoysStore.FALLBACK_ERROR)
