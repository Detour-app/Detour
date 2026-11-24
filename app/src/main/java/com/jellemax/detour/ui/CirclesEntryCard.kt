package com.jellemax.detour.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShareLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CirclesStore

/**
 * The Circles section of Friends: one row to the circles list, with how many
 * this rider shares their position in.
 *
 * It was one of the two rows on a Social screen that held nothing else, so
 * every trip to Friends or Circles cost an extra tap. #1024 dropped that
 * screen: the home sheet's Social card opens Friends, and Circles hangs off
 * Friends through this row.
 */
@Composable
internal fun CirclesEntryCard(onOpenCircles: () -> Unit) {
    val riderId by Account.riderId.collectAsStateWithLifecycle()

    // Same staleness reload CirclesScreen does on entry (Friends -> Circles ->
    // Friends is not a new visit); reloadIfStale skips the round trip inside
    // its window.
    LaunchedEffect(Unit) { CirclesStore.reloadIfStale() }
    val circlesState by CirclesStore.state.collectAsStateWithLifecycle()

    // "Sharing" means accepted membership with this device's own row sharing
    // on — not merely present in the list, which also holds pending invites
    // this rider never joined. Mirrors CirclePresence.sharingCircles (same
    // module-internal definition, re-derived here since that function isn't
    // visible outside the shared module).
    val circleCount = circlesState.circles.count { circle ->
        circle.status == "accepted" && circle.members.find { it.id == riderId }?.sharing == true
    }

    ListCard {
        HubRow(
            icon = Icons.Rounded.ShareLocation,
            title = stringResource(R.string.circles_title),
            onClick = onOpenCircles,
            trailingText = stringResource(R.string.circles_entry_sharing, circleCount),
            paintCard = false,
        )
    }
}
