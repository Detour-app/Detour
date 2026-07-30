package com.jellemax.maproulette.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.jellemax.maproulette.R
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.SpeedCameras
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** OpenFreeMap hosted vector styles, keyless and free. Neutral greys — "positron"
 *  by day, "dark" by night — so the map sits quietly under the Graphite chrome
 *  instead of the colourful "liberty" fighting the amber accent. */
fun openFreeMapStyleUrl(darkTheme: Boolean): String =
    if (darkTheme) "https://tiles.openfreemap.org/styles/dark"
    else "https://tiles.openfreemap.org/styles/positron"

/** Source/layer ids. One GeoJSON source per overlay kind; [MapOverlays.render]
 *  swaps only the data so the layers themselves are set up once. */
private const val SRC_REACH = "mr-reach"
private const val SRC_WEDGE = "mr-wedge"
private const val SRC_ROUTE = "mr-route"
private const val SRC_CANDIDATES = "mr-candidates"
private const val SRC_DEST = "mr-dest"
private const val SRC_POSITION = "mr-position"
private const val SRC_CAMERAS = "mr-cameras"
private const val IMG_DEST = "mr-img-dest"
private const val IMG_POSITION = "mr-img-position"
private const val IMG_CAMERA = "mr-img-camera"
const val LAYER_CANDIDATES = "mr-candidates-dot"
// Below city zoom the speed-camera icons pile up into an unreadable blob, and
// at loop-planning zoom they're just noise — hide them until zoomed past this.
private const val SPEED_CAMERA_MIN_ZOOM = 11f

/**
 * Owns the runtime sources and layers drawn on top of the basemap: the reach
 * circle and direction wedge, the route line, the spin candidates, and the
 * destination + own-position markers. Created once per [Style]; [render] only
 * pushes new GeoJSON, so overlays update without rebuilding the map.
 */
class MapOverlays(private val style: Style, context: Context, darkTheme: Boolean) {

    // The route line follows the app accent: amber on the dark basemap, blue on
    // the light one, so navigation matches the chrome instead of always amber.
    private val routeColor = if (darkTheme) ROUTE_COLOR_DARK else ROUTE_COLOR_LIGHT

