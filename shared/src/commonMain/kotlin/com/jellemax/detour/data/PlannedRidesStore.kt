package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlannedRidesState(
    /** Rides this rider owns or is invited to, soonest start first, past rides included.
     *  Null until the first load lands: "nothing planned" and "the server has not answered"
     *  must not render the same (§4.1). */
    val rides: List<PlannedRide>? = null,
    val busy: Boolean = false,
    /** Why the last [PlannedRidesStore.reload] failed. Cleared when the next load starts. */
    val loadError: String? = null,
    /** Why the last create/update/cancel/invite/answer failed. One-shot: a screen shows it
     *  once, then calls [PlannedRidesStore.clearActionError]. */
    val actionError: String? = null,
    /** When the server last answered; null until a load has succeeded. See [ConvoysState.loadedAtMs]. */
    val loadedAtMs: Long? = null,
)

/**
 * "My planned rides" and the actions on them, for every surface: planning, editing,
 * cancelling, inviting and answering an RSVP. Whether the caller may start a ride is
 * [PlannedRide.canStart].
 *
 * Same shape and contracts as [ConvoysStore]: actions are `suspend` and the caller supplies the
 * coroutine; an ordinary failure never throws, it lands in [PlannedRidesState.actionError] (an
 * action) or [PlannedRidesState.loadError] (a load); every mutation re-reads the list from the
 * server rather than patching the local copy (§4.1).
 */
object PlannedRidesStore {

    private val _state = MutableStateFlow(PlannedRidesState())
    val state: StateFlow<PlannedRidesState> = _state.asStateFlow()

    /** Drops everything back to the defaults. Called from [Auth.clear]. */
    internal fun reset() {
        _state.update { it.cleared() }
    }

    /** The entry point a screen should use on open; see [ConvoysStore.reloadIfStale]. */
    @Throws(Exception::class)
    suspend fun reloadIfStale() {
        if (!isStale(_state.value.loadedAtMs, nowMs())) return
        reload()
    }

    @Throws(Exception::class)
    suspend fun reload() {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        // Built from the await's result and applied to the live `it` in the final update, as
        // ConvoysStore.fetchAndCommit does — not to a snapshot taken before the suspend.
        val apply: (PlannedRidesState) -> PlannedRidesState = try {
            val rides = PlannedRides.list()
            val now = nowMs()
            val transform: (PlannedRidesState) -> PlannedRidesState = { s -> s.loaded(rides, now) }
            transform
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transform: (PlannedRidesState) -> PlannedRidesState = { s -> s.failed(e) }
            transform
        }
        _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, apply(it)) }
    }

    /** Drops [PlannedRidesState.actionError] once a screen has shown it. */
    fun clearActionError() {
        _state.update { it.actionCleared() }
    }

    /** The new ride's id, or null with the failure left in [state]'s `actionError`. */
    @Throws(Exception::class)
    suspend fun create(draft: PlannedRideDraft): String? =
        act { PlannedRides.create(draft).id }

    /** True on success. Owner only; an invitee's attempt fails with the server's 403. */
    @Throws(Exception::class)
    suspend fun update(rideId: String, draft: PlannedRideDraft): Boolean =
        act { PlannedRides.update(rideId, draft) } != null

    /** True on success. Owner only. */
    @Throws(Exception::class)
    suspend fun cancel(rideId: String): Boolean =
        act { PlannedRides.delete(rideId) } != null

    /** True on success. Owner only; one rider who may not be invited refuses the whole call. */
    @Throws(Exception::class)
    suspend fun invite(rideId: String, riderIds: List<RiderId>, circleIds: List<String>): Boolean =
        act { PlannedRides.invite(rideId, riderIds, circleIds) } != null

    /**
     * The answer the server recorded, or null on failure. [RsvpAnswer.PENDING] is not an answer
     * ([RsvpAnswer.isAnswer]) and returns null without a request — there is nothing the server
     * would do with it but refuse.
     */
    @Throws(Exception::class)
    suspend fun answer(rideId: String, answer: RsvpAnswer): RsvpAnswer? {
        if (!answer.isAnswer) return null
        return act { PlannedRides.answer(rideId, answer) }
    }

    private suspend fun <T> act(block: suspend () -> T): T? {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.actionStarting() }
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Epoch-guarded like [reload]: a failure landing after a sign-out must not put its
            // banner on the next rider's store. See FriendsStore.act.
            _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, it.actionFailed(e)) }
            return null
        }
        reload()
        return result
    }
}

/**
 * Whether [me] may send [answer] to this ride: an invitee (not the owner), with one of the
 * three real answers. Any answer may follow any other, pending included — a rider can change
 * their mind until the ride is cancelled; only going back to pending is not a move.
 */
fun PlannedRide.acceptsAnswer(me: RiderId, answer: RsvpAnswer): Boolean = canAnswer(me) && answer.isAnswer

/**
 * Both kinds of error, the action's first, for a screen that still shows them in one inline
 * line as it did when they shared a field. Goes when the screens move each kind to its own
 * place (#212's migration issues).
 */
val PlannedRidesState.inlineError: String? get() = actionError ?: loadError

/** A load supersedes an action error nobody cleared, as it did when both shared one field. */
internal fun PlannedRidesState.starting() = copy(busy = true, loadError = null, actionError = null)

internal fun PlannedRidesState.actionStarting() = copy(busy = true, actionError = null)

internal fun PlannedRidesState.loaded(rides: List<PlannedRide>, nowMs: Long) =
    copy(rides = rides, busy = false, loadError = null, loadedAtMs = nowMs)

/** Keeps [PlannedRidesState.rides] as it was: a failed refresh is not an empty list. */
internal fun PlannedRidesState.failed(e: Exception) = copy(busy = false, loadError = failureSentence(e))

internal fun PlannedRidesState.actionFailed(e: Exception) = copy(busy = false, actionError = failureSentence(e))

/** [result] if [epoch] still names the current session, this state untouched otherwise. Same
 *  guard as [ConvoysState.commitIfCurrent]. */
internal fun PlannedRidesState.commitIfCurrent(epoch: Int, currentEpoch: Int, result: PlannedRidesState) =
    if (epoch == currentEpoch) result else this

internal fun PlannedRidesState.cleared() = PlannedRidesState()

/** [PlannedRidesState.actionError] dropped, once a screen has shown it; nothing else changes. */
internal fun PlannedRidesState.actionCleared() = copy(actionError = null)
