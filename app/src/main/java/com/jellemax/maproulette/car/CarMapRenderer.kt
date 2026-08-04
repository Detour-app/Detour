package com.jellemax.maproulette.car

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
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.ui.MapOverlays
import com.jellemax.maproulette.ui.openFreeMapStyleUrl
import com.jellemax.maproulette.ui.setCamera
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

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
 */
class CarMapRenderer(
    private val carContext: CarContext,
    private val darkTheme: Boolean,
) : SurfaceCallback {

    // MainActivity initialises MapLibre for the phone UI, but the car flow can
    // be the first thing to touch the SDK in this process — the head unit
    // starts the app without the activity ever running — and MapView's
    // constructor throws when it isn't initialised. getInstance is idempotent.
    private val mapView = run {
        MapLibre.getInstance(carContext)
        MapView(carContext)
    }

    private val hud = HudOverlay(carContext)

    private var mapLibreMap: MapLibreMap? = null
    var overlays: MapOverlays? = null
        private set

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null

    /** Called on every GPS fix: follows the camera and updates the HUD numbers. */
    fun updatePosition(pos: LatLon, bearingDeg: Float, speedKmh: Double, limitKmh: Double?, zoom: Double) {
        hud.update(speedKmh, limitKmh)
        mapLibreMap?.let { setCamera(it, pos.lat, pos.lon, zoom, bearingDeg) }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        if (width <= 0 || height <= 0) return
        tearDownDisplay()

        val display = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "MapRouletteCarMap",
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

        presentation = Presentation(carContext, display.display).apply {
            setContentView(
                FrameLayout(context).apply {
                    addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    addView(hud, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                },
            )
            show()
        }

        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
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
            map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { style ->
                overlays = MapOverlays(style, carContext, darkTheme)
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Keep the followed position centered in what's actually visible,
        // not behind the action strip / trip-status chrome the host draws
        // over part of the surface.
        hud.setSafeArea(visibleArea)
        val map = mapLibreMap ?: return
        map.setPadding(
            visibleArea.left, visibleArea.top,
            (mapView.width - visibleArea.right).coerceAtLeast(0),
            (mapView.height - visibleArea.bottom).coerceAtLeast(0),
        )
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        tearDownDisplay()
    }

    /** Tears down the map. Call once, when the nav screen is destroyed. */
    fun destroy() {
        tearDownDisplay()
    }

    private fun tearDownDisplay() {
        if (presentation == null && virtualDisplay == null) return
        mapLibreMap = null
        overlays = null
        runCatching {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        (mapView.parent as? FrameLayout)?.removeAllViews()
    }
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
