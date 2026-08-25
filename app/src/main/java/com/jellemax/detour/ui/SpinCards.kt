package com.jellemax.detour.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.ModeSwipePolicy
import com.jellemax.detour.tracking.TripStats
import kotlin.math.abs
import kotlinx.coroutines.launch

private val DIRECTION_NAMES = listOf("North", "North-east", "East", "South-east",
    "South", "South-west", "West", "North-west")

/** How much of its opacity the dock's left cell loses at full drag. Stops short
 *  of invisible: the mode you are dragging away from stays readable. */
private const val DRAG_FADE = 0.55f

/** Persistent glass bar at the bottom of the map: the spin dock. Tapping the
 *  left cell opens the sheet; the dice FAB spins right away without needing
 *  the sheet open at all. */
@Composable
internal fun SpinDock(
    mode: TravelMode,
    radiusKm: Float,
    directionDeg: Float?,
    spinning: Boolean,
    destination: LatLon?,
    route: RouteResult?,
    origin: LatLon?,
    inAppAvailable: Boolean,
    onSwitchMode: (TravelMode) -> Unit,
    switchBlockedReason: String?,
    onSwitchBlocked: (String) -> Unit,
    onSpin: () -> Unit,
    onExpand: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    /** Px. Remembered *here* and not in MapScreen on purpose: a per-frame
     *  snapshot read invalidates its nearest restartable scope, and MapScreen's
     *  Scaffold content lambda is reached through only inline Box/Column/Row
     *  wrappers - a read there would invalidate the whole lambda every frame of
     *  the drag. */
    val offsetX = remember { Animatable(0f) }
    /** Read by the hint animation in the next commit, which must not fight a
     *  finger that is already on the card. */
    var dragging by remember { mutableStateOf(false) }

    val commitPx = with(density) { ModeSwipePolicy.COMMIT_DP.dp.toPx() }
    val other = ModeSwipePolicy.other(mode)

    // pointerInput(Unit) captures its parameters as plain values, frozen at
    // first composition. Everything the gesture callbacks read goes through
    // rememberUpdatedState or the gate silently stops gating, the callbacks
    // fire into a stale lambda, and `other` switches to whatever the mode was
    // when the screen opened. None of that has a compiler signal.
    val blockedRef = rememberUpdatedState(switchBlockedReason)
    val otherRef = rememberUpdatedState(other)
    val onSwitchRef = rememberUpdatedState(onSwitchMode)
    val onBlockedRef = rememberUpdatedState(onSwitchBlocked)

    val settle = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(MaterialTheme.shapes.extraLarge)
            // The non-gesture equivalent. A swipe is invisible to TalkBack, and
            // the mode bar this replaced was a plain, focusable control.
            // Read directly rather than through the refs above: this lambda is
            // rebuilt on every composition, so it is never stale.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Switch to ${other.label}") {
                        val blocked = switchBlockedReason
                        if (blocked == null) {
                            onSwitchMode(other)
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var rawPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        rawPx = 0f
                        dragging = true
                        tracker.resetTracking()
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { offsetX.animateTo(0f, settle) }
                    },
                    onDragEnd = {
                        dragging = false
                        val blocked = blockedRef.value
                        // px/s -> dp/s: toDp() divides by density, which is the
                        // same conversion a velocity needs. ModeSwipePolicy's
                        // fling threshold is in dp/s so the units must match.
                        val velocityDp = tracker.calculateVelocity().x.toDp().value
                        val offsetDp = offsetX.value.toDp().value
                        if (ModeSwipePolicy.commits(offsetDp, velocityDp, blocked != null)) {
                            onSwitchRef.value(otherRef.value)
                        } else if (blocked != null && abs(offsetDp) >= ModeSwipePolicy.MIN_INTENT_DP) {
                            onBlockedRef.value(blocked)
                        }
                        scope.launch { offsetX.animateTo(0f, settle) }
                    },
                ) { change, dragAmount ->
                    rawPx += dragAmount
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val targetDp = ModeSwipePolicy.dragOffsetDp(
                        rawPx.toDp().value,
                        blocked = blockedRef.value != null,
                    )
                    val targetPx = targetDp.dp.toPx()
                    scope.launch { offsetX.snapTo(targetPx) }
                }
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    // graphicsLayer, not offset/alpha modifiers: the lambda runs
                    // in the draw phase, so the per-frame read never triggers a
                    // recomposition at all.
                    .graphicsLayer {
                        translationX = offsetX.value
                        alpha = 1f - (abs(offsetX.value) / commitPx).coerceIn(0f, 1f) * DRAG_FADE
                    }
                    .clickable(onClick = onExpand),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mode.icon, contentDescription = null)
                Column {
                    Text(
                        "${if (mode.maxKm <= 10f) "%.1f".format(radiusKm) else radiusKm.toInt()} km",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${mode.label} · " + (directionDeg?.let { DIRECTION_NAMES[(it / 45f).toInt()] }
                            ?: "any direction"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(Icons.Outlined.ExpandLess, contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onSpin,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(52.dp),
            ) {
                if (spinning) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Outlined.Casino, contentDescription = "Spin")
                }
            }
            NavIconButton(
                destination = destination,
                route = route?.waypoints,
                origin = origin,
                mode = mode,
                inAppAvailable = inAppAvailable,
                onNavigateInApp = onNavigateInApp,
                onNavigate = onNavigate,
            )
        }
    }
}

