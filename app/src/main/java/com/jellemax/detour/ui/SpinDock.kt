package com.jellemax.detour.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.ModeSwipePolicy
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How much of its opacity the dock's left cell loses at full drag. Stops short
 *  of invisible: the mode you are dragging away from stays readable. */
private const val DRAG_FADE = 0.55f

/**
 * The dock's swipe offset, in px, from two sources that never both drive it.
 *
 * The drag phase writes a plain float state synchronously on every pointer
 * move; the settle phase is an [Animatable] that springs the card home on
 * release. Splitting them is what keeps the per-event path free of a
 * `launch` - `Animatable.snapTo` is a suspend function, so writing the drag
 * through it means a coroutine per pointer event, and a settle still running
 * from a previous release is only preempted once the first one lands.
 *
 * [dragging] selects which source [offsetPx] reads, so the handover is a
 * single state flip rather than a cancellation race.
 */
@Stable
private class ModeSwipeState(private val scope: CoroutineScope) {

    /** Live finger offset while [dragging]. Written synchronously, no coroutine. */
    var dragPx by mutableFloatStateOf(0f)
        private set

    /** Carries the card home after a release. Never snapped per pointer event,
     *  and private so the phase machine above stays the only way to move the
     *  card - a caller reaching in here directly would desync [dragging] from
     *  what is actually on screen, which no test here can catch. */
    private val settle = Animatable(0f)

    var dragging by mutableStateOf(false)
        private set

    /** What the card should be offset by right now, whichever phase owns it. */
    val offsetPx: Float get() = if (dragging) dragPx else settle.value

    /** A new drag takes over immediately: flipping [dragging] switches
     *  [offsetPx]'s source, so any settle still animating stops being read
     *  without needing to be cancelled first. */
    fun begin() {
        dragPx = 0f
        dragging = true
    }

    fun drag(px: Float) {
        dragPx = px
    }

    /** Hands the drag's final offset to the spring. `snapTo` runs before
     *  [dragging] flips back, so [offsetPx] never reads a stale [settle]
     *  value for a frame. A settle still running from an earlier release is
     *  cancelled by this `snapTo` through Animatable's own mutex. */
    fun release() {
        val from = dragPx
        scope.launch {
            settle.snapTo(from)
            dragging = false
            settle.animateTo(0f, SETTLE_SPEC)
        }
    }

    /**
     * Throws the cell sideways and lets it spring back, twice. Deliberately
     * drives the same offset the real drag does, so the motion demonstrated is
     * the motion required rather than a description of it.
     *
     * Returns early rather than fighting a finger already on the card. If a
     * drag starts mid-nudge, [begin] flips the read source to [dragPx] and
     * this animation stops being rendered even though it is still running -
     * the next [release] cancels it through Animatable's own mutex.
     */
    suspend fun nudge(px: Float) {
        if (dragging) return
        repeat(2) {
            settle.animateTo(px, tween(durationMillis = 150, easing = LinearEasing))
            settle.animateTo(0f, SETTLE_SPEC)
        }
    }
}

private val SETTLE_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The mode swipe. Not a composable: everything it reads is passed in already
 * remembered, because `pointerInput(Unit)` captures its arguments as plain
 * values frozen at first composition. Reading a parameter directly in here
 * instead of through its [State] wrapper is a silent bug with no compiler
 * signal - the gate stops gating and the target mode freezes at whatever it
 * was when the screen opened.
 */
