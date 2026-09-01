# Swipe mode on the spin dock — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the permanent bottom `ModeBar` from the map screen and switch travel mode with a horizontal swipe on the spin dock, with a discoverability hint that retires itself after three uses.

**Architecture:** Every decision the gesture makes — is it blocked, does this drag commit, how far does the card move under resistance, is the hint due — is extracted into `ModeSwipePolicy`, a pure `internal object` in `com.jellemax.detour.map`, unit-tested with JUnit. The Compose layer in `SpinDock` becomes a thin caller: it converts px to dp, asks the policy, and animates. This mirrors the repo's existing `NavPolicy` / `CameraAuthority` / `MapMotion` pattern, and it is the only way any of this gets automated coverage — the repo has no Compose test infrastructure at all.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Kotlin Multiplatform `shared/` module, JUnit 4 (`app/src/test`), Gradle via `devcontainer-exec`.

**Spec:** `docs/superpowers/specs/2026-08-25-swipe-mode-on-spin-dock-design.md`
**Issue:** <https://github.com/maxke24/Detour/issues/70>
**Visual reference:** <https://claude.ai/code/artifact/b2aee7a9-213b-4a82-a408-956bd78edf28>

---

## Before you start

Every Gradle command in this plan runs **inside the devcontainer**. The host has no Android SDK.
Prefix with `devcontainer-exec`, exactly as written in each step. File reading, writing, editing
and all `git` commands stay on the host.

Two rules from `detour-compose-state-hazards` govern almost every Compose edit below. They are
repeated at the point of use, but read them once now:

1. **`pointerInput(Unit)` captures its parameters as plain values, not delegated state.** Any
   parameter a gesture callback reads must go through `rememberUpdatedState` first, or it freezes
   at the value it had on first composition. This is the single most likely way this change ships
   a silent bug.
2. **A per-frame snapshot read invalidates the nearest enclosing restartable scope.** The drag
   offset is remembered inside `SpinDock` and read inside a `graphicsLayer` lambda, which defers
   the read to the draw phase. Do not hoist it to `MapScreen`.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt` | **New.** Pure decisions: block reason, mode pairing, drag resistance, commit test, hint policy, hint-variant parsing. No Compose imports, no Android imports. |
| `app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt` | **New.** JUnit coverage of every function above. |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt` | Two persisted values: `modeSwipesUsed` (Long) and `swipeHintVariant` (String). |
| `app/src/main/java/com/jellemax/detour/ui/SpinCards.kt` | `SpinDock` gains the drag gesture, the offset `Animatable`, the semantics action and both hint animations. |
| `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` | Deletes the `bottomBar` slot, fixes the navigation-bar inset, schedules the hint, wires the dock. |
| `app/src/main/java/com/jellemax/detour/ui/MapChrome.kt` | `ModeBar` deleted. |
| `app/src/debug/java/com/jellemax/detour/debug/DebugSwipeHintReceiver.kt` | **New, debug source set only.** Switches hint variant and re-arms the hint. |
| `app/src/debug/AndroidManifest.xml` | Registers the receiver. |
| `docs/DEBUG_INTENTS.md` | Documents the two intent arms. |
| `app/build.gradle.kts` | `versionName` 1.79.1 → 1.80.0. |

Tasks 1–3 build and test the policy. Task 4 adds persistence. Tasks 5–7 do the Compose work.
Task 8 adds the debug switch. Task 9 bumps the version and runs the full gate. Task 10 is
on-device verification, which is the only evidence that exists for the gesture itself.

---

### Task 1: ModeSwipePolicy — block reason and mode pairing

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt`
- Test: `app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt`:

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [ModeSwipePolicy] - every decision the spin dock's mode swipe makes.
 * The gesture itself has no automated coverage anywhere (this repo has no
 * Robolectric and no androidTest source set), so the arithmetic and the gates
 * are pulled out here where they can be tested without a device.
 */
class ModeSwipePolicyTest {

    @Test
    fun `nothing in flight means the swipe is allowed`() {
        assertNull(ModeSwipePolicy.blockedReason(spinning = false, tracking = false))
    }

    @Test
    fun `a spin in flight blocks the swipe`() {
        assertEquals(
            "Cancel the spin to change mode",
            ModeSwipePolicy.blockedReason(spinning = true, tracking = false),
        )
    }

    @Test
    fun `a recording trip blocks the swipe`() {
        assertEquals(
            "Stop the trip to change mode",
            ModeSwipePolicy.blockedReason(spinning = false, tracking = true),
        )
    }

    /** Both at once is reachable: a spin can be started while a trip records.
     *  The spin is the one the dice button can cancel, so it is named first. */
    @Test
    fun `a spin outranks a trip when both are in flight`() {
        assertEquals(
            "Cancel the spin to change mode",
            ModeSwipePolicy.blockedReason(spinning = true, tracking = true),
        )
    }

    @Test
    fun `each mode pairs with the other one`() {
        assertEquals(TravelMode.CAR, ModeSwipePolicy.other(TravelMode.MOTO))
        assertEquals(TravelMode.MOTO, ModeSwipePolicy.other(TravelMode.CAR))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*ModeSwipePolicyTest*'
```

Expected: FAIL to **compile**, with `Unresolved reference: ModeSwipePolicy`.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt`:

```kotlin
package com.jellemax.detour.map

import com.jellemax.detour.data.TravelMode

/**
 * Every decision the spin dock's mode swipe makes. Pure: values in, one answer
 * out, no clock of its own, no Compose and no Android.
 *
 * It lives here rather than inside `SpinDock` because this repo has no Compose
 * test infrastructure - CI runs `:app:testDebugUnitTest` and
 * `:shared:testDebugUnitTest` only, with no Robolectric and no `androidTest`
 * source set. Arithmetic left inside a composable is arithmetic no test can
 * reach. Same reasoning as [NavPolicy] and [CameraAuthority].
 *
 * All distances are in **dp** and all velocities in **dp/s**. The caller owns
 * the px conversion, because only it has a Density.
 */
