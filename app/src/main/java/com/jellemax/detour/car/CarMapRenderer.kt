package com.jellemax.detour.car

import android.app.Presentation
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.CircleFixes
import com.jellemax.detour.data.CirclePresence
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.map.MapMotion
import com.jellemax.detour.data.MemberFix
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.drive.FriendPosition
import com.jellemax.detour.drive.SectionAverageTracker
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.ui.MapOverlays
import com.jellemax.detour.ui.PositionMarker
import com.jellemax.detour.ui.openFreeMapStyleUrl
import com.jellemax.detour.ui.setCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// Camera easing, ported from the phone map's follow loop (MapScreen.kt): each
// tick the camera closes the same fraction of its gap to the last fix, covering
// ~63% of it in one tau. GPS arrives about once a second, and a map that only
// moves when a fix lands reads as a broken app even when the fix behind it is
// current — on the car screen doubly so, because there is nothing else on it.
private const val CAM_POS_TAU = 0.35
private const val CAM_BEARING_TAU = 0.5
private const val CAM_ZOOM_TAU = 1.2

// Driven by a plain timer rather than Choreographer/withFrameNanos: the map
// lives on a VirtualDisplay that keeps running with the phone's own screen off,
// and vsync callbacks do not. ~30 fps is smooth for a camera that is only ever
// panning and turning slowly.
private const val CAM_FRAME_MS = 33L

// Below these an eased step isn't worth a redraw: ~0.2 m of pan (sub-pixel at
// driving zooms), a hair of zoom, a tenth of a degree of rotation. Once the
// ease settles inside all three the camera is left alone, so a car stopped at a
// light stops re-rendering entirely.
private const val CAM_POS_EPS_DEG = 2e-6
private const val CAM_ZOOM_EPS = 2e-3
private const val CAM_BEARING_EPS_DEG = 0.1f

/** Below this the GPS bearing is noise, so the map keeps the heading it had
 *  instead of spinning while you wait at a junction. */
private const val BEARING_HOLD_MPS = 2.0

/** Same cadence the phone map polls at (see MapCameraTuning's
 *  CIRCLE_FIX_POLL_MS): a circle member only posts a fix every
 *  CirclePresence.ACTIVE_INTERVAL_MS, so asking faster would just re-fetch
 *  the same row. Read from there rather than retyped. */
private const val CIRCLE_FIX_POLL_MS = CirclePresence.ACTIVE_INTERVAL_MS

