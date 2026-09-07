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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaceKind
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.presentation.homeShortcutPlaces

/**
 * How tall the sheet stands at `fontScale` 1, excluding the gesture inset it
 * consumes inside itself. Not measured — added up from what it draws, the way
 * the fitted-camera padding beside it is:
 *
 * ```
 *  26  drag handle      10 top + 4 handle + 12 bottom
 *  48  "Where to?" bar  SearchIsland's pill: its 48 dp trailing slot, which
 *                       holds a 40 dp avatar and sets the pill's height
 *  44  chip row         12 gap + 32 AssistChip
 *  92  card row         14 gap + 14 + 26 icon + 6 + 20 labelLarge + 12
 *  14  bottom padding
 * ---
 * 224, and 232 here to keep 8 dp of headroom over it.
 * ```
 *
 * Read by [rememberRetainedMap] to keep the basemap's attribution above the
 * sheet, which is a licence obligation rather than a cosmetic choice — so
 * re-derive this when the sheet's contents change, and prefer overshooting.
 */
internal val HOME_SHEET_HEIGHT = 232.dp

/**
 * How much taller the sheet gets per unit of `fontScale`. Only the text grows;
 * the handle, the paddings and the 26 dp card icon do not, so scaling
 * [HOME_SHEET_HEIGHT] as a whole would push the attribution ~180 dp up the map
 * at the largest accessibility setting to buy the ~48 dp actually needed.
 *
 * The three text runs that can grow the sheet, once each has overtaken the
 * fixed box it sits in: the 24 sp search field (past the 48 dp avatar slot), the
 * 20 sp chip label (past the 32 dp AssistChip minimum) and the 20 sp card
 * label, which grows from the start. 24 + 20 + 20 = 64 dp per unit — the
 * steepest the sheet ever grows, so `HOME_SHEET_HEIGHT + this * (fontScale -
 * 1)` stays at or above the real height at every scale, keeping the 8 dp of
 * headroom at scale 1 and at most ~24 dp more than needed at scale 2.
 */
internal val HOME_SHEET_FONT_SCALE_GROWTH = 64.dp

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
 * @param mode the travel mode a spin would roll under. A readout, not a
 *   control — the switch itself lives in `SpinSheet`, one tap away through the
 *   same chip, which is where the Spin chip's glyph comes from; see
 *   [ShortcutChipRow].
 */