    init {
        ContextCompat.getDrawable(context, R.drawable.ic_map_pin)?.let {
            style.addImage(IMG_DEST, it.toBitmap())
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_dot)?.let {
            style.addImage(IMG_POSITION, it.toBitmap())
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_camera)?.let {
            style.addImage(IMG_CAMERA, it.toBitmap())
        }
        listOf(SRC_REACH, SRC_WEDGE, SRC_ROUTE, SRC_CANDIDATES, SRC_DEST, SRC_POSITION, SRC_CAMERAS)
            .forEach { style.addSource(GeoJsonSource(it)) }

        // Bottom-to-top: fills, then the route (dark casing under the colored
        // line), then markers, with the tappable candidates on top.
        style.addLayer(FillLayer("mr-reach-fill", SRC_REACH).withProperties(
            PropertyFactory.fillColor("#2196F3"), PropertyFactory.fillOpacity(0.09f)))
        style.addLayer(LineLayer("mr-reach-line", SRC_REACH).withProperties(
            PropertyFactory.lineColor("#2196F3"), PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.7f)))
        style.addLayer(FillLayer("mr-wedge-fill", SRC_WEDGE).withProperties(
            PropertyFactory.fillColor("#FF9800"), PropertyFactory.fillOpacity(0.11f)))
        style.addLayer(LineLayer("mr-route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#0B1220"), PropertyFactory.lineWidth(11f),
            PropertyFactory.lineOpacity(0.85f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(LineLayer("mr-route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(SymbolLayer("mr-position", SRC_POSITION).withProperties(
            PropertyFactory.iconImage(IMG_POSITION),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        style.addLayer(SymbolLayer("mr-dest", SRC_DEST).withProperties(
            PropertyFactory.iconImage(IMG_DEST), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        // Speed cameras: static markers fed by the prefetch loop. Sit under the
        // candidate dots so a spin result is never hidden behind a camera.
        style.addLayer(SymbolLayer("mr-cameras", SRC_CAMERAS).withProperties(
            PropertyFactory.iconImage(IMG_CAMERA),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)
        ).also { it.setMinZoom(SPEED_CAMERA_MIN_ZOOM) })
        // Candidates as colored discs with a white ring; the color matches the
        // card row, and a tap is resolved by querying this layer.
        style.addLayer(CircleLayer(LAYER_CANDIDATES, SRC_CANDIDATES).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f)))
    }

    private fun setData(sourceId: String, fc: FeatureCollection) {
        (style.getSource(sourceId) as? GeoJsonSource)?.setGeoJson(fc)
    }

    /** Replace the speed-camera markers. Fed by the prefetch loop, not [render],
     *  because cameras refresh only as you near the edge of the fetched area. */
    fun setCameras(cameras: List<SpeedCameras.Camera>) {
        setData(SRC_CAMERAS, FeatureCollection.fromFeatures(
            cameras.map { Feature.fromGeometry(Point.fromLngLat(it.at.lon, it.at.lat)) }))
    }

    /** Push the current world state to the overlay sources. Pass [reachMeters]
     *  null to hide the reach circle/wedge (e.g. while navigating). */
    fun render(
        myLocation: LatLon?,
        destination: LatLon?,
        routePolyline: List<LatLon>?,
        reachMeters: Double?,
        directionDeg: Int?,
        candidates: List<CandidatePin>,
        showPosition: Boolean,
    ) {
        setData(SRC_REACH, if (myLocation != null && reachMeters != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(circle(myLocation, reachMeters)))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_WEDGE, if (myLocation != null && reachMeters != null && directionDeg != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(wedge(myLocation, reachMeters, directionDeg)))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_ROUTE, if (routePolyline != null && routePolyline.size >= 2)
            FeatureCollection.fromFeature(Feature.fromGeometry(
                LineString.fromLngLats(routePolyline.map { Point.fromLngLat(it.lon, it.lat) })))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_CANDIDATES, FeatureCollection.fromFeatures(
            candidates.mapIndexed { i, c ->
                Feature.fromGeometry(Point.fromLngLat(c.at.lon, c.at.lat)).apply {
                    addNumberProperty("index", i)
                    addStringProperty("color", String.format("#%06X", 0xFFFFFF and c.colorArgb))
                }
            }))

        setData(SRC_DEST, if (destination != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_POSITION, if (myLocation != null && showPosition)
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(myLocation.lon, myLocation.lat)))
        else FeatureCollection.fromFeatures(emptyList()))
    }

    companion object {
        const val ROUTE_COLOR_DARK = "#E8B04B"  // amber — the Graphite night accent
        const val ROUTE_COLOR_LIGHT = "#2F80ED" // blue — the day accent
    }
}

/** A spin candidate rendered as a colored map dot. */
data class CandidatePin(val at: LatLon, val colorArgb: Int)

private fun circle(center: LatLon, radiusMeters: Double, steps: Int = 64): Polygon {
    val ring = (0..steps).map { i -> offset(center, radiusMeters, i * 360.0 / steps) }
        .map { Point.fromLngLat(it.lon, it.lat) }
    return Polygon.fromLngLats(listOf(ring))
}

private fun wedge(center: LatLon, radiusMeters: Double, directionDeg: Int): Polygon {
    val arc = (-45..45 step 5).map { d -> offset(center, radiusMeters, (directionDeg + d).toDouble()) }
    val ring = (listOf(center) + arc + center).map { Point.fromLngLat(it.lon, it.lat) }
    return Polygon.fromLngLats(listOf(ring))
}

/** Point [meters] from [from] along [bearingDeg], flat-earth (fine at map scale). */
private fun offset(from: LatLon, meters: Double, bearingDeg: Double): LatLon {
    val rad = Math.toRadians(bearingDeg)
    val dLat = meters * cos(rad) / 111_320.0
    val dLon = meters * sin(rad) / (111_320.0 * cos(Math.toRadians(from.lat)))
    return LatLon(from.lat + dLat, from.lon + dLon)
}

/** Camera bounds fitted to [points], with [paddingPx] on the top/left/right
 *  and [bottomPaddingPx] on the bottom. Separate bottom padding because the
 *  expanded spin card sits over roughly the bottom half of the screen — a fit
 *  that only knew about [paddingPx] would tuck the route right behind it. */
fun cameraForPoints(map: MapLibreMap, points: List<LatLon>, paddingPx: Int, bottomPaddingPx: Int = paddingPx) {
    if (points.isEmpty()) return
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lon)) }
    val bounds = if (points.size == 1)
        LatLngBounds.Builder()
            .include(LatLng(points[0].lat + 0.005, points[0].lon + 0.005))
            .include(LatLng(points[0].lat - 0.005, points[0].lon - 0.005)).build()
    else builder.build()
    map.animateCamera(
        CameraUpdateFactory.newLatLngBounds(bounds, paddingPx, paddingPx, paddingPx, bottomPaddingPx))
}

