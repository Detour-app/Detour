package com.jellemax.detour.presentation

import com.jellemax.detour.data.SavedPlace

/** One saved place as the list renders it. */
data class PlaceRow(
    val id: Long,
    val name: String,
    val subtitle: String,
)

/**
 * Marks whether the Saved places screen's initial disk read has completed.
 * [PlacesPresenter]'s KDoc explains why this is all `state` carries.
 */
data class PlacesState(
    val loaded: Boolean = false,
)

/**
 * Pure map from stored places to display rows. Returns the list directly, not
 * wrapped in [PlacesState] — [PlacesState] only tracks the initial-load flag,
 * which this mapper has no opinion on. See [PlacesPresenter]'s KDoc.
 *
 * The store already sorts by lowercased name, so this deliberately preserves
 * its order rather than sorting again — a second ordering here could disagree
 * with the map's shortcut chips, which read the same store.
 */
fun placesStateFrom(places: List<SavedPlace>): List<PlaceRow> = places.map { p ->
    PlaceRow(
        id = p.id,
        name = p.name,
        // Coordinates deliberately keep '.' whatever the rider's separator
        // setting says. This is a comma-separated PAIR: with a comma decimal it
        // would read "50,12345, 4,56789", which no one can parse and which is
        // wrong the moment it is copied or shared. Not an inconsistency to fix.
        subtitle = "${formatFixed(p.location.lat, 5, '.')}, ${formatFixed(p.location.lon, 5, '.')}",
    )
}
