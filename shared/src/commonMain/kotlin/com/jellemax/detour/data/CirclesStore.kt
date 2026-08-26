package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the Circles screen and its detail pane show: the list plus the
 * one circle currently open in it.
 *
 * Two independent busy/error pairs, not one. [busy]/[error] cover list
 * operations — create, invite, respond, leave, toggle sharing.
 * [detailBusy]/[detailError] cover the detail pane — opening a circle,
 * refreshing it, sharing or unsharing a place. They used to be one pair,
 * which meant merely opening a circle disabled Invite/Leave/sharing while
 * its detail loaded, and a list mutation's failure could show twice (once
 * per error render site) because both sites read the same field.
 *
 * [places] and [events] belong to [selectedId], not to every circle at
 * once — they are cleared on a *change* of the selection, including to
 * null, but not on a reselect of the same circle, which is what a refresh
 * is (see [selecting]). Clearing on change stops a slow detail load from
 * surfacing under the wrong circle's heading; keeping on reselect stops a
 * refresh from flashing the empty state while it refetches. Selection
 * lives in this same state rather than a fourth store precisely so the two
 * cannot disagree about which circle is open.
 *
 * Not here, on purpose (spec, "Where the boundary falls"): `notifyEnabled`
 * is already shared through `Settings.notifyArrivals`; `showBatteryPrompt`
 * needs `PowerManager`; the notification-permission launcher is a runtime
 * permission; every dialog's own form fields are transient UI state. None
 * of the four are bookkeeping this store exists to unify.
 */
data class CirclesState(
    val circles: List<Group> = emptyList(),
    val selectedId: String? = null,
    val places: List<CirclePlace> = emptyList(),
    val events: List<PlaceEvent> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val detailBusy: Boolean = false,
    val detailError: String? = null,
)

/**
 * Circle membership plus the detail pane's shared places and arrival/departure
 * events — the largest of the duplicated state machines, fifteen
 * remember/effect sites in `CircleDetailSection` alone and the same logic
 * again in `CirclesModel`/`CircleDetailView` on iOS.
 *
 * Same no-scope rule as [FriendsStore] and [ConvoysStore]: `commonMain` has
 * no `Dispatchers`, so every action is `suspend` and the caller supplies the
 * coroutine. Same never-throws-for-an-ordinary-failure contract too — see
 * `FriendsStore`'s private `act` for the full reasoning; a
 * [CancellationException] is the one thing that still propagates, since it
 * is the caller's own doing (a screen key changing, a sign-out mid-load) and
 * must never become an error banner.
 */
object CirclesStore {

    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(CirclesState())
    val state: StateFlow<CirclesState> = _state.asStateFlow()

    /** Drops everything back to [CirclesState]'s defaults — the selected
     *  circle included, so a leaked [CirclesState.selectedId] cannot make the
     *  next rider's [CirclesStore.select] reload calls land on a circle that
     *  was never theirs. Called from [Auth.clear] rather than by a screen;
     *  see that function's doc for why. */
    internal fun reset() {
        _state.update { it.cleared() }
    }

