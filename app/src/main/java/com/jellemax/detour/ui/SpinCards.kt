package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.LoopDuration
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.presentation.DIRECTION_NAMES
import com.jellemax.detour.presentation.spinStateFrom

/** Same hue `MapLibreMap.kt` paints for the live "reach" circle on the map -
 *  not a `GraphiteTheme` token (that circle is native map paint, not Compose),
 *  reused here so the result callout's pin matches the pin already on the
 *  map for the same destination. */
private val DESTINATION_ORANGE = Color(0xFFFF9800)

/** The prototype's result callout: a spin's outcome, or a picked destination,
 *  in one highlighted row. Was inline in [SpinSheet]; moved out so the drive
 *  sheet (`RideSheet.kt`) shows the place a rider just searched for in the
 *  same row. [onRespin] draws the trailing "Re-spin" action; null leaves it
 *  out. */
@Composable
internal fun ResultCallout(
    title: String,
    subtitle: String?,
    onRespin: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
            .border(1.dp, DESTINATION_ORANGE.copy(alpha = 0.4f), MaterialTheme.shapes.large)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = DESTINATION_ORANGE)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        onRespin?.let {
            Text(
                "Re-spin",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = it),
            )
        }
    }
}

/** The spin sheet: everything the home sheet's Spin chip expands into. Same
 *  glass card the home sheet uses, just taller — a drag-handle bar stands in
 *  for an actual drag gesture, tap it (or the chevron) to fold back to the
 *  home sheet. */
/**
 * Minutes a spin sizes its loop to, or null when the loop is sized by length —
 * or [mode] spins no loop at all. MapScreen reads it for the spin and the reach
 * circle; the sheet collects the same two settings itself to draw its toggle.
 */
@Composable
internal fun loopMinutesSetting(mode: TravelMode): Float? {
    val byTime by Settings.loopByTime.collectAsStateWithLifecycle()
    val minutes by Settings.loopMinutes.collectAsStateWithLifecycle()
    return minutes.takeIf { mode.roundTrip && byTime }
}

/**
 * The loop length, in km, the reach circle is drawn from: the slider for a
 * length-sized loop, and for a time-sized one the same first guess
 * ([LoopDuration.guessMeters]) the spin asks the router for.
 */
internal fun loopLengthKm(timedMinutes: Float?, radiusKm: Float): Double =
    timedMinutes?.let { LoopDuration.guessMeters(it) / 1000.0 } ?: radiusKm.toDouble()

