package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The combined fog of war: your friends' traces unioned with your own, so a
 * group uncovers the map together.
 *
 * Held in memory only. These are someone else's traces — they are not ours to
 * write to disk, and re-fetching them costs one request per app launch. The
 * server hands them over only when both sides have opted in
 * ([Settings.shareFog]), so an empty list is the normal state, not an error.
 */
object FriendFog {

    private val _traces = MutableStateFlow<List<List<LatLon>>>(emptyList())
    val traces: StateFlow<List<List<LatLon>>> = _traces.asStateFlow()

    /**
     * Never throws for the reason its own doc says — the network/parse leg
     * below is caught internally. `@Throws(Exception::class)` is here anyway
     * because that "never" is an internal promise, not something the Swift
     * side can rely on if this function's body ever grows a path that
     * doesn't hold it; see [SyncClient.sync]'s doc for why `Exception`
     * rather than nothing.
     *
     * Epoch-guarded the same way [FriendsStore]/[ConvoysStore]/[CirclesStore]
     * guard their own commits: [Auth.sessionEpoch] is captured before the
     * request, and the fetched traces are only written if it is still current
     * when the request returns. Without this, `Auth.clear()` resetting this
     * object mid-request (a sign-out, a 401, a server switch) does not stop
     * an already in-flight refresh from writing the departed rider's friends'
     * GPS traces straight back over the reset — on iOS this is the whole
     * bug, since `RootView`'s `.task(id: SettingsValues.shared.shareFog)`
     * only re-runs when `shareFog` itself changes, not on sign-out, so
     * nothing re-fetches to correct it afterwards and the stale union stays
     * on the map for the rest of the app session.
     */
    @Throws(Exception::class)
    suspend fun refresh() {
        val epoch = Auth.sessionEpoch.value
        if (!SyncClient.configured() || !Account.signedIn || !Settings.shareFog.value) {
            _traces.value = emptyList()
            return
        }
        val result = try {
            val array = Api.requestJson("GET", "/friends/fog").optArray("traces") ?: JsonArrayEmpty
            TraceStore.parseLines(array.indices.map { array.optString(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        _traces.update { it.commitIfCurrent(epoch, Auth.sessionEpoch.value, result) }
    }

    /** Stop showing friends' territory the moment sharing is turned off, rather
     *  than at the next launch. */
    fun clear() {
        _traces.value = emptyList()
    }
}

/**
 * [result] if [epoch] still names the session an in-flight [FriendFog.refresh]
 * started under, checked against [currentEpoch] read fresh at commit time —
 * or this list untouched otherwise. Same guard as the three feature stores'
 * own `commitIfCurrent`, applied directly to the traces list since
 * [FriendFog] has no wrapping state type of its own to hang it off.
 *
 * `internal` and pure so it can be asserted directly — the race it prevents
 * needs two overlapping coroutines to reproduce, which this module's test
 * style (plain kotlin.test, no coroutine test dispatcher) cannot stage.
 */
internal fun List<List<LatLon>>.commitIfCurrent(
    epoch: Int,
    currentEpoch: Int,
    result: List<List<LatLon>>,
): List<List<LatLon>> = if (epoch == currentEpoch) result else this
