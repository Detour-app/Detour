package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the join confirm screen is, for the link [ConvoyJoinState.link] holds. */
sealed interface ConvoyJoinStep {
    /** Asking the server which convoy the link opens. */
    data object Checking : ConvoyJoinStep

    /** The server answered; the rider confirms or backs out. */
    data class Ready(val preview: ConvoyInvitePreview) : ConvoyJoinStep

    /** The rider confirmed and the join is in flight. */
    data class Joining(val preview: ConvoyInvitePreview) : ConvoyJoinStep

    /** The server refused the link for a reason §4.1 names; the screen says which. */
    data class Refused(val refusal: ConvoyJoinRefusal) : ConvoyJoinStep

    /** Anything else went wrong (no network, a 500). Worth a retry, unlike [Refused]. */
    data class Failed(val message: String) : ConvoyJoinStep

    /**
     * The link was minted on [serverUrl], and this install now talks to another server (the rider
     * switched while signed out, after pasting it). Nothing was sent: the token means nothing to
     * any other server, and is the first server's credential.
     */
    data class OtherServer(val serverUrl: String) : ConvoyJoinStep
}

/**
 * [link] is the link the rider opened, held until they join or back out. [step] is null while
 * nothing has been asked of the server yet — which is also how a signed-out rider's link waits
 * for them to sign in.
 */
data class ConvoyJoinState(val link: ConvoyInviteLink? = null, val step: ConvoyJoinStep? = null)

/**
 * The joiner's side of convoy invite links (#763): preview the link, confirm, join. Wire calls
 * are [ConvoyInvites]; this holds the one link being looked at, so the confirm screen survives a
 * recomposition and a sign-in without asking the server twice — a preview spends from the same
 * per-token cap a join does (CIRCLES_AND_CONVOYS.md §4.1). A call cut off mid-flight (rotation
 * recreating the screen, the rider leaving it) steps back to where it started, so the screen that
 * comes back asks again rather than waiting on an answer nobody is collecting.
 *
 * Memory only, never written down: a token is a credential to the convoy, and the same reason
 * `Destination` carries no token keeps it off disk here. A process death mid sign-in loses it,
 * and the rider opens the link again.
 *
 * Same contract as [ConvoysStore]: actions are `suspend` with the caller's scope, a failure
 * lands in [ConvoyJoinState.step] instead of throwing, and an answer that comes back under
 * another session or for another link is dropped.
 */
object ConvoyJoinStore {

    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(ConvoyJoinState())
    val state: StateFlow<ConvoyJoinState> = _state.asStateFlow()

    /** Called from [Auth.clear] when a session ended: a link opened for one rider is not the next
     *  one's to join. Not when there was no session, so a link held signed out survives the realm
     *  reset the first sign-in can run (`RoutingServer.rememberDiscoveredIssuer`). */
    internal fun reset() {
        _state.value = ConvoyJoinState()
    }

    /** Holds [link] for the confirm screen, replacing any link held before. Asks nothing of the
     *  server: [check] does, once a session exists. */
    fun open(link: ConvoyInviteLink) {
        _state.value = ConvoyJoinState(link = link)
    }

    /** The rider backed out, or the screen is done with the link. */
    fun dismiss() {
        reset()
    }

    /**
     * Previews the held link. Does nothing once a step is set, so a screen can call this from an
     * effect that re-runs without spending the link's attempts again once an answer is in; [retry]
     * is the way to ask a second time. Cancelled before the answer, it steps back to no step, and
     * the next call asks again.
     *
     * The server is checked here, not only at paste: a link held signed out outlives a switch of
     * server, and its token must not go to the new one. [ConvoyJoinStep.OtherServer] is checked
     * again on the next call, so switching back previews the link.
     */
    @Throws(Exception::class)
    suspend fun check() {
        val start = _state.value
        val link = start.link ?: return
        if (start.step != null && start.step !is ConvoyJoinStep.OtherServer) return
        if (!onLinkServer(link)) return
        val epoch = Auth.sessionEpoch.value
        _state.update { it.copy(step = ConvoyJoinStep.Checking) }
        val step = attempt(link) { ConvoyJoinStep.Ready(ConvoyInvites.preview(link.token)) }
        commit(epoch, link, ConvoyJoinState(link, step))
    }