private fun Modifier.modeSwipeGesture(
    state: ModeSwipeState,
    blocked: State<String?>,
    other: State<TravelMode>,
    onSwitch: State<(TravelMode) -> Unit>,
    onBlocked: State<(String) -> Unit>,
): Modifier = pointerInput(Unit) {
    val tracker = VelocityTracker()
    var rawPx = 0f
    detectHorizontalDragGestures(
        onDragStart = {
            rawPx = 0f
            tracker.resetTracking()
            state.begin()
        },
        onDragCancel = { state.release() },
        onDragEnd = {
            val blockedNow = blocked.value
            // px/s -> dp/s: toDp() divides by density, which is the same
            // conversion a velocity needs. ModeSwipePolicy's fling threshold
            // is in dp/s so the units must match.
            val velocityDp = tracker.calculateVelocity().x.toDp().value
            val offsetDp = state.offsetPx.toDp().value
            if (ModeSwipePolicy.commits(offsetDp, velocityDp, blockedNow != null)) {
                onSwitch.value(other.value)
            } else if (blockedNow != null && abs(offsetDp) >= ModeSwipePolicy.MIN_INTENT_DP) {
                onBlocked.value(blockedNow)
            }
            state.release()
        },
    ) { change, dragAmount ->
        rawPx += dragAmount
        tracker.addPosition(change.uptimeMillis, change.position)
        val targetDp = ModeSwipePolicy.dragOffsetDp(
            rawPx.toDp().value,
            blocked = blocked.value != null,
        )
        state.drag(targetDp.dp.toPx())
    }
}

/**
 * The mode switch as a TalkBack action. A swipe is invisible to a screen
 * reader, and the bar this replaced was a plain focusable control.
 *
 * Unlike [modeSwipeGesture] this reads its arguments directly and must: it is
 * rebuilt on every composition, so there is nothing stale to capture. Wrapping
 * these in [rememberUpdatedState] would be cargo-culting the gesture's fix onto
 * a case that does not have the problem.
 */
private fun Modifier.modeSwitchAction(
    other: TravelMode,
    blockedReason: String?,
    onSwitch: (TravelMode) -> Unit,
): Modifier = semantics {
    customActions = listOf(
        CustomAccessibilityAction("Switch to ${other.label}") {
            if (blockedReason == null) {
                onSwitch(other)
                true
            } else {
                false
            }
        },
    )
}

/** The dock's left cell: what mode you are in and how far you are asking for.
 *  Takes its modifier from the caller, which owns the drag transform and the
 *  tap that expands the sheet. */
