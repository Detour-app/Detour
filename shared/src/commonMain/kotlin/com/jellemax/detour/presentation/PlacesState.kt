package com.jellemax.detour.presentation

import com.jellemax.detour.data.SavedPlace

/** One saved place as the list renders it. */
data class PlaceRow(
    val id: Long,
    val name: String,
    val subtitle: String,
)

/** Everything the Saved places screen renders. */
data class PlacesState(
    val loaded: Boolean = false,
    val rows: List<PlaceRow> = emptyList(),
)

/**
 * Pure map from stored places to display rows. The store already sorts by
 * lowercased name, so this deliberately preserves its order rather than sorting
 * again — a second ordering here could disagree with the map's shortcut chips,
 * which read the same store.
 */
fun placesStateFrom(places: List<SavedPlace>): PlacesState = PlacesState(
    loaded = true,
    rows = places.map { p ->
        PlaceRow(
            id = p.id,
            name = p.name,
            subtitle = "${formatFixed(p.location.lat, 5)}, ${formatFixed(p.location.lon, 5)}",
        )
    },
)
