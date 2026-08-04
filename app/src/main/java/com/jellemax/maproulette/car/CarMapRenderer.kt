package com.jellemax.maproulette.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Surface
import android.view.View
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.ui.MapOverlays
import com.jellemax.maproulette.ui.openFreeMapStyleUrl
import com.jellemax.maproulette.ui.setCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Android Auto gives an app only a raw [Surface] via [SurfaceCallback] — no
 * map widget. Drives an offscreen [MapView] the same way the phone's map
 * does (same style, same [MapOverlays]) and blits a bitmap snapshot of it
 * onto the car surface at ~30fps: MapLibre's own documented pattern for this
 * (github.com/maplibre/MapLibre-Android-Auto-Sample), since there is no
 * native car-surface renderer. Texture mode + a hardware layer are required
 * for `View.draw(Canvas)` to actually capture the GL content — the default
 * SurfaceView-backed mode renders outside the view hierarchy and would
 * produce a blank bitmap.
 *
 * Also draws the speed/speed-limit HUD directly onto the surface's canvas
 * each frame, since that can't be a MapLibre layer.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    darkTheme: Boolean,
) : SurfaceCallback {

    // MainActivity initialises MapLibre for the phone UI, but the car flow can
    // be the first thing to touch the SDK in this process — the head unit
    // starts the app without the activity ever running — and MapView's
    // constructor throws when it isn't initialised. getInstance is idempotent.
    private val mapView = run {
        MapLibre.getInstance(carContext)
        MapView(
            carContext,
            MapLibreMapOptions.createFromAttributes(carContext).textureMode(true),
        ).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
    }

    private var mapLibreMap: MapLibreMap? = null
    var overlays: MapOverlays? = null
        private set

    private var surface: Surface? = null
    private var renderJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    @Volatile private var currentSpeedKmh: Double? = null
    @Volatile private var speedLimitKmh: Double? = null

    init {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false
            mapLibreMap = map
            map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { style ->
                overlays = MapOverlays(style, carContext, darkTheme)
            }
        }
    }

    /** Called on every GPS fix: follows the camera and updates the HUD numbers. */
    fun updatePosition(pos: LatLon, bearingDeg: Float, speedKmh: Double, limitKmh: Double?, zoom: Double) {
        currentSpeedKmh = speedKmh
        speedLimitKmh = limitKmh
        mapLibreMap?.let { setCamera(it, pos.lat, pos.lon, zoom, bearingDeg) }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        if (width <= 0 || height <= 0) return
        mapView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        mapView.layout(0, 0, width, height)
        startRenderLoop()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Keep the followed position centered in what's actually visible,
        // not behind the action strip / trip-status chrome the host draws
        // over part of the surface.
        val map = mapLibreMap ?: return
        map.setPadding(
            visibleArea.left, visibleArea.top,
            (mapView.width - visibleArea.right).coerceAtLeast(0),
            (mapView.height - visibleArea.bottom).coerceAtLeast(0),
        )
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        stopRenderLoop()
        surface = null
    }

    /** Tears down the offscreen map. Call once, when the nav screen is destroyed. */
    fun destroy() {
        stopRenderLoop()
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
    }

    private fun startRenderLoop() {
        stopRenderLoop()
        renderJob = scope.launch {
            while (isActive) {
                drawFrame()
                delay(33)
            }
        }
    }

    private fun stopRenderLoop() {
        renderJob?.cancel()
        renderJob = null
    }

    private fun drawFrame() {
        val target = surface?.takeIf { it.isValid } ?: return
        val width = mapView.width
        val height = mapView.height
        if (width <= 0 || height <= 0) return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mapView.draw(Canvas(bitmap))
        val canvas = try {
            target.lockCanvas(null)
        } catch (e: Exception) {
            null
        } ?: run { bitmap.recycle(); return }
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            drawHud(canvas)
        } finally {
            target.unlockCanvasAndPost(canvas)
            bitmap.recycle()
        }
    }

    private val speedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC1A1A1A") }
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 56f
    }
    private val limitRingBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val limitRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 14f
    }
    private val limitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 52f
    }

    private fun drawHud(canvas: Canvas) {
        val speed = currentSpeedKmh
        val cy = canvas.height - 140f
        var cx = 140f
        if (speed != null) {
            canvas.drawCircle(cx, cy, 100f, speedBgPaint)
            canvas.drawText("%.0f".format(speed), cx, cy + 20f, speedTextPaint)
            cx += 240f
        }
        val limit = speedLimitKmh
        if (limit != null) {
            canvas.drawCircle(cx, cy, 100f, limitRingBgPaint)
            canvas.drawCircle(cx, cy, 90f, limitRingPaint)
            canvas.drawText("%.0f".format(limit), cx, cy + 18f, limitTextPaint)
        }
    }
}
