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

/** What launch does about the [requiredStartupPermissions] (#501). */
enum class StartupAsk {
    /** Everything is granted, or location is and the rest was refused once. */
    READY,

    /** First ask: the explainer, then the system dialogs on its Continue. */
    EXPLAIN,

    /** Location was refused after the explainer: the denied snackbar, not the dialog again. */
    LOCATION_DENIED,
}

/**
 * What launch does when [missing] says some startup permission is not granted.
 *
 * The explainer goes before the first system dialog, once; [explainerShown] is
 * whether the rider has pressed its Continue. After that a refusal is not
 * re-asked at launch: a refused activity or notification permission leaves
 * the map to start without it (Settings' dormancy row and Circles ask again
 * where they are needed), and a refused location gets the snackbar whose
 * Allow / Open settings action is the way back (#499).
 *
 * Android-only like the rest of this file: iOS asks per permission at the
 * point of use, with its own usage strings.
 */
fun startupAsk(missing: Boolean, explainerShown: Boolean, locationGranted: Boolean): StartupAsk = when {
    !missing -> StartupAsk.READY
    !explainerShown -> StartupAsk.EXPLAIN
    locationGranted -> StartupAsk.READY
    else -> StartupAsk.LOCATION_DENIED
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

/** What the denied-location error's action does (#499). */
enum class LocationRecovery { ASK_AGAIN, OPEN_SETTINGS }

/**
 * Which [LocationRecovery] to offer once location has been denied.
 *
 * [canAskAgain] is Android's `shouldShowRequestPermissionRationale`: true after
 * one denial, false once the system has stopped showing the dialog (a second
 * denial, or "Don't ask again") — then only app settings can grant it. It is
 * also false before the first ask, which this never sees: the action only
 * exists after a denial.
 */
fun locationRecovery(canAskAgain: Boolean): LocationRecovery =
    if (canAskAgain) LocationRecovery.ASK_AGAIN else LocationRecovery.OPEN_SETTINGS
