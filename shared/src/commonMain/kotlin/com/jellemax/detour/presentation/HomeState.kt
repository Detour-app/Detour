package com.jellemax.detour.presentation

import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaceKind

/** What currently occupies the map screen's single bottom-card slot. */
enum class HomeBottomCard { NAV, CANDIDATES, DRIVE, DESTINATION, COLLAPSED, EXPANDED }

/**
 * Picks the one card that occupies the map's bottom slot — first match wins.
 *
 * `MapScreen.kt` used to ask these questions twice, ~1200 lines apart: once
 * for the card itself and once as `dockShown`, the flag the mode-swipe hint
 * waited on. Drift between the two armed the hint against a bottom card that
 * wasn't on screen, so it fired on that card's very next composition — as
 * part of the screen arriving, which is the one thing the hint's delay existed
 * to prevent. The hint and the dock it taught are both gone; this stayed, as
 * the one place the slot's occupant is decided.
 *
 * [tripActive] is a trip being recorded with no route to follow — it takes the
 * slot as the drive sheet. It ranks below [hasCandidates] so a convoy vote
 * round still surfaces on a moving phone.
 *
 * [hasDestination] is a concrete point to go to — a searched place, a saved
 * chip, a dropped pin. It takes the slot as the navigation dock (#254): the
 * destination, a Moto/Car toggle, and Start, with none of the spin sheet's
 * discovery controls. It ranks below [tripActive] so a destination set
 * mid-trip stays the drive sheet's own Where-to concern rather than swapping
 * a moving rider's stats for the dock, and above [collapsed] because a known
 * destination is exactly what should displace the idle home/spin sheet. A
 * loop spin nulls the destination, so it falls through to the spin sheet.
 */
fun homeBottomCard(
    navigating: Boolean,
    hasCandidates: Boolean,
    tripActive: Boolean,
    hasDestination: Boolean,
    collapsed: Boolean,
): HomeBottomCard = when {
    navigating -> HomeBottomCard.NAV
    hasCandidates -> HomeBottomCard.CANDIDATES
    tripActive -> HomeBottomCard.DRIVE
    hasDestination -> HomeBottomCard.DESTINATION
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
 * hidden with no fix to centre it on, and hidden once a concrete destination
 * is set: the circle shows where a random spin *could* land, so it is clutter
 * the moment you have somewhere specific to go (a searched place, a saved chip,
 * a dropped pin, a picked candidate). A loop spin leaves the destination null,
 * so its reach still draws.
 */
fun reachMeters(
    hasLocation: Boolean,
    navigating: Boolean,
    hasDestination: Boolean,
    roundTrip: Boolean,
    radiusKm: Double,
): Double? = when {
    !hasLocation || navigating || hasDestination -> null
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

/**
 * The saved places the home sheet's shortcut row shows: Home, then Work, then
 * the rider's favourites in the store's order. The two singletons come from the
 * places marked [SavedPlaceKind.HOME] / [SavedPlaceKind.WORK] — so a home called
 * "Huis" still ranks first and draws the house glyph — and every favourite
 * follows, in the order the store already sorts them (lowercased name).
 *
 * Nothing else appears: a plain [SavedPlaceKind.NONE] place is reachable from
 * the saved-places screen, not from this row. There is no randomness and no
 * seed — two visits to an unchanged store produce the identical row, which is
 * what #268 replaced the #204 dice with. A rider with no home, work or
 * favourites gets no place chips at all, rather than a fallback to an arbitrary
 * place.
 *
 * The row is capped only by the sheet, not here: the chips scroll inside the
 * space Spin and Save-pin leave (see `ShortcutChipRow`), so a rider with many
 * favourites reaches the later ones by scrolling while those two stay on screen.
 */
fun homeShortcutPlaces(places: List<SavedPlace>): List<SavedPlace> =
    listOfNotNull(
        places.firstOrNull { it.kind == SavedPlaceKind.HOME },
        places.firstOrNull { it.kind == SavedPlaceKind.WORK },
    ) + places.filter { it.kind == SavedPlaceKind.FAVOURITE }