@Composable
internal fun ColumnScope.HomeSheet(
    username: String,
    onOpenHub: () -> Unit,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onPickDestination: (GeocodeResult) -> Unit,
    savedPlaces: List<SavedPlace>,
    shortcutSeed: Int,
    onPickPlace: (SavedPlace) -> Unit,
    canSavePin: Boolean,
    onSavePin: () -> Unit,
    mode: TravelMode,
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
            // The seed is the slot's ([MapBottomSlot]), not this sheet's: the
            // sheet leaves the composition whenever the spin sheet or a
            // candidate round takes the slot, so a seed rolled here re-rolled
            // the third chip on every Spin round trip. Above the branch below
            // for the same reason at a smaller scale — opening the search bar
            // takes the row off screen, not the sheet. Leaving the map and
            // coming back is what re-rolls it.
            val shortcuts = remember(savedPlaces, shortcutSeed) {
                homeShortcutPlaces(savedPlaces, shortcutSeed)
            }
            // Nothing below the bar survives a search: the results need the
            // room, and neither a shortcut nor a card is worth reaching for
            // with a half-typed query on screen.
            if (!searchOpen) {
                ShortcutChipRow(
                    places = shortcuts,
                    canSavePin = canSavePin,
                    onPick = onPickPlace,
                    onSavePin = onSavePin,
                    mode = mode,
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
 *  nothing. The ride sheets (`RideSheet.kt`) draw the same bar when open, where
 *  the card around it is the control. */
@Composable
internal fun DragHandle() {
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
 * dropped.
 *
 * [places] is what [homeShortcutPlaces] selected — Home, Work and one other,
 * at most — not the whole store the map's chips used to render.
 *
 * The Spin chip still shows [mode], as its leading glyph rather than the
 * `Spin · Car` it used to spell out. The dock that used to show the mode went
 * with the mode switch into `SpinSheet`, and the idle map must say whether it
 * is about to roll a car route or a moto one — mode decides the radius default,
 * round-trip planning, the routing profile and whether lean and g-force get
 * recorded. The word was preferred to [TravelMode.icon] while it "cost
 * nothing"; with five chips to fit in 328 dp it costs 34 of them, which is the
 * premise that changed. The glyph keeps the mode on the idle sheet, the
 * segmented control it opens spells it out and announces it
 * (`SpinCards.kt`), and the dice it replaces was saying what the word `Spin`
 * beside it already says.
 */
@Composable
private fun ShortcutChipRow(
    places: List<SavedPlace>,
    canSavePin: Boolean,
    onPick: (SavedPlace) -> Unit,
    onSavePin: () -> Unit,
    mode: TravelMode,
    onSpinSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The places give way rather than push: they scroll inside whatever
        // the two chips after them leave, so Spin and Save pin are on screen
        // however long a saved name is. Capping the row at three places was
        // not enough on its own — an M3 chip spends 50 dp on chrome before a
        // character of label, so five of them only fit once Home and Work drop
        // their labels, the third place drops its glyph, Spin drops the mode
        // word and Save pin drops to its `+`. [PLACE_CHIP_MAX_WIDTH] carries
        // that arithmetic. The scroll stays as the overflow insurance for a
        // long name and for the largest font scales, where the two fixed chips
        // take the row and the places are the right thing to lose first.
        if (places.isNotEmpty()) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                places.forEach { p -> PlaceChip(p, onPick) }
            }
        }
        // The dice moved into the spin sheet with the dock, so this is now the
        // only way to reach it — and, with it, the only way to roll a spin.
        AssistChip(
            onClick = onSpinSettings,
            label = { Text("Spin", fontWeight = FontWeight.SemiBold, maxLines = 1) },
            leadingIcon = {
                Icon(mode.icon, contentDescription = mode.label, Modifier.size(18.dp))
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
        // The `+` alone. The words cost 54 dp the row does not have, and a
        // plus beside the place chips is the prototype's own affordance for
        // adding one; the name it lost is the icon's description, so a screen
        // reader still reads "Save pin". In the label slot rather than as a
        // leading icon, so it takes `labelColor` like the Home and Work glyphs
        // — a leading icon would default to `primary` (AssistChipTokens
        // .IconColor) and make this the second accent chip in the row.
        AssistChip(
            onClick = onSavePin,
            enabled = canSavePin,
            label = {
                Icon(Icons.Outlined.Add, contentDescription = "Save pin", Modifier.size(18.dp))
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

/**
 * How wide a named place chip may grow before its name ellipsizes.
 *
 * The row has to hold five chips inside the 328 dp a 360 dp screen leaves at
 * `fontScale` 1 (360 − 2 × 16 dp of sheet padding), and an `AssistChip` spends
 * **50 dp** on chrome before a character of label. That is two paddings, not
 * one: the chip pads itself 8 dp each side (`AssistChipPadding`, material3
 * 1.4.0 `Chip.kt:2958,2955`, applied at `:2059`) *and* the label slot pads
 * itself another 8 dp each side (`:2076`), with the measured width being the
 * plain sum of leading icon, padded label and trailing icon (`:2281`). So an
 * 18 dp glyph costs 16 + 18 + 16 whether it sits in the leading slot or is the
 * whole label, and a text-only chip still costs 16 + 16.
 *
 * ```
 *  50  Home             glyph, no label
 *  50  Work             glyph, no label
 *  60  the third place  32 + up to 28 dp of name — this constant
 *  80  Spin             50 + "Spin"
 *  50  Save pin         the `+` alone
 *  32  four 8 dp gaps
 * ---
 * 322, inside 328 at the longest name this allows.
 * ```
 *
 * The 42/34 dp this KDoc claimed before counted the chip's padding once and
 * missed the label slot's own, which is why the row it described as 316 dp
 * really drew about 372 and clipped the third chip's tail. The row before that
 * — three named place chips with glyphs, `Spin · Car` and `Save pin` — was
 * about 490 dp, so Spin and Save pin started off the right edge entirely.
 *
 * 28 dp of name is four or five characters, which is the honest ceiling for
 * five chips on a 360 dp screen; anything longer ellipsizes and the region
 * scrolls.
 */
private val PLACE_CHIP_MAX_WIDTH = 60.dp

/** A saved place's chip. Home and Work are the glyph alone: the house and the
 *  briefcase say exactly what those two words say, and the row cannot afford
 *  to say it twice — their name goes to the screen reader as the icon's
 *  description instead. Selected by the rider's [SavedPlaceKind], the same mark
 *  that puts them at the head of the row.
 *
 *  Every other place is its name alone, capped and ellipsized: the name is the
 *  only thing that identifies it, and the pin glyph that used to lead it said
 *  nothing while costing 18 dp of that name (see [PLACE_CHIP_MAX_WIDTH]).
 *  Dropping it also settles the tint — every glyph in the row now renders at
 *  `labelColor`, leaving `primary` to the one chip that means it. */
@Composable
private fun PlaceChip(place: SavedPlace, onPick: (SavedPlace) -> Unit) {
    val glyph = when (place.kind) {
        SavedPlaceKind.HOME -> Icons.Outlined.Home
        SavedPlaceKind.WORK -> Icons.Outlined.Work
        else -> null
    }
    AssistChip(
        onClick = { onPick(place) },
        modifier = Modifier.widthIn(max = PLACE_CHIP_MAX_WIDTH),
        label = {
            if (glyph != null) {
                Icon(glyph, contentDescription = place.name, Modifier.size(18.dp))
            } else {
                Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
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
