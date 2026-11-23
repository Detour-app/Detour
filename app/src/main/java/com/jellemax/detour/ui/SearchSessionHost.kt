package com.jellemax.detour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.presentation.SearchSession
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [SearchSession] whose lookup runs on [Dispatchers.IO] — the one thing about it commonMain
 * cannot say. Every Android surface builds its session here, the car included (#1005).
 */
fun androidSearchSession(): SearchSession =
    SearchSession(search = { query, near -> withContext(Dispatchers.IO) { Geocoder.search(query, near) } })

/**
 * A phone screen's [SearchSession] for as long as the caller stays composed: it answers
 * [query], biases toward [anchor] when there is one and otherwise toward the tracker's last
 * accurate fix. The caller draws `session.state`.
 *
 * The fix comes from [TripTrackingService.lastFix], not a screen's `myLocation`: that one
 * also takes the fused-location fallback's ungated fix, which is fine for centring a map and
 * wrong for ranking street names. Collected in an effect rather than as Compose state, so a
 * fix a second does not recompose the search box.
 */
@Composable
fun rememberSearchSession(query: String, anchor: LatLon? = null): SearchSession {
    val session = remember { androidSearchSession() }
    LaunchedEffect(session) {
        TripTrackingService.lastFix.collect { fix ->
            fix?.let { session.onFix(LatLon(it.lat, it.lon), it.accuracyMeters) }
        }
    }
    LaunchedEffect(session, anchor) { session.setAnchor(anchor) }
    // Read through State so the one long-lived run sees every keystroke without re-keying.
    val latestQuery by rememberUpdatedState(query)
    LaunchedEffect(session) { session.run(snapshotFlow { latestQuery }) }
    return session
}
