package com.jellemax.detour.nav

import androidx.navigation3.runtime.NavKey

/**
 * The navigation moves this app performs, as operations on the back stack.
 *
 * These are extension functions on `MutableList` rather than methods on Nav 3's
 * `NavBackStack`, and that is the point: `NavBackStack` *is* a `MutableList`, so
 * the app calls these on the real stack while a test calls them on an
 * `ArrayList`. Every edge this app can take becomes assertable under
 * `:app:testDebugUnitTest` with plain JUnit — no Robolectric, no
 * `compose-ui-test`, no `androidTest` source set, none of which this repo has.
 *
 * They are generic in the key type because `rememberNavBackStack` hands back a
 * `NavBackStack<NavKey>` and `MutableList` is invariant, so a
 * `MutableList<NavKey>` is not a `MutableList<Destination>`. One set of functions
 * then serves both the real stack and a `mutableListOf<Destination>` in a test,
 * which is what keeps the test off a double.
 *
 * That absence is why #68 was filed. The old model needed a `depth` table and a
 * parent-guessing `BackHandler` kept in step by hand, and the issue's own
 * complaint was that "there is no test that can catch them disagreeing". There
 * is now, and that is this file's reason to exist.
 *
 * ## Why a wrapper here when `car/` has none
 *
 * `car/` already owns a real back stack and calls it directly —
 * `screenManager.push(...)` and `screenManager.pop()` in `SpinScreen.kt:192,230`,
 * `SearchScreen.kt:97,184`, `NavScreen.kt:164,272,492`. The verb names below are
 * taken from there rather than invented, so the two surfaces read alike; note
 * `open` would have been the wrong word regardless, since `SecretBox.open` already
 * means "decrypt" in this codebase.
 *
 * What is *not* copied from the car side is calling the stack inline. An inline
 * `stack.add(...)` cannot be reached without composing the UI, which on this repo
 * means it cannot be reached at all. The car screens have the same problem and it
 * is not a model to follow.
 *
 * ## What is deliberately not here
 *
 * No parent table. [pop] removes whatever is underneath, and what is underneath is
 * whatever the rider pushed. The two deep links at the bottom of this file are the
 * only exception, and they say why.
 */

/**
 * Go to [to].
 *
 * Ignores a push onto a destination already on top, which is the double-tap guard
 * the old model got for free by assigning the same value to a `mutableStateOf`
 * twice. Without it, two quick taps on a Hub row stack two identical entries and
 * back has to be pressed twice to undo one visible move.
 */
fun <T : NavKey> MutableList<T>.push(to: T) {
    if (lastOrNull() != to) add(to)
}

/**
 * Undo one push.
 *
 * A no-op at the root: back on the map falls through to the system, which is how
 * the app has always exited. The old code said this twice — once as
 * `BackHandler(enabled = screen != Screen.MAP)` and once as the `when` block's
 * `Screen.HUB -> Screen.MAP` — and here it is just the stack having one entry
 * left, so there is no second place to keep in step.
 */
fun <T : NavKey> MutableList<T>.pop() {
    if (size > 1) removeAt(lastIndex)
}

/**
 * Leave everything and return to the map.
 *
 * This is `RoutesScreen`'s `onNavigate` — start riding a saved route. It is the
 * one edge the depth inference read correctly by accident: depth 2 to depth 0
 * looks like a pop, so it animated as one, but it is not a pop of *one*. Popping
 * once from Routes lands on Hub. Written down because the old model could not
 * express the difference and nothing would have caught it.
 */
fun <T : NavKey> MutableList<T>.returnToMap() {
    if (size > 1) subList(1, size).clear()
}

/**
 * The stack a tapped trip-ended notification lands on.
 *
 * A deep link arrives with no history, so the way back out has to be stated
 * rather than recorded. This and [circleNotificationStack] are the only two places
 * a parent relationship is still declared, and they declare it for two
 * destinations rather than for all nineteen.
 *
 * A null [startTimeMs] — no trip, or one that no longer exists — lands on the
 * history list rather than a blank detail screen, because the trip may have been
 * deleted or dropped by a `/sync` merge between the notification and the tap.
 * Same fallback the old `LaunchedEffect` chose, kept deliberately.
 */
fun tripNotificationStack(startTimeMs: Long?): List<Destination> = buildList {
    add(Destination.Map)
    add(Destination.Hub)
    add(Destination.History)
    if (startTimeMs != null) add(Destination.TripDetail(startTimeMs))
}

/**
 * The stack a tapped arrival/departure notification lands on.
 *
 * Same reasoning as [tripNotificationStack]. A null [circleId] opens the circles
 * list, which is what the old code did by setting `screen = Screen.CIRCLES` and
 * letting `CirclesScreen` decide whether the id named a real circle.
 */
fun circleNotificationStack(circleId: String?): List<Destination> = buildList {
    add(Destination.Map)
    add(Destination.Hub)
    add(Destination.Circles)
    if (circleId != null) add(Destination.CircleDetail(circleId))
}
