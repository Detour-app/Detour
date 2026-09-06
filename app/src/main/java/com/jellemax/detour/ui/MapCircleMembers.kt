package com.jellemax.detour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.jellemax.detour.data.CircleFixes
import com.jellemax.detour.drive.FriendPosition
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Circle members on the map: the poll that fetches them, and the two places
 * their positions have to be drawn.
 *
 * Every circle you are in, always — not just whichever one CirclesScreen last
 * had open. A circle is the always-on relationship
 * (docs/CIRCLES_AND_CONVOYS.md section 2); making the map go blank until you
 * walk into another screen and pick one defeats the point of it, and the
 * selection lived in memory, so every app restart lost it.
 *
 * Polled rather than socketed: a circle fix only changes once a minute or so
 * server-side, so polling faster would just repeat the same row.
 */
@Composable
internal fun MapCircleMemberMarkers(
    s: MapScreenState,
    accountRiderId: RiderId,
    mapOverlays: MapOverlays?,
    fogView: FogView,
    convoyPeers: Map<RiderId, FriendPosition>,
) {
    LaunchedEffect(accountRiderId) {
        if (accountRiderId.value.isBlank()) {
            s.circleFixes = emptyList()  // signed out: nothing to ask the server for
            return@LaunchedEffect
        }
        while (true) {
            s.circleFixes = try {
                withContext(Dispatchers.IO) { CircleFixes.othersFixes(accountRiderId) }
            } catch (e: Exception) {
                s.circleFixes // offline or server down; keep the last known positions
            }
            delay(CIRCLE_FIX_POLL_MS)
        }
    }

    LaunchedEffect(mapOverlays, s.circleFixes) {
        mapOverlays?.setCircleMembers(s.circleFixes)
    }

    // The fog scrim is a sibling View over the GL surface, so it covers the
    // member and peer symbol layers too. Clear it around them, or the markers
    // the map just drew stay invisible on any ground you haven't driven —
    // which is most of where a circle member actually is.
    LaunchedEffect(s.circleFixes, convoyPeers) {
        fogView.peers = s.circleFixes.map { LatLon(it.fix.lat, it.fix.lon) } +
            convoyPeers.values.map { LatLon(it.lat, it.lon) }
        fogView.invalidate()
    }
}
