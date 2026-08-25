package com.jellemax.detour.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Swaps [target] for its content with a push or a pop, whichever the move was.
 *
 * This app has two hand-rolled navigation layers — the app-wide screens in
 * MainActivity and the spokes off the Settings root — and each holds its
 * position in a single value rather than a stack. A single value cannot say
 * which way the rider travelled: `SETTINGS -> HUB` and `HUB -> SETTINGS` are
 * the same pair of values in the opposite order. So the direction is recovered
 * from [depthOf]: going shallower is a pop, anything else is a push.
 *
 * That inference is only sound while the destinations form a tree of fixed
 * levels, which both layers currently are. It is a stand-in for a back stack,
 * not a replacement — with a stack the direction is known rather than derived,
 * and both layers' hand-written parent-guessing back handlers would fall out of
 * it too. Migrating to androidx Navigation 3, whose NavDisplay takes
 * `transitionSpec` and `popTransitionSpec` as separate parameters for exactly
 * this reason, is the intended end state and is tracked separately.
 *
 * Sibling moves — equal depths — are not produced by either layer today and
 * fall to the push branch rather than being given a third animation.
 *
 * @param depthOf how deep a destination sits; 0 is the root of that layer.
 * @param label names the transition for tooling, as AnimatedContent requires.
 */
@Composable
fun <T> PushPopContent(
    target: T,
    depthOf: (T) -> Int,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (depthOf(targetState) < depthOf(initialState)) {
                // Pop: what we are returning to eases in from the left while the
                // screen being left slides off to the right, the way it came in.
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            } else {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            }
        },
        label = label,
    ) { current ->
        content(current)
    }
}