/**
 * Android Auto gives an app only a raw [Surface] via [SurfaceCallback] — no
 * map widget — so the car screen is turned into a real [android.view.Display]:
 * a [VirtualDisplay] backed by that surface, with a [Presentation] hosting the
 * same [MapView] and [MapOverlays] the phone map uses.
 *
 * The obvious alternative — keep the MapView offscreen and blit snapshots of
 * it onto the surface — cannot work: MapLibre only creates its GL renderer
 * once the view is attached to a window, so an unattached MapView never draws
 * anything and `getMapAsync` never fires. Going through a display attaches it
 * for real, and MapLibre renders straight to the car surface.
 *
 * The speed/speed-limit HUD sits in a [HudOverlay] above the map in the same
 * Presentation, since it can't be a MapLibre layer.
 *
 * Overlay state (route, position, cameras, friends) is *kept here* rather than
 * pushed from the nav screen on every fix. Two reasons: a surface swap builds a
 * fresh style with empty sources, which then has to be refilled from something;
 * and re-serialising a route polyline of a few thousand points into GeoJSON
 * once a second — which is what a full [MapOverlays.render] per fix costs — is
 * enough main-thread work on a head unit to make the whole map feel stuck.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    private val darkTheme: Boolean,
) : SurfaceCallback {

    // MainActivity initialises MapLibre for the phone UI, but the car flow can
    // be the first thing to touch the SDK in this process — the head unit
    // starts the app without the activity ever running — and MapView's
    // constructor throws when it isn't initialised. getInstance is idempotent.
    init {
        MapLibre.getInstance(carContext)
    }

    private val hud = HudOverlay(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The marker the phone is set to. Collected here rather than in each car
    // screen: this is the one object both NavScreen and SpinScreen render
    // through, and the style's own load path (MapOverlays.init) only covers the
    // icon as it stood when the surface arrived.
    init {
        scope.launch {
            Settings.mapIcon.collect { icon -> withOverlays { it.setPositionIcon(icon) } }
        }
        // The route colour, for the same reason: it belongs to the map, and the
        // driver can change it on the phone while the car screen is up.
        scope.launch {
            Settings.routeColor.collect { color -> withOverlays { it.setRouteColor(color) } }
        }
        // Convoy peers and circle members, for the same reason: they belong to
        // the map, not to whichever screen happens to be on top. Collected in
        // NavScreen before, which meant they existed only while a route was
        // running — the following map you get with no route showed nobody, and
        // circle members were never drawn on the car at all.
        scope.launch {
            ConvoyLiveClient.peers.collect { peers -> setFriends(peers.values) }
        }
        scope.launch {
            while (true) {
                val me = Account.username.value
                if (me.isNotBlank()) {
                    // Offline or server down: keep the last known positions
                    // rather than blanking the map on one failed poll.
                    runCatching { withContext(Dispatchers.IO) { CircleFixes.othersFixes(me) } }
                        .onSuccess { setCircleMembers(it) }
                } else {
                    // Signed out: nothing to ask the server for, and nothing
                    // of the previous rider's worth keeping — matches the
                    // phone map's own blank-handle clear (MapScreen.kt), the
                    // other consumer of this same CircleFixes.othersFixes
                    // chain. Without this a blank handle skipped the branch
                    // entirely and left the departed rider's members in
                    // `circleMembers` for the life of the renderer.
                    setCircleMembers(emptyList())
                }
                delay(CIRCLE_FIX_POLL_MS)
            }
        }
    }

    // Built fresh for each surface instead of once per renderer. Tearing the
    // display down has to call MapView.onDestroy(), which releases the native
    // renderer permanently, so the same view cannot be re-attached to the next
    // Presentation — onCreate/getMapAsync on a destroyed MapView never draws.
    // A real head unit hands out a new surface every time the user switches to
    // another car app and comes back, which left the map black from the second
    // surface on; the DHU only ever creates one, so this never showed up there.
    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var overlays: MapOverlays? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null

    // The host reports the visible area independently of the surface, and does
    // not repeat it for a replacement surface, so it is kept and re-applied to
    // each new map rather than read back from the callback.
    private val visibleArea = Rect()
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // What should be on the map. Re-applied to every new style.
    private var routePolyline: List<LatLon>? = null
    private var drivenFraction: Double? = null
    private var destination: LatLon? = null
    private var position: LatLon? = null
    private var positionBearing: Double? = null
    private var cameras: List<SpeedCameras.Camera> = emptyList()
    private var friends: Collection<FriendPosition> = emptyList()
    private var circleMembers: Collection<MemberFix> = emptyList()

    // Where the camera is being eased to, and where it currently is.
    private var targetPos: LatLon? = null
    private var targetBearing: Float? = null
    private var targetZoom = 16.0
    private var camLat = Double.NaN
    private var camLon = 0.0
    private var camBearing = 0f
    private var camZoom = 16.0
    private var easeJob: Job? = null

    /** Where to point the camera, from the latest fix. The camera itself eases
     *  there over the following frames rather than jumping on each fix. */
    fun follow(pos: LatLon, bearingDeg: Float?, speedMps: Double, zoom: Double) {
        targetPos = pos
        if (bearingDeg != null && speedMps > BEARING_HOLD_MPS) targetBearing = bearingDeg
        targetZoom = zoom
        if (camLat.isNaN()) {
            // First fix: start from it instead of easing in from the null island.
            camLat = pos.lat
            camLon = pos.lon
            camZoom = zoom
            camBearing = targetBearing ?: 0f
            mapLibreMap?.let { applyCamera(it) }
        }
    }

    /** Speed, posted-limit and trajectcontrole-average readouts.
     *
     *  [section] defaults to the tracker's own "not inside one" pair so free
     *  drive keeps its two-argument call: [SpinScreen] prefetches posted limits
     *  but never the enforcement relations, so it has no section to be in. */
    fun updateHud(
        speedKmh: Double,
        limitKmh: Double?,
        section: SectionAverageTracker.Reading = SectionAverageTracker.Reading(null, null),
    ) = hud.update(speedKmh, limitKmh, section)

    /** The line to draw, and the pin at the end of it. Pushed once per route —
     *  on start and on each reroute — not per fix. */
    fun setRoute(polyline: List<LatLon>?, destination: LatLon?) {
        this.routePolyline = polyline
        this.destination = destination
        // Progress belongs to the line it was measured along: a reroute starts
        // a new one, and the next fix says how far along *it* we are.
        this.drivenFraction = null
        withOverlays { pushRoute(it) }
    }

    /** How much of the route is behind us (0..1), so the map can fade it out.
     *  A per-fix call, and a cheap one: the overlay skips an update that
     *  wouldn't visibly move the line. */
    fun setDrivenFraction(fraction: Double?) {
        drivenFraction = fraction
        withOverlays { it.setDrivenFraction(fraction) }
    }

    /** The own-position marker. The cheap per-fix update: one point of GeoJSON,
     *  leaving the route line the map has already tessellated alone. */
    fun setPosition(pos: LatLon, bearingDeg: Float? = null) {
        position = pos
        positionBearing = bearingDeg?.toDouble() ?: positionBearing
        withOverlays { it.setPosition(pos, positionBearing) }
    }

    fun setCameras(cameras: List<SpeedCameras.Camera>) {
        this.cameras = cameras
        withOverlays { it.setCameras(cameras) }
    }

    fun setFriends(friends: Collection<FriendPosition>) {
        this.friends = friends
        withOverlays { it.setFriends(friends) }
    }

    fun setCircleMembers(fixes: Collection<MemberFix>) {
        this.circleMembers = fixes
        withOverlays { it.setCircleMembers(fixes) }
    }

    /** Style calls throw once the map behind them is gone — which on a head
     *  unit is every time the driver switches to another car app and the
     *  surface is handed back — and all four callers above are flow collectors,
     *  where an exception doesn't just skip a frame, it ends the process. */
    private fun withOverlays(block: (MapOverlays) -> Unit) {
        val current = overlays ?: return
        runCatching { block(current) }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        if (width <= 0 || height <= 0) return
        tearDownDisplay()
        surfaceWidth = width
        surfaceHeight = height
        // The HUD draws in surface pixels; this is the only place the surface's
        // own density is reported.
        hud.setSurfaceDpi(surfaceContainer.dpi)

        val display = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "DetourCarMap",
            width,
            height,
            surfaceContainer.dpi,
            surface,
            // OWN_CONTENT_ONLY is not optional: without it the platform treats
            // the display as screen mirroring and demands CAPTURE_VIDEO_OUTPUT.
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: return
        virtualDisplay = display

        // The HUD carries its numbers and safe area across a surface swap, so
        // it is the one child that outlives the Presentation it was in.
        val view = MapView(carContext)
        mapView = view

        presentation = Presentation(carContext, display.display).apply {
            setContentView(
                FrameLayout(context).apply {
                    addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    addView(hud, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                },
            )
            show()
        }

        view.onCreate(null)
        view.onStart()
        view.onResume()
        view.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false
            // Attribution has to stay, but every other corner is taken: the
            // host draws its ETA card bottom left and the action strip top
            // right, and the HUD owns the bottom right.
            val edge = (8 * carContext.resources.displayMetrics.density).toInt()
            map.uiSettings.logoGravity = Gravity.TOP or Gravity.START
            map.uiSettings.attributionGravity = Gravity.TOP or Gravity.START
            map.uiSettings.setLogoMargins(edge, edge, 0, 0)
            map.uiSettings.setAttributionMargins(edge, edge, 0, 0)
            mapLibreMap = map
            applyPadding(map)
            if (!camLat.isNaN()) applyCamera(map)
            map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { style ->
                val fresh = MapOverlays(style, carContext, darkTheme)
                overlays = fresh
                // A replacement surface starts with empty sources; refill them
                // from what we already know instead of waiting for the next
                // route change to redraw the line.
                pushRoute(fresh)
                position?.let { fresh.setPosition(it, positionBearing) }
                if (cameras.isNotEmpty()) fresh.setCameras(cameras)
                if (friends.isNotEmpty()) fresh.setFriends(friends)
                // Signed-out guarded here too, not just in the poll loop
                // above: the loop only notices a sign-out on its own
                // CIRCLE_FIX_POLL_MS cadence, and a surface recreated (car
                // app switched away and back) inside that window must not
                // redraw circleMembers that tick hasn't caught up to clearing
                // yet.
                if (circleMembers.isNotEmpty() && Account.username.value.isNotBlank()) {
                    fresh.setCircleMembers(circleMembers)
                }
            }
            startCameraLoop()
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Keep the followed position centered in what's actually visible,
        // not behind the action strip / trip-status chrome the host draws
        // over part of the surface.
        if (visibleArea.isEmpty) return
        this.visibleArea.set(visibleArea)
        hud.setSafeArea(visibleArea)
        mapLibreMap?.let { applyPadding(it) }
    }

    /** Insets the camera to [visibleArea]. Measured against the surface rather
     *  than the MapView, whose width/height are still 0 when the style loads
     *  before the Presentation's first layout pass — the map fills the virtual
     *  display, so the two are the same size once laid out. */
    private fun applyPadding(map: MapLibreMap) {
        if (visibleArea.isEmpty || surfaceWidth <= 0 || surfaceHeight <= 0) return
        map.setPadding(
            visibleArea.left, visibleArea.top,
            (surfaceWidth - visibleArea.right).coerceAtLeast(0),
            (surfaceHeight - visibleArea.bottom).coerceAtLeast(0),
        )
    }

    private fun pushRoute(overlays: MapOverlays) {
        overlays.render(
            myLocation = position,
            destination = destination,
            routePolyline = routePolyline,
            reachMeters = null,
            directionDeg = null,
            candidates = emptyList(),
            positionMarker = if (position != null) PositionMarker.Draw else PositionMarker.Hide,
            positionBearingDeg = positionBearing,
        )
        // render() starts the line off undriven, so how far along it we are has
        // to follow — this is also what restores the faded part on a surface
        // swap mid-drive, rather than waiting for the next fix.
        overlays.setDrivenFraction(drivenFraction)
    }

    /**
     * Eases the camera toward the last fix, a step at a time, and pushes it
     * only when the step is big enough to see. This is the difference between a
     * map that glides along the road and one that lurches once a second.
     */
    private fun startCameraLoop() {
        easeJob?.cancel()
        easeJob = scope.launch {
            var lastNs = System.nanoTime()
            var appliedLat = Double.NaN
            var appliedLon = 0.0
            var appliedZoom = 0.0
            var appliedBearing = 0f
            while (isActive) {
                delay(CAM_FRAME_MS)
                val map = mapLibreMap ?: continue
                val ns = System.nanoTime()
                // Clamp dt so a stalled render or a paused loop can't teleport.
                // 0.1, matching the phone (MapScreen.kt's frame loops). At 0.25 a
                // single tick closed ~51% of the remaining gap against ~25% at
                // 0.1, so a resume after the loop was paused arrived as a lurch
                // rather than an ease. The snap guard below is what handles a gap
                // too large to ease at all; this bound is for the ordinary case.
                val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
                lastNs = ns
                if (camLat.isNaN()) continue

                targetPos?.let { target ->
                    if (MapMotion.shouldSnap(LatLon(camLat, camLon), target)) {
                        // Too far to be continuous motion — the session was
                        // backgrounded while the car kept driving, or the host
                        // paused the surface. Easing across that distance walks the
                        // camera over ground the driver never saw. Bearing and zoom
                        // re-anchor with it so the whole camera teleports as one
                        // rather than arriving and then rotating.
                        camLat = target.lat
                        camLon = target.lon
                        targetBearing?.let { camBearing = it }
                        camZoom = targetZoom
                    } else {
                        val a = 1.0 - exp(-dt / CAM_POS_TAU)
                        camLat += (target.lat - camLat) * a
                        camLon += (target.lon - camLon) * a
                    }
                }
                targetBearing?.let { target ->
                    camBearing = smoothBearing(
                        camBearing, target, (1.0 - exp(-dt / CAM_BEARING_TAU)).toFloat())
                }
                camZoom += (targetZoom - camZoom) * (1.0 - exp(-dt / CAM_ZOOM_TAU))

                var dBearing = (camBearing - appliedBearing) % 360f
                if (dBearing > 180f) dBearing -= 360f
                if (dBearing < -180f) dBearing += 360f
                val moved = appliedLat.isNaN() ||
                    abs(camLat - appliedLat) > CAM_POS_EPS_DEG ||
                    abs(camLon - appliedLon) > CAM_POS_EPS_DEG ||
                    abs(camZoom - appliedZoom) > CAM_ZOOM_EPS ||
                    abs(dBearing) > CAM_BEARING_EPS_DEG
                if (!moved) continue
                applyCamera(map)
                appliedLat = camLat
                appliedLon = camLon
                appliedZoom = camZoom
                appliedBearing = camBearing
            }
        }
    }

    private fun applyCamera(map: MapLibreMap) {
        setCamera(map, camLat, camLon, camZoom, camBearing)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        tearDownDisplay()
    }

    /** Tears down the map. Call once, when the nav screen is destroyed. */
    fun destroy() {
        tearDownDisplay()
        scope.cancel()
    }

    private fun tearDownDisplay() {
        easeJob?.cancel()
        easeJob = null
        if (presentation == null && virtualDisplay == null) return
        mapLibreMap = null
        overlays = null
        mapView?.let { view ->
            runCatching {
                view.onPause()
                view.onStop()
                view.onDestroy()
            }
            (view.parent as? FrameLayout)?.removeAllViews()
        }
        // Dropped rather than kept for the next surface: onDestroy() above is
        // one-way, so the replacement has to be a new MapView.
        mapView = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }
}