    @Throws(Exception::class)
    suspend fun reload() {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.starting() }
        // See FriendsStore.reload's comment: the transform is built from the
        // await's result and only applied to the live `it` inside the final
        // `update { }` below, not to a `_state.value` snapshot taken before
        // the suspending call — which is also what lets a selection made
        // while this reload was in flight (a tapped arrival notification, or
        // a second tap in the list; see `CirclesState.loaded`'s
        // `selectedId` handling) survive into the committed result instead of
        // being silently reverted to whatever it was when this reload started.
        val apply: (CirclesState) -> CirclesState = try {
            val circles = Groups.list(KIND)
            val transform: (CirclesState) -> CirclesState = { s -> s.loaded(circles) }
            transform
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transform: (CirclesState) -> CirclesState = { s -> s.failed(e) }
            transform
        }
        _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, apply(it)) }
    }

    /** Opens [groupId]'s detail pane, or closes it for null. Clears the
     *  previous circle's [CirclesState.places]/[CirclesState.events] first
     *  if this is an actual change of circle (see [selecting]), then loads
     *  the new one's. */
    @Throws(Exception::class)
    suspend fun select(groupId: String?) {
        _state.update { it.selecting(groupId) }
        if (groupId != null) loadDetail(groupId)
    }

    /** Same selection change as [select], without the load. For a caller
     *  that is about to bring the detail pane into composition, whose own
     *  effect calls [select] on mount and does the one load this pair needs
     *  — Android's `CirclesScreen.kt` `CircleDetailSection` `LaunchedEffect`,
     *  and its `onOpen`/deep-link callers, which used to call [select]
     *  themselves and so fired that same load a second time. Two
     *  `GET places`/`GET events` per open, and the slower of the two
     *  responses could win over the newer one, since [commitIfViewing] only
     *  compares ids, not request order.
     *
     *  Not `suspend`: unlike [select], this does no I/O, so a caller does not
     *  need a coroutine just to flip the selection. Not `@Throws` either — it
     *  cannot fail. iOS does not need this: its own single loader
     *  (`.task(id: mapState.viewedCircleId)` in CirclesScreen.swift) already
     *  calls [select] exactly once per open. */
    fun selectOnly(groupId: String) {
        _state.update { it.selecting(groupId) }
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

    /** True on success; false leaves the failure in [state]'s `error`. A
     *  circle left successfully drops out of the reloaded list, which is
     *  what clears [CirclesState.selectedId] if it was the one open — see
     *  [loaded]. */
    @Throws(Exception::class)
    suspend fun leave(groupId: String): Boolean =
        act { Groups.leave(groupId) } != null

    /** True on success; false leaves the failure in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun setSharing(groupId: String, sharing: Boolean): Boolean =
        act { Groups.setSharing(groupId, sharing) } != null

    /** True on success; false leaves the failure in [state]'s `detailError`.
     *  Reloads the open circle's detail afterwards, same as [unsharePlace]. */
    @Throws(Exception::class)
    suspend fun sharePlace(groupId: String, place: SavedPlace, radiusM: Double): Boolean =
        actDetail { CirclePlaces.share(groupId, place, radiusM) } != null

    /** Removes a shared place by its own server identifier — [CirclePlace.serverId],
     *  not the circle it was shared into, because that is what
     *  [CirclePlaces.delete] takes. True on success; false leaves the failure
     *  in [state]'s `detailError`. */
    @Throws(Exception::class)
    suspend fun unsharePlace(serverId: String): Boolean =
        actDetail { CirclePlaces.delete(serverId) } != null

    /** Loads [CirclesState.places] and [CirclesState.events] for [groupId],
     *  newest event first — the order both platforms' screens already show
     *  them in. Busy and failure go through [CirclesState.detailBusy]/
     *  [CirclesState.detailError], not the list's [CirclesState.busy]/
     *  [CirclesState.error] — so opening a circle or refreshing its detail
     *  never disables Invite, Leave or the sharing switch, which read the
     *  list pair. */
    private suspend fun loadDetail(groupId: String) {
        // Captured for the same reason [reload]'s commit is guarded on it:
        // [commitIfViewing] alone is currently safe (a stale commit landing
        // here means the new rider selected the very same circle id, which
        // means they are a member and the content is theirs to see either
        // way) but it is a proxy, not the session itself, and this is the
        // last commit in this module that rested on one instead of on
        // [Auth.sessionEpoch] — see [act]/[actDetail] for the guard this
        // brings it in line with.
        val epoch = Auth.sessionEpoch.value
        _state.update { it.detailStarting() }
        val result = try {
            val places = CirclePlaces.places(groupId)
            val events = CircleEvents.events(groupId, sinceMs = 0L).sortedByDescending { it.tsMs }
            _state.value.detailLoaded(places, events)
        } catch (e: CancellationException) {
            // A cancelled load must not leave `detailBusy` stuck on with
            // nothing left to clear it. Guarded the same way the commit below
            // is: only touch it if `groupId` is still the circle being
            // viewed. The ordinary case — this coroutine was cancelled
            // because the rider tapped a different circle before this one
            // answered — has already moved `selectedId` on by the time this
            // runs, and that circle's own `detailStarting()` owns
            // `detailBusy` now; clearing it out from under that load would
            // flash the spinner off while it is still genuinely loading.
            if (_state.value.selectedId == groupId) _state.update { it.detailIdle() }
            throw e
        } catch (e: Exception) {
            _state.value.detailFailed(e)
        }
        // Only commit if this is still the circle being viewed. Two selections
        // can be in flight at once — tap one circle, then another before the
        // first answers — and the slower response arriving last would otherwise
        // write its places under the newer circle's heading. [selecting] clears
        // the detail on every change precisely to stop that, and without this
        // check a late reply puts it straight back.
        //
        // The caller cancelling the stale coroutine would also fix it, and
        // Android's screen does: Compose keys `LaunchedEffect(circle.id)` on
        // the circle id, and that really does cancel the coroutine behind a
        // stale `select`. iOS's `.task(id:)` does not, though it looks like
        // it should — cancelling a Swift `Task` does not reach the Kotlin
        // coroutine an exported `suspend fun` runs on, because the ObjC
        // completion-handler bridge it compiles to has no cancellation path.
        // So on iOS this check is not a second line of defence, it is the
        // only one: the stale request keeps running regardless of what the
        // Swift `Task` around it does, and this is what stops its answer from
        // landing. This store cannot see its callers anyway, so the guarantee
        // belongs here rather than in a convention every future call site has
        // to know.
        //
        // Known gap, not fixed here: if the pane closes (`select(null)`)
        // while this load is still running and was never cancelled — the iOS
        // case, per the paragraph above — this discard branch leaves
        // `detailBusy` stuck true, because nothing is left to hand it off to:
        // there is no new `loadDetail` whose own `detailStarting()` would
        // otherwise own it. Invisible today (`detailBusy` only gates a button
        // inside the detail pane, which is closed by then), and clearing it
        // unconditionally here would be wrong for the same reason
        // `detailStarting()` exists — it would also fire when `selectedId`
        // moved to a *different* circle, wiping out that circle's own
        // genuine spinner mid-load.
        //
        // Both guards apply, viewing first: [commitIfViewing] decides
        // whether this response still belongs on screen at all, and
        // [commitIfCurrent] then decides whether the session that asked for
        // it is still the one this store holds — a sign-out-then-sign-back-
        // in-as-yourself that happens to land on the same circle id passes
        // the first guard and must still be caught by the second.
        _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, it.commitIfViewing(groupId, result)) }
    }

    /** Runs a circle-list mutation, then reloads the whole list — same
     *  shape as [FriendsStore]'s private `act`, see its doc for why an
     *  ordinary failure is reported through `state.error` and returned as
     *  null rather than thrown. */
    private suspend fun <T> act(block: suspend () -> T): T? {
        // Epoch-guarded like [reload]'s commit: a mutation failing after the
        // rider signed out must not write its banner onto the next rider's
        // freshly reset store. See FriendsStore.act for the full reasoning.
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

    /** Same shape as [act], but for a detail mutation: reloads the open
     *  circle's places/events afterwards instead of the circle list, since
     *  that is the only part a place share or removal can have changed.
     *  Busy and failure go through [CirclesState.detailBusy]/
     *  [CirclesState.detailError] rather than [act]'s list pair, for the
     *  same reason [loadDetail] does. A null [CirclesState.selectedId]
     *  (nothing open any more) just skips the reload — clearing
     *  [CirclesState.detailBusy] instead of leaving it stuck, since nothing
     *  else will. */
    private suspend fun <T> actDetail(block: suspend () -> T): T? {
        val epoch = Auth.sessionEpoch.value
        _state.update { it.detailStarting() }
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, it.detailFailed(e)) }
            return null
        }
        val selectedId = _state.value.selectedId
        if (selectedId != null) loadDetail(selectedId) else _state.update { it.detailIdle() }
        return result
    }

    /** The discriminator [Groups] routes on. "circle" here, "convoy" in
     *  [ConvoysStore]; one entity on the server, two kinds. */
    private const val KIND = "circle"
}

