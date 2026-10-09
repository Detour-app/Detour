package com.jellemax.detour.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jellemax.detour.data.Features
import com.jellemax.detour.data.Settings
import com.jellemax.detour.map.LocationRecovery
import com.jellemax.detour.map.StartupAsk
import com.jellemax.detour.map.locationRecovery
import com.jellemax.detour.map.requiredStartupPermissions
import com.jellemax.detour.map.shouldRequestMic
import com.jellemax.detour.map.startupAsk
import kotlinx.coroutines.launch

/**
 * Every permission the map asks for, and when.
 *
 * Four launchers and three effects that had nothing to do with the rest of
 * MapScreen except that a launcher must be created inside a composable — which
 * is also why this is a composable rather than a plain class, and why it cannot
 * move to `map/` beside the policy it calls.
 *
 * What *is* in `map/PermissionPolicy.kt` is every decision this makes: which
 * permissions the SDK level needs, and whether the microphone is worth asking
 * for. This file is the Android plumbing around those answers, and holds no
 * rule of its own.
 *
 * Returns the background-location launcher and the permission explainer's
 * Continue, because the dialogs that fire them live in `MapDialogs.kt`, and the
 * way back from a denied location, because Spin offers it too.
 *
 * Also shows `s.error` in the snackbar: the one error that carries an action
 * is the denied location this file raises (#499).
 */
@Composable
internal fun rememberMapPermissions(
    s: MapScreenState,
    snackbarHostState: SnackbarHostState,
    convoyConnected: Boolean,
    activeConvoyId: String?,
    onLocationReady: () -> Unit,
): MapPermissions {
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
            s.error = LOCATION_DENIED_ERROR
        }
    }

    // Not keyed on anything that changes, so it runs once per composition: a
    // recreation while the explainer is up raises it again, which is right —
    // its Continue has not been pressed yet.
    LaunchedEffect(Unit) {
        val missing = requiredStartupPermissions(Build.VERSION.SDK_INT).any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        when (startupAsk(missing, Settings.permissionExplainerShown(), fineLocationGranted(context))) {
            StartupAsk.READY -> ready()
            StartupAsk.EXPLAIN -> s.showPermissionExplainer = true
            StartupAsk.LOCATION_DENIED -> s.error = LOCATION_DENIED_ERROR
        }
    }

    fun continueFromExplainer() {
        s.showPermissionExplainer = false
        Settings.setPermissionExplainerShown()
        permissionLauncher.launch(requiredStartupPermissions(Build.VERSION.SDK_INT).toTypedArray())
    }

    val offerLocationIfDenied = rememberLocationRecovery(s, snackbarHostState, permissionLauncher, ready)

    return MapPermissions(bgLocationLauncher, offerLocationIfDenied, ::continueFromExplainer)
}

/**
 * The way back from a denied location (#499), which used to be a dead end
 * until the next cold start: the snackbar for `s.error`, its Allow / Open
 * settings action, and the check Spin calls. Returns that check.
 */
@Composable
private fun rememberLocationRecovery(
    s: MapScreenState,
    snackbarHostState: SnackbarHostState,
    permissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    onLocationReady: () -> Unit,
): () -> Boolean {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    // As in rememberMapPermissions: the launcher callback outlives a recomposition.
    val ready by rememberUpdatedState(onLocationReady)

    // Settings is a round trip, not a dead end: a rider who grants location
    // there comes back to a map that starts, rather than one that waits for
    // the next cold start.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (fineLocationGranted(context)) {
            // The Allow path clears it before asking; this one must clear it
            // after, or the sheet keeps saying location is required.
            if (s.error == LOCATION_DENIED_ERROR) s.error = null
            ready()
        }
    }

    suspend fun offerLocationRecovery() {
        when (showLocationDenied(snackbarHostState, locationRecovery(canAskForLocationAgain(activity)))) {
            LocationRecovery.ASK_AGAIN -> {
                // Cleared so a second denial changes the key again and gets
                // its snackbar — now offering settings.
                s.error = null
                permissionLauncher.launch(requiredStartupPermissions(Build.VERSION.SDK_INT).toTypedArray())
            }
            LocationRecovery.OPEN_SETTINGS -> settingsLauncher.launch(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null)),
            )
            null -> Unit
        }
    }

    // `error` has a dozen writers and, until the snackbar, one reader — inside
    // SpinSheet, which is collapsed by default. A denied location permission
    // therefore reported itself to nobody. The snackbar shows it whatever the
    // bottom card is doing; the sheet keeps its own copy for when it is open.
    LaunchedEffect(s.error) {
        when (val error = s.error) {
            null -> Unit
            LOCATION_DENIED_ERROR -> offerLocationRecovery()
            else -> snackbarHostState.showSnackbar(error)
        }
    }

    fun offerLocationIfDenied(): Boolean {
        if (fineLocationGranted(context)) return false
        // Through s.error when it changes; directly when it already holds this
        // string, because an equal key raises no second snackbar.
        if (s.error == LOCATION_DENIED_ERROR) {
            scope.launch { offerLocationRecovery() }
        } else {
            s.error = LOCATION_DENIED_ERROR
        }
        return true
    }

    return ::offerLocationIfDenied
}

private fun fineLocationGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Whether the system will still show its location dialog — no Activity, no dialog. */
private fun canAskForLocationAgain(activity: Activity?): Boolean =
    activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
        activity, Manifest.permission.ACCESS_FINE_LOCATION,
    )

/**
 * Shows the denied-location snackbar with [recovery]'s action, and returns it
 * if the rider tapped it. Decided when the snackbar shows, not when location
 * was denied: whether the system will still show its dialog can change in
 * between.
 */
private suspend fun showLocationDenied(
    snackbarHostState: SnackbarHostState,
    recovery: LocationRecovery,
): LocationRecovery? {
    // A second Spin tap while it is up would queue a copy behind it.
    if (snackbarHostState.currentSnackbarData?.visuals?.message == LOCATION_DENIED_ERROR) return null
    val result = snackbarHostState.showSnackbar(
        message = LOCATION_DENIED_ERROR,
        actionLabel = when (recovery) {
            LocationRecovery.ASK_AGAIN -> "Allow"
            LocationRecovery.OPEN_SETTINGS -> "Open settings"
        },
        // An action makes the default Indefinite: it would never leave, and
        // every later s.error snackbar would queue behind it.
        duration = SnackbarDuration.Long,
    )
    return recovery.takeIf { result == SnackbarResult.ActionPerformed }
}

/** The red sheet text and the snackbar for a denied location — one string, so
 *  MapScreen can tell this error apart and give it its action. */
internal const val LOCATION_DENIED_ERROR = "Location permission is required"

internal class MapPermissions(
    val bgLocationLauncher: ManagedActivityResultLauncher<String, Boolean>,
    /** If location is not granted, shows the denied snackbar with Allow or
     *  Open settings and returns true; false when there is nothing to offer. */
    val offerLocationIfDenied: () -> Boolean,
    /** The permission explainer's Continue (#501): fires the system dialogs. */
    val continueFromExplainer: () -> Unit,
)
