package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult

/**
 * When the navigation dock asks for a route before Start (#1027).
 *
 * A picked place, a saved-place chip or a dropped pin leaves a destination
 * with no route; the dock then had nothing to show but the name, and a server
 * that could not route only said so after Start. The preview fills that gap
 * with the same request Start makes, so the dock's callout gets its km · min
 * and the map its line.
 *
 * Asked for only when every one of these holds:
 * - a destination and **no route yet** — a picked spin candidate or a seeded
 *   route already has its line, and a preview that landed is a route too, so
 *   this does not ask twice;
 * - **not navigating, and Start not fetching** — Start's own request is the
 *   one guidance follows, and a preview landing over it would replace the
 *   line from where the rider is with one from where they were;
 * - a **usable routing server** — without one the dock hands off to an
 *   external app, which routes for itself;
 * - a **position** to route from.
 */
fun routePreviewWanted(
    destination: LatLon?,
    route: RouteResult?,
    navigating: Boolean,
    startFetching: Boolean,
    serverUsable: Boolean,
    hasOrigin: Boolean,
): Boolean = destination != null && route == null && !navigating && !startFetching && serverUsable && hasOrigin

/**
 * Whether a preview asked for [asked] may still be written when it lands:
 * the rider has not ✕'d or re-picked, nothing else drew a route meanwhile,
 * and Start has not taken over.
 */
fun routePreviewStillCurrent(
    asked: LatLon,
    destination: LatLon?,
    route: RouteResult?,
    navigating: Boolean,
    startFetching: Boolean,
): Boolean = destination == asked && route == null && !navigating && !startFetching