/** Exponentially smooths a compass bearing toward [target], taking the shortest
 *  way round the 0/360 wrap, so heading-up rotation eases instead of snapping to
 *  each noisy raw GPS fix. Same as the phone map's. */
private fun smoothBearing(current: Float, target: Float, alpha: Float): Float {
    var delta = (target - current) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return (current + delta * alpha + 360f) % 360f
}

// Sign diameter as a fraction of the visible height, clamped to a sane band in
// physical millimetres-worth of dp. A head unit is read at arm's length in a
// moving car, so the posted limit wants to be roughly a fifth of the screen —
// what Waze and Google Maps both land on.
private const val SIGN_HEIGHT_FRACTION = 0.20f
private const val SIGN_MIN_DP = 56f
private const val SIGN_MAX_DP = 108f

// Vienna Convention proportions: the red rim is about 12% of the sign's
// diameter, and the digits stand a little over half of it.
private const val SIGN_RIM_RATIO = 0.12f
private const val SIGN_DIGIT_RATIO = 0.54f

// The trajectcontrole average sits a little smaller than the speed and the
// posted limit — the phone chip's own 72dp against the speed dial's 80dp. It is
// the reading you check, not the one you drive by, and entry 11's worry about a
// fifth readout at arm's length is answered by drawing it as plainly secondary.
private const val AVG_DISC_RATIO = 0.9f