internal object ModeSwipePolicy {

    /** Travel past this commits the switch on release. */
    const val COMMIT_DP = 84f

    /** ...or a release faster than this, however short the travel. */
    const val FLING_DP_PER_S = 400f

    /** A blocked drag is allowed this much travel before it stops following the
     *  finger: enough to read as received, not enough to read as working. */
    const val BLOCKED_CLAMP_DP = 8f

    /** How much of the finger's travel past the limit still reaches the card. */
    const val RESISTANCE = 0.35f

    fun blockedReason(spinning: Boolean, tracking: Boolean): String? = when {
        // The dice button cancels a spin, so name the one with a visible exit
        // first when both are true.
        spinning -> "Cancel the spin to change mode"
        tracking -> "Stop the trip to change mode"
        else -> null
    }

    /** With two modes there is no third target to swipe toward: either
     *  direction means "the other one". */
    fun other(mode: TravelMode): TravelMode =
        if (mode == TravelMode.MOTO) TravelMode.CAR else TravelMode.MOTO
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*ModeSwipePolicyTest*'
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt \
        app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt
git commit -m "feat(map): add ModeSwipePolicy with the swipe's block rules

The spin dock is about to gain a mode swipe. Every decision it makes goes
here rather than inside the composable, because a composable is where this
repo cannot test anything: no Robolectric, no androidTest source set.

Same shape as NavPolicy - values in, one answer out."
```

---

### Task 2: ModeSwipePolicy — drag resistance and the commit test

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt`
- Test: `app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these to the body of `ModeSwipePolicyTest`, and add `import org.junit.Assert.assertFalse`
and `import org.junit.Assert.assertTrue` to the imports:

```kotlin
    @Test
    fun `travel inside the commit distance follows the finger exactly`() {
        assertEquals(40f, ModeSwipePolicy.dragOffsetDp(40f, blocked = false), 0.01f)
        assertEquals(-40f, ModeSwipePolicy.dragOffsetDp(-40f, blocked = false), 0.01f)
        assertEquals(84f, ModeSwipePolicy.dragOffsetDp(84f, blocked = false), 0.01f)
    }

    /** Past the commit point the card keeps moving, but slower than the finger,
     *  so the threshold is felt rather than read. */
    @Test
    fun `travel past the commit distance is resisted`() {
        // 84 + (184 - 84) * 0.35 = 119
        assertEquals(119f, ModeSwipePolicy.dragOffsetDp(184f, blocked = false), 0.01f)
        assertEquals(-119f, ModeSwipePolicy.dragOffsetDp(-184f, blocked = false), 0.01f)
    }

    @Test
    fun `a blocked drag resists from a far shorter limit`() {
        assertEquals(6f, ModeSwipePolicy.dragOffsetDp(6f, blocked = true), 0.01f)
        // 8 + (108 - 8) * 0.35 = 43 ... a long blocked pull still moves, but the
        // first 8dp is the only part that tracks the finger.
        assertEquals(43f, ModeSwipePolicy.dragOffsetDp(108f, blocked = true), 0.01f)
    }

    @Test
    fun `a short slow drag does not commit`() {
        assertFalse(ModeSwipePolicy.commits(offsetDp = 30f, velocityDpPerS = 50f, blocked = false))
    }

    @Test
    fun `travel past the commit distance commits`() {
        assertTrue(ModeSwipePolicy.commits(offsetDp = 84f, velocityDpPerS = 0f, blocked = false))
        assertTrue(ModeSwipePolicy.commits(offsetDp = -90f, velocityDpPerS = 0f, blocked = false))
    }

    @Test
    fun `a fling commits even when the travel is short`() {
        assertTrue(ModeSwipePolicy.commits(offsetDp = 20f, velocityDpPerS = 600f, blocked = false))
        assertTrue(ModeSwipePolicy.commits(offsetDp = -20f, velocityDpPerS = -600f, blocked = false))
    }

    @Test
    fun `a blocked drag never commits, however far or fast`() {
        assertFalse(ModeSwipePolicy.commits(offsetDp = 200f, velocityDpPerS = 900f, blocked = true))
    }

