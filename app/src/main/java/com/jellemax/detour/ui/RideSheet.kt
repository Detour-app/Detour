package com.jellemax.detour.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.presentation.ActiveTripCardState
import com.jellemax.detour.presentation.NavState
import com.jellemax.detour.presentation.activeTripCardStateFrom
import com.jellemax.detour.tracking.ReplayClock
import com.jellemax.detour.tracking.TripStats
import kotlinx.coroutines.delay

/**
 * The two sheets the map's bottom slot shows while the rider is moving.
 *
 * [DriveSheet] is a trip being recorded with no route to follow; [NavSheet] is
 * turn-by-turn. Each is a compact strip when closed — the one or two numbers
 * worth a glance at speed, and a cross that ends the outermost thing running —
 * and grows the trip's stats and its actions when tapped open. The nav cross
 * ends navigation only: the trip keeps recording, as it does after arrival,
 * and the slot drops to the drive sheet, whose cross ends the trip.
 *
 * Both are stateless beyond the one-second clock the elapsed readout needs;
 * the open/closed flag is the map screen's, reset on every slot change.
 */

/** Whether a ride sheet is open, and the tap that flips it. Owned by the map
 *  screen, reset there on every slot change. */
internal data class SheetToggle(
    val expanded: Boolean,
    val onToggle: () -> Unit,
)

/** Everything the drive sheet's Where to? bar needs — the same values the
 *  home sheet hands `SearchIsland` — grouped so the sheet stays under the
 *  parameter limit (`docs/guidelines/state-holders.md` §14.2). */
internal data class WhereTo(
    val username: String,
    val onAvatarClick: () -> Unit,
    val open: Boolean,
    val onOpenChange: (Boolean) -> Unit,
    val onPick: (GeocodeResult) -> Unit,
)

/** What `NavButton` needs to offer Go from the drive sheet, grouped for the
 *  same reason as [WhereTo]. */
internal data class GoTarget(
    val destination: LatLon?,
    val destinationName: String?,
    val route: RouteResult?,
    val origin: LatLon?,
    val mode: TravelMode,
    val inAppAvailable: Boolean,
    val onNavigateInApp: () -> Unit,
    val onNavigate: () -> Unit,
)

/** A trip being recorded with nothing to navigate to. Closed: elapsed time
 *  over distance. Open: the full stats, a Where to? bar so the trip can
 *  become a navigated one without ending it, and End trip. */
