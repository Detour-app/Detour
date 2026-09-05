package com.jellemax.detour.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.ModeSwipePolicy
import com.jellemax.detour.presentation.HomeBottomCard
import com.jellemax.detour.presentation.NavState
import com.jellemax.detour.presentation.inAppNavAvailable
import com.jellemax.detour.presentation.shortcutChipsShown
import com.jellemax.detour.presentation.spinStateFrom
import com.jellemax.detour.tracking.TripStats

/**
 * Everything that sits along the bottom edge of the map: the end-trip button,
 * the active-trip card, the shortcut chips, and the single card slot that the
 * navigation bar, the candidates pane, the spin dock and the spin sheet take
 * turns occupying.
 *
 * Stateless by construction. Every `remember`, every effect and every write
 * that decides what belongs here stays with the map screen; this takes the
 * decided values in and reports events back out. The two `remember`s below are
 * the deliberate exception — they hold nothing anyone else could use, and
 * exist only so a card that is animating *out* still has something to draw.
 *
 * A `BoxScope` extension so the slot keeps owning its own alignment and insets
 * rather than having them handed down as a modifier the caller could get wrong.
 */
@Composable
internal fun BoxScope.MapBottomSlot(
    stats: TripStats?,
    onEndTrip: () -> Unit,
    savedPlaces: List<SavedPlace>,
    navigating: Boolean,
    destination: LatLon?,
    destinationName: String?,
    route: RouteResult?,
    myLocation: LatLon?,
    serverConfig: ServerConfig,
    onPickPlace: (SavedPlace) -> Unit,
    onSavePin: () -> Unit,
    bottomCard: HomeBottomCard,
    navState: NavState,
    onExitNavigation: () -> Unit,
    displayCandidates: List<RouteCandidate>,
    convoyVotes: Map<RiderId, Int>?,
    activeConvoyMembers: List<GroupMember>,
    onPickCandidate: (Int, RouteCandidate) -> Unit,
    onReroll: () -> Unit,
    onCancelCandidates: () -> Unit,
    onShare: (() -> Unit)?,
    onGoWithLead: (() -> Unit)?,
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
    switchBlockedReason: String?,
    onSwitchMode: (TravelMode) -> Unit,
    onSwitchBlocked: (String) -> Unit,
    hintRequest: Boolean,
    swipeHintVariantName: String,
    onHintPlayed: () -> Unit,
    onSpin: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    onTrack: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // Unconditional since the mode bar left: nothing else in this
            // Scaffold consumes the gesture inset any more. Correct for all
            // three occupants of this Column - the nav bar always wanted it,
            // and the candidates card and the dock were relying on the mode
            // bar this change removed (#70).
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Ending a trip used to mean expanding the spin card and hunting
        // for a button. It now sits here whatever else is on screen, in
        // the bottom corner your thumb rests in — start-aligned by this
        // Column, which is where the row that used to hold it put it.
        // That row's SpaceBetween existed only to pin the speed HUD to
        // the far end, and the HUD has gone to the top-left island.
        AnimatedVisibility(
            visible = stats != null,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            EndTripButton(onClick = onEndTrip)
        }

        // The exiting card still composes for a few frames after `stats`
        // goes null; keep the last value so it animates out with content.
        val shownStats = remember { mutableStateOf(stats) }
        if (stats != null) shownStats.value = stats
        AnimatedVisibility(
            visible = stats != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            shownStats.value?.let { ActiveTripCard(it) }
        }

        // Shortcut chips: one-tap a saved place to set it as destination,
        // or save the pin you just dropped. Hidden while navigating.
        AnimatedVisibility(
            visible = shortcutChipsShown(
                navigating = navigating,
                hasSavedPlaces = savedPlaces.isNotEmpty(),
                hasDestination = destination != null,
            ),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ShortcutChips(
                places = savedPlaces,
                canSavePin = destination != null,
                onPick = onPickPlace,
                onSavePin = onSavePin,
            )
        }

        // bottomCard is decided once, up where dockShown reads it too;
        // animate the handover here instead of hard-swapping so the
        // bottom of the screen stops popping.
        //
        // Shared by the two cards that offer a "navigate" button, so
        // they cannot disagree about whether one is possible.
        val inAppAvailable = inAppNavAvailable(
            serverUsable = serverConfig.usable,
            hasDestination = destination != null,
            hasRouteInstructions = route?.instructions?.isNotEmpty() == true,
        )
        // Same trick as shownStats: the exiting candidates pane must
        // not render an empty card after a cancel clears the list.
        val shownCandidates = remember { mutableStateOf(displayCandidates) }
        if (displayCandidates.isNotEmpty()) shownCandidates.value = displayCandidates
        AnimatedContent(
            targetState = bottomCard,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 10 })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "bottomCard",
        ) { card ->
            when (card) {
                HomeBottomCard.NAV -> NavigationBottomBar(
                    state = navState,
                    onExit = onExitNavigation,
                )
                HomeBottomCard.CANDIDATES -> CandidatesCard(
                    candidates = shownCandidates.value,
                    // mode/radiusKm/directionDeg feed spinStateFrom's other
                    // readouts too (SpinDock/SpinSheet, elsewhere in this
                    // when-chain) - reused here only for .candidates, the
                    // per-row name/distance/duration text this card renders.
                    rows = spinStateFrom(
                        mode, radiusKm, directionDeg, shownCandidates.value,
                        Settings.decimalSeparatorChar(),
                    )
                        .candidates,
                    onPick = onPickCandidate,
                    onReroll = onReroll,
                    onCancel = onCancelCandidates,
                    // Non-null only once a spin has actually been shared - that's
                    // also what tells the card to show votes instead of Reroll.
                    convoyVotes = convoyVotes,
                    members = activeConvoyMembers,
                    onShare = onShare,
                    // The sharer's button only: closing the round is
                    // one device's call, same reason the auto-commit
                    // above is.
                    onGoWithLead = onGoWithLead,
                )
                HomeBottomCard.COLLAPSED -> SpinDock(
                    mode = mode,
                    radiusKm = radiusKm,
                    directionDeg = directionDeg,
                    spinning = spinning,
                    destination = destination,
                    route = route,
                    origin = myLocation,
                    inAppAvailable = inAppAvailable,
                    onSpin = onSpin,
                    onExpand = onExpand,
                    onNavigateInApp = onNavigateInApp,
                    onNavigate = onNavigate,
                    onSwitchMode = onSwitchMode,
                    switchBlockedReason = switchBlockedReason,
                    onSwitchBlocked = onSwitchBlocked,
                    hintRequest = hintRequest,
                    hintVariant = ModeSwipePolicy.HintVariant.of(swipeHintVariantName),
                    onHintPlayed = onHintPlayed,
                )
                HomeBottomCard.EXPANDED -> SpinSheet(
                    mode = mode,
                    radiusKm = radiusKm,
                    onRadiusChange = onRadiusChange,
                    minRadiusKm = minRadiusKm,
                    onMinRadiusChange = onMinRadiusChange,
                    poiKind = poiKind,
                    onPoiKindChange = onPoiKindChange,
                    directionDeg = directionDeg,
                    onDirectionChange = onDirectionChange,
                    spinning = spinning,
                    error = error,
                    route = route,
                    destinationName = destinationName,
                    destination = destination,
                    origin = myLocation,
                    stats = stats,
                    inAppAvailable = inAppAvailable,
                    onSpin = onSpin,
                    onCollapse = onCollapse,
                    onNavigateInApp = onNavigateInApp,
                    onNavigate = onNavigate,
                    onTrack = onTrack,
                )
            }
        }
    }
}