/**
 * Speed, posted-limit and trajectcontrole-average readouts, drawn over the map
 * inside the Presentation.
 *
 * Sized from the **car surface**, not from the phone. The two are unrelated:
 * [android.util.DisplayMetrics.density] here belongs to the CarContext, which a
 * head unit routinely reports as 1.0 while handing out a 1920-wide surface —
 * so a sign laid out in those dp came out a couple of centimetres across on a
 * screen with pixels to spare, which is what made it look like a low-resolution
 * asset rather than the vector drawing it is. Everything below is derived from
 * the surface's own dpi and the visible area, and drawn straight into surface
 * pixels, so the sign scales up with the display instead of ignoring it.
 *
 * Anchored to the bottom **right** of [safeArea], where Waze and Google Maps put
 * the same readouts: the host owns the bottom left (ETA card) and the top right
 * (action strip).
 */
private class HudOverlay(context: android.content.Context) : View(context) {

    // Overwritten by [setSurfaceDpi] as soon as a surface arrives; the phone's
    // density is only a placeholder for the frames before that.
    private var density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private var speedKmh: Double? = null
    private var limitKmh: Double? = null
    private var section = SectionAverageTracker.Reading(null, null)
    private val safeArea = Rect()

    /** The car surface's real pixel density. Different surface, different
     *  scale — a replacement surface can arrive with another dpi. */
    fun setSurfaceDpi(dpi: Int) {
        val next = (dpi / 160f).coerceAtLeast(1f)
        if (next == density) return
        density = next
        postInvalidate()
    }

