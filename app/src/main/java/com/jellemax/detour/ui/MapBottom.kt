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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.GeocodeResult
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
import com.jellemax.detour.presentation.HomeBottomCard
import com.jellemax.detour.presentation.NavState
import com.jellemax.detour.presentation.inAppNavAvailable
import com.jellemax.detour.presentation.spinStateFrom
import com.jellemax.detour.tracking.TripStats

/**
 * Everything that sits along the bottom edge of the map: the end-trip button,
 * the active-trip card, and the single slot that the navigation bar, the
 * candidates pane, the home sheet and the spin sheet take turns occupying.
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
    destination: LatLon?,
    destinationName: String?,
    route: RouteResult?,
    myLocation: LatLon?,
    serverConfig: ServerConfig,
    username: String,
    onOpenHub: () -> Unit,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onPickDestination: (GeocodeResult) -> Unit,
    onPickPlace: (SavedPlace) -> Unit,
    onSavePin: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenSocial: () -> Unit,
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
    onSpin: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    onTrack: () -> Unit,
) {
    Column(
        Modifier
            // Full height with its content stacked at the bottom, rather than a
            // wrap-height Column pinned there. That is what gives the home sheet
            // a bounded height to take a weight against — without it the search
            // results, which now grow *upward* out of the sheet's bar, have
            // nothing to be clipped by and run off the top of the screen.
            .fillMaxSize()
            // Consumed here so the same results can never reach under the
            // status bar either. The gesture inset is not consumed here: see
            // the AnimatedContent below, where it differs per occupant.
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
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
            modifier = Modifier.padding(horizontal = 12.dp),
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
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            shownStats.value?.let { ActiveTripCard(it) }
        }

        // bottomCard is decided once, up in MapScreen; animate the handover
        // here instead of hard-swapping so the bottom of the screen stops
        // popping.
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
            // The home sheet runs to the bottom edge of the screen and consumes
            // the gesture inset inside its own surface, so it must not be given
            // it here as well. The other three float above that edge and still
            // take it as padding — the mode bar that used to carry it for them
            // is what left (#70), not the need for it.
            Box(
                if (card == HomeBottomCard.COLLAPSED) Modifier
                else Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                when (card) {
                    HomeBottomCard.NAV -> NavigationBottomBar(
                        state = navState,
                        onExit = onExitNavigation,
                    )
                    HomeBottomCard.CANDIDATES -> CandidatesCard(
                        candidates = shownCandidates.value,
                        // mode/radiusKm/directionDeg feed spinStateFrom's other
                        // readouts too (SpinSheet, below in this when-chain) -
                        // reused here only for .candidates, the per-row
                        // name/distance/duration text this card renders.
                        rows = spinStateFrom(
                            mode, radiusKm, directionDeg, shownCandidates.value,
                            Settings.decimalSeparatorChar(),
                        )
                            .candidates,
                        onPick = onPickCandidate,
                        onReroll = onReroll,
                        onCancel = onCancelCandidates,
                        // Non-null swaps the card's Reroll button for the
                        // vote tally; see the call site for when that is.
                        convoyVotes = convoyVotes,
                        members = activeConvoyMembers,
                        onShare = onShare,
                        // Null on every device but the sharer's, so only one
                        // of them draws a button that closes the round.
                        onGoWithLead = onGoWithLead,
                    )
                    HomeBottomCard.COLLAPSED -> Unit
                    HomeBottomCard.EXPANDED -> SpinSheet(
                        mode = mode,
                        onSelectMode = onSelectMode,
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

        // Outside the AnimatedContent, and that is deliberate: the sheet owns a
        // text field and its results, so cross-fading it in and out would drop
        // a half-typed query and the keyboard with it every time a spin landed.
        // It is the resting occupant of the slot above, hence the same test.
        if (bottomCard == HomeBottomCard.COLLAPSED) {
            HomeSheet(
                username = username,
                onOpenHub = onOpenHub,
                searchOpen = searchOpen,
                onSearchOpenChange = onSearchOpenChange,
                onPickDestination = onPickDestination,
                savedPlaces = savedPlaces,
                onPickPlace = onPickPlace,
                canSavePin = destination != null,
                onSavePin = onSavePin,
                mode = mode,
                onSpinSettings = onExpand,
                onOpenRoutes = onOpenRoutes,
                onOpenSocial = onOpenSocial,
            )
        }
    }
}
