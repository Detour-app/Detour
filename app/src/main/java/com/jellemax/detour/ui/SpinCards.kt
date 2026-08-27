package com.jellemax.detour.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val DIRECTION_NAMES = listOf("North", "North-east", "East", "South-east",
    "South", "South-west", "West", "North-west")

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