@Composable
private fun ModeCell(
    mode: TravelMode,
    radiusKm: Float,
    directionDeg: Float?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(mode.icon, contentDescription = null)
        Column {
            Text(
                "${if (mode.maxKm <= 10f) "%.1f".format(radiusKm) else radiusKm.toInt()} km",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${mode.label} · " + (directionDeg?.let { DIRECTION_NAMES[(it / 45f).toInt()] }
                    ?: "any direction"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(Icons.Outlined.ExpandLess, contentDescription = "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The dice. Spins on tap; while a spin is in flight it becomes its own
 *  progress indicator and the same tap cancels it. */
@Composable
private fun SpinButton(
    spinning: Boolean,
    onSpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onSpin,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(52.dp),
    ) {
        if (spinning) {
            CircularProgressIndicator(
                Modifier.size(22.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(Icons.Outlined.Casino, contentDescription = "Spin")
        }
    }
}

/**
 * The swipe's discoverability affordance. Two variants ship at once so they can
 * be compared by hand - this app has no analytics, so a measured A/B is not on
 * the table. The loser is deleted along with [ModeSwipePolicy.HintVariant].
 *
 * Calls [onPlayed] whichever variant ran, and whether or not it actually
 * animated: the caller's "already shown this visit" flag must clear either way,
 * or a hint suppressed by a finger on the card would re-fire forever.
 */
@Composable
private fun BoxScope.ModeSwipeHint(
    request: Boolean,
    variant: ModeSwipePolicy.HintVariant,
    swipe: ModeSwipeState,
    onPlayed: () -> Unit,
) {
    val arrowsAlpha = remember { Animatable(0f) }
    val nudgePx = with(LocalDensity.current) { ModeSwipePolicy.HINT_NUDGE_DP.dp.toPx() }
    val onPlayedRef = rememberUpdatedState(onPlayed)

    LaunchedEffect(request) {
        if (!request) return@LaunchedEffect
        try {
            when (variant) {
                ModeSwipePolicy.HintVariant.NUDGE -> swipe.nudge(nudgePx)
                ModeSwipePolicy.HintVariant.ARROWS -> {
                    if (!swipe.dragging) {
                        arrowsAlpha.animateTo(0.35f, tween(450, easing = LinearEasing))
                        // A hold, expressed as an animation to the current
                        // value on purpose. delay() would ignore the system's
                        // animator duration scale, so with animations turned
                        // off this pause would outlast the fades either side
                        // of it.
                        arrowsAlpha.animateTo(0.35f, tween(500, easing = LinearEasing))
                        arrowsAlpha.animateTo(0f, tween(650, easing = LinearEasing))
                    }
                }
            }
        } finally {
            // Fires however this exits, and being interrupted is the likely
            // exit: nudge() animates the same Animatable the drag does, so a
            // user reacting to the hint by grabbing the card cancels it
            // through Animatable's mutex. Leaving the caller's request flag
            // set would replay the hint on the next time this composable is
            // recreated - which happens every time the sheet opens - at the
            // one user who has definitely already learned the gesture.
            onPlayedRef.value()
        }
    }

    // Non-interactive by construction: no pointerInput, so it cannot swallow a
    // touch, and cleared semantics so TalkBack never announces a decoration as
    // a control. The real switch is on the card's own custom action.
    Icon(
        Icons.Outlined.SwapHoriz,
        contentDescription = null,
        modifier = Modifier
            .align(Alignment.Center)
            .size(28.dp)
            .graphicsLayer { alpha = arrowsAlpha.value }
            .clearAndSetSemantics { },
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** Persistent glass bar at the bottom of the map: the spin dock. Tapping the
 *  left cell opens the sheet; the dice FAB spins right away without needing
 *  the sheet open at all. */
@Composable
internal fun SpinDock(
    mode: TravelMode,
    radiusKm: Float,
    directionDeg: Float?,
    spinning: Boolean,
    destination: LatLon?,
    route: RouteResult?,
    origin: LatLon?,
    inAppAvailable: Boolean,
    onSwitchMode: (TravelMode) -> Unit,
    switchBlockedReason: String?,
    onSwitchBlocked: (String) -> Unit,
    hintRequest: Boolean,
    hintVariant: ModeSwipePolicy.HintVariant,
    onHintPlayed: () -> Unit,
    onSpin: () -> Unit,
    onExpand: () -> Unit,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val swipe = remember(scope) { ModeSwipeState(scope) }
    val commitPx = with(LocalDensity.current) { ModeSwipePolicy.COMMIT_DP.dp.toPx() }
    val other = ModeSwipePolicy.other(mode)

    // pointerInput(Unit) captures its parameters as plain values, frozen at
    // first composition, so everything the gesture reads goes through
    // rememberUpdatedState. None of that has a compiler signal.
    val blockedRef = rememberUpdatedState(switchBlockedReason)
    val otherRef = rememberUpdatedState(other)
    val onSwitchRef = rememberUpdatedState(onSwitchMode)
    val onBlockedRef = rememberUpdatedState(onSwitchBlocked)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(MaterialTheme.shapes.extraLarge)
            .modeSwitchAction(other, switchBlockedReason, onSwitchMode)
            .modeSwipeGesture(swipe, blockedRef, otherRef, onSwitchRef, onBlockedRef),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeCell(
                    mode = mode,
                    radiusKm = radiusKm,
                    directionDeg = directionDeg,
                    modifier = Modifier
                        .weight(1f)
                        // graphicsLayer, not offset/alpha modifiers: the lambda runs
                        // in the draw phase, so the per-frame read never triggers a
                        // recomposition at all.
                        .graphicsLayer {
                            translationX = swipe.offsetPx
                            alpha = 1f - (abs(swipe.offsetPx) / commitPx).coerceIn(0f, 1f) * DRAG_FADE
                        }
                        .clickable(onClick = onExpand),
                )
                SpinButton(spinning = spinning, onSpin = onSpin)
                NavIconButton(
                    destination = destination,
                    route = route?.waypoints,
                    origin = origin,
                    mode = mode,
                    inAppAvailable = inAppAvailable,
                    onNavigateInApp = onNavigateInApp,
                    onNavigate = onNavigate,
                )
            }
            ModeSwipeHint(
                request = hintRequest,
                variant = hintVariant,
                swipe = swipe,
                onPlayed = onHintPlayed,
            )
        }
    }
}