    /** Whole km/h, which is all any of the three discs ever shows. */
    private fun whole(value: Double?) = value?.let { "%.0f".format(it) }

    fun update(speed: Double, limit: Double?, section: SectionAverageTracker.Reading) {
        // Once a second, and only when the rounded readout actually changes:
        // an invalidate on the virtual display costs a full recomposite of the
        // car surface, which is the last thing the map needs competing with.
        // The section average is compared rounded for the same reason the speed
        // is — it creeps continuously and the disc shows whole km/h.
        val unchanged = whole(speedKmh) == whole(speed) &&
            limit == limitKmh &&
            whole(section.averageKmh) == whole(this.section.averageKmh) &&
            section.limitKmh == this.section.limitKmh
        if (unchanged) return
        speedKmh = speed
        limitKmh = limit
        this.section = section
        postInvalidate()
    }

    fun setSafeArea(area: Rect) {
        if (area.isEmpty || area == safeArea) return
        safeArea.set(area)
        postInvalidate()
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val signFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    // Traffic red, not Material red 700. The old #D32F2F is a muted, dark
    // brick: on a dark map at arm's length it read as brown-ish and the sign
    // lost the one thing that makes it recognisable before you can read the
    // number. This is close to RAL 3020, which is what the sign by the road
    // actually is.
    private val signRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8112D") }
    /** Faint dark edge so a white sign doesn't dissolve into a pale map. */
    private val signEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.STROKE
    }
    private val speedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E60F1116") }
    // Lifted along with the rim: the over-limit disc is read at a glance too,
    // and #B3261E next to a bright sign looked like a dead pixel.
    private val speedOverBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6C62828") }
    // The trajectcontrole average. Teal rather than the speed disc's near-black
    // because at a glance the colour is the only thing distinguishing the two
    // numbers — they are both white-on-dark km/h in the same row. It reuses
    // speedOverBgPaint when the average is over the section limit, so "over" is
    // the same red wherever it appears on this HUD.
    private val avgBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6134E4A") }

