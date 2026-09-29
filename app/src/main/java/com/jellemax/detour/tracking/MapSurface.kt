package com.jellemax.detour.tracking

/**
 * A screen that draws a live map off [TripTrackingService.lastFix], and so asks
 * the tracker for navigation-grade fixes while it is on screen.
 *
 * The phone map and the car map come and go independently, which is why
 * [TripTrackingService.setUiVisible] keeps a set of these rather than one
 * flag. With a single boolean, locking a phone that had Detour open cleared the
 * flag the car had set: the tracker fell back to batched idle fixes (or, with
 * auto-detect off, stopped outright) and the head unit's free-drive map froze
 * until the phone was unlocked again.
 */
enum class MapSurface { PHONE, CAR }

/** [visible] with [surface] shown or hidden. Hiding one surface never hides
 *  another. */
internal fun withMapVisible(
    visible: Set<MapSurface>,
    surface: MapSurface,
    shown: Boolean,
): Set<MapSurface> = if (shown) visible + surface else visible - surface