@Composable
internal fun DriveSheet(
    toggle: SheetToggle,
    stats: TripStats,
    whereTo: WhereTo,
    go: GoTarget,
    onEndTrip: () -> Unit,
) {
    val state = rememberActiveTripCardState(stats)
    val expanded = toggle.expanded
    // The keyboard covers exactly the region the search results grow into.
    RideSheetCard(toggle, modifier = Modifier.imePadding()) {
        if (expanded) DragHandle()
        RideSheetHeader(
            headline = state.durationText,
            subline = "${state.distanceText} · ${stats.mode.label} · recording",
            isError = false,
            crossDescription = "End trip",
            onCross = onEndTrip,
        )
        if (expanded) {
            TripStatsRows(stats, state)
            Column(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchIsland(
                    open = whereTo.open,
                    onOpenChange = whereTo.onOpenChange,
                    username = whereTo.username,
                    onAvatarClick = whereTo.onAvatarClick,
                    onPick = whereTo.onPick,
                    // Weighted so the results, which grow upward out of the
                    // bar, give way to the bar rather than the other way round.
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Nothing below the bar survives a search, as in the home sheet.
                if (!whereTo.open) {
                    // A route with no destination is a loop spin, and its Go is
                    // this one: without it, a loop spun before the trip started
                    // had no way to be navigated until End trip.
                    if (go.destination != null || go.route != null) {
                        ResultCallout(
                            title = go.destinationName ?: "Loop found",
                            subtitle = go.route?.distanceMeters?.let { formatDistanceKm(it) },
                            onRespin = null,
                        )
                        NavButton(
                            destination = go.destination,
                            route = go.route?.waypoints,
                            origin = go.origin,
                            mode = go.mode,
                            inAppAvailable = go.inAppAvailable,
                            onNavigateInApp = go.onNavigateInApp,
                            onNavigate = go.onNavigate,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    EndButton(
                        label = "End trip",
                        icon = Icons.Rounded.Stop,
                        onClick = onEndTrip,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Turn-by-turn. Closed: arrival over what is left, on top of the route's
 *  progress track. Open: the destination, the trip's stats, and both ways out. */
@Composable
internal fun NavSheet(
    toggle: SheetToggle,
    state: NavState,
    destinationName: String?,
    stats: TripStats?,
    onEndNavigation: () -> Unit,
    onEndTrip: () -> Unit,
) {
    val expanded = toggle.expanded
    RideSheetCard(toggle) {
        RouteProgressTrack(state.progressFraction, Modifier.fillMaxWidth())
        if (expanded) DragHandle()
        RideSheetHeader(
            // arrivalText is "" before the first fix; the banner's headline
            // already says "Waiting for GPS…" / "Rerouting…" for that case.
            headline = state.arrivalText.ifEmpty { state.headlineText },
            subline = state.remainingText,
            isError = state.offRoute,
            crossDescription = "End navigation",
            onCross = onEndNavigation,
        )
        if (expanded) {
            destinationName?.let { name ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.LocationOn,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
            stats?.let { TripStatsRows(it, rememberActiveTripCardState(it)) }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "End route", not "End navigation": the longer label does not
                // fit half a row on a 360 dp phone. The cross above keeps the
                // fuller phrase for a screen reader.
                EndButton(
                    label = "End route",
                    icon = Icons.Rounded.Close,
                    onClick = onEndNavigation,
                    modifier = Modifier.weight(1f),
                )
                EndButton(
                    label = "End trip",
                    icon = Icons.Rounded.Stop,
                    onClick = onEndTrip,
                    modifier = Modifier.weight(1f),
                    enabled = stats != null,
                )
            }
        }
    }
}

/** The glass card both ride sheets sit in. The whole card toggles: the
 *  cross, the buttons and the search bar inside it take their own taps first,
 *  and everything else is the handle. */
@Composable
private fun RideSheetCard(
    toggle: SheetToggle,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = toggle.onToggle,
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(MaterialTheme.shapes.extraLarge)
            .semantics {
                contentDescription =
                    if (toggle.expanded) "Collapse ride sheet" else "Expand ride sheet"
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(content = content)
    }
}

/** The closed strip: one bold number, one quiet line, and the cross. The
 *  cross is the same error-tinted outline the nav bar's End pill had, minus
 *  the word — at speed the glyph is the label. */
@Composable
private fun RideSheetHeader(
    headline: String,
    subline: String,
    isError: Boolean,
    crossDescription: String,
    onCross: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        OutlinedIconButton(
            onClick = onCross,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = crossDescription },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(20.dp))
        }
    }
}

/** An outlined, error-tinted action for the open sheets' explicit ways out. */
@Composable
private fun EndButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        // Half the default: two of these share a row, and "End navigation"
        // wrapped its second word out of existence at the default 24 dp.
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The live trip's display state, re-derived once a second so the elapsed
 * time counts up even without GPS updates. The tick is a UI concern and stays
 * here; the clock goes *into* the mapper as `nowMs` rather than being read
 * inside a formatter.
 */
@Composable
internal fun rememberActiveTripCardState(stats: TripStats): ActiveTripCardState {
    var now by remember { mutableLongStateOf(ReplayClock.nowMs()) }
    LaunchedEffect(stats.startTimeMs) {
        while (true) {
            now = ReplayClock.nowMs()
            delay(1000)
        }
    }
    // The whole Android-shaped part of the readout: unpacking TripStats. The
    // service records metres per second and the mapper renders km/h, and the
    // sheet wants the trip's peak g, not the g of the current corner.
    return activeTripCardStateFrom(
        startTimeMs = stats.startTimeMs,
        nowMs = now,
        distanceMeters = stats.distanceMeters,
        topSpeedKmh = stats.topSpeedMps * 3.6,
        leanDeg = stats.currentLeanAngleDeg,
        maxLeanDeg = stats.maxLeanAngleDeg,
        maxGForce = stats.maxGForce,
        hardEvents = stats.hardBrakeCount + stats.hardAccelCount + stats.hardCornerCount,
        stopCount = stats.stopCount,
        currentlyOverLimit = stats.currentlyOverLimit,
        // Resolved on the render path and passed down, as Format.kt does it.
        sep = Settings.decimalSeparatorChar(),
    )
}

/** Live trip numbers, minus the ones already on screen: current speed is the
 *  HUD, and a car has no lean angle worth printing. */
@Composable
internal fun TripStatsRows(stats: TripStats, state: ActiveTripCardState) {
    Column(Modifier.fillMaxWidth()) {
        // Three weighted columns per row, not six unweighted ones in one:
        // on a moto trip the single row ran together ("3:1419,9 km132 km/h")
        // and wrapped "Max G" onto three lines at 1080 px.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatItem("Time", state.durationText, Modifier.weight(1f))
            StatItem("Distance", state.distanceText, Modifier.weight(1f))
            StatItem("Top", state.topSpeedText, Modifier.weight(1f))
        }
        if (stats.mode.tracksLean || stats.mode.tracksGForce) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (stats.mode.tracksLean) {
                    StatItem("Lean", state.leanText, Modifier.weight(1f))
                    StatItem("Max lean", state.maxLeanText, Modifier.weight(1f))
                }
                if (stats.mode.tracksGForce) {
                    StatItem("Max G", state.maxGForceText, Modifier.weight(1f))
                }
            }
        }
        if (state.detailsShown) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (stats.hardBrakeCount > 0) StatItem("Hard brakes", "${stats.hardBrakeCount}")
                if (stats.hardAccelCount > 0) StatItem("Hard accel", "${stats.hardAccelCount}")
                if (stats.hardCornerCount > 0) StatItem("Hard corners", "${stats.hardCornerCount}")
                if (stats.stopCount > 0) StatItem("Stops", "${stats.stopCount}")
                if (stats.currentlyOverLimit) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Speed", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Over limit", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "Not a score to chase — these numbers are informational only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            maxLines = 1)
    }
}
