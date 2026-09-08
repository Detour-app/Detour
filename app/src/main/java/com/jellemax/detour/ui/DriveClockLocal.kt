package com.jellemax.detour.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.jellemax.detour.tracking.DriveClock
import com.jellemax.detour.tracking.DriveClocks

/**
 * The drive's clock, for the composables that read it.
 *
 * This app defines no other `CompositionLocal` of its own — it consumes
 * `LocalDensity` and `LocalContentColor`, and that is all — so this is close to
 * a new pattern here, which `CONTRIBUTING.md` asks to be deliberate about. #307
 * left the choice open; the argument for it over a parameter, and over parking
 * the clock on an existing holder:
 *
 * - **A parameter costs six signatures and a drilling pass.** `MapCameraLoops`,
 *   `MapPositionMarker`, `MapNavigationSession`, `MapHazardAlerts` and
 *   `rememberActiveTripCardState` each read it, and `MapScreen` would have to
 *   hold it and pass it to four of them. `MapNavigationSession` already takes
 *   five parameters, so it would reach six against `docs/guidelines/boundaries.md`
 *   §8.4's gate of seven — on files that are mid-way through the MapScreen
 *   refactor chain.
 * - **An existing holder is the wrong lifetime.** `MapScreenState` is
 *   screen-scoped and `RetainedMap` is Activity-scoped; the clock is
 *   process-scoped. §8.1's first test — "different lifetime → different owner" —
 *   calls that non-negotiable, and it is the mistake `RetainedMap` itself exists
 *   to have fixed once.
 *
 * A `staticCompositionLocalOf` rather than `compositionLocalOf`: reads are not
 * tracked, which is correct here because the value never changes. [DriveClocks]
 * holds one instance for the life of the process in both variants — a replay
 * re-paces the clock *inside* that instance — so no provider ever supplies a
 * second one, and nothing needs to recompose when a replay starts.
 *
 * **That is also what makes it safe to capture.** A `CompositionLocal` can only
 * be read in a `@Composable`, so `MapCamera`'s `withFrameNanos` loops hoist it
 * to composition scope and hold the instance for the life of the effect.
 * `.claude/skills/detour-compose-state-hazards` §2 is about exactly that kind of
 * plain-value capture going stale; it cannot here, because there is never a
 * second instance to go stale against. Hoist it, do not try to re-read it per
 * frame.
 *
 * **The default is the wiring.** Nothing calls `CompositionLocalProvider` for
 * this, and nothing should: [DriveClocks] already holds the one clock the
 * process has, so the default supplies it to every composable and a provider
 * would only be a second way to say the same thing. The seam is here if a test
 * harness or a preview ever needs to override it — this app has neither today
 * (no Robolectric, no `compose-ui-test`), which is why the unit tests for the
 * timing machinery live against [com.jellemax.detour.tracking.TripEndDetector]
 * instead of against a composable.
 */
val LocalDriveClock = staticCompositionLocalOf<DriveClock> { DriveClocks.current }
