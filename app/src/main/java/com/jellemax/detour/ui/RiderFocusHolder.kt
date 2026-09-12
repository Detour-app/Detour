package com.jellemax.detour.ui

import com.jellemax.detour.data.RiderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request to frame a rider's position on the map (issue #294): a row for
 * someone whose position the app holds publishes one, MapScreen picks it up
 * and does the framing. [requestedAtMs] is carried alongside the id so
 * MapScreen can give up if the rider's position never arrives, rather than
 * waiting forever.
 */
internal data class RiderFocusRequest(
    val riderId: RiderId,
    val displayName: String,
    val requestedAtMs: Long,
)

/**
 * Process-scoped, same lifetime and same reason as [SpinResultHolder]: a tap
 * on a circle-member row navigates back to the map, which is a fresh
 * composition (`NavDisplay` composes only the top entry — see
 * [MapScreenState]'s KDoc), so the request has to outlive that swap and
 * survive an activity recreation in between.
 *
 * Read once by [MapScreenState]'s seed and cleared by MapScreen once it has
 * either framed the rider or given up — unlike [SpinResultHolder], nothing
 * keeps republishing this every recomposition, because a rider focus is a
 * one-shot deep link into the map rather than state the map itself owns.
 *
 * ## Why only `CircleDetailScreen` publishes to this today
 *
 * #294 asks for both a circle member row and a Friends leaderboard row.
 * `CircleMemberRow` already carries the `sharing` flag `MapCircleMemberMarkers`
 * itself polls on, so wiring a tap there costs nothing beyond this file and
 * the row it's on. `LeaderboardRow` (`FriendsState.kt`) carries no such
 * signal — knowing whether a friend has a position to frame means joining
 * the leaderboard against circle membership and live convoy state, which
 * `friendsBoardStateFrom` does not do and which is a presentation-state
 * change worth its own review pass rather than folding into this one. The
 * map-side half below (this file, plus the two effects in `MapScreen.kt`
 * that consume it) already covers the Friends case too, once that join
 * exists — a caller just has to publish a request the same way.
 */
internal object RiderFocusHolder {
    private val _state = MutableStateFlow<RiderFocusRequest?>(null)
    val state: StateFlow<RiderFocusRequest?> = _state.asStateFlow()

    fun request(riderId: RiderId, displayName: String) {
        _state.value = RiderFocusRequest(riderId, displayName, System.currentTimeMillis())
    }

    /** Clears [request], but only the one MapScreen actually handled — a
     *  newer request published while an older one was still being framed
     *  must not be discarded out from under the rider who just made it. */
    fun clear(handled: RiderFocusRequest) {
        _state.compareAndSet(handled, null)
    }
}