/**
 * [result] if [groupId] is still the circle being viewed, otherwise this state
 * untouched — the guard that stops a slow detail response landing under a
 * circle the rider has already moved on from.
 *
 * `internal` and pure so it can be asserted directly: the race it prevents
 * needs two overlapping coroutines to reproduce, which this module's test style
 * (plain kotlin.test, no coroutine test dispatcher) cannot stage.
 */
internal fun CirclesState.commitIfViewing(groupId: String, result: CirclesState): CirclesState =
    if (selectedId == groupId) result else this

/** [result] if [epoch] still names the session an in-flight [CirclesStore.reload]
 *  started under, checked against [currentEpoch] read fresh at commit time —
 *  or this state untouched otherwise. Same guard as [FriendsStore]'s
 *  matching function; see its doc for the full reasoning. */
internal fun CirclesState.commitIfCurrent(epoch: Int, currentEpoch: Int, result: CirclesState): CirclesState =
    if (epoch == currentEpoch) result else this

/** Every field back to [CirclesState]'s defaults — [CirclesState.selectedId]
 *  included, so a leaked selection cannot make the next rider's
 *  [CirclesStore.select] reload calls land on a circle that was never
 *  theirs. `internal` and pure, called from [CirclesStore.reset] and
 *  asserted directly — see [FriendsState.cleared]'s doc for why. */