/** Camera position for the follow loop: target/zoom/bearing in one shot. */
fun setCamera(map: MapLibreMap, lat: Double, lon: Double, zoom: Double, bearingDeg: Float) {
    map.cameraPosition = CameraPosition.Builder()
        .target(LatLng(lat, lon)).zoom(zoom).bearing(bearingDeg.toDouble()).tilt(0.0).build()
}

/**
 * Fog-of-war overlay: a dark scrim over the whole map with a clear corridor
 * punched along every driven trace and around the current position. Sits as a
 * child View over the GL surface and reprojects through [map] each time the
 * camera moves, so it stays glued to the map in heading-up mode.
 */
class FogView(context: Context) : View(context) {
    var map: MapLibreMap? = null
        set(value) {
            field?.removeOnCameraIdleListener(idleListener)
            field = value
            value?.addOnCameraIdleListener(idleListener)
        }
    // Raw GPS tracks carry a point every few metres; the fog corridor is tens of
    // metres wide, so projecting every one through the per-point JNI call is the
    // bulk of the pan cost. Store a decimated copy — points within ~25 m of the
    // last kept one are dropped — which cuts the projection work several-fold with
    // no visible change to the corridor.
    var traces: List<List<LatLon>> = emptyList()
        set(value) { field = value.map { decimate(it) } }
    var currentLocation: LatLon? = null
    var corridorMeters: Float = 200f
    var active: Boolean = false
        set(value) {
            // Rising edge: the last snapshot (if any) predates the toggle, so ask
            // for a fresh one instead of waiting for the next camera gesture.
            val request = value && !field
            field = value
            if (request) requestSnapshot()
        }

    init {
        setWillNotDraw(false)
    }

    private val fogColor = Color.argb(150, 8, 10, 26)
    // Undiscovered ground reads as "not yet seen" better when it's out of focus,
    // not just darker. A sibling View can't backdrop-blur the GL map surface, so
    // the frost is faked from a map snapshot taken when the camera settles:
    // downscale hard, upscale back (a cheap two-pass box blur), then dim. While
    // the camera is moving the snapshot no longer lines up, so onDraw falls back
    // to the plain scrim and the frost returns on the next idle.
    private var blurred: Bitmap? = null
    private var blurredCam: CameraPosition? = null
    // Fading the frost in over the scrim hides the scrim→frost pop when the
    // camera settles. Fade-out gets no such treatment on purpose: the moment
    // the camera moves the snapshot no longer lines up, so lingering over it
    // would smear a stale image across the wrong roads — snap back instead.
    private var frostFadeStartMs = 0L
    private val frostPaint = Paint()
    private val idleListener = MapLibreMap.OnCameraIdleListener { requestSnapshot() }

