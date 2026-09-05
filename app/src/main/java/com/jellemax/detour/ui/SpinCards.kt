package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.presentation.DIRECTION_NAMES
import com.jellemax.detour.presentation.spinStateFrom
import com.jellemax.detour.tracking.TripStats

/** Same hue `MapLibreMap.kt` paints for the live "reach" circle on the map -
 *  not a `GraphiteTheme` token (that circle is native map paint, not Compose),
 *  reused here so the result callout's pin matches the pin already on the
 *  map for the same destination. */
private val DESTINATION_ORANGE = Color(0xFFFF9800)

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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Casino, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Spin the map",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.ExpandMore, contentDescription = "Collapse")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            // Purely informational - spin() biases the point/road roll toward
            // fog-of-war territory by passing ExploredArea.load() into
            // pickThreeCandidates. Round-trip mode takes neither
            // (RoutingClient.roundTrip and RoundTripPlanner.plan have no
            // ExploredArea parameter), so the badge is gated off there rather
            // than claiming a bias the loop planner does not apply. No onClick:
            // there is no opt-out wired into spin() to toggle, so this isn't a
            // control.
            if (!mode.roundTrip) {
                Row {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.BlurOn, contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text("Unexplored", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // roundTrip is a fixed property of the mode (only Moto has it), not
            // a chooseable segment — so it gates the destination-type row the
            // same way it always gated the old dropdown, rather than adding a
            // "Loop" option to pick.
            if (!mode.roundTrip) {
                ScrollingPillRow(
                    options = PoiKind.entries.map { it.label },
                    selectedIndex = PoiKind.entries.indexOf(poiKind),
                    onSelect = { onPoiKindChange(PoiKind.entries[it]) },
                )
            }

            // Every number below comes off spinStateFrom's radiusText - the
            // same %-decimal-below-maxKm/whole-number-at-or-above rule the
            // candidates card's rows read off it, so no two surfaces format
            // the same radiusKm differently. directionDeg and candidates are
            // irrelevant to this readout, so a null/empty roll is enough to
            // reuse the mapper for it.
            val radiusState =
                spinStateFrom(mode, radiusKm, null, emptyList(), Settings.decimalSeparatorChar())
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (mode.roundTrip) "Trip length" else "Radius",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    radiusState.radiusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
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
                        // "Off" is a state label, not a number - kept as a literal.
                        // The km case reuses radiusText's own mode.maxKm cutoff via
                        // spinStateFrom rather than re-deriving it here, so a
                        // min-distance reading is always formatted exactly the
                        // same way a radius reading is.
                        if (minRadiusKm <= 0f) "Off"
                        else spinStateFrom(
                            mode, minRadiusKm, null, emptyList(),
                            Settings.decimalSeparatorChar(),
                        ).radiusText,
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

            // The prototype's result callout: a spin's outcome (a loop's
            // distance, a chosen candidate's name and/or its route distance)
            // in one highlighted row instead of two independent optional
            // lines of text. Hidden while a spin runs: spin() only clears the
            // previous destination/route once its network work returns, so the
            // row would otherwise keep advertising the last result as if it
            // were the pending one - and its Re-spin shares onSpin with the
            // button below, which toggles to Cancel while spinning. With the
            // gate, Re-spin only renders when onSpin still rolls.
            val resultDistance = route?.distanceMeters?.let { formatDistanceKm(it) }
            if (!spinning && (destinationName != null || resultDistance != null)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
                        .border(1.dp, DESTINATION_ORANGE.copy(alpha = 0.4f), MaterialTheme.shapes.large)
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null,
                        tint = DESTINATION_ORANGE)
                    Column(Modifier.weight(1f)) {
                        Text(
                            destinationName ?: "Loop found",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        resultDistance?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "Re-spin",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onSpin),
                    )
                }
            }

            Button(
                onClick = onSpin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (spinning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Casino, contentDescription = null, Modifier.size(20.dp))
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
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Track ${mode.label.lowercase()}", maxLines = 1)
                    }
                }
            }
        }
    }
}
