package com.jellemax.detour.map

import android.Manifest
import android.os.Build

/**
 * Which permissions the map needs, and when to ask for the two that cannot be
 * asked for alongside the rest.
 *
 * These were three conditions inline in `MapScreen`, each gated on
 * `Build.VERSION.SDK_INT`. That is the worst place for a rule nobody can run:
 * the branches differ only on API levels the developer's own phone is not, so
 * the branch that matters is the one never seen. A wrong gate here is not a
 * crash — it is a permission silently never requested, on somebody else's
 * Android version.
 *
 * Parameterised on the SDK level rather than reading `Build.VERSION.SDK_INT`
 * themselves, the same way `tracking/Dormancy.kt` takes its permission facts as
 * booleans, so every branch is reachable from a test on one machine.
 */

/**
 * The permissions to request at first composition, for [sdkInt].
 *
 * Fine and coarse location always; activity recognition from Q, which is what
 * lets the tracker use the cheap in-vehicle probe rather than hold a fast GPS
 * request open; notifications from Tiramisu, without which the foreground
 * service runs with no visible notification.
 */
fun requiredStartupPermissions(sdkInt: Int): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (sdkInt >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

/**
 * Whether to raise the app's own background-location disclosure.
 *
 * Background location must be requested separately from fine location and only
 * after it is granted (a system requirement from Android 11), and Play requires
 * our own disclosure before the system prompt. Below Q there is no separate
 * background permission to ask for, so there is nothing to disclose.
 */
fun needsBackgroundDisclosure(sdkInt: Int, backgroundGranted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.Q && !backgroundGranted

/**
 * Whether to ask for the microphone.
 *
 * Deliberately not asked for upfront with location: nothing needs it until
 * push-to-talk does. [hasActiveConvoy] is checked as well as [convoyConnected]
 * because the same socket also stays connected for a circle's arrival
 * notifications with no convoy joined at all, which needs no microphone — and
 * with [pushToTalkEnabled] off no talk button ever renders, so asking would buy
 * nothing in that build.
 */
fun shouldRequestMic(
    pushToTalkEnabled: Boolean,
    convoyConnected: Boolean,
    hasActiveConvoy: Boolean,
    micGranted: Boolean,
): Boolean = pushToTalkEnabled && convoyConnected && hasActiveConvoy && !micGranted
