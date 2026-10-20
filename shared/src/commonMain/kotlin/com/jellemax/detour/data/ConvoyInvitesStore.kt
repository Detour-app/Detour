package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConvoyInvitesState(
    /** The convoy whose invite sheet is open, or null before the first [ConvoyInvitesStore.open]. */
    val convoyId: String? = null,
    /** [convoyId]'s guests and link expiry as the server last answered. Null until that answer
     *  lands, so "no guests yet" and "not loaded" don't render the same. */
    val guests: ConvoyGuests? = null,
    /**
     * The links this device minted, by convoy id, token included. The server keeps only a hash
     * (CIRCLES_AND_CONVOYS.md §4.1), so this is the one place a host can share the same link
     * again after closing the sheet. Kept per convoy, so opening another convoy's sheet doesn't
     * cost the first one its link.
     */
    val minted: Map<String, ConvoyInvite> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The host's side of convoy invite links: mint, share, revoke, and the guests who came in by
 * link. Wire calls are [ConvoyInvites]; this keeps the minted token and the guest list for the
 * sheet that shows them.
 *
 * Same contract as [ConvoysStore]: actions are `suspend` with the caller's scope, an ordinary
 * failure lands in [ConvoyInvitesState.error] instead of throwing, every mutation re-reads the
 * guest list rather than patching it, and a commit started under another session is dropped.
 */
object ConvoyInvitesStore {

    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(ConvoyInvitesState())
    val state: StateFlow<ConvoyInvitesState> = _state.asStateFlow()

    /** Drops every minted token and guest list. Called from [Auth.clear]: a token is the
     *  previous rider's to share, not the next one's. */
    internal fun reset() {
        _state.update { ConvoyInvitesState() }
    }

    /** Points the sheet at [convoyId] and loads its guests. */
    @Throws(Exception::class)
    suspend fun open(convoyId: String) {
        _state.update { it.opened(convoyId) }
        act(convoyId) { noChange }
    }

    /** Mints a link for the open convoy. The server revokes any earlier one, so a link shared
     *  before stops working. */
    @Throws(Exception::class)
    suspend fun createLink() {
        val convoyId = _state.value.convoyId ?: return
        act(convoyId) {
            val invite = ConvoyInvites.createLink(convoyId)
            return@act { s -> s.copy(minted = s.minted + (convoyId to invite)) }
        }
    }

    /** Stops new joins on the open convoy's link; guests already in stay. */
    @Throws(Exception::class)
    suspend fun revokeLink() {
        val convoyId = _state.value.convoyId ?: return
        act(convoyId) {
            ConvoyInvites.revokeLink(convoyId)
            return@act { s -> s.copy(minted = s.minted - convoyId) }
        }
    }

    /** Removes a guest from the open convoy; the server keeps them out of every later link. */
    @Throws(Exception::class)
    suspend fun removeGuest(riderId: RiderId) {
        val convoyId = _state.value.convoyId ?: return
        act(convoyId) {
            ConvoyInvites.removeGuest(convoyId, riderId)
            noChange
        }
    }

    /**
     * Runs [block], then re-reads [convoyId]'s guests. [block] answers with its own change to
     * the state (a minted or revoked token), which is committed even when the re-read fails or
     * the sheet has moved on to another convoy: a link the server minted is live, and losing its
     * token would leave the host nothing to share. The guest list and error commit only while
     * [convoyId] is still the one open, and nothing commits once the session has changed — a
     * token minted before a sign-out is not the next rider's.
     */
    private suspend fun act(convoyId: String, block: suspend () -> StateChange) {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.copy(busy = true, error = null) }
        var change = noChange
        val answer: StateChange = try {
            change = block()
            val guests = ConvoyInvites.guests(convoyId)
            val loaded: StateChange = { s -> s.copy(guests = guests, busy = false, error = null) }
            loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message?.ifBlank { null } ?: FALLBACK_ERROR
            val failed: StateChange = { s -> s.copy(busy = false, error = message) }
            failed
        }
        commit(epoch, convoyId, change, answer)
    }

    private fun commit(epoch: Int, convoyId: String, change: StateChange, answer: StateChange) {
        _state.update { it.committed(epoch == Auth.sessionEpoch.value, convoyId, change, answer) }
    }

    private val noChange: StateChange = { it }
}

internal typealias StateChange = (ConvoyInvitesState) -> ConvoyInvitesState

/** What [ConvoyInvitesStore.act] commits; see its doc for the three cases. */
internal fun ConvoyInvitesState.committed(
    sameSession: Boolean,
    convoyId: String,
    change: StateChange,
    answer: StateChange,
): ConvoyInvitesState = when {
    !sameSession -> this
    this.convoyId != convoyId -> change(this)
    else -> answer(change(this))
}

/** [convoyId] open; its guests dropped when it isn't the convoy already open, so the sheet never
 *  shows one convoy's guests under another's name. Minted tokens stay. */
internal fun ConvoyInvitesState.opened(convoyId: String): ConvoyInvitesState =
    if (this.convoyId == convoyId) this else copy(convoyId = convoyId, guests = null, error = null)

/**
 * The open convoy's link this device can share, or null when there is none to share: never
 * minted here, past its expiry at [nowMs], or no longer the server's usable link (revoked, or
 * replaced by one minted on another device — the host's other phone, say). Before the guest list
 * has loaded, the minted link is trusted on its expiry alone; the load corrects it.
 */
fun ConvoyInvitesState.shareableLink(nowMs: Long): ConvoyInvite? {
    val invite = minted[convoyId ?: return null] ?: return null
    if (invite.expiresAtMs <= nowMs) return null
    val loaded = guests ?: return invite
    return invite.takeIf { loaded.inviteExpiresAtMs == it.expiresAtMs }
}
