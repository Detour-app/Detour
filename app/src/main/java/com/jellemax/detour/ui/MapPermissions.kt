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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jellemax.detour.data.Features
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.Settings
import com.jellemax.detour.map.LocationRecovery
import com.jellemax.detour.map.StartupAsk
import com.jellemax.detour.map.locationRecovery
import com.jellemax.detour.map.requiredStartupPermissions
import com.jellemax.detour.map.shouldRequestMic
import com.jellemax.detour.map.startupAsk
import com.jellemax.detour.map.startupPermissionsToAsk
import com.jellemax.detour.tracking.TripTrackingService
import com.jellemax.detour.tracking.hasLocationPermission
import com.jellemax.detour.tracking.hasPreciseLocation
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
 * Continue, because the dialogs that fire them live in `MapDialogs.kt`, the
 * way back from a denied location, because Spin offers it too, and the start
 * of a recorded trip, because only that needs precise location (#500).
 *
 * Also shows `s.error` in the snackbar: the two errors that carry an action
 * are the denied and the not-precise location this file raises (#499, #500).
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
    ) { _ ->
        // Approximate is enough for the map, spin and search (#500); a trip
        // asks for precise when it starts recording.
        if (hasLocationPermission(context)) {
            ready()
        } else {
            s.error = LOCATION_DENIED_ERROR
        }
    }

    // Not keyed on anything that changes, so it runs once per composition: a
    // recreation while the explainer is up raises it again, which is right —
    // its Continue has not been pressed yet.
    LaunchedEffect(Unit) {
        val missing = startupPermissionsToAsk(
            sdkInt = Build.VERSION.SDK_INT,
            granted = requiredStartupPermissions(Build.VERSION.SDK_INT).filterTo(mutableSetOf()) {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        ).isNotEmpty()
        when (startupAsk(missing, Settings.permissionExplainerShown(), hasLocationPermission(context))) {
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
    val recording = rememberPreciseForRecording(snackbarHostState)

    return MapPermissions(
        bgLocationLauncher = bgLocationLauncher,
        offerLocationIfDenied = offerLocationIfDenied,
        continueFromExplainer = ::continueFromExplainer,
        recordTrip = recording::recordTrip,
        dropPendingTrip = recording::dropPendingTrip,
    )
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
        if (hasLocationPermission(context)) {
            // The Allow path clears it before asking; this one must clear it
            // after, or the sheet keeps saying location is required.
            if (s.error == LOCATION_DENIED_ERROR) s.error = null
            ready()
        }
    }

    suspend fun offerLocationRecovery() {
        val recovery = locationRecovery(canAskForLocationAgain(activity))
        when (showLocationSnackbar(snackbarHostState, LOCATION_DENIED_ERROR, recovery)) {
            LocationRecovery.ASK_AGAIN -> {
                // Cleared so a second denial changes the key again and gets
                // its snackbar — now offering settings.
                s.error = null
                permissionLauncher.launch(requiredStartupPermissions(Build.VERSION.SDK_INT).toTypedArray())
            }
            LocationRecovery.OPEN_SETTINGS -> settingsLauncher.launch(appSettingsIntent(context))
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
        if (hasLocationPermission(context)) return false
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

/**
 * Recording a trip with only approximate location granted (#500): ask for
 * precise, start the trip if the rider grants it, and record nothing if they
 * refuse — saying why, with Allow or Open settings as for a denied location.
 *
 * Not through `s.error` like the denied location: this snackbar belongs to one
 * trip start, not to the screen, and has no sheet text to keep.
 */
@Composable
private fun rememberPreciseForRecording(snackbarHostState: SnackbarHostState): PreciseForRecording {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val gate = remember { PreciseForRecording(context) }

    // Fine and coarse together: with coarse already held, Android 12+ shows
    // this as the "change to precise" dialog.
    val preciseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> if (!gate.startPendingIfPrecise() && gate.isPending) gate.onRefused() }

    // Sent there for precise: the map already had location, so a grant only
    // starts the waiting trip.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { gate.startPendingIfPrecise() }

    SideEffect {
        gate.askForPrecise = { preciseLauncher.launch(PRECISE_LOCATION_PERMISSIONS) }
        gate.onRefused = {
            scope.launch {
                val recovery = locationRecovery(canAskForLocationAgain(activity))
                when (showLocationSnackbar(snackbarHostState, PRECISE_LOCATION_NEEDED_ERROR, recovery)) {
                    LocationRecovery.ASK_AGAIN -> preciseLauncher.launch(PRECISE_LOCATION_PERMISSIONS)
                    LocationRecovery.OPEN_SETTINGS -> settingsLauncher.launch(appSettingsIntent(context))
                    // Dismissed or timed out: the rider chose not to record this one.
                    null -> gate.dropPendingTrip()
                }
            }
        }
    }
    return gate
}

/**
 * The trip waiting on a precise-location answer, and where it was headed. Held
 * across the system dialog and a settings round trip; dropped when the rider
 * dismisses the snackbar or navigation ends first — a late grant must not
 * start a navigation trip no navigation will ever end.
 */
private class PreciseForRecording(private val context: Context) {
    private var pendingTo: LatLon? = null
    var isPending = false
        private set
    /** Set after every composition, so they always reach the current launchers. */
    var askForPrecise: () -> Unit = {}
    var onRefused: () -> Unit = {}

    fun recordTrip(to: LatLon?) {
        if (hasPreciseLocation(context)) {
            TripTrackingService.start(context, to?.lat, to?.lon)
            return
        }
        pendingTo = to
        isPending = true
        askForPrecise()
    }

    fun dropPendingTrip() {
        isPending = false
        pendingTo = null
    }

    /** Starts the waiting trip if precise is now granted; false if it is not. */
    fun startPendingIfPrecise(): Boolean {
        if (!hasPreciseLocation(context)) return false
        if (isPending) TripTrackingService.start(context, pendingTo?.lat, pendingTo?.lon)
        dropPendingTrip()
        return true
    }
}

private val PRECISE_LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun appSettingsIntent(context: Context): Intent =
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))

/** Whether the system will still show its location dialog — no Activity, no dialog. */
private fun canAskForLocationAgain(activity: Activity?): Boolean =
    activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
        activity, Manifest.permission.ACCESS_FINE_LOCATION,
    )

/**
 * Shows a location snackbar — denied, or not precise — with [recovery]'s
 * action, and returns it if the rider tapped it. Decided when the snackbar
 * shows, not when location was refused: whether the system will still show
 * its dialog can change in between.
 */
private suspend fun showLocationSnackbar(
    snackbarHostState: SnackbarHostState,
    message: String,
    recovery: LocationRecovery,
): LocationRecovery? {
    // A second Spin tap while it is up would queue a copy behind it.
    if (snackbarHostState.currentSnackbarData?.visuals?.message == message) return null
    val result = snackbarHostState.showSnackbar(
        message = message,
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

/** The snackbar for a trip refused precise location (#500): approximate runs
 *  the map, but recording a drive needs fixes accurate to metres. */
private const val PRECISE_LOCATION_NEEDED_ERROR = "Recording a trip needs precise location"

internal class MapPermissions(
    val bgLocationLauncher: ManagedActivityResultLauncher<String, Boolean>,
    /** If location is not granted, shows the denied snackbar with Allow or
     *  Open settings and returns true; false when there is nothing to offer. */
    val offerLocationIfDenied: () -> Boolean,
    /** The permission explainer's Continue (#501): fires the system dialogs. */
    val continueFromExplainer: () -> Unit,
    /** Starts recording a trip toward the destination — first asking for
     *  precise location if only approximate is granted, and recording
     *  nothing if the rider refuses (#500). */
    val recordTrip: (LatLon?) -> Unit,
    /** Forgets a trip still waiting on that answer; navigation ended first. */
    val dropPendingTrip: () -> Unit,
)
