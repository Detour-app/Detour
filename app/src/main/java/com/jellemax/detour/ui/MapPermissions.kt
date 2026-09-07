package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.jellemax.detour.data.Features
import com.jellemax.detour.map.requiredStartupPermissions
import com.jellemax.detour.map.shouldRequestMic

/**
 * Every permission the map asks for, and when.
 *
 * Three launchers and two effects that had nothing to do with the rest of
 * MapScreen except that a launcher must be created inside a composable — which
 * is also why this is a composable rather than a plain class, and why it cannot
 * move to `map/` beside the policy it calls.
 *
 * What *is* in `map/PermissionPolicy.kt` is every decision this makes: which
 * permissions the SDK level needs, and whether the microphone is worth asking
 * for. This file is the Android plumbing around those answers, and holds no
 * rule of its own.
 *
 * Returns the background-location launcher, because the disclosure dialog is
 * what fires it and that lives in `MapDialogs.kt`.
 */
@Composable
internal fun rememberMapPermissions(
    s: MapScreenState,
    convoyConnected: Boolean,
    activeConvoyId: String?,
    onLocationReady: () -> Unit,
): ManagedActivityResultLauncher<String, Boolean> {
    val context = LocalContext.current
    // rememberUpdatedState, because both users below sit inside effects that
    // outlive a recomposition: a captured lambda would keep calling the first
    // composition's copy (compose-state-hazards §1).
    val ready by rememberUpdatedState(onLocationReady)

    // Background location must be requested separately from fine location,
    // after it is granted (system requirement on Android 11+).
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Mic permission is asked for once a convoy is actually joined, not
    // upfront with location — nothing needs it until push-to-talk does.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    // Keyed on the convoy's id, not on whether there is one: a rider who
    // switches straight from convoy A to convoy B on the same socket has to be
    // asked again if they refused the first time, and a boolean key does not
    // change on that switch. activeConvoyId != null rather than just
    // convoyConnected because the same socket also stays connected for a
    // circle's arrival notifications with no convoy joined at all (see
    // ConvoyLiveClient.setNotifyCircles), which needs no microphone.
    // Features.pushToTalk: off means no talk button ever renders, so asking
    // for the mic here would buy nothing this build (#154).
    LaunchedEffect(convoyConnected, activeConvoyId) {
        if (shouldRequestMic(
                pushToTalkEnabled = Features.pushToTalk,
                convoyConnected = convoyConnected,
                hasActiveConvoy = activeConvoyId != null,
                micGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            ready()
        } else {
            s.error = "Location permission is required"
        }
    }

    LaunchedEffect(Unit) {
        val needed = requiredStartupPermissions(Build.VERSION.SDK_INT)
        val missing = needed.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missing) {
            ready()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    return bgLocationLauncher
}
