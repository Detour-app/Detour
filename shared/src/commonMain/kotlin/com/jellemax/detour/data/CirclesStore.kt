package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the Circles screen and its detail pane show: the list plus the
 * one circle currently open in it.
 *
 * [places] and [events] belong to [selectedId], not to every circle at
 * once — they are cleared the instant the selection changes (see
 * [selecting]) so a slow detail load can never surface under the wrong
 * circle's heading. Selection lives in this same state rather than a
 * fourth store precisely so the two cannot disagree about which circle is
 * open.
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

    /** Opens [groupId]'s detail pane, or closes it for null. Always clears
     *  the previous circle's [CirclesState.places]/[CirclesState.events]
     *  first (see [selecting]), then loads the new one's. */
    @Throws(Exception::class)
    suspend fun select(groupId: String?) {
        _state.value = _state.value.selecting(groupId)
        if (groupId != null) loadDetail(groupId)
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

    /** True on success; false leaves the failure in [state]'s `error`.
     *  Reloads the open circle's detail afterwards, same as [unsharePlace]. */
    @Throws(Exception::class)
    suspend fun sharePlace(groupId: String, place: SavedPlace, radiusM: Double): Boolean =
        actDetail { CirclePlaces.share(groupId, place, radiusM) } != null

    /** Removes a shared place by its own server identifier — [CirclePlace.serverId],
     *  not the circle it was shared into, because that is what
     *  [CirclePlaces.delete] takes. True on success; false leaves the failure
     *  in [state]'s `error`. */
    @Throws(Exception::class)
    suspend fun unsharePlace(serverId: String): Boolean =
        actDetail { CirclePlaces.delete(serverId) } != null

    /** Loads [CirclesState.places] and [CirclesState.events] for [groupId],
     *  newest event first — the order both platforms' screens already show
     *  them in. A detail failure sets the same single [CirclesState.error]
     *  a list failure would. */
    private suspend fun loadDetail(groupId: String) {
        _state.value = _state.value.starting()
        _state.value = try {
            val places = CirclePlaces.places(groupId)
            val events = CircleEvents.events(groupId, sinceMs = 0L).sortedByDescending { it.tsMs }
            _state.value.detailLoaded(places, events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value.failed(e)
        }
    }

    /** Runs a circle-list mutation, then reloads the whole list — same
     *  shape as [FriendsStore]'s private `act`, see its doc for why an
     *  ordinary failure is reported through `state.error` and returned as
     *  null rather than thrown. */
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

    /** Same shape as [act], but for a detail mutation: reloads the open
     *  circle's places/events afterwards instead of the circle list, since
     *  that is the only part a place share or removal can have changed. A
     *  null [CirclesState.selectedId] (nothing open any more) just skips the
     *  reload rather than loading a circle that isn't there. */
    private suspend fun <T> actDetail(block: suspend () -> T): T? {
        _state.value = _state.value.starting()
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.failed(e)
            return null
        }
        _state.value.selectedId?.let { loadDetail(it) }
        return result
    }

    /** The discriminator [Groups] routes on. "circle" here, "convoy" in
     *  [ConvoysStore]; one entity on the server, two kinds. */
    private const val KIND = "circle"
}

internal fun CirclesState.starting() = copy(busy = true, error = null)

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

/** Opens (or closes, for null) [groupId]'s detail pane. Clears
 *  [CirclesState.places] and [CirclesState.events] unconditionally,
 *  including when [groupId] equals the current selection, because the
 *  alternative — showing the previous circle's places for as long as the
 *  new load takes — is someone else's addresses under the wrong heading. */
internal fun CirclesState.selecting(groupId: String?) =
    copy(selectedId = groupId, places = emptyList(), events = emptyList(), error = null)

internal fun CirclesState.detailLoaded(places: List<CirclePlace>, events: List<PlaceEvent>) =
    copy(places = places, events = events, busy = false, error = null)
