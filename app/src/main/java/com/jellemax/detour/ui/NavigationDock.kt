package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.TravelMode

/**
 * The bottom card for a rider who already has somewhere to go — a searched
 * place, a saved-place chip, a dropped pin (#254). Everything the spin sheet
 * is for once a concrete destination exists is noise here: the radius slider,
 * the direction dial, the POI-kind row and the Spin button all answer a
 * question this rider has already answered. So this shows only the three
 * things that are still live — where, on what, and go.
 *
 * The same glass card as [SpinSheet], and [ResultCallout] / [ChoiceRow] /
 * [NavButton] are the same components the spin and drive sheets draw, so the
 * dock reads as the same surface with fewer controls rather than a new one.
 *
 * Stateless. [go] is the same [GoTarget] the drive sheet takes; reusing it
 * keeps the destination, the route, the mode and both Start paths behind one
 * value and this composable under the parameter gate.
 *
 * [error] is the map screen's one error string, shown the same way the spin
 * sheet shows it. While this dock is up the realistic writer is
 * `startNavigation()` — "Waiting for your location…", a failed fetch — so a
 * tap on Start that cannot proceed says why instead of doing nothing.
 *
 * The loop case never reaches here — a loop spin nulls the destination, so
 * [homeBottomCard] keeps it in the spin sheet, where the loop's own
 * [ResultCallout] and [NavButton] already live.
 */
@Composable
internal fun NavigationDock(
    go: GoTarget,
    error: String?,
    onSelectMode: (TravelMode) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Navigation, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Navigate to",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear destination",
                    )
                }
            }

            // The router only reports distance/time once a route is back —
            // startNavigation()'s fetch, or a route seeded from RoutesScreen
            // or a convoy. Until then the callout is just the name; the pin
            // and the orange border still say "this is where you're headed".
            // formatDurationHistory ("25 min" / "1 h 12 min") is the wording a
            // route ETA takes elsewhere — the routes list, the nav sheet's
            // "… min left" — not the M:SS clock the live trip card uses.
            ResultCallout(
                title = go.destinationName ?: "Destination",
                subtitle = go.route?.let { route ->
                    listOfNotNull(
                        route.distanceMeters?.let { formatDistanceKm(it) },
                        route.timeMs?.let { formatDurationHistory(it) },
                    ).joinToString(" · ").ifEmpty { null }
                },
                onRespin = null,
            )

            Text("Mode", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                options = TravelMode.entries.map { it.label },
                selectedIndex = TravelMode.entries.indexOf(go.mode),
                onSelect = { onSelectMode(TravelMode.entries[it]) },
                modifier = Modifier.semantics {
                    contentDescription = "Travel mode, ${go.mode.label} selected"
                },
            )

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (go.inAppAvailable) {
                // The common case: one tap starts the trip recording and
                // in-app turn-by-turn together (startNavigation() does both),
                // and s.navigating flipping true hands the slot to NavSheet.
                Button(
                    onClick = go.onNavigateInApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(Icons.Outlined.Navigation, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Start", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            } else {
                // No usable routing server: fall back to the external nav app,
                // exactly as the spin and drive sheets do. NavButton owns the
                // remembered-app tap, the chooser and the launch; the trip
                // starts on go.onNavigate when it hands off.
                NavButton(
                    destination = go.destination,
                    route = go.route?.waypoints,
                    origin = go.origin,
                    mode = go.mode,
                    inAppAvailable = false,
                    onNavigateInApp = go.onNavigateInApp,
                    onNavigate = go.onNavigate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
