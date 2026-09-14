package com.jellemax.detour.tracking

/** What [TripTrackingService] should do with the running trip when a
 *  navigation session ends — see [navEndDecision] and issues #271, #272. */
enum class NavEndAction {
    /** End the trip now: navigation started it and the rider has stopped. */
    END_NOW,
    /** Leave the trip running but let auto-detection own its stop: the rider
     *  drove on past their guidance, so ending it here would split one drive
     *  into two. */
    HAND_TO_AUTODETECT,
    /** Do nothing: no trip, or a trip navigation did not start (an
     *  auto-detected drive, which navigation must never end or re-flag). */
    IGNORE,
}

/**
 * Navigating is driving, so a trip navigation started should not outlive it
 * (#272), and it must never be excluded from stopping the way #271 excluded
 * it. Only a [navStarted] trip is navigation's to end — an auto-detected drive
 * that merely had guidance laid over it is left untouched.
 *
 * [endNow] is the caller saying the end is definite (an arrival): end at once.
 * Otherwise the end is an Exit, which is ambiguous — pulled up, or carrying on
 * without guidance — so a rider still moving within [movingWindowMs] of now
 * keeps the drive, handed to auto-detection for its stop; a rider who has
 * stopped ends it.
 */
fun navEndDecision(
    tripActive: Boolean,
    navStarted: Boolean,
    endNow: Boolean,
    msSinceMoving: Long,
    movingWindowMs: Long,
): NavEndAction = when {
    !tripActive || !navStarted -> NavEndAction.IGNORE
    endNow -> NavEndAction.END_NOW
    msSinceMoving < movingWindowMs -> NavEndAction.HAND_TO_AUTODETECT
    else -> NavEndAction.END_NOW
}
