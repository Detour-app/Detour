package com.jellemax.detour.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.presentation.BreakPill
import com.jellemax.detour.presentation.RegroupBanner
import com.jellemax.detour.presentation.convoyBreakRegroupStateFrom
import com.jellemax.detour.presentation.formatDistanceKm
import kotlinx.coroutines.delay

/** How often the break pills re-check their age while nothing else changes. */
private const val BREAK_AGE_TICK_MS = 15_000L

/**
 * Break please, the host's Regroup here, the break pills and the regroup
 * banner, plus their map markers (#649). Shown only while this device is in a
 * convoy. The rules — who is host, how long a request stays, who has arrived —
 * are [convoyBreakRegroupStateFrom]'s; this file only draws them and sends.
 *
 * Reads the relay through [ConvoyLiveClient] itself, the way
 * [MapConvoyPeerMotion] does, so MapScreen hands in only what it owns: its
 * state (own fix, picked destination, error line), the overlays and how to
 * start a route.
 */
@Composable
internal fun MapBreakRegroup(
    s: MapScreenState,
    mapOverlays: MapOverlays?,
    onRouteTo: (LatLon, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activeConvoyId by ConvoyLiveClient.activeConvoyId.collectAsStateWithLifecycle()
    val connected by ConvoyLiveClient.connected.collectAsStateWithLifecycle()
    val breakRequests by ConvoyLiveClient.breakRequests.collectAsStateWithLifecycle()
    val pin by ConvoyLiveClient.regroupPin.collectAsStateWithLifecycle()
    val peers by ConvoyLiveClient.peers.collectAsStateWithLifecycle()
    val convoysState by ConvoysStore.state.collectAsStateWithLifecycle()
    val selfId by Account.riderId.collectAsStateWithLifecycle()
    // Requests age out on a clock, not on an event, so something has to
    // recompose when one does.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BREAK_AGE_TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }
    val state = convoyBreakRegroupStateFrom(
        convoy = convoysState.convoys.firstOrNull { it.id == activeConvoyId },
        selfId = selfId,
        breakRequests = breakRequests,
        pin = pin,
        positions = peers.mapValues { (_, p) -> LatLon(p.lat, p.lon) } +
            listOfNotNull(s.myLocation?.let { selfId to it }),
        // A request that arrived since the last tick is younger than nowMs
        // thinks; max keeps it from reading as from the future.
        nowMs = maxOf(nowMs, breakRequests.values.maxOfOrNull { it.receivedAtMs } ?: 0L),
    )

    val you = stringResource(R.string.map_break_you)
    val regroupPoint = stringResource(R.string.map_regroup_point)
    val markBreaks = state.breaks.map { b ->
        b.location to context.getString(R.string.map_break_marker, if (b.mine) you else b.name)
    }
    val markPin = state.regroup?.let { it.location to (it.label ?: regroupPoint) }
    LaunchedEffect(mapOverlays, markBreaks, markPin) {
        mapOverlays?.setConvoyMarks(markBreaks, markPin)
    }

    if (activeConvoyId == null) return
    val droppedPin = stringResource(R.string.map_dropped_pin)
    Column(
        modifier = modifier.widthIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.regroup?.let { banner ->
            RegroupCard(
                banner,
                isHost = state.isHost,
                onRoute = { onRouteTo(banner.location, banner.label ?: regroupPoint) },
                onClear = { ConvoyLiveClient.sendRegroupPinClear() },
            )
        }
        state.breaks.forEach { BreakPillChip(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Large on purpose: pressed with a glove, at a glance, on the move.
            RoundAction(
                icon = { Icon(Icons.Rounded.LocalCafe, contentDescription = null) },
                label = stringResource(R.string.map_break_please),
                enabled = connected,
                onClick = { askForBreak(s, context) },
                size = 72,
            )
            if (state.isHost) {
                Spacer(Modifier.width(12.dp))
                RoundAction(
                    icon = { Icon(Icons.Rounded.Flag, contentDescription = null) },
                    label = stringResource(R.string.map_regroup_here),
                    enabled = connected,
                    onClick = { dropRegroupPin(s, context, droppedPin) },
                    size = 64,
                )
            }
        }
    }
}

private fun askForBreak(s: MapScreenState, context: Context) {
    val at = s.myLocation
    if (at == null) s.error = context.getString(R.string.map_waiting_location)
    else ConvoyLiveClient.sendBreakRequest(at)
}

/** Here is the place the host picked — a long-pressed point or a saved place,
 *  both of which already land in the destination — or, with nothing picked,
 *  where the host is. A dropped pin's placeholder name is no label. */
private fun dropRegroupPin(s: MapScreenState, context: Context, droppedPinName: String) {
    val at = s.destination ?: s.myLocation
    if (at == null) s.error = context.getString(R.string.map_waiting_location)
    else ConvoyLiveClient.sendRegroupPin(
        at, s.destinationName?.takeIf { s.destination != null && it != droppedPinName },
    )
}

@Composable
private fun RoundAction(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Int,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon()
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun BreakPillChip(pill: BreakPill) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 2.dp,
    ) {
        Text(
            if (pill.mine) stringResource(R.string.map_break_pill_mine)
            else stringResource(R.string.map_break_pill, pill.name),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun RegroupCard(banner: RegroupBanner, isHost: Boolean, onRoute: () -> Unit, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.map_regroup_at, banner.label ?: stringResource(R.string.map_regroup_point)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            banner.distanceM?.let {
                Text(
                    if (banner.hereAlready) stringResource(R.string.map_regroup_you_are_here)
                    else formatDistanceKm(it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (banner.arrived.isNotEmpty()) {
                Text(
                    stringResource(R.string.map_regroup_arrived, banner.arrived.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (banner.notArrived.isNotEmpty()) {
                Text(
                    stringResource(R.string.map_regroup_on_the_way, banner.notArrived.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row {
                if (!banner.hereAlready) {
                    TextButton(onClick = onRoute) { Text(stringResource(R.string.map_regroup_route)) }
                }
                if (isHost) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.map_regroup_clear)) }
                }
            }
        }
    }
}
