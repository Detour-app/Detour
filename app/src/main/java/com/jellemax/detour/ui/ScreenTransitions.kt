package com.jellemax.detour.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
 * ## Retained state
 *
 * [AnimatedContent] composes only the target's content, so leaving a
 * destination disposes its whole composition — `rememberSaveable` included,
 * which otherwise survives only a rotation. A [rememberSaveableStateHolder]
 * gives each destination its own slot, so returning to one restores what it
 * had: scroll positions, and MapScreen's five saveables (`radiusKm`,
 * `minRadiusKm`, `poiKind`, `directionDeg`, `settingsCollapsed`).
 *
 * This restores *saveable* state only. Plain `remember`, `produceState` and
 * every `LaunchedEffect` still tear down and re-run on a return — that is the
 * navigation model, not this function, and #82's other two stages are what
 * address it.
 *
 * @param depthOf how deep a destination sits; 0 is the root of that layer.
 * @param label names the transition for tooling, as AnimatedContent requires.
 * @param keyOf names a destination's saved-state slot. Defaults to the target
 *   itself, which is right for a destination that carries no argument. A
 *   destination that shows *a* trip or *a* route must fold that identity into
 *   the key, or returning with a different one restores the previous one's
 *   scroll offset onto it — see MainActivity's `stateKeyOf`.
 */
@Composable
fun <T> PushPopContent(
    target: T,
    depthOf: (T) -> Int,
    label: String,
    modifier: Modifier = Modifier,
    keyOf: (T) -> Any = { it.toString() },
    content: @Composable (T) -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
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
        stateHolder.SaveableStateProvider(keyOf(current)) {
            content(current)
        }
    }
}
