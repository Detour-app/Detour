package com.jellemax.detour.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NamedMemberFix
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.map.CameraAuthority
import kotlinx.coroutines.Job

/**
 * The screen-lifetime state MapScreen used to keep as twenty loose `var`s.
 *
 * They were not loose by choice — a composable is the default owner of anything
 * nobody gives an owner to, and MapScreen is the app's start destination and its
 * only always-composed surface, so everything landed there. The cost was not the
 * line count: it was that nothing below the declarations could be moved to
 * another file, because every candidate closed over six or ten of these. The
 * composition tree alone references 56 of the composable's locals, so hoisting
 * it would have meant a 56-parameter function — [MapBottomSlot]'s 46-parameter
 * signature is what that road looks like two thirds of the way along.
 *
 * ## The lifetime this deliberately is, and is not
 *
 * `remember`, so this dies with the screen — the same lifetime the twenty vars
 * already had, which is what makes adopting it a refactor rather than a
 * behaviour change.
 *
 * It is emphatically **not** the place for the five `rememberSaveable` values
 * (`radiusKm`, `minRadiusKm`, `poiKind`, `directionDeg`, `settingsCollapsed`).
 * Those survive process death and this does not; moving them here would
 * downgrade that silently, with no compiler complaint and no test to catch it.
 * They stay in the composable.
 *
 * Nor is it [RetainedMap], which outlives navigation away from the screen but
 * dies on rotation, nor [SpinResultHolder], which is process-scoped precisely so
 * a spin survives activity recreation. Three lifetimes, three owners; the
 * mistake to avoid is assuming "the session" is one thing.
 */
internal class MapScreenState(seed: SpinResult) {

    // --- the spin result ------------------------------------------------
    // Seeded from SpinResultHolder, which is what carries these across an
    // activity recreation; this object only has to carry them across a
    // recomposition.
    var candidates: List<RouteCandidate> by mutableStateOf(seed.candidates)
    var destination: LatLon? by mutableStateOf(seed.destination)
    var destinationName: String? by mutableStateOf(seed.destinationName)
    var route: RouteResult? by mutableStateOf(seed.route)
    var spinning: Boolean by mutableStateOf(false)
    var spinJob: Job? by mutableStateOf(null)

    // --- navigation -----------------------------------------------------
    var navigating: Boolean by mutableStateOf(seed.navigating)
    var navProgress: NavEngine.Progress? by mutableStateOf(null)
    var rerouting: Boolean by mutableStateOf(false)
    var lastRerouteMs: Long by mutableLongStateOf(0L)

    // --- camera ---------------------------------------------------------
    /** Follow/park/gesture, all three transitions dispatched through
     *  [CameraAuthority.reduce] rather than written from ten sites. */
    var camAuthority: CameraAuthority.State by mutableStateOf(CameraAuthority.State())

    // --- position and hazards ------------------------------------------
    var myLocation: LatLon? by mutableStateOf(null)
    var speedLimitFetchJob: Job? by mutableStateOf(null)

    // --- convoy and circles ---------------------------------------------
    var convoyName: String? by mutableStateOf(null)
    var circleFixes: List<NamedMemberFix> by mutableStateOf(emptyList())

    // --- chrome ----------------------------------------------------------
    var layersOpen: Boolean by mutableStateOf(false)

    /** Whether the drive or nav sheet is open. Plain state, not saveable: the
     *  effect on `bottomCard` closes it on every slot change, first composition
     *  included, so a rotation would lose it either way. */
    var rideSheetExpanded: Boolean by mutableStateOf(false)
    var searchOpen: Boolean by mutableStateOf(false)
    var savePinTarget: LatLon? by mutableStateOf(null)
    var showBgLocationDisclosure: Boolean by mutableStateOf(false)

    /** Every failure the screen reports, from a denied permission to a spin
     *  that found nothing. A dozen writers and, before the snackbar, one
     *  reader inside a sheet that is collapsed by default. */
    var error: String? by mutableStateOf(null)
}
