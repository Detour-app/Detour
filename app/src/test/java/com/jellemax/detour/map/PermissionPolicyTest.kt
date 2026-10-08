package com.jellemax.detour.map

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three API-level permission gates that used to sit inline in
 * MapScreen, where no test could reach them.
 *
 * The point of every case below is a branch that the developer's own phone is
 * not: a wrong `>=` here does not crash, it silently never asks for a
 * permission on somebody else's Android version, and the feature that needed it
 * just quietly does nothing.
 */
class PermissionPolicyTest {

    @Test
    fun locationIsAlwaysRequestedEvenOnTheOldestSupportedRelease() {
        // minSdk is 26, below both conditional gates — the case a modern
        // device can never exercise.
        val p = requiredStartupPermissions(Build.VERSION_CODES.O)
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            p,
        )
    }

    @Test
    fun activityRecognitionArrivesAtQAndNotBefore() {
        // Without it the tracker cannot use the cheap in-vehicle probe and
        // falls back to holding a fast GPS request open.
        assertFalse(
            requiredStartupPermissions(Build.VERSION_CODES.P)
                .contains(Manifest.permission.ACTIVITY_RECOGNITION)
        )
        assertTrue(
            requiredStartupPermissions(Build.VERSION_CODES.Q)
                .contains(Manifest.permission.ACTIVITY_RECOGNITION)
        )
    }

    @Test
    fun notificationsArriveAtTiramisuAndNotBefore() {
        // Without it the foreground service runs with no visible notification.
        assertFalse(
            requiredStartupPermissions(Build.VERSION_CODES.S_V2)
                .contains(Manifest.permission.POST_NOTIFICATIONS)
        )
        assertTrue(
            requiredStartupPermissions(Build.VERSION_CODES.TIRAMISU)
                .contains(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test
    fun backgroundDisclosureIsOnlyForQAndUpAndOnlyWhenNotAlreadyGranted() {
        // Below Q there is no separate background permission to ask for, so
        // showing our own disclosure would be a dialog leading nowhere.
        assertFalse(needsBackgroundDisclosure(Build.VERSION_CODES.P, backgroundGranted = false))
        assertTrue(needsBackgroundDisclosure(Build.VERSION_CODES.Q, backgroundGranted = false))
        // Already granted: nothing to disclose, and re-asking every launch is
        // exactly the nag Play's policy is about.
        assertFalse(needsBackgroundDisclosure(Build.VERSION_CODES.TIRAMISU, backgroundGranted = true))
    }

    @Test
    fun theMicIsAskedForOnlyOnceEveryReasonToNeedItHolds() {
        assertTrue(shouldRequestMic(true, convoyConnected = true, hasActiveConvoy = true, micGranted = false))
        // A circle's arrival notifications keep the same socket connected with
        // no convoy joined — that needs no microphone.
        assertFalse(shouldRequestMic(true, convoyConnected = true, hasActiveConvoy = false, micGranted = false))
        // Feature off: no talk button ever renders, so the permission buys
        // nothing in this build.
        assertFalse(shouldRequestMic(false, convoyConnected = true, hasActiveConvoy = true, micGranted = false))
        // Already granted, and not connected: both are no-ops.
        assertFalse(shouldRequestMic(true, convoyConnected = true, hasActiveConvoy = true, micGranted = true))
        assertFalse(shouldRequestMic(true, convoyConnected = false, hasActiveConvoy = true, micGranted = false))
    }

    @Test
    fun aDeniedLocationIsAskedAgainWhileTheSystemWillStillShowTheDialog() {
        // One denial: the dialog still appears, so Allow re-requests.
        assertEquals(LocationRecovery.ASK_AGAIN, locationRecovery(canAskAgain = true))
        // Second denial: launching the request returns denied with no dialog,
        // so only app settings can grant it (#499).
        assertEquals(LocationRecovery.OPEN_SETTINGS, locationRecovery(canAskAgain = false))
    }

    @Test
    fun theExplainerGoesBeforeTheFirstAskAndARefusalIsNotReAskedAtLaunch() {
        // #501: nothing missing, nothing to explain or ask.
        assertEquals(StartupAsk.READY, startupAsk(missing = false, explainerShown = false, locationGranted = true))
        // A fresh install: the explainer, not a system dialog straight away.
        assertEquals(StartupAsk.EXPLAIN, startupAsk(missing = true, explainerShown = false, locationGranted = false))
        // Location granted, activity or notifications refused once: start the
        // map without them rather than raise their dialogs every launch.
        assertEquals(StartupAsk.READY, startupAsk(missing = true, explainerShown = true, locationGranted = true))
        // Location refused: the denied snackbar's Allow / Open settings, not
        // the explainer again.
        assertEquals(
            StartupAsk.LOCATION_DENIED,
            startupAsk(missing = true, explainerShown = true, locationGranted = false),
        )
    }
}