@Composable
internal fun SpinSheet(
    mode: TravelMode,
    onSelectMode: (TravelMode) -> Unit,
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
    inAppAvailable: Boolean,
    onSpin: () -> Unit,
    onCollapse: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        // Scrolls so a short viewport (landscape in a bar mount) still
        // reaches Spin and Go; unscrolled, the capped height squeezed the
        // sliders into each other and cut the buttons off (#421).
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val density = LocalDensity.current
            // See HomeSheet.kt's DragHandle for why this reads the current
            // onCollapse through rememberUpdatedState rather than keying the
            // gesture detector on it directly.
            val currentOnCollapse by rememberUpdatedState(onCollapse)
            var dragged by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCollapse)
                    .pointerInput(Unit) {
                        val thresholdPx = with(density) { SHEET_SWIPE_THRESHOLD.toPx() }
                        detectVerticalDragGestures(
                            // Reset at both ends, same reason as the home
                            // sheet's handle in HomeSheet.kt: a cancelled
                            // gesture never reaches onDragEnd, and the travel
                            // it left behind would count toward the next one.
                            onDragStart = { dragged = 0f },
                            onDragEnd = {
                                if (dragged >= thresholdPx) currentOnCollapse()
                                dragged = 0f
                            },
                            onDragCancel = { dragged = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            dragged += dragAmount
                        }
                    }
                    .semantics {
                        contentDescription = "Spin settings, expanded"
                        customActions = listOf(
                            CustomAccessibilityAction("Collapse") { currentOnCollapse(); true },
                        )
                    },
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
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Rounded.ExpandMore, contentDescription = "Collapse")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            // The phone's only travel-mode switch, and it sits above every row
            // the mode governs - the Unexplored badge, the destination-type
            // row, the slider's label and its range all react below it, so a
            // change reads as an effect of this control rather than as the
            // sheet rearranging itself. The row itself carries the
            // description: the pills are two words with no shared subject, and
            // "Switch to Moto" was the accessibility action the retired swipe
            // gesture used to expose.
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                options = TravelMode.entries.map { it.label },
                selectedIndex = TravelMode.entries.indexOf(mode),
                onSelect = { onSelectMode(TravelMode.entries[it]) },
                modifier = Modifier.semantics {
                    contentDescription = "Travel mode, ${mode.label} selected"
                },
            )

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
                ChoiceRow(
                    options = PoiKind.entries.map { it.label },
                    selectedIndex = PoiKind.entries.indexOf(poiKind),
                    onSelect = { onPoiKindChange(PoiKind.entries[it]) },
                )
            }

            // Every number below comes off spinStateFrom's radiusText - the
            // same %-decimal-below-maxKm/whole-number-at-or-above rule the
            // candidates card's rows read off it, so no two surfaces format
            // the same radiusKm differently. The candidate list is irrelevant
            // to this readout, so an empty roll is enough to reuse the mapper.
            val radiusState =
                spinStateFrom(mode, radiusKm, emptyList(), Settings.decimalSeparatorChar())
            // A loop can be sized by riding time instead ("half an hour for a
            // test ride"). Read from Settings rather than hoisted: the choice
            // outlives the screen, and MapBottomSlot is past §8.4's gate already.
            val loopByTime by Settings.loopByTime.collectAsStateWithLifecycle()
            val loopMinutes by Settings.loopMinutes.collectAsStateWithLifecycle()
            val timed = mode.roundTrip && loopByTime
            if (mode.roundTrip) {
                ChoiceRow(
                    options = listOf("Distance", "Time"),
                    selectedIndex = if (loopByTime) 1 else 0,
                    onSelect = { Settings.setLoopByTime(it == 1) },
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Size the loop by ${if (loopByTime) "time" else "distance"}"
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        timed -> "Trip time"
                        mode.roundTrip -> "Trip length"
                        else -> "Radius"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (timed) formatDurationHistory(LoopDuration.targetMs(loopMinutes))
                    else radiusState.radiusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (timed) {
                Slider(
                    value = loopMinutes,
                    onValueChange = { Settings.setLoopMinutes(it) },
                    valueRange = LoopDuration.MIN_MINUTES..LoopDuration.MAX_MINUTES,
                    // Whole 5-minute stops; `steps` counts the ones between the ends.
                    steps = ((LoopDuration.MAX_MINUTES - LoopDuration.MIN_MINUTES) /
                        LoopDuration.STEP_MINUTES).toInt() - 1,
                )
            } else {
                Slider(
                    value = radiusKm,
                    onValueChange = onRadiusChange,
                    valueRange = mode.minKm..mode.maxKm,
                )
            }

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
                            mode, minRadiusKm, emptyList(),
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
            ChoiceRow(
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
            // A loop also says how long it rides — the number a time-sized
            // spin was asked for, and worth knowing for a distance-sized one.
            val resultDistance = route?.let { r ->
                r.distanceMeters?.let { formatDistanceKm(it) }?.let { km ->
                    val t = r.timeMs?.takeIf { mode.roundTrip && destinationName == null }
                    if (t != null) "$km · ${formatDurationHistory(t)}" else km
                }
            }
            if (!spinning && (destinationName != null || resultDistance != null)) {
                ResultCallout(
                    title = destinationName ?: "Loop found",
                    subtitle = resultDistance,
                    onRespin = onSpin,
                )
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

            // Navigating is driving: the one action a destination offers. A
            // drive is recorded whichever nav app takes it, and the trip is
            // inferred from navigation rather than a separate Track button —
            // auto-detect (on by default) already covers recording with no
            // destination. (#272)
            NavButton(
                destination = destination,
                route = route?.waypoints,
                origin = origin,
                mode = mode,
                inAppAvailable = inAppAvailable,
                onNavigateInApp = onNavigateInApp,
                onNavigate = onNavigate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