    /** Asks again after [ConvoyJoinStep.Failed]. A [ConvoyJoinStep.Refused] link stays refused. */
    @Throws(Exception::class)
    suspend fun retry() {
        _state.update { if (it.step is ConvoyJoinStep.Failed) it.copy(step = null) else it }
        check()
    }

    /**
     * Joins the previewed convoy, then reloads [ConvoysStore] so it is in the list the rider goes
     * live from, and lets go of the link. A refusal here (the host revoked between preview and
     * confirm) lands as [ConvoyJoinStep.Refused] like one at preview. Cancelled before the
     * server answers, it steps back to [ConvoyJoinStep.Ready] so the rider can confirm again; a
     * second join of a convoy the rider is already in answers that convoy (#775).
     */
    @Throws(Exception::class)
    suspend fun join() {
        val start = _state.value
        val link = start.link ?: return
        val preview = (start.step as? ConvoyJoinStep.Ready)?.preview ?: return
        if (!onLinkServer(link)) return
        val epoch = Auth.sessionEpoch.value
        _state.update { it.copy(step = ConvoyJoinStep.Joining(preview)) }
        val step = attempt(link) {
            ConvoyInvites.join(link.token)
            null
        }
        // Committed before the reload, so a cancel during the reload can't step a finished join
        // back to Ready.
        commit(epoch, link, if (step == null) ConvoyJoinState() else ConvoyJoinState(link, step))
        if (step == null) ConvoysStore.reload()
    }

    /** False, with [link] stepped to [ConvoyJoinStep.OtherServer], when this install no longer
     *  talks to the server [link] was minted on. */
    private fun onLinkServer(link: ConvoyInviteLink): Boolean {
        val apiBase = SyncClient.url()
        _state.update { it.checkedAgainst(apiBase) }
        return link.isOn(apiBase)
    }

    private fun commit(epoch: Int, link: ConvoyInviteLink, next: ConvoyJoinState) {
        _state.update { it.committed(epoch == Auth.sessionEpoch.value, link, next) }
    }

    /** [block]'s step, or the step its failure earns. A cancel steps [link]'s state back first. */
    private suspend fun attempt(link: ConvoyInviteLink, block: suspend () -> ConvoyJoinStep?): ConvoyJoinStep? = try {
        block()
    } catch (e: CancellationException) {
        _state.update { it.cancelled(link) }
        throw e
    } catch (e: Exception) {
        joinStepFor(e)
    }
}

/** What a failed preview or join shows: the refusal §4.1 names, or the plain error. */
internal fun joinStepFor(e: Exception): ConvoyJoinStep =
    if (e is ConvoyJoinException) ConvoyJoinStep.Refused(e.refusal)
    else ConvoyJoinStep.Failed(e.message?.ifBlank { null } ?: ConvoyJoinStore.FALLBACK_ERROR)

/**
 * The held link stepped to [ConvoyJoinStep.OtherServer] when it wasn't minted on [apiBase]
 * ([SyncClient.url]); otherwise unchanged. Nothing held, or a link on [apiBase], is left alone, so
 * an earlier [ConvoyJoinStep.OtherServer] stays until [ConvoyJoinStore.check] asks the server.
 */
internal fun ConvoyJoinState.checkedAgainst(apiBase: String?): ConvoyJoinState =
    if (link == null || link.isOn(apiBase)) this else copy(step = ConvoyJoinStep.OtherServer(link.serverUrl))

/**
 * What [ConvoyJoinStore] commits when an answer for [link] lands: [next], unless the session
 * changed or the rider has since opened another link (or backed out), in which case the answer
 * belongs to nobody on screen and is dropped.
 */
internal fun ConvoyJoinState.committed(
    sameSession: Boolean,
    link: ConvoyInviteLink,
    next: ConvoyJoinState,
): ConvoyJoinState = if (sameSession && this.link == link) next else this

/**
 * What [ConvoyJoinStore] steps back to when a preview or join for [link] is cancelled before it
 * answers: [ConvoyJoinStep.Checking] to no step (so the next check asks), [ConvoyJoinStep.Joining]
 * to [ConvoyJoinStep.Ready] (so the rider can confirm again). Any other state, or another link,
 * is left alone.
 */
internal fun ConvoyJoinState.cancelled(link: ConvoyInviteLink): ConvoyJoinState = when {
    this.link != link -> this
    step is ConvoyJoinStep.Checking -> copy(step = null)
    step is ConvoyJoinStep.Joining -> copy(step = ConvoyJoinStep.Ready(step.preview))
    else -> this
}