    /** A tap that jitters a pixel is not a swipe. */
    @Test
    fun `a drag of essentially nothing does not commit`() {
        assertFalse(ModeSwipePolicy.commits(offsetDp = 0.4f, velocityDpPerS = 0f, blocked = false))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*ModeSwipePolicyTest*'
```

Expected: FAIL to compile, `Unresolved reference: dragOffsetDp` and `Unresolved reference: commits`.

- [ ] **Step 3: Write the minimal implementation**

Add `import kotlin.math.abs` at the top of `ModeSwipePolicy.kt`, and these two functions inside
the object, after `other`:

```kotlin
    /**
     * How far the card's left cell should sit for a raw finger travel of
     * [rawDp]. Linear up to the limit, then compressed by [RESISTANCE], so the
     * card never runs away from the finger and the threshold is felt.
     *
     * [blocked] only changes where the compression starts: a refused swipe still
     * moves, it just stops tracking almost immediately.
     */
    fun dragOffsetDp(rawDp: Float, blocked: Boolean): Float {
        val limit = if (blocked) BLOCKED_CLAMP_DP else COMMIT_DP
        val magnitude = abs(rawDp)
        if (magnitude <= limit) return rawDp
        val sign = if (rawDp < 0f) -1f else 1f
        return sign * (limit + (magnitude - limit) * RESISTANCE)
    }

    /**
     * Whether releasing here switches the mode. [offsetDp] is the *resisted*
     * offset the card is actually showing, not the raw finger travel.
     *
     * The velocity arm is what makes a quick flick work without a long pull.
     */
    fun commits(offsetDp: Float, velocityDpPerS: Float, blocked: Boolean): Boolean {
        if (blocked) return false
        if (abs(offsetDp) < 1f) return false
        return abs(offsetDp) >= COMMIT_DP || abs(velocityDpPerS) >= FLING_DP_PER_S
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*ModeSwipePolicyTest*'
```

Expected: `BUILD SUCCESSFUL`, 13 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt \
        app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt
git commit -m "feat(map): add the swipe's resistance curve and commit test

Commit at 84dp of travel or a fling over 400dp/s. Past the threshold the
card keeps moving at 35% of the finger, so the commit point is felt rather
than guessed at.

A blocked drag resists from 8dp instead and never commits: visibly
received, then refused. Silent inertness reads as a broken app."
```

---

### Task 3: ModeSwipePolicy — hint policy and variant parsing

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt`
- Test: `app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to the body of `ModeSwipePolicyTest`:

```kotlin
    @Test
    fun `a first-time user with an idle dock is due the hint`() {
        assertTrue(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 0L, blocked = false))
    }

    @Test
    fun `the hint fires at most once per map visit`() {
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = true, swipesUsed = 0L, blocked = false))
    }

    @Test
    fun `the hint retires once the gesture has been used three times`() {
        assertTrue(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 2L, blocked = false))
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 3L, blocked = false))
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 9L, blocked = false))
    }

    /** Demonstrating a gesture the user is not allowed to make right now is
     *  worse than not demonstrating it. */
    @Test
    fun `a blocked swipe suppresses the hint`() {
        assertFalse(ModeSwipePolicy.hintDue(alreadyShown = false, swipesUsed = 0L, blocked = true))
    }

    @Test
    fun `the hint variant is read from its stored name`() {
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of("nudge"))
        assertEquals(ModeSwipePolicy.HintVariant.ARROWS, ModeSwipePolicy.HintVariant.of("arrows"))
        assertEquals(ModeSwipePolicy.HintVariant.ARROWS, ModeSwipePolicy.HintVariant.of("ARROWS"))
    }

    /** The value is written by a debug broadcast, so a typo must not crash the
     *  map screen - and the whole variant disappears once one of them wins. */
    @Test
    fun `an unknown or missing variant falls back to the nudge`() {
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of(null))
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of(""))
        assertEquals(ModeSwipePolicy.HintVariant.NUDGE, ModeSwipePolicy.HintVariant.of("wiggle"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*ModeSwipePolicyTest*'
```

Expected: FAIL to compile, `Unresolved reference: hintDue` and `Unresolved reference: HintVariant`.

- [ ] **Step 3: Write the minimal implementation**

Add these constants alongside the existing ones in `ModeSwipePolicy`:

```kotlin
    /** Successful swipes after which the hint stops for good. */
    const val HINT_AFTER_USES = 3L

    /** How long the dock sits still before the hint plays. Long enough that it
     *  reads as an offer rather than as part of the screen arriving. */
    const val HINT_DELAY_MS = 4_000L

    /** How far the nudge variant throws the cell. */
    const val HINT_NUDGE_DP = 16f
```

And this enum plus function, at the end of the object:

```kotlin
    /**
     * Which hint animation plays. Both ship at once behind a debug-only
     * broadcast so they can be compared by hand - this app has no analytics,
     * no telemetry and no remote config, so a measured A/B is not available.
     * The loser gets deleted along with this enum.
     */
    enum class HintVariant {
        /** The cell throws itself sideways and springs back: the motion shown
         *  is the motion required. */
        NUDGE,

        /** A faint double-headed arrow fades over the card. */
        ARROWS;

        companion object {
            /** Tolerant of anything: the stored name comes from a debug
             *  broadcast, and a typo must not take the map screen down. */
            fun of(name: String?): HintVariant =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NUDGE
        }
    }

    /**
     * Whether to schedule the hint. [alreadyShown] is per map visit;
     * [swipesUsed] is persisted and outlives the process.
     */
    fun hintDue(alreadyShown: Boolean, swipesUsed: Long, blocked: Boolean): Boolean =
        !alreadyShown && swipesUsed < HINT_AFTER_USES && !blocked
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*ModeSwipePolicyTest*'
```

Expected: `BUILD SUCCESSFUL`, 19 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/map/ModeSwipePolicy.kt \
        app/src/test/java/com/jellemax/detour/map/ModeSwipePolicyTest.kt
git commit -m "feat(map): add the swipe hint's schedule and variant parsing

Fires once per map visit, 4s after the dock settles, and retires after
three successful swipes. Suppressed while the swipe is blocked -
demonstrating a gesture the user cannot currently make is worse than not
demonstrating it.

HintVariant.of() is deliberately tolerant: the stored name arrives from a
debug broadcast, and a typo must not take the map screen down."
```

---

### Task 4: Persist the swipe counter and the hint variant

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt`

**No unit test.** `Settings` binds itself to the platform `prefs("settings")` inside `init()` and
holds it in a private field; there is no injection point, so the object cannot be exercised from
`commonTest`. The one decision worth testing here — parsing the stored variant name — was
deliberately put in `ModeSwipePolicy.HintVariant.of` in Task 3, where it *is* tested. What
remains in this task is a read and a write with no branching.

- [ ] **Step 1: Add the backing state**

In `Settings.kt`, alongside the other `MutableStateFlow` declarations (near `_tripMode`, around
line 85), add:

```kotlin
    /** How many times the spin dock's mode swipe has been used successfully.
     *  Drives the discoverability hint, which retires at
     *  [ModeSwipePolicy.HINT_AFTER_USES]. Long rather than Int because [Prefs]
     *  has no Int overload. */
    private val _modeSwipesUsed = MutableStateFlow(0L)
    val modeSwipesUsed: StateFlow<Long> = _modeSwipesUsed

    /** Which mode-swipe hint animation plays: "nudge" or "arrows". A raw String
     *  rather than an enum because this is a temporary A/B knob - it is deleted
     *  along with the losing variant. Parsed by
     *  [com.jellemax.detour.map.ModeSwipePolicy.HintVariant.of], which is
     *  tolerant of anything. */
    private val _swipeHintVariant = MutableStateFlow("nudge")
    val swipeHintVariant: StateFlow<String> = _swipeHintVariant
```

- [ ] **Step 2: Load both in `init()`**

In `Settings.init()`, alongside the other load lines (next to
`_tripMode.value = TravelMode.of(...)`), add:

```kotlin
        _modeSwipesUsed.value = prefs.long("mode_swipes_used", 0L)
        _swipeHintVariant.value = prefs.string("swipe_hint_variant", "nudge")
```

- [ ] **Step 3: Add the setters**

Alongside `setTripMode`, add:

```kotlin
    fun setModeSwipesUsed(value: Long) {
        _modeSwipesUsed.value = value
        prefs.put("mode_swipes_used", value)
    }

    fun setSwipeHintVariant(value: String) {
        _swipeHintVariant.value = value
        prefs.put("swipe_hint_variant", value)
    }
```

- [ ] **Step 4: Verify it compiles on every target that consumes `shared/`**

```bash
devcontainer-exec ./gradlew :shared:compileDebugKotlinAndroid :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Existing shared tests unchanged.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt
git commit -m "feat(settings): persist the mode-swipe counter and hint variant

Both outlive the composition on purpose. AppRoot swaps screens with a bare
AnimatedContent and no rememberSaveableStateHolder, so leaving the map for
the Hub disposes MapScreen entirely - even rememberSaveable would reset,
and the hint would come back for a user who had already learned it.

Long rather than Int: Prefs has string/bool/float/long and no Int overload."
```

---

### Task 5: The swipe gesture on SpinDock

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/SpinCards.kt:47-127`

No automated test is possible for this task — it is Compose, and the repo has no
`compose-ui-test`. All of its decisions were tested in Tasks 1–3; what is added here is the
plumbing between the gesture and the policy. It is verified by build in Step 4 and by hand in
Task 10.

- [ ] **Step 1: Add the imports**

At the top of `SpinCards.kt`, add:

```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.jellemax.detour.map.ModeSwipePolicy
import kotlinx.coroutines.launch
import kotlin.math.abs
```

- [ ] **Step 2: Add the three new parameters to `SpinDock`**

In the `SpinDock` signature, after `inAppAvailable: Boolean`, add:

```kotlin
    onSwitchMode: (TravelMode) -> Unit,
    switchBlockedReason: String?,
    onSwitchBlocked: (String) -> Unit,
```

- [ ] **Step 3: Add the gesture state at the top of the composable body**

Immediately inside `SpinDock`'s body, before the `Card(`:

```kotlin
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    /** Px. Remembered *here* and not in MapScreen on purpose: a per-frame
     *  snapshot read invalidates its nearest restartable scope, and MapScreen's
     *  Scaffold content lambda is reached through only inline Box/Column/Row
     *  wrappers - a read there would invalidate the whole lambda every frame of
     *  the drag. */
    val offsetX = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    val commitPx = with(density) { ModeSwipePolicy.COMMIT_DP.dp.toPx() }
    val other = ModeSwipePolicy.other(mode)

    // pointerInput(Unit) captures its parameters as plain values, frozen at
    // first composition. Everything the gesture callbacks read goes through
    // rememberUpdatedState or the gate silently stops gating, the callbacks
    // fire into a stale lambda, and `other` switches to whatever the mode was
    // when the screen opened. None of that has a compiler signal.
    val blockedRef = rememberUpdatedState(switchBlockedReason)
    val otherRef = rememberUpdatedState(other)
    val onSwitchRef = rememberUpdatedState(onSwitchMode)
    val onBlockedRef = rememberUpdatedState(onSwitchBlocked)

    val settle = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
```

- [ ] **Step 4: Attach the gesture and the semantics action to the Card**

Replace the `Card(`'s `modifier` argument — currently
`modifier = modifier.fillMaxWidth().glassBorder(MaterialTheme.shapes.extraLarge),` — with:

```kotlin
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(MaterialTheme.shapes.extraLarge)
            // The non-gesture equivalent. A swipe is invisible to TalkBack, and
            // the mode bar this replaced was a plain, focusable control.
            // Read directly rather than through the refs above: this lambda is
            // rebuilt on every composition, so it is never stale.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Switch to ${other.label}") {
                        val blocked = switchBlockedReason
                        if (blocked == null) {
                            onSwitchMode(other)
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var rawPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        rawPx = 0f
                        dragging = true
                        tracker.resetTracking()
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { offsetX.animateTo(0f, settle) }
                    },
                    onDragEnd = {
                        dragging = false
                        val blocked = blockedRef.value
                        val velocityDp = tracker.calculateVelocity().x.toDp().value
                        val offsetDp = offsetX.value.toDp().value
                        if (ModeSwipePolicy.commits(offsetDp, velocityDp, blocked != null)) {
                            onSwitchRef.value(otherRef.value)
                        } else if (blocked != null && abs(offsetDp) > 1f) {
                            onBlockedRef.value(blocked)
                        }
                        scope.launch { offsetX.animateTo(0f, settle) }
                    },
                ) { change, dragAmount ->
                    rawPx += dragAmount
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val targetDp = ModeSwipePolicy.dragOffsetDp(
                        rawPx.toDp().value,
                        blocked = blockedRef.value != null,
                    )
                    val targetPx = targetDp.dp.toPx()
                    scope.launch { offsetX.snapTo(targetPx) }
                }
            },
```

- [ ] **Step 5: Make the left cell follow the drag**

The inner `Row` currently reads:

```kotlin
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onExpand),
```

Replace that `Modifier` chain with:

```kotlin
            Row(
                Modifier
                    .weight(1f)
                    // graphicsLayer, not offset/alpha modifiers: the lambda runs
                    // in the draw phase, so the per-frame read never triggers a
                    // recomposition at all.
                    .graphicsLayer {
                        translationX = offsetX.value
                        alpha = 1f - (abs(offsetX.value) / commitPx).coerceIn(0f, 1f) * 0.55f
                    }
                    .clickable(onClick = onExpand),
```

- [ ] **Step 6: Build to verify it compiles**

```bash
devcontainer-exec ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. It will *not* build yet if `MapScreen` has not been updated —
that is Task 7, and this step is expected to fail with
`No value passed for parameter 'onSwitchMode'` at the `SpinDock(` call site. That is the
signal to move on; do not add default values to the new parameters to make it compile early.
A default would let a future caller silently opt out of the gesture.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/SpinCards.kt
git commit -m "feat(ui): swipe the spin dock sideways to change travel mode

The drag can start anywhere on the card, including over the dice and Go
buttons; only the left cell moves, so the primary actions stay put under
the finger. Decisions all live in ModeSwipePolicy - this only converts px
to dp and animates.

Every parameter the gesture callbacks read goes through
rememberUpdatedState. pointerInput(Unit) captures its parameters as plain
values, so a direct read would freeze the gate, the callbacks and the
target mode at whatever they were on first composition, with no compiler
signal.

A semantics custom action carries the same switch for TalkBack, which
cannot discover a swipe. Does not compile alone: MapScreen wires the new
parameters in a following commit."
```

---

### Task 6: The discoverability hint on SpinDock

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/SpinCards.kt`

- [ ] **Step 1: Add the imports**

```kotlin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
```

`Box`, `Alignment` and `LaunchedEffect` may already be imported — check before adding, and do not
duplicate.

- [ ] **Step 2: Add three more parameters to `SpinDock`**

After `onSwitchBlocked: (String) -> Unit,`:

```kotlin
    hintRequest: Boolean,
    hintVariant: ModeSwipePolicy.HintVariant,
    onHintPlayed: () -> Unit,
```

- [ ] **Step 3: Add the hint state and the player**

After the `settle` declaration from Task 5:

```kotlin
    /** Alpha of the arrows variant's overlay. Unused by the nudge variant, which
     *  drives [offsetX] instead. */
    val hintArrowsAlpha = remember { Animatable(0f) }
    val onHintPlayedRef = rememberUpdatedState(onHintPlayed)

    LaunchedEffect(hintRequest) {
        if (!hintRequest) return@LaunchedEffect
        // A hint that fights the user's own finger is worse than no hint.
        if (!dragging) {
            when (hintVariant) {
                ModeSwipePolicy.HintVariant.NUDGE -> {
                    val nudgePx = with(density) { ModeSwipePolicy.HINT_NUDGE_DP.dp.toPx() }
                    repeat(2) {
                        offsetX.animateTo(nudgePx, tween(150, easing = LinearEasing))
                        offsetX.animateTo(0f, settle)
                    }
                }
                ModeSwipePolicy.HintVariant.ARROWS -> {
                    hintArrowsAlpha.animateTo(0.35f, tween(450, easing = LinearEasing))
                    hintArrowsAlpha.animateTo(0.35f, tween(500, easing = LinearEasing))
                    hintArrowsAlpha.animateTo(0f, tween(650, easing = LinearEasing))
                }
            }
        }
        onHintPlayedRef.value()
    }
```

- [ ] **Step 4: Draw the arrows overlay**

The `Card`'s content is currently a single `Row(...)`. Wrap it in a `Box` so the overlay can sit
on top. Change:

```kotlin
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
```

to:

```kotlin
    ) {
      Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
```

and close the `Box` after that `Row`'s closing brace, adding the overlay inside it:

```kotlin
        // Non-interactive by construction: no pointerInput, so it cannot swallow
        // a touch, and cleared semantics so TalkBack never announces a decoration
        // as a control.
        Icon(
            Icons.Outlined.SwapHoriz,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(28.dp)
                .graphicsLayer { alpha = hintArrowsAlpha.value }
                .clearAndSetSemantics { },
            tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
```

- [ ] **Step 5: Build**

```bash
devcontainer-exec ./gradlew :app:assembleDebug
```

Expected: still fails at the `SpinDock(` call site in `MapScreen.kt` with
`No value passed for parameter`. Any *other* error is a real one — fix it before moving on.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/SpinCards.kt
git commit -m "feat(ui): teach the mode swipe with a hint the dock plays itself

A gesture with no affordance is a gesture nobody finds. Two variants ship
at once so they can be compared by hand - this app has no analytics, so a
measured A/B is not on the table.

nudge reuses the drag's own Animatable, so the motion shown is exactly the
motion required. arrows draws a SwapHoriz overlay with no pointerInput and
cleared semantics, so it can neither swallow a touch nor be announced as a
control.

Both abort if the user is already dragging."
```

---

### Task 7: Remove the bar and wire the dock

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapChrome.kt:44-58`

`ModeBar` and its only call site must go in **one commit** — deleting either alone leaves the
tree not compiling.

- [ ] **Step 1: Delete `ModeBar`**

In `MapChrome.kt`, delete the whole doc comment and function at lines 44–58:

```kotlin
/** The app's three places. Selecting one also tells the tracking service what
 *  you are riding, which decides the stats it bothers to record. */
@Composable
internal fun ModeBar(selected: TravelMode, onSelect: (TravelMode) -> Unit) {
    NavigationBar {
        TravelMode.entries.forEach { m ->
            NavigationBarItem(
                selected = m == selected,
                onClick = { onSelect(m) },
                icon = { Icon(m.icon, contentDescription = null) },
                label = { Text(m.label) },
            )
        }
    }
}
```

Then delete these three now-unused imports from the same file:

```kotlin
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import com.jellemax.detour.data.TravelMode
```

- [ ] **Step 2: Delete the `bottomBar` slot**

In `MapScreen.kt`, delete lines 1524–1533 — the comment and the whole `bottomBar` argument:

```kotlin
        // Modes are the app's top-level places, so they live in the one bar that
        // is always in reach of a thumb. Navigation hides it: nothing to switch
        // to mid-route, and the map wants the pixels.
        bottomBar = {
            AnimatedVisibility(
                visible = !navigating,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { ModeBar(mode, ::selectMode) }
        },
```

- [ ] **Step 3: Drop the now-always-zero content padding**

`Scaffold` no longer has a bottom bar and already sets
`contentWindowInsets = WindowInsets(0, 0, 0, 0)`, so `calculateBottomPadding()` is permanently 0.
Change the `Box` at `MapScreen.kt:1537-1541` from:

```kotlin
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = scaffoldPadding.calculateBottomPadding()),
        ) {
```

to:

```kotlin
        Box(Modifier.fillMaxSize()) {
```

and change the lambda parameter on the line above from `{ scaffoldPadding ->` to `{ _ ->`.

- [ ] **Step 4: Make the navigation-bar inset unconditional**

This is the regression the bar's removal forces. `navigationBarsPadding()` was conditional on
`navigating` **because the Scaffold's bottom bar consumed the inset the rest of the time**. With
the bar gone, every non-navigating case renders under the gesture bar. At `MapScreen.kt:1617`,
change:

```kotlin
                    .then(if (navigating) Modifier.navigationBarsPadding() else Modifier)
```

to:

```kotlin
                    // Unconditional since the mode bar left: nothing else in this
                    // Scaffold consumes the gesture inset any more. Correct for all
                    // three occupants of this Column - the nav bar always wanted it,
                    // and the candidates card and the dock were relying on ModeBar.
                    .navigationBarsPadding()
```

- [ ] **Step 5: Add the hint scheduling and the blocked reason**

In `MapScreen.kt`, next to `var navigating by remember { mutableStateOf(false) }` (line 268), add:

```kotlin
    val modeSwipesUsed by Settings.modeSwipesUsed.collectAsStateWithLifecycle()
    val swipeHintVariantName by Settings.swipeHintVariant.collectAsStateWithLifecycle()

    /** Non-null while something in flight makes a mode change wrong to allow.
     *  Navigation and an open candidate round need no entry here: both replace
     *  the dock with a different card in the same slot. */
    val switchBlockedReason = ModeSwipePolicy.blockedReason(
        spinning = spinning,
        tracking = stats != null,
    )

    // Scheduled here rather than inside SpinDock because SpinDock is disposed
    // every time the sheet expands or a candidate round opens - a guard in there
    // would replay the hint on every collapse. MapScreen's composition is the
    // map visit: AppRoot has no rememberSaveableStateHolder, so leaving for the
    // Hub disposes this whole screen.
    var hintShown by remember { mutableStateOf(false) }
    var hintRequest by remember { mutableStateOf(false) }
    val hintDue = ModeSwipePolicy.hintDue(
        alreadyShown = hintShown,
        swipesUsed = modeSwipesUsed,
        blocked = switchBlockedReason != null,
    )
    LaunchedEffect(hintDue) {
        if (!hintDue) return@LaunchedEffect
        delay(ModeSwipePolicy.HINT_DELAY_MS)
        hintShown = true
        hintRequest = true
    }
```

Add `import com.jellemax.detour.map.ModeSwipePolicy` if it is not already present. `delay`,
`collectAsStateWithLifecycle`, `LaunchedEffect`, `remember`, `mutableStateOf`, `getValue` and
`setValue` are all already imported in this file.

- [ ] **Step 6: Wire the dock**

At the `BottomCard.COLLAPSED -> SpinDock(` call site (`MapScreen.kt:1743`), after the existing
`onNavigate = { ... },` argument, add:

```kotlin
                            onSwitchMode = { m ->
                                selectMode(m)
                                Settings.setModeSwipesUsed(modeSwipesUsed + 1)
                            },
                            switchBlockedReason = switchBlockedReason,
                            // Not via `error`: LaunchedEffect(error) re-keys on
                            // value, so a second identical refusal in a row would
                            // raise no snackbar at all. It also renders as a red
                            // error line inside SpinSheet, which a refusal is not.
                            onSwitchBlocked = { reason ->
                                scope.launch { snackbarHostState.showSnackbar(reason) }
                            },
                            hintRequest = hintRequest,
                            hintVariant = ModeSwipePolicy.HintVariant.of(swipeHintVariantName),
                            onHintPlayed = { hintRequest = false },
```

- [ ] **Step 7: Build and run the full unit suite**

```bash
devcontainer-exec ./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. If the compiler reports `AnimatedVisibility`, `slideInVertically`,
`slideOutVertically`, `fadeIn` or `fadeOut` as unused imports in `MapScreen.kt`, leave them — they
are used elsewhere in that file. Confirm with
`grep -c 'slideInVertically' app/src/main/java/com/jellemax/detour/ui/MapScreen.kt` before
deleting any import.

- [ ] **Step 8: Verify the hazard greps**

```bash
.claude/skills/detour-staged-refactor/scripts/tier0-greps.sh main
```

Expected: the `rememberUpdatedState` count has gone **up** (Task 5 added five). A *drop* is a
failure — it means one was deleted, which is a behaviour change with no compiler signal. The
script also prints every `LaunchedEffect`/`DisposableEffect` declaration the range touched: the
only new one should be the `LaunchedEffect(hintDue)` from Step 5, and no existing key list should
appear as modified.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/MapScreen.kt \
        app/src/main/java/com/jellemax/detour/ui/MapChrome.kt
git commit -m "feat(ui): drop the bottom mode bar, give the map its 240px back

ModeBar held two options and one bit of state in 240px of permanent
chrome - 11.9% of a 1080x2412 screen once the gesture inset is counted -
directly under a card already showing the same state. The swipe replaces
it; this deletes the bar and its only call site together, because either
alone does not compile.

navigationBarsPadding() becomes unconditional. It was gated on
\`navigating\` only because the Scaffold's bottomBar consumed the inset the
rest of the time; without that, the dock renders under the gesture bar in
every case except navigation.

The refusal snackbar deliberately bypasses \`error\`: LaunchedEffect(error)
re-keys on value, so a second identical refusal would raise nothing.

Closes #70."
```

---

### Task 8: The debug variant switch

**Files:**
- Create: `app/src/debug/java/com/jellemax/detour/debug/DebugSwipeHintReceiver.kt`
- Modify: `app/src/debug/AndroidManifest.xml`
- Modify: `docs/DEBUG_INTENTS.md`

- [ ] **Step 1: Write the receiver**

Create `app/src/debug/java/com/jellemax/detour/debug/DebugSwipeHintReceiver.kt`:

```kotlin
package com.jellemax.detour.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jellemax.detour.data.Settings

/**
 * Switches which mode-swipe hint the spin dock plays, and re-arms it.
 *
 * The hint ships in two variants at once because this app has no analytics,
 * no telemetry and no remote config: a measured A/B is not available, so the
 * two are compared by hand. Once one wins, this receiver, its manifest entry,
 * `Settings.swipeHintVariant` and the losing animation all get deleted.
 *
 * Debug source set only: this class does not exist in a release build, and
 * neither does the manifest entry that registers it (app/src/debug/AndroidManifest.xml).
 *
 * ```
 * # play the arrows variant next time
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
 *     --es variant arrows
 *
 * # back to the nudge
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
 *     --es variant nudge
 *
 * # re-arm: zero the swipe counter so the hint fires again
 * adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
 *     --ez reset true
 * ```
 *
 * The reset arm is not a convenience. The hint retires permanently after three
 * successful swipes, so without it there is exactly one chance to judge each
 * variant on a given install - and no way at all to see it again afterwards
 * short of clearing app data, which would take the trip history with it.
 *
 * Leaving and re-entering the map screen is still required after a broadcast:
 * the hint fires once per map visit.
 */
class DebugSwipeHintReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Both are in-memory writes plus a SharedPreferences put, so unlike
        // DebugTripEndedReceiver this needs no goAsync().
        intent.getStringExtra(EXTRA_VARIANT)?.let { variant ->
            Settings.setSwipeHintVariant(variant)
            Log.i(TAG, "hint variant set to '$variant'")
        }
        if (intent.getBooleanExtra(EXTRA_RESET, false)) {
            Settings.setModeSwipesUsed(0L)
            Log.i(TAG, "swipe counter reset; the hint will fire on the next map visit")
        }
    }

    private companion object {
        const val TAG = "DebugSwipeHint"
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_RESET = "reset"
    }
}
```

- [ ] **Step 2: Register it**

In `app/src/debug/AndroidManifest.xml`, inside `<application>`, after the existing
`DebugTripEndedReceiver` entry:

```xml
        <!-- Switches which mode-swipe hint the spin dock plays and re-arms it,
             so the two variants can be compared without a rebuild. Same
             exported="true" trade-off as the receiver above, and the same
             reason it is acceptable: this entry never reaches a release build.
             See the receiver's own doc for the commands. -->
        <receiver
            android:name="com.jellemax.detour.debug.DebugSwipeHintReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.jellemax.detour.DEBUG_SWIPE_HINT" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 3: Document it**

Append a section to `docs/DEBUG_INTENTS.md`, matching the format of the sections already there:

```markdown
## Mode-swipe hint (`DebugSwipeHintReceiver`)

The spin dock's mode swipe plays a discoverability hint in one of two variants.
There is no analytics in this app, so the two are compared by hand rather than
measured. Switch between them without rebuilding:

```sh
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
  --es variant arrows      # or: nudge
```

The hint fires once per map visit and retires permanently after three
successful swipes. To see it again, re-arm the counter:

```sh
adb shell am broadcast \
  -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver \
  --ez reset true
```

Both arms can be sent in one broadcast. After either, leave the map screen and
come back — the hint is scheduled once per visit.

```sh
adb logcat -s DebugSwipeHint   # the receiver logs what it set
```

Deleted along with the losing variant once one of them wins.
```

- [ ] **Step 4: Build the debug variant**

```bash
devcontainer-exec ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Confirm nothing leaked into release**

```bash
devcontainer-exec ./gradlew :app:assembleRelease
grep -rn "DebugSwipeHintReceiver" app/src/main app/src/release 2>/dev/null || echo "clean: debug source set only"
```

Expected: `BUILD SUCCESSFUL`, then `clean: debug source set only`.

- [ ] **Step 6: Commit**

```bash
git add app/src/debug/java/com/jellemax/detour/debug/DebugSwipeHintReceiver.kt \
        app/src/debug/AndroidManifest.xml \
        docs/DEBUG_INTENTS.md
git commit -m "feat(debug): switch and re-arm the mode-swipe hint over adb

Two hint variants ship at once and there is no analytics to choose between
them, so the choice is made by hand. Rebuilding to flip a constant makes a
back-to-back comparison cost two minutes a look; this makes it cost two
seconds.

The reset arm matters more than the switch: the hint retires after three
successful swipes, so without it each variant gets exactly one showing per
install and no way back short of clearing app data - which would take the
trip history with it.

Debug source set only; nothing here reaches a release build."
```

---

### Task 9: Version bump and the full gate

**Files:**
- Modify: `app/build.gradle.kts:76`

- [ ] **Step 1: Bump the version**

Per `CLAUDE.md`: a new backward-compatible feature bumps the minor. Change line 76 from:

```kotlin
        versionName = "1.79.1"
```

to:

```kotlin
        versionName = "1.80.0"
```

Do **not** touch `versionCode` on line 75 — it is CI-stamped from the run number.

- [ ] **Step 2: Run everything CI runs**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
devcontainer-exec ./gradlew :app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease
.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh
```

Expected: `BUILD SUCCESSFUL` from both Gradle invocations, and the secret-fields script exiting 0.
The release build matters on its own: R8 catches what a debug build does not.

- [ ] **Step 3: Re-check the state-hazard preconditions**

```bash
.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
```

Expected: this script asserts fixed counts of `rememberUpdatedState`, `lastFix` and
`withFrameNanos` lines in `MapScreen.kt`. This change adds no `rememberUpdatedState` to
`MapScreen.kt` — all five went into `SpinCards.kt` — and touches no `lastFix` collector and no
frame loop, so **all five assertions should still pass**. If any fails, something was edited that
this plan did not intend; stop and read the diff before continuing.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore(release): 1.80.0

Mode swipe on the spin dock, bottom mode bar removed. New feature,
backward compatible: minor bump per CLAUDE.md. versionCode is CI-stamped
and deliberately untouched."
```

---

### Task 10: Verify it on a device

Nothing above proves the gesture works. There is no Compose test infrastructure in this repo —
CI runs `:app:testDebugUnitTest` and `:shared:testDebugUnitTest` only, with no Robolectric, no
`compose-ui-test` and no `androidTest` source set. This task is the only evidence that exists for
everything in Tasks 5–8.

Read the `detour-adb` skill before the first adb command. In particular: the installed package is
`io.github.maxke24.detour.debug`, **not** the Kotlin package `com.jellemax.detour`, and
`adb uninstall` / `pm clear` destroy the user's trip history and are never the answer to a blocked
step.

- [ ] **Step 1: Install and capture the "after" state**

```bash
devcontainer-exec ./gradlew :app:installDebug
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
adb exec-out screencap -p > /tmp/after.png
adb exec-out uiautomator dump /dev/tty > /tmp/after-ui.xml
```

- [ ] **Step 2: Confirm the bar is gone and the dock cleared the gesture bar**

```bash
grep -c 'text="Moto"' /tmp/after-ui.xml
grep -o 'bounds="\[36,[0-9]*\]\[1044,[0-9]*\]"' /tmp/after-ui.xml | head -1
```

Expected: the first prints `0` — no ModeBar node. The second prints the dock's bounds; its lower
edge must be **above 2364** (the gesture inset starts there on this device), where before the
change it sat at 2088 with the bar below it.

- [ ] **Step 3: Exercise the gesture by hand**

Confirm each, and say which artifact shows it:

- A slow drag under 84 dp springs back with no mode change.
- A drag past 84 dp commits: icon, `25 km`/`120 km` and the `Car · …`/`Moto · …` subtitle all change together.
- A short fast flick commits.
- A tap on the left cell still expands the sheet — the drag did not eat the click.
- Inside the expanded sheet, both sliders and both pill rows still drag horizontally.

- [ ] **Step 4: Exercise both refusals**

Start a spin, and while the dice shows its spinner, drag the dock: it should resist ~8 dp, spring
back, and raise "Cancel the spin to change mode". Drag a **second** time without dismissing the
first snackbar — the message must appear again. (A second identical message vanishing is the
`LaunchedEffect(error)` equal-key trap; if it happens, the wiring in Task 7 Step 6 went through
`error` instead of `showSnackbar`.)

Then start a trip and repeat: "Stop the trip to change mode".

- [ ] **Step 5: Exercise both hint variants**

```bash
adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver --ez reset true
adb shell am broadcast -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugSwipeHintReceiver --es variant nudge
adb logcat -s DebugSwipeHint -d | tail -4
```

Leave the map screen (open the Hub), come back, and wait 4 s: the cell should nudge twice.
Repeat with `--es variant arrows` and confirm the arrow fades in and out instead. Confirm the
arrows overlay does **not** swallow a tap — tapping through it still expands the sheet.

- [ ] **Step 6: Confirm the hint retires**

With the counter reset, swipe three times, then leave the map and return. No hint should play.
Confirm the counter survived a process restart:

```bash
adb shell am force-stop io.github.maxke24.detour.debug
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
```

Still no hint. Then `--ez reset true` and confirm it comes back.

- [ ] **Step 7: Check TalkBack**

Enable TalkBack, focus the dock, and open its actions menu (swipe up-then-right by default).
"Switch to Moto" / "Switch to Car" should be listed and should work. Start a spin and confirm the
action is refused rather than switching silently.

- [ ] **Step 8: Report**

Write up what was observed and name the artifact for each claim. Per `detour-adb`: an assertion
backed by a `uiautomator` node plus a screenshot is a result; the same sentence backed by nothing
is a guess, and unverified device claims are this project's known failure mode.

**No GPS replay is required for this change.** No `lastFix` collector moved, no `withContext`
entered one, and no effect key list changed — the three things that would make this a Tier 2
change needing a before/after replay.

---

## Self-review notes

**Spec coverage.** Every section of the spec maps to a task: the bar removal and inset fix →
Task 7; the gesture and its thresholds → Tasks 1, 2, 5; the semantics action → Task 5; the gate
table → Tasks 1, 7; the hint state, scheduling and both variants → Tasks 3, 4, 6, 7; the debug
switch and its docs → Task 8; the version bump → Task 9; the verification section → Tasks 9 and
10. The spec's "radius reset" section is a decision to change nothing and correctly has no task.

**Naming consistency.** `ModeSwipePolicy` members are referenced identically across tasks:
`blockedReason(spinning, tracking)`, `other(mode)`, `dragOffsetDp(rawDp, blocked)`,
`commits(offsetDp, velocityDpPerS, blocked)`, `hintDue(alreadyShown, swipesUsed, blocked)`,
`HintVariant.of(name)`, and the constants `COMMIT_DP`, `FLING_DP_PER_S`, `BLOCKED_CLAMP_DP`,
`RESISTANCE`, `HINT_AFTER_USES`, `HINT_DELAY_MS`, `HINT_NUDGE_DP`. `SpinDock`'s six new
parameters — `onSwitchMode`, `switchBlockedReason`, `onSwitchBlocked`, `hintRequest`,
`hintVariant`, `onHintPlayed` — are declared in Tasks 5 and 6 and passed in Task 7 Step 6 under
exactly those names. `Settings.modeSwipesUsed` / `setModeSwipesUsed` and
`Settings.swipeHintVariant` / `setSwipeHintVariant` are consistent between Tasks 4, 7 and 8.

**Known follow-up, not in this plan.** Once a hint variant wins, the loser, `HintVariant`,
`Settings.swipeHintVariant`, `DebugSwipeHintReceiver`, its manifest entry and its
`DEBUG_INTENTS.md` section all get deleted. That is a separate issue to file, not dead code to
leave behind.
