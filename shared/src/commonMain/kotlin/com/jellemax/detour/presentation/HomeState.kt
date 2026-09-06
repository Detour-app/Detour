package com.jellemax.detour.presentation

import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.SavedPlace

/** What currently occupies the map screen's single bottom-card slot. */
enum class HomeBottomCard { NAV, CANDIDATES, COLLAPSED, EXPANDED }

/**
 * Picks the one card that occupies the map's bottom slot — first match wins.
 *
 * `MapScreen.kt` used to ask these three questions twice, ~1200 lines apart:
 * once for the card itself and once as `dockShown`, the flag the mode-swipe
 * hint waited on. Drift between the two armed the hint against a bottom card
 * that wasn't on screen, so it fired on that card's very next composition — as
 * part of the screen arriving, which is the one thing the hint's delay existed
 * to prevent. The hint and the dock it taught are both gone; this stayed, as
 * the one place the slot's occupant is decided.
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

/** Home and Work are recognised by name, because [SavedPlace] carries no type
 *  and a field added to rank two chips would be a schema change for a shortcut
 *  row. The home sheet draws its house and briefcase glyphs off these same two
 *  predicates, so the place that ranks first is always the one that looks the
 *  part. */
val SavedPlace.isHome: Boolean get() = name.equals("home", ignoreCase = true)
val SavedPlace.isWork: Boolean get() = name.equals("work", ignoreCase = true)

/**
 * The saved places the home sheet's shortcut row shows: Home, then Work, then
 * one of the rest picked by [seed] — at most three. The row used to render the
 * whole store, so a rider with a dozen places got a dozen chips and pushed the
 * Spin and Save-pin chips off the right edge; the full list already has its own
 * screen, and this row is for the two that are always worth a tap plus one
 * suggestion.
 *
 * [seed] is an argument rather than a clock or an ambient `Random` so the
 * caller decides when the third chip re-rolls — once per visit to the map,
 * never per frame — and a test can pin it. Any [seed] is valid: it is reduced
 * modulo the number of candidates, negatives included.
 *
 * A second place also called Home is not a candidate for the third chip
 * either: it would read as the same shortcut twice.
 */
fun homeShortcutPlaces(places: List<SavedPlace>, seed: Int): List<SavedPlace> {
    val others = places.filterNot { it.isHome || it.isWork }
    return listOfNotNull(
        places.firstOrNull { it.isHome },
        places.firstOrNull { it.isWork },
        if (others.isEmpty()) null else others[seed.mod(others.size)],
    )
}