    // sans-serif-black is the closest stock face to the heavy grotesque on a
    // real sign, and matches the phone HUD's FontWeight.Black.
    private val heavy = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
    private val limitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = heavy
    }
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = heavy
    }
    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        textAlign = Paint.Align.CENTER
    }

    /** Baseline offset that centers text of [paint] on a circle's center. */
    private fun baselineOffset(paint: Paint) = -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

    /**
     * Drop shadow under a disc of [radius] at ([cx], [cy]).
     *
     * A radial gradient rather than [Paint.setShadowLayer] or a blur mask: both
     * of those force the view into software rendering, and this one is composited
     * onto a VirtualDisplay that the head unit is reading every frame. A gradient
     * is hardware-accelerated and, at this size, indistinguishable from a real
     * blur — where the flat 25%-black disc it replaces read as a second, offset
     * sign rather than as a shadow.
     */
    private fun drawShadow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val outer = radius * 1.16f
        shadowPaint.shader = RadialGradient(
            cx, cy + radius * 0.06f, outer,
            intArrayOf(0x59000000, 0x59000000, 0x00000000),
            floatArrayOf(0f, 0.80f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy + radius * 0.06f, outer, shadowPaint)
    }

    /** Sets [paint]'s size so [text] is [target] tall but never wider than
     *  [maxWidth] — "130" has to fit the same disc "50" sits comfortably in. */
    private fun fitText(paint: Paint, text: String, target: Float, maxWidth: Float) {
        paint.textSize = target
        val width = paint.measureText(text)
        if (width > maxWidth) paint.textSize = target * (maxWidth / width)
    }

    override fun onDraw(canvas: Canvas) {
        // Clamped to the view, not trusted as given: the visible area is kept
        // across a surface swap because the host doesn't repeat it, so a
        // smaller replacement surface inherits the old, larger rect. Position
        // was already at its mercy; size is now too.
        val areaBottom = if (safeArea.isEmpty) height else min(safeArea.bottom, height)
        val areaRight = if (safeArea.isEmpty) width else min(safeArea.right, width)
        val areaHeight = if (safeArea.isEmpty) height else areaBottom - max(0, safeArea.top)
        if (areaHeight <= 0) return

        val diameter = (areaHeight * SIGN_HEIGHT_FRACTION)
            .coerceIn(dp(SIGN_MIN_DP), dp(SIGN_MAX_DP))
        val radius = diameter / 2f
        val margin = dp(12f)
        val gap = dp(10f)
        val bottom = areaBottom - margin
        var cx = areaRight - margin

        // Right to left, so the current speed ends up in the corner itself and
        // the posted limit sits inboard of it.
        val speed = speedKmh
        if (speed != null) {
            cx -= radius
            val cy = bottom - radius
            val limit = limitKmh
            val speeding = limit != null && speed > limit + 5
            drawShadow(canvas, cx, cy, radius)
            canvas.drawCircle(cx, cy, radius, if (speeding) speedOverBgPaint else speedBgPaint)
            // The unit label sits under the number, as on the phone HUD, so the
            // pair reads as one instrument rather than two loose discs.
            val text = "%.0f".format(speed)
            fitText(speedTextPaint, text, diameter * 0.42f, diameter * 0.70f)
            unitTextPaint.textSize = diameter * 0.15f
            canvas.drawText(text, cx, cy + baselineOffset(speedTextPaint) - diameter * 0.06f, speedTextPaint)
            canvas.drawText("km/h", cx, cy + radius * 0.62f, unitTextPaint)
            cx -= radius + gap
        }

        val limit = limitKmh
        if (limit != null) {
            cx -= radius
            val cy = bottom - radius
            drawShadow(canvas, cx, cy, radius)
            canvas.drawCircle(cx, cy, radius, signRimPaint)
            signEdgePaint.strokeWidth = radius * 0.035f
            canvas.drawCircle(cx, cy, radius - signEdgePaint.strokeWidth / 2f, signEdgePaint)
            canvas.drawCircle(cx, cy, radius * (1f - 2f * SIGN_RIM_RATIO), signFacePaint)
            val text = "%.0f".format(limit)
            val face = diameter * (1f - 2f * SIGN_RIM_RATIO)
            fitText(limitTextPaint, text, diameter * SIGN_DIGIT_RATIO, face * 0.82f)
            canvas.drawText(text, cx, cy + baselineOffset(limitTextPaint), limitTextPaint)
            cx -= radius + gap
        }

        // Inside a trajectcontrole: the running average, furthest inboard, so
        // right-to-left the row reads speed · posted limit · average — the same
        // order as the phone's SpeedHud, where the chip is leftmost.
        //
        // Smaller than the other two on purpose, at the phone chip's own 72/80
        // ratio. Register entry 11's objection to this readout is that a fifth
        // thing at arm's length is too much; drawing it as plainly secondary is
        // the answer to that, not dropping it — the average is the number the
        // gantry pair is actually measuring.
        val average = section.averageKmh
        if (average != null) {
            val avgRadius = radius * AVG_DISC_RATIO
            val avgDiameter = avgRadius * 2f
            cx -= avgRadius
            val cy = bottom - avgRadius
            val sectionLimit = section.limitKmh
            val over = sectionLimit != null && average > sectionLimit
            drawShadow(canvas, cx, cy, avgRadius)
            canvas.drawCircle(cx, cy, avgRadius, if (over) speedOverBgPaint else avgBgPaint)
            // Ø, the same glyph the phone chip uses, so the two surfaces label
            // this number identically. Both strings are fitted rather than
            // sized outright: "Ø 120" and "avg km/h" are both wider than the
            // speed disc's "120"/"km/h" in a disc that is 10% smaller.
            val text = "Ø %.0f".format(average)
            fitText(speedTextPaint, text, avgDiameter * 0.34f, avgDiameter * 0.76f)
            fitText(unitTextPaint, "avg km/h", avgDiameter * 0.15f, avgDiameter * 0.80f)
            canvas.drawText(text, cx, cy + baselineOffset(speedTextPaint) - avgDiameter * 0.06f, speedTextPaint)
            canvas.drawText("avg km/h", cx, cy + avgRadius * 0.62f, unitTextPaint)
        }
    }
}
