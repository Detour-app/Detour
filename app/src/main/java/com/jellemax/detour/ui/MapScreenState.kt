package com.jellemax.detour.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NamedMemberFix
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.ViaStops
import com.jellemax.detour.drive.CameraWarner
import com.jellemax.detour.map.CameraAuthority
import com.jellemax.detour.map.NavPolicy
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
/** What [MapTopChrome] needs to draw the fog-of-war toggle, grouped so the
 *  rail stays under the parameter limit — the same reason [WhereTo] exists
 *  (`docs/guidelines/state-holders.md` §14.2). */
internal data class MapLayers(
    val fogEnabled: Boolean,
    val onToggleFog: () -> Unit,
)

internal class MapScreenState(seed: SpinResult, riderFocusSeed: RiderFocusRequest?) {

    // --- the spin result ------------------------------------------------
    // Seeded from SpinResultHolder, which is what carries these across an
    // activity recreation; this object only has to carry them across a
    // recomposition.
    var candidates: List<RouteCandidate> by mutableStateOf(seed.candidates)
    private var destinationState: LatLon? by mutableStateOf(seed.destination)

    /** Any other destination drops [vias]: they were the stops on the way to
     *  this one, and a dozen writers set it without knowing they exist. */
    var destination: LatLon?
        get() = destinationState
        set(value) {
            if (value != destinationState) {
                vias = emptyList()
                viaRide = null
            }
            destinationState = value
        }
    var destinationName: String? by mutableStateOf(seed.destinationName)

    /** A planned route's stops still to ride through on the way to
     *  [destination] (#736), dropped as [followPlanned] sees them passed. */
    var vias: List<LatLon> by mutableStateOf(seed.vias)
        private set
    var route: RouteResult? by mutableStateOf(seed.route)
    var spinning: Boolean by mutableStateOf(false)
    var spinJob: Job? by mutableStateOf(null)

    // --- navigation -----------------------------------------------------
    var navigating: Boolean by mutableStateOf(seed.navigating)
    var navProgress: NavEngine.Progress? by mutableStateOf(null)
    var rerouting: Boolean by mutableStateOf(false)
    var lastRerouteMs: Long by mutableLongStateOf(0L)

    /** Where the rider is along a line fetched through [vias], for
     *  [followPlanned]. Not state: nothing draws it, and only the next fix
     *  reads it. */
    private var viaRide: ViaStops.Ride? = null

    /**
     * The rider at [at] moved on along [line], forward only, while [line] is
     * one fetched through planned stops: drops the [vias] they have passed and
     * returns where they are on it, for [NavEngine.progressAlong]. Null for
     * any other line, which [NavEngine.progress] places on its own.
     *
     * Kept after the last stop is passed, until the line changes: a line
     * through stops can ride a road twice, and its way back is where the
     * global snap goes wrong (#736).
     */
    fun followPlanned(line: List<LatLon>, at: LatLon): NavEngine.Along? {
        val ride = viaRide?.takeIf { it.line === line }
        if (ride == null && vias.isEmpty()) {
            viaRide = null
            return null
        }
        val next = ViaStops.follow(ride, line, vias, at, NavPolicy.OFF_ROUTE_METERS).also { viaRide = it }
        val left = ViaStops.ahead(next)
        if (left.size < vias.size) vias = left
        return next.along
    }

    // --- camera ---------------------------------------------------------
    /** Follow/park/gesture, all three transitions dispatched through
     *  [CameraAuthority.reduce] rather than written from ten sites.
     *
     *  A seed that still holds candidates starts parked, the same park the
     *  spin that produced them took (#427). Starting from a following camera
     *  put the restored list on screen with the map back on the rider and no
     *  pin in view, and MapScreen's restore framing would have been dragged
     *  straight back by the frame loop. */
    var camAuthority: CameraAuthority.State by mutableStateOf(
        if (seed.candidates.isEmpty()) CameraAuthority.State()
        else CameraAuthority.reduce(CameraAuthority.State(), CameraAuthority.Action.SpinStarted)
    )

    // --- position and hazards ------------------------------------------
    var myLocation: LatLon? by mutableStateOf(null)
    var speedLimitFetchJob: Job? by mutableStateOf(null)
    /** The camera banner's countdown (#549), written by MapHazardAlerts every
     *  fix. Per entry, not retained: the first fix after a return rewrites it,
     *  and a retained copy would show a camera passed while the map was away. */
    var cameraCountdown: CameraWarner.Countdown? by mutableStateOf(null)

    // --- convoy and circles ---------------------------------------------
    var convoyName: String? by mutableStateOf(null)
    var circleFixes: List<NamedMemberFix> by mutableStateOf(emptyList())

    /** The rider whose map marker was last tapped, or null with the card
     *  closed (issue #156). Holds only the id — the card's contents are
     *  re-derived from the live peer/circle collections each recomposition, so
     *  they update in place and go stale on their own. Plain state, not
     *  saveable: a transient info card, dismissed on a tap or Back.
     *
     *  Also opened, not just by a marker tap, once [pendingRiderFrame] below
     *  resolves a position for a rider asked for by name (#294) — the same
     *  card either way, since by the time it opens the two cases are
     *  indistinguishable: a rider whose position is known. */
    var tappedRider: RiderId? by mutableStateOf(null)

    /** A rider asked for by name (today: a `CircleDetailScreen` member row;
     *  see [RiderFocusHolder]'s KDoc for why the Friends leaderboard isn't
     *  wired to this yet) whose position the map has not resolved yet,
     *  seeded from [RiderFocusHolder] so the request survives the activity
     *  recreation a screen swap plus a rotate can both cause between the tap
     *  and this composition existing. Cleared by MapScreen once framed, or
     *  once it gives up waiting — see the effect that reads it. */
    var pendingRiderFrame: RiderFocusRequest? by mutableStateOf(riderFocusSeed)

    // --- chrome ----------------------------------------------------------
    /** Whether the drive or nav sheet is open. Plain state, not saveable: the
     *  effect on `bottomCard` closes it on every slot change, first composition
     *  included, so a rotation would lose it either way. */
    var rideSheetExpanded: Boolean by mutableStateOf(false)
    var searchOpen: Boolean by mutableStateOf(false)
    var savePinTarget: LatLon? by mutableStateOf(null)
    /** A spin result waiting to be named and kept in Routes (#589). */
    var saveSpinDraft: SavedRoute? by mutableStateOf(null)
    var showBgLocationDisclosure: Boolean by mutableStateOf(false)
    var showPermissionExplainer: Boolean by mutableStateOf(false)

    /** Every failure the screen reports, from a denied permission to a spin
     *  that found nothing. A dozen writers and, before the snackbar, one
     *  reader inside a sheet that is collapsed by default. */
    var error: String? by mutableStateOf(null)
}