internal fun CirclesState.cleared() = CirclesState()

internal fun CirclesState.starting() = copy(busy = true, error = null)

/** Same as [starting], for the detail pair — kept separate so a detail
 *  load or mutation never disables the list-scoped controls (Invite,
 *  Leave, the sharing switch) that read [CirclesState.busy]. */
internal fun CirclesState.detailStarting() = copy(detailBusy = true, detailError = null)

/** Clears [CirclesState.detailBusy] with nothing else to show for it — the
 *  no-op end of a detail action that found no circle open to reload, or a
 *  detail load cancelled before it reached [commitIfViewing]. Neither is a
 *  failure, so [CirclesState.detailError] is left alone; there is just a
 *  spinner that must stop. */
internal fun CirclesState.detailIdle() = copy(detailBusy = false)

/** Drops [CirclesState.selectedId] if it names a circle no longer in
 *  [circles] — a circle can vanish between the list load and the tap
 *  (someone removed you, or you left it on another device), and a detail
 *  pane pointed at nothing is worse than none. */
internal fun CirclesState.loaded(circles: List<Group>) = copy(
    circles = circles,
    selectedId = selectedId?.takeIf { id -> circles.any { it.id == id } },
    busy = false,
    error = null,
)

internal fun CirclesState.failed(e: Exception) =
    copy(busy = false, error = e.message?.ifBlank { null } ?: CirclesStore.FALLBACK_ERROR)

/** Same as [failed], for the detail pair. */
internal fun CirclesState.detailFailed(e: Exception) =
    copy(detailBusy = false, detailError = e.message?.ifBlank { null } ?: CirclesStore.FALLBACK_ERROR)

/** Opens (or closes, for null) [groupId]'s detail pane. Clears
 *  [CirclesState.places] and [CirclesState.events] on an actual change of
 *  selection, including to null, because the alternative — showing the
 *  previous circle's places for as long as the new load takes — is someone
 *  else's addresses under the wrong heading. Leaves them untouched on a
 *  reselect of the *same* circle, which is what a refresh does, so a
 *  refresh keeps showing the stale detail instead of flashing the empty
 *  state while it refetches. Either way only [CirclesState.detailError] is
 *  cleared — the list's own [CirclesState.error] is not this transition's
 *  business. */
internal fun CirclesState.selecting(groupId: String?): CirclesState =
    if (groupId == selectedId) copy(selectedId = groupId, detailError = null)
    else copy(selectedId = groupId, places = emptyList(), events = emptyList(), detailError = null)

internal fun CirclesState.detailLoaded(places: List<CirclePlace>, events: List<PlaceEvent>) =
    copy(places = places, events = events, detailBusy = false, detailError = null)