    private fun requestSnapshot() {
        val m = map ?: return
        if (!active || width <= 0 || height <= 0) return
        val cam = m.cameraPosition
        m.snapshot { shot ->
            if (!active) return@snapshot
            val bw = max(1, (width + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
            val bh = max(1, (height + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
            // Two createScaledBitmap passes: down to ~1/12 screen res for the
            // blur radius, back up to buffer res with filtering for the frost.
            val tiny = Bitmap.createScaledBitmap(shot, max(1, bw / 4), max(1, bh / 4), true)
            blurred = Bitmap.createScaledBitmap(tiny, bw, bh, true)
            tiny.recycle()
            blurredCam = cam
            invalidate()
        }
    }

    /** The snapshot only lines up while the camera sits exactly where it was taken. */
    private fun blurUsable(cam: CameraPosition, bw: Int, bh: Int): Boolean {
        val b = blurred ?: return false
        val c = blurredCam ?: return false
        val t = cam.target ?: return false
        val ct = c.target ?: return false
        return b.width == bw && b.height == bh &&
            abs(t.latitude - ct.latitude) < 1e-7 && abs(t.longitude - ct.longitude) < 1e-7 &&
            abs(cam.zoom - c.zoom) < 1e-4 && abs(cam.bearing - c.bearing) < 1e-3 &&
            abs(cam.tilt - c.tilt) < 1e-3
    }
    private val clearPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val clearFillPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    // A soft scrim doesn't need pixel-exact edges, so the buffer is rendered at a
    // fraction of screen resolution and blown back up on draw. Everything here is
    // a software (CPU, main-thread) canvas — erasing and path-filling a full 1440×
    // 3120 ARGB bitmap every camera move cost ~65 ms/frame; at 1/DOWNSCALE it's a
    // ~9× smaller bitmap, which is what takes the fog off the jank budget.
    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null
    private val upscalePaint = Paint().apply { isFilterBitmap = true }
    private val dst = android.graphics.RectF()

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val m = map ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val bw = max(1, (w + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
        val bh = max(1, (h + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
        val buf = buffer?.takeIf { it.width == bw && it.height == bh }
            ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                buffer = it
                bufferCanvas = Canvas(it)
            }
        val bufCanvas = bufferCanvas ?: return
        val frost = blurred?.takeIf { blurUsable(m.cameraPosition, bw, bh) }
        buf.eraseColor(fogColor)
        if (frost != null) {
            // Frosted base cross-faded over the scrim; the tint restores the
            // dimming the corridor contrast relies on, scaled with the fade so
            // mid-fade frames don't double-darken.
            val now = android.os.SystemClock.uptimeMillis()
            if (frostFadeStartMs == 0L) frostFadeStartMs = now
            val a = ((now - frostFadeStartMs) * 255 / FROST_FADE_MS).toInt().coerceAtMost(255)
            frostPaint.alpha = a
            bufCanvas.drawBitmap(frost, 0f, 0f, frostPaint)
            bufCanvas.drawColor(Color.argb(FROST_TINT_ALPHA * a / 255, 8, 10, 26))
            if (a < 255) postInvalidateOnAnimation()
        } else {
            frostFadeStartMs = 0L
        }

        // Buffer space: full-res screen coords scaled down by FOG_DOWNSCALE.
        val s = 1f / FOG_DOWNSCALE
        val proj = m.projection
        val lat = currentLocation?.lat ?: m.cameraPosition.target?.latitude ?: 0.0
        val metersPerPx = proj.getMetersPerPixelAtLatitude(lat).toFloat()
        val corridorPx = max(18f, corridorMeters / metersPerPx)
        clearPaint.strokeWidth = corridorPx * s

        // toScreenLocation is a per-point JNI call, so projecting every trace
        // every frame is what made panning lag. Cull whole traces whose bounding
        // box doesn't touch the padded viewport first — most are off-screen when
        // zoomed in, and the bbox test is cheap arithmetic with no projection.
        val vb = proj.visibleRegion.latLngBounds
        val padDeg = (corridorMeters * 2.0) / 111_000.0
        val north = vb.latitudeNorth + padDeg
        val south = vb.latitudeSouth - padDeg
        val east = vb.longitudeEast + padDeg
        val west = vb.longitudeWest - padDeg

        val pt = PointF()
        for (trace in traces) {
            if (trace.isEmpty()) continue
            var tN = -90.0; var tS = 90.0; var tE = -180.0; var tW = 180.0
            for (p in trace) {
                if (p.lat > tN) tN = p.lat
                if (p.lat < tS) tS = p.lat
                if (p.lon > tE) tE = p.lon
                if (p.lon < tW) tW = p.lon
            }
            if (tS > north || tN < south || tW > east || tE < west) continue
            val path = Path()
            var first = true
            for (p in trace) {
                val sp = proj.toScreenLocation(LatLng(p.lat, p.lon))
                if (first) { path.moveTo(sp.x * s, sp.y * s); first = false }
                else path.lineTo(sp.x * s, sp.y * s)
            }
            bufCanvas.drawPath(path, clearPaint)
        }
        currentLocation?.let { loc ->
            val sp = proj.toScreenLocation(LatLng(loc.lat, loc.lon))
            pt.set(sp.x * s, sp.y * s)
            bufCanvas.drawCircle(pt.x, pt.y,
                max(corridorPx, corridorMeters * 1.75f / metersPerPx) * s, clearFillPaint)
        }
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(buf, null, dst, upscalePaint)
    }

    companion object {
        // 1/3 resolution: the scrim edge stays soft, the CPU fill drops ~9×.
        private const val FOG_DOWNSCALE = 3
        private const val FROST_FADE_MS = 250L
        // Lighter than the scrim's 150: once frosted, the blur itself carries
        // part of the "hidden" signal, so the dim can ease off.
        private const val FROST_TINT_ALPHA = 110
        // ~25 m in degrees of latitude; used as the decimation floor for traces.
        private const val DECIMATE_DEG = 2.25e-4

        /** Drop points within [DECIMATE_DEG] of the last kept one; endpoints stay. */
        private fun decimate(trace: List<LatLon>): List<LatLon> {
            if (trace.size <= 2) return trace
            val out = ArrayList<LatLon>(trace.size)
            var last = trace[0]
            out.add(last)
            for (i in 1 until trace.size - 1) {
                val p = trace[i]
                if (abs(p.lat - last.lat) > DECIMATE_DEG || abs(p.lon - last.lon) > DECIMATE_DEG) {
                    out.add(p)
                    last = p
                }
            }
            out.add(trace[trace.size - 1])
            return out
        }
    }
}
