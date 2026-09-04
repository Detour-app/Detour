package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.TravelMode

/** A point in a route thumbnail's own coordinate box, y growing downward. */
data class ThumbPoint(val x: Double, val y: Double)

/** One saved route as the list renders it. */
data class RouteCard(
    val id: Long,
    val name: String,
    val subtitle: String,
    val sharedBy: String,
    val polyline: List<LatLon>,
)

/** Everything the Routes screen renders. */
data class RoutesState(
    val loaded: Boolean = false,
    val cards: List<RouteCard> = emptyList(),
)

/**
 * "214 km · 5 stops · 25 min". [distanceMeters] and [timeMs] are both null
 * until a route has been routed at least once (an imported file may carry
 * only waypoints); whichever is missing is dropped rather than shown as
 * zero, so the parts compose cleanly with no stray separator either way.
 */
fun routeSubtitle(distanceMeters: Double?, stopCount: Int, timeMs: Long? = null): String {
    val stops = "$stopCount ${if (stopCount == 1) "stop" else "stops"}"
    val parts = buildList {
        distanceMeters?.let { add("${groupThousands((it / 1000.0).toLong())} km") }
        add(stops)
        timeMs?.let { add(formatDurationHistory(it)) }
    }
    return parts.joinToString(" · ")
}

/**
 * Google Maps' `maps/dir/` directions URL's `travelmode` parameter for a
 * route's own mode — NOT [TravelMode.gmapsMode]'s single-letter codes ("d"),
 * which only mean anything to the `google.navigation:q=` scheme MapScreen's
 * navigateGoogleMaps uses. Kept as its own mapping so the two URL schemes'
 * spellings don't get conflated by a future "simplification".
 */
fun gmapsTravelMode(mode: TravelMode): String = when (mode) {
    TravelMode.MOTO -> "two-wheeler"
    TravelMode.CAR -> "driving"
}

/**
 * Normalises a route's polyline into a [width] x [height] box, inset by
 * [padding], preserving nothing but the shape's extent — this is a sketch, not
 * a projection, so no spherical correction is applied.
 *
 * Latitude is flipped because screen y grows downward and north should be up.
 * A polyline with no extent in an axis is centred on that axis rather than
 * dividing by zero.
 */
fun thumbnailPoints(
    polyline: List<LatLon>,
    width: Double,
    height: Double,
    padding: Double,
): List<ThumbPoint> {
    if (polyline.isEmpty()) return emptyList()
    val minLat = polyline.minOf { it.lat }
    val maxLat = polyline.maxOf { it.lat }
    val minLon = polyline.minOf { it.lon }
    val maxLon = polyline.maxOf { it.lon }
    val spanLat = maxLat - minLat
    val spanLon = maxLon - minLon
    val innerW = width - 2 * padding
    val innerH = height - 2 * padding
    return polyline.map { p ->
        val x = if (spanLon == 0.0) width / 2
        else padding + (p.lon - minLon) / spanLon * innerW
        val y = if (spanLat == 0.0) height / 2
        else padding + (maxLat - p.lat) / spanLat * innerH
        ThumbPoint(x, y)
    }
}

/** Pure map from stored routes to display cards. */
fun routesStateFrom(routes: List<SavedRoute>): RoutesState = RoutesState(
    loaded = true,
    cards = routes.map { r ->
        RouteCard(
            id = r.id,
            name = r.name,
            subtitle = routeSubtitle(r.distanceMeters, r.stops.size, r.timeMs),
            sharedBy = r.sharedBy,
            polyline = r.polyline,
        )
    },
)