/** The spin sheet: everything the dock's left cell expands into. Same glass
 *  card the dock uses, just taller — a drag-handle bar stands in for an
 *  actual drag gesture, tap it (or the chevron) to fold back to the dock. */
@Composable
internal fun SpinSheet(
    mode: TravelMode,
    radiusKm: Float,
    onRadiusChange: (Float) -> Unit,
    minRadiusKm: Float,
    onMinRadiusChange: (Float) -> Unit,
    poiKind: PoiKind,
    onPoiKindChange: (PoiKind) -> Unit,
    directionDeg: Float?,
    onDirectionChange: (Float?) -> Unit,
    spinning: Boolean,
    error: String?,
    route: RouteResult?,
    destinationName: String?,
    destination: LatLon?,
    origin: LatLon?,
    stats: TripStats?,
    inAppAvailable: Boolean,
    onSpin: () -> Unit,
    onCollapse: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    onTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 34.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            CircleShape,
                        ),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Spin a destination",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.ExpandMore, contentDescription = "Collapse")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            // roundTrip is a fixed property of the mode (only Moto has it), not
            // a chooseable segment — so it gates the destination-type row the
            // same way it always gated the old dropdown, rather than adding a
            // "Loop" option to pick.
            if (!mode.roundTrip) {
                SegmentedPillRow(
                    options = PoiKind.entries.map { it.label },
                    selectedIndex = PoiKind.entries.indexOf(poiKind),
                    onSelect = { onPoiKindChange(PoiKind.entries[it]) },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (mode.roundTrip) "Trip length" else "Radius",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (mode.maxKm <= 10f) "%.1f km".format(radiusKm)
                    else "${radiusKm.toInt()} km",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            route?.distanceMeters?.let {
                Text("Loop found: ${formatDistanceKm(it)}", style = MaterialTheme.typography.bodySmall)
            }
            destinationName?.let {
                Text("→ $it", style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = radiusKm,
                onValueChange = onRadiusChange,
                valueRange = mode.minKm..mode.maxKm,
            )

            if (!mode.roundTrip) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Min distance", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (minRadiusKm <= 0f) "Off"
                        else if (mode.maxKm <= 10f) "%.1f km".format(minRadiusKm)
                        else "${minRadiusKm.toInt()} km",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = minRadiusKm,
                    onValueChange = onMinRadiusChange,
                    valueRange = 0f..radiusKm,
                )
            }

            Text("Direction", style = MaterialTheme.typography.labelLarge)
            ScrollingPillRow(
                options = listOf("Any") + DIRECTION_NAMES,
                selectedIndex = directionDeg?.let { (it / 45f).toInt() + 1 } ?: 0,
                onSelect = { i -> onDirectionChange(if (i == 0) null else (i - 1) * 45f) },
            )

            Button(
                onClick = onSpin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (spinning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Casino, contentDescription = null, Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (spinning) "Cancel" else "Spin",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavButton(
                    destination = destination,
                    route = route?.waypoints,
                    origin = origin,
                    mode = mode,
                    inAppAvailable = inAppAvailable,
                    onNavigateInApp = onNavigateInApp,
                    onNavigate = onNavigate,
                    modifier = Modifier.weight(1f),
                )
                if (stats == null) {
                    OutlinedButton(onClick = onTrack, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Track ${mode.label.lowercase()}", maxLines = 1)
                    }
                }
            }
        }
    }
}
