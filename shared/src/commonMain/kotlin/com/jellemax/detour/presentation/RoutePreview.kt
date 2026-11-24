package com.jellemax.detour.presentation

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
 * - the destination is **not the one guidance just ran to** ([guidedTo]) —
 *   arrival and Exit drop the route but keep the pin, and a preview there
 *   would re-park the camera on a line from where the rider now stands;
 * - **not [guidanceBusy]** (navigating, or Start fetching) — Start's own
 *   request is the one guidance follows, and a preview landing over it would replace the
 *   line from where the rider is with one from where they were;
 * - a **usable routing server** — without one the dock hands off to an
 *   external app, which routes for itself;
 * - a **position** to route from.
 */
fun routePreviewWanted(
    destination: LatLon?,
    guidedTo: LatLon?,
    route: RouteResult?,
    guidanceBusy: Boolean,
    serverUsable: Boolean,
    hasOrigin: Boolean,
): Boolean = destination != null && destination != guidedTo && route == null &&
    !guidanceBusy && serverUsable && hasOrigin

/**
 * Whether a preview asked for [asked] may still be written when it lands:
 * the rider has not ✕'d or re-picked, nothing else drew a route meanwhile,
 * and Start has not taken over ([guidanceBusy], as in [routePreviewWanted]).
 */
fun routePreviewStillCurrent(
    asked: LatLon,
    destination: LatLon?,
    route: RouteResult?,
    guidanceBusy: Boolean,
): Boolean = destination == asked && route == null && !guidanceBusy
