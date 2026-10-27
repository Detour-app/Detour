package com.jellemax.detour.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jellemax.detour.R
import com.jellemax.detour.convoy.ConvoyLiveService
import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.PlannedRide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starting a planned ride as a live convoy (#758), host only, at any time ([PlannedRide.canStart]).
 *
 * After the host confirms, it makes a convoy named after the ride, asks
 * [PlannedRide.convoyInvitees] into it with the ordinary convoy invite, goes live, and rides the
 * attached route the way Routes does ([rideSavedRoute]); [onRide] opens the map, where the convoy
 * shows. With no route the map opens as it is. Riders the server would not invite (not the
 * host's friend) go to [onNotice]: the detail screen is gone by the time anyone could read them
 * there.
 *
 * Screen-lifetime, like [SavedLoopRide], which it rides a saved loop through. Its own [error]
 * and [starting] rather than the planned-rides store's: a failure here is the convoy store's or
 * routing's.
 */
internal class PlannedRideStart(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRide: () -> Unit,
    private val onNotice: (String) -> Unit,
) {
    /** The ride waiting on the host's confirm. */
    var confirming: PlannedRide? by mutableStateOf(null)
    var starting: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
    val loopRide = SavedLoopRide(context, scope, onStatus = { error = it }, onRide = onRide)

    fun confirm() {
        val ride = confirming ?: return
        confirming = null
        error = null
        starting = true
        scope.launch {
            val started = ConvoysStore.createAndInvite(ride.title, ride.convoyInvitees.map { it.username })
            starting = false
            if (started == null) {
                error = ConvoysStore.state.value.error
                return@launch
            }
            ConvoyLiveService.start(context, started.id)
            if (started.refused.isNotEmpty()) {
                onNotice(context.getString(R.string.planned_start_not_asked, started.refused.joinToString(", ")))
            }
            val route = ride.route
            if (route == null) onRide() else rideSavedRoute(context, route, loopRide, onStatus = { error = it }, onRide)
        }
    }
}

@Composable
internal fun rememberPlannedRideStart(onRide: () -> Unit, onNotice: (String) -> Unit): PlannedRideStart {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ride by rememberUpdatedState(onRide)
    val notice by rememberUpdatedState(onNotice)
    return remember { PlannedRideStart(context, scope, { ride() }, { notice(it) }) }
}

/** The "start this ride?" confirm, and the loop-join choice a saved loop route may ask after it. */
@Composable
internal fun PlannedRideStartDialogs(start: PlannedRideStart) {
    start.confirming?.let { ride ->
        ConfirmDialog(
            title = stringResource(R.string.planned_start_title, ride.title),
            text = stringResource(R.string.planned_start_text),
            confirmLabel = stringResource(R.string.planned_start_confirm),
            onConfirm = start::confirm,
            onDismiss = { start.confirming = null },
        )
    }
    SavedLoopJoinDialog(start.loopRide)
}
