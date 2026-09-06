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
    hasActiveConvoy: Boolean,
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
    LaunchedEffect(convoyConnected, hasActiveConvoy) {
        if (shouldRequestMic(
                pushToTalkEnabled = Features.pushToTalk,
                convoyConnected = convoyConnected,
                hasActiveConvoy = hasActiveConvoy,
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
