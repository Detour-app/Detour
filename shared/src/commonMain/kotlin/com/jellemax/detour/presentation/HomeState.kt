package com.jellemax.detour.presentation

import com.jellemax.detour.data.RouteCandidate

/** What currently occupies the map screen's single bottom-card slot. */
enum class HomeBottomCard { NAV, CANDIDATES, COLLAPSED, EXPANDED }

/**
 * Picks the one card that occupies the map's bottom slot — first match wins.
 *
 * `MapScreen.kt` used to ask these three questions twice, ~1200 lines apart:
 * once for the card itself and once as `dockShown`, the flag the mode-swipe
 * hint waits on. Drift between the two armed the hint against a dock that
 * wasn't on screen, so it fired on the dock's very next composition — as part
 * of the screen arriving, which is the one thing the hint's delay exists to
 * prevent. Both now read this, and `dockShown` is just the card being
 * [HomeBottomCard.COLLAPSED].
 */
fun homeBottomCard(
    navigating: Boolean,
    hasCandidates: Boolean,
    collapsed: Boolean,
): HomeBottomCard = when {
    navigating -> HomeBottomCard.NAV
    hasCandidates -> HomeBottomCard.CANDIDATES
    collapsed -> HomeBottomCard.COLLAPSED
    else -> HomeBottomCard.EXPANDED
}

/**
 * What the map and the candidates card actually show: my own spin's
 * candidates, unless a convoy spin is on the table, in which case everyone —
 * the sharer included — shows the same three from [offered] instead. Keeps
 * pins and votes pointed at the same coordinates on every device, even on a
 * phone that rolled no spin at all.
 *
 * An empty [offered] is still an offer and still wins; only a null one falls
 * through to [own].
 */
fun displayCandidates(
    offered: List<RouteCandidate>?,
    own: List<RouteCandidate>,
): List<RouteCandidate> = offered ?: own

/**
 * Whether in-app turn-by-turn can be offered: a usable routing server plus
 * somewhere to go — either a dropped destination or a route that already
 * carries instructions.
 */
fun inAppNavAvailable(
    serverUsable: Boolean,
    hasDestination: Boolean,
    hasRouteInstructions: Boolean,
): Boolean = serverUsable && (hasDestination || hasRouteInstructions)

/**
 * Radius of the reach circle drawn around you, in metres, or null to draw
 * none.
 *
 * For a round trip the slider means trip *length*, so the reach is about a
 * quarter of it. Hidden while navigating — the route is the answer by then —
 * and hidden with no fix to centre it on.
 */
fun reachMeters(
    hasLocation: Boolean,
    navigating: Boolean,
    roundTrip: Boolean,
    radiusKm: Double,
): Double? = when {
    !hasLocation || navigating -> null
    roundTrip -> radiusKm * 250.0
    else -> radiusKm * 1000.0
}

/**
 * Whether an OBD-2 adapter has fed *this* trip, from timestamps rather than a
 * per-trip accumulator, so it clears itself on reconnect.
 *
 * The data must have been seen after the trip started: the adapter's
 * last-data stamp is never reset, so a previous trip's adapter — since
 * unplugged — is not this trip's signal to report as lost.
 */
fun obd2FedThisTrip(tripStartMs: Long?, lastDataAtMs: Long?): Boolean =
    tripStartMs != null && lastDataAtMs != null && lastDataAtMs > tripStartMs

/**
 * Whether the shortcut chips (one-tap a saved place, or save the pin you just
 * dropped) are on screen. Hidden while navigating, and hidden when there is
 * neither a saved place to offer nor a pin to save.
 */
fun shortcutChipsShown(
    navigating: Boolean,
    hasSavedPlaces: Boolean,
    hasDestination: Boolean,
): Boolean = !navigating && (hasSavedPlaces || hasDestination)

/**
 * Whether the hold-to-talk button is on screen.
 *
 * Needs its own feature flag as well as a live relay: the rebuilt relay
 * carries positions and votes but drops voice frames, so a button shown on
 * that alone would transmit into nothing and read as a bug. The connection
 * can also be up for a circle's notify-only join with no convoy at all, hence
 * [hasActiveConvoy] on top of [convoyConnected].
 */
fun pushToTalkShown(
    featureEnabled: Boolean,
    convoyConnected: Boolean,
    hasActiveConvoy: Boolean,
): Boolean = featureEnabled && convoyConnected && hasActiveConvoy
