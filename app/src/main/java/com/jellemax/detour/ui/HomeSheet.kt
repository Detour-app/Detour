package com.jellemax.detour.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.SavedPlace

/**
 * How tall the sheet stands, excluding the gesture inset it consumes inside
 * itself. Not measured — added up from what it draws, the way the fitted-camera
 * padding beside it is:
 *
 * ```
 *  26  drag handle      10 top + 4 handle + 12 bottom
 *  40  "Where to?" bar  SearchIsland's pill: 6 + 28 avatar + 6
 *  44  chip row         12 gap + 32 AssistChip
 *  86  card row         14 gap + 12 + 26 icon + 6 + label + 12
 *  14  bottom padding
 * ---
 * 210, plus headroom for the two labels at a large font scale.
 * ```
 *
 * Read by [rememberRetainedMap] to keep the basemap's attribution above the
 * sheet, which is a licence obligation rather than a cosmetic choice — so
 * re-derive this when the sheet's contents change, and prefer overshooting.
 */
internal val HOME_SHEET_HEIGHT = 224.dp

/** The prototype's `rgba(22,25,17,.96)` has no exact token. It sits between
 *  `surfaceContainerLowest` (0xFF101309) and `surfaceContainerLow` (0xFF1A1E15);
 *  the latter is the nearer of the two per channel, so that is the one used. */
private const val SHEET_ALPHA = 0.96f

/**
 * The map's idle home state: a bottom sheet carrying everywhere you might want
 * to go next — search, your saved places, the spin settings, Routes and Social.
 *
 * Replaces the spin dock, which was the map's resting bottom card and also the
 * phone's only travel-mode switch. That switch is gone with it; the mode still
 * reaches the tracking service through `Settings.tripMode`, which a saved
 * route's mode still writes.
 *
 * A `ColumnScope` extension, and it must be composed inside a Column whose
 * height is bounded: the sheet takes `weight(1f, fill = false)` so that, with
 * the keyboard up and the search results expanding *upward* out of the bar,
 * the results shrink instead of pushing the bar off the top of the screen.
 *
 * @param canSavePin whether there is a dropped pin to save. The `Save pin` chip
 *   is drawn either way — the prototype's `+` is unconditional — and disabled
 *   rather than hidden when there is nothing to save.
 */
@Composable
internal fun ColumnScope.HomeSheet(
    username: String,
    onOpenHub: () -> Unit,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onPickDestination: (GeocodeResult) -> Unit,
    savedPlaces: List<SavedPlace>,
    onPickPlace: (SavedPlace) -> Unit,
    canSavePin: Boolean,
    onSavePin: () -> Unit,
    onSpinSettings: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenSocial: () -> Unit,
) {
    Surface(
        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = SHEET_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            Modifier
                // The sheet runs to the very bottom edge, so the gesture inset
                // is consumed in here rather than by the slot above (#70's
                // padding, moved rather than dropped). Unioned with the IME so
                // the keyboard lifts the whole sheet — bar, results and all —
                // instead of covering the results that expand out of it.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp)
                .padding(bottom = 14.dp),
        ) {
            DragHandle()
            SearchIsland(
                open = searchOpen,
                onOpenChange = onSearchOpenChange,
                username = username,
                onAvatarClick = onOpenHub,
                onPick = onPickDestination,
                // Weighted so the results, which now grow upward out of the
                // bar, give way to the bar rather than the other way round.
                modifier = Modifier.weight(1f, fill = false),
            )
            // Nothing below the bar survives a search: the results need the
            // room, and neither a shortcut nor a card is worth reaching for
            // with a half-typed query on screen.
            if (!searchOpen) {
                ShortcutChipRow(
                    places = savedPlaces,
                    canSavePin = canSavePin,
                    onPick = onPickPlace,
                    onSavePin = onSavePin,
                    onSpinSettings = onSpinSettings,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DestinationCard(
                        icon = Icons.Outlined.Route,
                        label = "Routes",
                        onClick = onOpenRoutes,
                        modifier = Modifier.weight(1f),
                    )
                    DestinationCard(
                        icon = Icons.Outlined.Diversity3,
                        label = "Social",
                        onClick = onOpenSocial,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Decoration, not a control: the sheet has one height, so there is nothing to
 *  drag. It says "this is a sheet, and the map continues above it" — hence the
 *  cleared semantics, so a screen reader is not offered a handle that moves
 *  nothing. */
@Composable
private fun DragHandle() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 12.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
        )
    }
}

/**
 * One-tap a saved place, open the spin settings, or save the pin you just
 * dropped. Scrolls horizontally when the places overflow, as the chips over the
 * map used to.
 */
@Composable
private fun ShortcutChipRow(
    places: List<SavedPlace>,
    canSavePin: Boolean,
    onPick: (SavedPlace) -> Unit,
    onSavePin: () -> Unit,
    onSpinSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        places.forEach { p ->
            AssistChip(
                onClick = { onPick(p) },
                label = { Text(p.name, maxLines = 1) },
                leadingIcon = {
                    Icon(p.glyph, contentDescription = null, Modifier.size(18.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
        }
        // The dice moved into the spin sheet with the dock, so this is now the
        // only way to reach it — and, with it, the only way to roll a spin.
        AssistChip(
            onClick = onSpinSettings,
            label = { Text("Spin", fontWeight = FontWeight.SemiBold) },
            leadingIcon = {
                Icon(Icons.Outlined.Casino, contentDescription = null, Modifier.size(18.dp))
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                labelColor = MaterialTheme.colorScheme.primary,
                leadingIconContentColor = MaterialTheme.colorScheme.primary,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
        )
        AssistChip(
            onClick = onSavePin,
            enabled = canSavePin,
            label = { Text("Save pin") },
            leadingIcon = {
                Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

/** Which glyph a saved place gets. Matched on the name, because [SavedPlace]
 *  carries no icon or type and a field added purely to pick a glyph would be a
 *  schema change for a decoration. Anything unrecognised keeps the pin the
 *  chips over the map always drew. */
private val SavedPlace.glyph: ImageVector
    get() = when {
        name.equals("home", ignoreCase = true) -> Icons.Outlined.Home
        name.equals("work", ignoreCase = true) -> Icons.Outlined.Work
        else -> Icons.Outlined.Place
    }

/** One of the two cards along the bottom of the sheet. Convoys is deliberately
 *  absent: there is no convoy destination to open, and the prototype's badge
 *  colour is not in this theme. */
@Composable
private fun DestinationCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
