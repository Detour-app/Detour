package com.jellemax.detour.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Map top chrome: a right-aligned rail of the controls worth reaching for
 *  while driving (follow toggle, fog of war, and a compass while the map is free to
 *  hold a rotation), headed by the convoy pill.
 *  Everything else moved to the Hub or, with the redesign, to the home sheet —
 *  the "Where to?" bar and the avatar included.
 *
 *  Everything here is End-aligned, the pill included. The speed island is drawn
 *  by `MapScreen` from the same origin — top inset plus the same 12 dp — and
 *  Start-aligned, so anything this chrome puts at its own Start would sit on
 *  top of the island rather than beside it. Keeping the pill in the rail's
 *  column is what makes that impossible by construction, instead of by a top
 *  offset on the island that has to be re-derived whenever this column's first
 *  child changes. */
@Composable
internal fun MapTopChrome(
    followMe: Boolean,
    convoyName: String?,
    layers: MapLayers,
    onToggleFollow: () -> Unit,
    // Non-null exactly while the camera is idle enough for a bearing to stay
    // put — `CameraAuthority.State.northUpAvailable`. One nullable callback
    // rather than a flag beside a lambda, matching `onShare` in MapScreen.
    onFaceNorth: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        // End-aligned because the convoy pill is wider than the 40.dp
        // buttons: without this the column widens to the pill and centres
        // the buttons in it, shifting them off the rail.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(visible = convoyName != null, enter = fadeIn(), exit = fadeOut()) {
                ConvoyPill(name = convoyName ?: "")
            }
            GlassRailButton(
                icon = if (followMe) Icons.Outlined.MyLocation
                    else Icons.Outlined.LocationSearching,
                contentDescription = if (followMe) "Stop following my location"
                    else "Follow my location",
                tinted = followMe,
                onClick = onToggleFollow,
            )
            // The map keeps whatever rotation it has once the camera parks
            // (#260), so this is how a rider gets back to north — deliberately,
            // instead of having it done for them by the next pinch. Hidden
            // while the follow loop is aiming the camera, because it rewrites
            // the bearing every frame and a level would not survive the tap.
            AnimatedVisibility(
                visible = onFaceNorth != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                GlassRailButton(
                    icon = Icons.Outlined.Explore,
                    contentDescription = "Face north",
                    onClick = { onFaceNorth?.invoke() },
                )
            }
            // A straight toggle rather than a "Layers" button opening a panel
            // with one switch in it: the fog is the only layer there is, so
            // the panel was a second tap and a card over the map for nothing.
            GlassRailButton(
                icon = if (layers.fogEnabled) Icons.Outlined.Visibility
                    else Icons.Outlined.VisibilityOff,
                contentDescription = if (layers.fogEnabled) "Hide fog of war"
                    else "Show fog of war",
                tinted = layers.fogEnabled,
                onClick = layers.onToggleFog,
            )
        }
    }
}

/** Small pill at the head of the top-right rail naming the convoy this device
 *  is currently live in, i.e. whenever [ConvoyLiveClient.connected] is true.
 *
 *  Single-line: the name comes off the server with no length cap, and a wrapped
 *  one would both grow the pill downwards into the rail and widen it across the
 *  map towards the speed island on the opposite corner. */
@Composable
private fun ConvoyPill(name: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.glassBorder(CircleShape),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = glassContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One 40dp glass button in the top-right rail; tinted primary when its
 *  toggle is active (currently just the follow button). */
@Composable
private fun GlassRailButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tinted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.size(40.dp).glassBorder(CircleShape),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = glassContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon, contentDescription = contentDescription,
                Modifier.size(20.dp),
                tint = if (tinted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
