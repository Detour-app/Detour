package com.jellemax.detour.car

import android.app.Presentation
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.net.FriendPosition
import com.jellemax.detour.ui.MapOverlays
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
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs
import kotlin.math.exp

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
    private var destination: LatLon? = null
    private var position: LatLon? = null
    private var cameras: List<SpeedCameras.Camera> = emptyList()
    private var friends: Collection<FriendPosition> = emptyList()

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

    /** Speed and posted-limit readouts. */
    fun updateHud(speedKmh: Double, limitKmh: Double?) = hud.update(speedKmh, limitKmh)

    /** The line to draw, and the pin at the end of it. Pushed once per route —
     *  on start and on each reroute — not per fix. */
    fun setRoute(polyline: List<LatLon>?, destination: LatLon?) {
        this.routePolyline = polyline
        this.destination = destination
        withOverlays { pushRoute(it) }
    }

    /** The own-position dot. The cheap per-fix update: one point of GeoJSON,
     *  leaving the route line the map has already tessellated alone. */
    fun setPosition(pos: LatLon) {
        position = pos
        withOverlays { it.setPosition(pos) }
    }

    fun setCameras(cameras: List<SpeedCameras.Camera>) {
        this.cameras = cameras
        withOverlays { it.setCameras(cameras) }
    }

    fun setFriends(friends: Collection<FriendPosition>) {
        this.friends = friends
        withOverlays { it.setFriends(friends) }
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
                position?.let { fresh.setPosition(it) }
                if (cameras.isNotEmpty()) fresh.setCameras(cameras)
                if (friends.isNotEmpty()) fresh.setFriends(friends)
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
            showPosition = position != null,
        )
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
                val dt = ((ns - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.25)
                lastNs = ns
                if (camLat.isNaN()) continue

                targetPos?.let { target ->
                    val a = 1.0 - exp(-dt / CAM_POS_TAU)
                    camLat += (target.lat - camLat) * a
                    camLon += (target.lon - camLon) * a
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

/**
 * Speed and posted-limit readouts, drawn over the map inside the Presentation.
 *
 * Sizes are in dp rather than raw pixels — a car screen reports a low density
 * (160dpi on the DHU, so 800x400 logical px for the whole display), and fixed
 * pixel sizes that look fine on a phone cover a quarter of it. Everything is
 * anchored to the bottom **right** of [safeArea], where Waze and Google Maps
 * put the same readouts: the host owns the bottom left (ETA card) and the top
 * right (action strip).
 */
private class HudOverlay(context: android.content.Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val speedRadius = dp(26f)
    private val limitRadius = dp(24f)
    private val gap = dp(10f)
    private val margin = dp(12f)

    private var speedKmh: Double? = null
    private var limitKmh: Double? = null
    private val safeArea = Rect()

    fun update(speed: Double, limit: Double?) {
        // Once a second, and only when the rounded readout actually changes:
        // an invalidate on the virtual display costs a full recomposite of the
        // car surface, which is the last thing the map needs competing with.
        if (speedKmh?.let { "%.0f".format(it) } == "%.0f".format(speed) && limit == limitKmh) return
        speedKmh = speed
        limitKmh = limit
        postInvalidate()
    }

    fun setSafeArea(area: Rect) {
        if (area.isEmpty || area == safeArea) return
        safeArea.set(area)
        postInvalidate()
    }

    private val speedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC1A1A1A") }
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(20f)
    }
    private val limitRingBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val limitRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
    }
    private val limitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = dp(18f)
    }

    /** Baseline offset that centers text of [paint] on a circle's center. */
    private fun baselineOffset(paint: Paint) = -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

    override fun onDraw(canvas: Canvas) {
        val bottom = (if (safeArea.isEmpty) height else safeArea.bottom) - margin
        var cx = (if (safeArea.isEmpty) width else safeArea.right) - margin

        // Right to left, so the current speed ends up in the corner itself and
        // the posted limit sits inboard of it.
        val speed = speedKmh
        if (speed != null) {
            cx -= speedRadius
            val cy = bottom - speedRadius
            canvas.drawCircle(cx, cy, speedRadius, speedBgPaint)
            canvas.drawText("%.0f".format(speed), cx, cy + baselineOffset(speedTextPaint), speedTextPaint)
            cx -= speedRadius + gap
        }

        val limit = limitKmh
        if (limit != null) {
            cx -= limitRadius
            val cy = bottom - limitRadius
            canvas.drawCircle(cx, cy, limitRadius, limitRingBgPaint)
            canvas.drawCircle(cx, cy, limitRadius - limitRingPaint.strokeWidth / 2f, limitRingPaint)
            canvas.drawText("%.0f".format(limit), cx, cy + baselineOffset(limitTextPaint), limitTextPaint)
        }
    }
}
