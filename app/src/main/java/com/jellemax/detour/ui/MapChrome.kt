package com.jellemax.detour.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Map top chrome: a full-width search pill with an avatar that opens the Hub,
 *  and a right-aligned rail of the two controls worth reaching for while
 *  driving (follow toggle, layers). Everything else moved to the Hub. */
@Composable
internal fun MapTopChrome(
    followMe: Boolean,
    fogEnabled: Boolean,
    username: String,
    convoyName: String?,
    // Hoisted to MapScreen so a tap on the map can close the panel — the job
    // the Popup's dismissOnClickOutside used to do.
    layersOpen: Boolean,
    onLayersOpenChange: (Boolean) -> Unit,
    onToggleFollow: () -> Unit,
    onSearch: () -> Unit,
    onToggleFog: () -> Unit,
    onOpenHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchPill(username = username, onSearch = onSearch, onAvatarClick = onOpenHub)
        AnimatedVisibility(visible = convoyName != null, enter = fadeIn(), exit = fadeOut()) {
            ConvoyPill(name = convoyName ?: "")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // End-aligned because the layers panel below is wider than the 40.dp
            // buttons: without this the column widens to the card and centres
            // the buttons in it, shifting them off the rail.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                GlassRailButton(
                    icon = if (followMe) Icons.Outlined.MyLocation else Icons.Outlined.LocationSearching,
                    contentDescription = if (followMe) "Stop following my location"
                        else "Follow my location",
                    tinted = followMe,
                    onClick = onToggleFollow,
                )
                GlassRailButton(
                    icon = Icons.Outlined.Layers,
                    contentDescription = "Map layers",
                    tinted = layersOpen,
                    onClick = { onLayersOpenChange(!layersOpen) },
                )
                // Inline rather than a Popup on purpose. A Popup is its own
                // window, so the button sitting outside it counted as an
                // outside-click *and* still ran its own onClick: the panel
                // closed and reopened on the same tap, and the button could
                // never close it. One window, one handler, and the toggle is
                // correct by construction.
                AnimatedVisibility(visible = layersOpen, enter = fadeIn(), exit = fadeOut()) {
                    Card(
                        modifier = Modifier.glassBorder(MaterialTheme.shapes.large),
                        shape = MaterialTheme.shapes.large,
                        colors = glassCardColors(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (fogEnabled) Icons.Outlined.Visibility
                                    else Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                            )
                            Text("Fog of war", modifier = Modifier.weight(1f))
                            Switch(checked = fogEnabled, onCheckedChange = { onToggleFog() })
                        }
                    }
                }
            }
        }
    }
}

/** Full-width glass search pill: tapping the body opens search, tapping the
 *  avatar opens the Hub. */
@Composable
private fun SearchPill(
    username: String,
    onSearch: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().glassBorder(CircleShape),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = glassContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSearch)
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                "Where to?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** Small pill under [SearchPill] naming the convoy this device is currently
 *  live in, i.e. whenever [ConvoyLiveClient.connected] is true. */
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
            Text(name, style = MaterialTheme.typography.bodyMedium)
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
