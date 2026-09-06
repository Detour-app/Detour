package com.jellemax.detour.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.jellemax.detour.data.FogGeometry
import com.jellemax.detour.data.FogSegment
import com.jellemax.detour.data.FogTransform
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.LatLonBox
import com.jellemax.detour.data.Perf
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Projection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Fog-of-war overlay: a dark scrim over the whole map with a clear corridor
 * punched along every driven trace and around the current position. Sits as a
 * child View over the GL surface and follows [map] through its own projection,
 * so it stays glued to the map in heading-up mode.
 *
 * It does *not* re-project on every camera move. The corridor is stroked into a
 * mask that reaches past the viewport, and a frame whose camera the mask still
 * covers takes it out of the scrim through a transform rather than walking the
 * rider's history again — history that the follow camera would otherwise have it
 * re-project at the display rate for as long as the ride lasts. #213.
 */
class FogView(context: Context) : View(context) {
    var map: MapLibreMap? = null
        set(value) {
            field?.removeOnCameraIdleListener(idleListener)
            field = value
            value?.addOnCameraIdleListener(idleListener)
            // A second map is a second projection, and the standing corridor mask
            // was placed through the first one.
            maskDirty = true
        }
    // Raw GPS tracks carry a point every few metres; the fog corridor is tens of
    // metres wide, so projecting every one through the per-point JNI call is the
    // bulk of the pan cost. Store a decimated copy — points within ~25 m of the
    // last kept one are dropped — which cuts the projection work several-fold with
    // no visible change to the corridor.
    var traces: List<List<LatLon>> = emptyList()
        set(value) {
            // Re-decimates the whole stored set on every store write, so it grows
            // with the rider's history. #84.
            val t = Perf.start()
            field = value
            stored = value.mapNotNull { corridorOf(it) }
            maskDirty = true
            Perf.end(t, "FogView.traces") {
                listOf("segments" to value.size, "points" to value.sumOf { it.size })
            }
        }
    // The in-progress trace, kept out of [traces] because it grows with every
    // GPS fix — folding it in re-decimated the whole stored set once a second.
    // This one small list is decimated alone instead.
    var liveTrace: List<LatLon> = emptyList()
        set(value) {
            // Written once per GPS fix, whether or not a trip is being recorded,
            // and rebuilding the mask is the expensive half of a draw — so a fix
            // that added no point must not cost one.
            if (value == field) return
            field = value
            live = corridorOf(value)
            maskDirty = true
        }
    // What the draw pass walks: the decimated points together with the box they
    // decimated into. The box is what the viewport cull tests and it only changes
    // when the trace does, so computing it here rather than in onDraw takes a walk
    // over the rider's entire history off every frame — the walk the cull existed
    // to avoid in the first place. #213.
    private var stored: List<Corridor> = emptyList()
    private var live: Corridor? = null
    var currentLocation: LatLon? = null
    // Everyone else the map is drawing: circle members and convoy peers. The
    // scrim sits over the GL surface, so a marker on ground you have never
    // driven is simply invisible under it — and a circle exists precisely to
    // show someone standing somewhere you haven't been. Cleared like the
    // corridor is, so the person is visible without lifting the fog anywhere
    // they aren't.
    var peers: List<LatLon> = emptyList()
    var corridorMeters: Float = 200f
        set(value) {
            if (value == field) return
            field = value
            // The corridor is stroked into the mask at this width, so a change to
            // the fog radius is a change to what is already drawn there.
            maskDirty = true
        }
    // Dark fog reads as night on a light basemap and vice versa, so the scrim/
    // frost tint switch with the app theme; see FOG_DARK/FOG_LIGHT below.
    var darkTheme: Boolean = true
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
        // Feathered corridor edges. A BlurMaskFilter on the clear paints did
        // this in software and cost a full CPU blur per trace per frame — with
        // a screen of traces that alone blew the frame budget (measured 150 ms+
        // frames, 100% jank). A RenderEffect blurs the view's composited output
        // once, on the RenderThread's GPU pass, for ~nothing; the corridors are
        // punched hard-edged and soften in that pass. Below API 31 there is no
        // RenderEffect: edges stay hard, softened only by the 1/3-res upscale.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(
                FEATHER_RADIUS_PX, FEATHER_RADIUS_PX, Shader.TileMode.CLAMP))
        }
    }

    // Scrim + frost tint both key off the same per-theme RGB (FOG_DARK/FOG_LIGHT)
    // so they can't drift apart when one gets retuned without the other.
    private val fogTheme: FogTheme
        get() = if (darkTheme) FOG_DARK else FOG_LIGHT
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

    private var lastSnapshotMs = 0L

    private fun requestSnapshot() {
        val m = map ?: return
        if (!active || width <= 0 || height <= 0) return
        val bw = max(1, (width + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
        val bh = max(1, (height + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
        // The follow loop eases the camera every frame, so onCameraIdle fires in
        // bursts; unthrottled that meant a full-screen GL readback plus an ~18 MB
        // bitmap allocation per burst (the measured second-long main-thread
        // stalls). Rate-limit, and skip entirely when the standing frost already
        // matches the camera.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSnapshotMs < SNAPSHOT_MIN_INTERVAL_MS) return
        if (blurUsable(m.cameraPosition, bw, bh)) return
        lastSnapshotMs = now
        val cam = m.cameraPosition
        m.snapshot { shot ->
            if (!active) return@snapshot
            // The scale chain walks millions of source pixels; off the UI thread
            // so the settle never hitches. One worker at a time by construction:
            // requests are throttled well above a scale pass's duration.
            Thread {
                // Three createScaledBitmap passes (down to ~1/6, up to ~1/2, up
                // to full buffer res, all bilinear) — a single down/up pass was
                // too weak to read as frost once the tint went light.
                val tiny = Bitmap.createScaledBitmap(shot, max(1, bw / 6), max(1, bh / 6), true)
                val mid = Bitmap.createScaledBitmap(tiny, max(1, bw / 2), max(1, bh / 2), true)
                tiny.recycle()
                val result = Bitmap.createScaledBitmap(mid, bw, bh, true)
                mid.recycle()
                post {
                    blurred = result
                    blurredCam = cam
                    invalidate()
                }
            }.start()
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
    // The corridor is stroked into its own mask as solid white and taken out of
    // the scrim when that mask is blitted, so this one paints where it used to
    // erase; the CLEAR became the DST_OUT on [maskPaint].
    private val corridorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        color = Color.WHITE
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

    // The punched corridor, kept between frames. The scrim and the reveal holes
    // around live positions are cheap and are drawn fresh every frame; projecting
    // the corridor is neither, and the follow loop moves the camera on every one
    // of them. So the corridor is stroked once into a mask of its own — with a
    // margin around the viewport, so the camera has somewhere to move to — and
    // every frame after that takes it out of the scrim through the transform
    // between the camera it was projected at and the camera now. #213.
    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var maskDirty = true
    // Two world points whose position in mask pixels is known, re-projected each
    // frame to recover where the mask now belongs. Nothing else about the old
    // camera is kept: two points fix a translation, a rotation and a scale
    // between them, and at zero tilt that is the entire transform.
    private var maskRefA: LatLng? = null
    private var maskRefB: LatLng? = null
    private var maskRef: FogSegment? = null
    private var maskPlacement: FogTransform? = null
    // What the standing mask cost to build, reported by the frame that built it
    // so the onDraw series still carries the size its duration ran over.
    private var maskPoints = 0
    private var maskTraces = 0
    private val maskMatrix = Matrix()
    private val maskValues = FloatArray(9).also { it[Matrix.MPERSP_2] = 1f }
    private val maskPaint = Paint().apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    // Reused across every projection in a frame: LatLng is mutable and
    // toScreenLocation has no allocation-free overload, so this is the one
    // allocation per point that can actually be taken out.
    private val scratchLatLng = LatLng()
    private val corridorPath = Path()

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val m = map ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Started after the bails, so the series measures paints and not
        // no-ops. Every optimisation in this class — the 25 m decimation, the
        // bounding-box cull, the 1/3-resolution buffer, the snapshot throttle,
        // the corridor mask — was tuned against a number measured once and then
        // discarded, and a regression in any of them is invisible today. Per
        // frame while the map pans, so this label aggregates; see PerfLog.isHot.
        val perfMark = Perf.start()

        val bw = max(1, (w + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
        val bh = max(1, (h + FOG_DOWNSCALE - 1) / FOG_DOWNSCALE)
        val buf = buffer?.takeIf { it.width == bw && it.height == bh }
            ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                buffer = it
                bufferCanvas = Canvas(it)
            }
        val bufCanvas = bufferCanvas ?: return
        paintBase(m, buf, bufCanvas, bw, bh)

        // Buffer space: full-res screen coords scaled down by FOG_DOWNSCALE.
        val s = 1f / FOG_DOWNSCALE
        val proj = m.projection
        val lat = currentLocation?.lat ?: m.cameraPosition.target?.latitude ?: 0.0
        val metersPerPx = proj.getMetersPerPixelAtLatitude(lat).toFloat()
        val corridorPx = max(18f, corridorMeters / metersPerPx)

        val rebuilt = ensureMask(m, bw, bh, corridorPx, metersPerPx)
        maskPlacement?.let { punchCorridor(bufCanvas, it) }

        // The reveal around the rider and around everyone else on the map stays
        // out of the mask and is projected fresh: it is a handful of points, and
        // it moves under a still camera, which is exactly when the mask is not
        // being rebuilt.
        val revealPx = max(corridorPx, corridorMeters * REVEAL_RADIUS_FACTOR / metersPerPx) * s
        currentLocation?.let { revealAt(bufCanvas, proj, it, s, revealPx) }
        for (peer in peers) revealAt(bufCanvas, proj, peer, s, revealPx)

        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(buf, null, dst, upscalePaint)
        // Points projected, not points held: the cull is most of what keeps a
        // rebuild affordable, so the covariate has to be the work actually done —
        // and a frame that re-used the mask projected nothing, which is what
        // separates the two kinds of frame in the aggregate.
        Perf.end(perfMark, "FogView.onDraw") {
            listOf(
                "points" to if (rebuilt) maskPoints else 0,
                "traces" to if (rebuilt) maskTraces else 0,
            )
        }
    }

    /** The scrim, with the frost snapshot cross-faded over it when one lines up. */
    private fun paintBase(m: MapLibreMap, buf: Bitmap, bufCanvas: Canvas, bw: Int, bh: Int) {
        val theme = fogTheme
        buf.eraseColor(Color.argb(theme.scrimAlpha, theme.r, theme.g, theme.b))
        val frost = blurred?.takeIf { blurUsable(m.cameraPosition, bw, bh) }
        if (frost == null) {
            frostFadeStartMs = 0L
            return
        }
        // Frosted base cross-faded over the scrim; the tint restores the
        // dimming the corridor contrast relies on, scaled with the fade so
        // mid-fade frames don't double-darken.
        val now = android.os.SystemClock.uptimeMillis()
        if (frostFadeStartMs == 0L) frostFadeStartMs = now
        val a = ((now - frostFadeStartMs) * 255 / FROST_FADE_MS).toInt().coerceAtMost(255)
        frostPaint.alpha = a
        bufCanvas.drawBitmap(frost, 0f, 0f, frostPaint)
        bufCanvas.drawColor(Color.argb(theme.frostTintAlpha * a / 255, theme.r, theme.g, theme.b))
        if (a < 255) postInvalidateOnAnimation()
    }

    /** One clear circle at [at], projected now rather than taken from the mask. */
    private fun revealAt(canvas: Canvas, proj: Projection, at: LatLon, scale: Float, radiusPx: Float) {
        scratchLatLng.latitude = at.lat
        scratchLatLng.longitude = at.lon
        val sp = proj.toScreenLocation(scratchLatLng)
        canvas.drawCircle(sp.x * scale, sp.y * scale, radiusPx, clearFillPaint)
    }

    /**
     * Makes the corridor mask usable for this frame, re-projecting it only when
     * it has to be. Returns whether it re-projected.
     *
     * What forces a rebuild: the traces changed, the view is tilted (the
     * transform is a similarity, which a tilted view is not), or the standing
     * mask is no longer [reusable] under the camera now. The last of those is the
     * one that matters while driving — an uncovered strip draws as fog over
     * ground the rider has already been down.
     */
    private fun ensureMask(m: MapLibreMap, bw: Int, bh: Int, strokePx: Float, metersPerPx: Float): Boolean {
        val mw = bw + 2 * FOG_MASK_MARGIN_PX
        val mh = bh + 2 * FOG_MASK_MARGIN_PX
        val standing = mask?.takeIf { it.width == mw && it.height == mh }
        if (standing != null && !maskDirty && m.cameraPosition.tilt < TILT_EPS) {
            val placed = maskTransform(m.projection)
            if (placed != null && reusable(placed, mw, mh, bw, bh)) {
                maskPlacement = placed
                return false
            }
        }
        buildMask(m, mw, mh, strokePx, metersPerPx)
        return true
    }

    /** Whether the standing mask can be re-used at [placed] rather than rebuilt. */
    private fun reusable(placed: FogTransform, mw: Int, mh: Int, bw: Int, bh: Int): Boolean {
        // Zooming in never uncovers the mask, so coverage on its own would let a
        // pinch keep re-using pixels rasterised several zoom levels out until the
        // corridor edge turned to mush. For a similarity the scale is s² = a² + c².
        val scaleSq = placed.a * placed.a + placed.c * placed.c
        if (scaleSq > MAX_MASK_SCALE_SQ) return false
        return FogGeometry.covers(placed, mw.toDouble(), mh.toDouble(), bw.toDouble(), bh.toDouble())
    }

    /** Strokes every visible corridor into a fresh mask and anchors it. */
    private fun buildMask(m: MapLibreMap, mw: Int, mh: Int, strokePx: Float, metersPerPx: Float) {
        val target = mask?.takeIf { it.width == mw && it.height == mh }
            ?: Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888).also {
                mask = it
                maskCanvas = Canvas(it)
            }
        val canvas = maskCanvas ?: return
        target.eraseColor(Color.TRANSPARENT)
        val proj = m.projection
        val margin = FOG_MASK_MARGIN_PX.toFloat()
        anchorMask(proj, margin)
        // Mask pixels are buffer pixels shifted by the margin, so the mask this
        // call produces belongs at exactly that offset and needs no projection
        // to place it.
        maskPlacement = FogTransform(1.0, 0.0, 0.0, 1.0, -margin.toDouble(), -margin.toDouble())

        corridorPaint.strokeWidth = strokePx
        val view = maskViewport(proj, metersPerPx)
        var points = 0
        var drawn = 0
        // Drawn in full-resolution screen coordinates: the canvas carries the
        // downscale and the margin, so neither the projection loop nor the stroke
        // width has to.
        canvas.save()
        canvas.translate(margin, margin)
        canvas.scale(1f / FOG_DOWNSCALE, 1f / FOG_DOWNSCALE)
        for (corridor in stored) {
            val projected = strokeCorridor(canvas, proj, corridor, view)
            points += projected
            if (projected > 0) drawn++
        }
        live?.let {
            val projected = strokeCorridor(canvas, proj, it, view)
            points += projected
            if (projected > 0) drawn++
        }
        canvas.restore()
        maskPoints = points
        maskTraces = drawn
        maskDirty = false
    }

    /**
     * Strokes [corridor] into the mask unless the viewport cannot see it, and
     * returns how many points that cost.
     *
     * toScreenLocation is a per-point JNI call, so projecting every trace is what
     * made panning lag. The cull is cheap arithmetic against a box computed when
     * the trace was stored, and when zoomed in most of a rider's history fails
     * it.
     */
    private fun strokeCorridor(canvas: Canvas, proj: Projection, corridor: Corridor, view: LatLonBox): Int {
        if (FogGeometry.culled(corridor.box, view)) return 0
        corridorPath.rewind()
        var first = true
        for (p in corridor.points) {
            scratchLatLng.latitude = p.lat
            scratchLatLng.longitude = p.lon
            val sp = proj.toScreenLocation(scratchLatLng)
            if (first) {
                corridorPath.moveTo(sp.x, sp.y)
                first = false
            } else {
                corridorPath.lineTo(sp.x, sp.y)
            }
        }
        canvas.drawPath(corridorPath, corridorPaint)
        return corridor.points.size
    }

    /**
     * Remembers two world points and where they sit in mask pixels, so a later
     * frame recovers the mask's placement by projecting them again.
     *
     * Taken from two screen positions rather than chosen in world coordinates, so
     * the pair is always well separated and always somewhere the map can project.
     */
    private fun anchorMask(proj: Projection, margin: Float) {
        val leftX = width * 0.25f
        val rightX = width * 0.75f
        val midY = height * 0.5f
        maskRefA = proj.fromScreenLocation(PointF(leftX, midY))
        maskRefB = proj.fromScreenLocation(PointF(rightX, midY))
        val s = 1f / FOG_DOWNSCALE
        maskRef = FogSegment(
            (leftX * s + margin).toDouble(), (midY * s + margin).toDouble(),
            (rightX * s + margin).toDouble(), (midY * s + margin).toDouble(),
        )
    }

    /** Where the standing mask belongs under the camera now, or null when its
     *  reference span has collapsed and only a rebuild is safe. */
    private fun maskTransform(proj: Projection): FogTransform? {
        val from = maskRef ?: return null
        val a = maskRefA ?: return null
        val b = maskRefB ?: return null
        val s = 1f / FOG_DOWNSCALE
        val pa = proj.toScreenLocation(a)
        val pb = proj.toScreenLocation(b)
        return FogGeometry.transform(
            from,
            FogSegment(
                (pa.x * s).toDouble(), (pa.y * s).toDouble(),
                (pb.x * s).toDouble(), (pb.y * s).toDouble(),
            ),
        )
    }

    /** Takes the mask out of the scrim at [placed]. */
    private fun punchCorridor(canvas: Canvas, placed: FogTransform) {
        val bitmap = mask ?: return
        maskValues[Matrix.MSCALE_X] = placed.a.toFloat()
        maskValues[Matrix.MSKEW_X] = placed.b.toFloat()
        maskValues[Matrix.MTRANS_X] = placed.tx.toFloat()
        maskValues[Matrix.MSKEW_Y] = placed.c.toFloat()
        maskValues[Matrix.MSCALE_Y] = placed.d.toFloat()
        maskValues[Matrix.MTRANS_Y] = placed.ty.toFloat()
        maskMatrix.setValues(maskValues)
        canvas.drawBitmap(bitmap, maskMatrix, maskPaint)
    }

    /**
     * The box a rebuild projects into: what is on screen, plus the corridor's own
     * width and the margin the mask carries, so a trace that scrolls in while the
     * mask is still being re-used was already stroked into it.
     */
    private fun maskViewport(proj: Projection, metersPerPx: Float): LatLonBox {
        val vb = proj.visibleRegion.latLngBounds
        val padMeters = corridorMeters * 2.0 + FOG_MASK_MARGIN_PX * FOG_DOWNSCALE * metersPerPx
        val padLat = padMeters / METERS_PER_DEG_LAT
        // A degree of longitude is shorter the further from the equator, so the
        // same padding in metres is more degrees of it; without this the east/west
        // pad is half what it should be by 60°.
        val padLon = padLat / max(POLAR_COS_FLOOR, cos(Math.toRadians(vb.center.latitude)))
        return LatLonBox(
            north = vb.latitudeNorth + padLat,
            south = vb.latitudeSouth - padLat,
            east = vb.longitudeEast + padLon,
            west = vb.longitudeWest - padLon,
        )
    }

    companion object {
        // 1/3 resolution: the scrim edge stays soft, the CPU fill drops ~9×.
        private const val FOG_DOWNSCALE = 3
        private const val FROST_FADE_MS = 250L
        // Screen-space feather for the corridor edges via RenderEffect (GPU).
        private const val FEATHER_RADIUS_PX = 6f
        // Idle fires in bursts while the follow loop eases the camera; one
        // snapshot a second is plenty for a static frost.
        private const val SNAPSHOT_MIN_INTERVAL_MS = 1_000L
        // ~25 m in degrees of latitude; used as the decimation floor for traces.
        private const val DECIMATE_DEG = 2.25e-4
        // How far past the viewport, in buffer pixels, the corridor mask is
        // stroked. This is the whole budget the camera has to move on before a
        // re-projection, so it trades bitmap for frames: 96 here is ~288 screen
        // pixels of pan (or a rotation of some 20° about the middle of a phone
        // screen) and costs about a megapixel of ARGB on a 1440-wide device.
        private const val FOG_MASK_MARGIN_PX = 96
        // The mask is placed by a similarity, which a tilted view is not; the app
        // never tilts of its own accord ([setCamera] pins tilt at 0), so this is
        // about a gesture that did.
        private const val TILT_EPS = 0.01
        // How far the mask may be blown up before it is worth re-rasterising:
        // 1.5x, squared, because the scale comes out of the transform squared.
        // It is a 1/3-resolution bitmap under a blur, so there is room, but not
        // several zoom levels of it.
        private const val MAX_MASK_SCALE_SQ = 2.25
        // The reveal around a live position is wider than the corridor: standing
        // still should clear a little more than driving past does.
        private const val REVEAL_RADIUS_FACTOR = 1.75f
        private const val METERS_PER_DEG_LAT = 111_000.0
        // Longitude padding divides by cos(latitude), which goes to zero at the
        // poles; the floor keeps the pad finite where nobody is riding anyway.
        private const val POLAR_COS_FLOOR = 0.05

        // One RGB per theme feeds both the scrim and the frost tint, so the two
        // can't be retuned out of sync with each other.
        private class FogTheme(val r: Int, val g: Int, val b: Int, val scrimAlpha: Int, val frostTintAlpha: Int)
        private val FOG_DARK = FogTheme(r = 8, g = 10, b = 26, scrimAlpha = 150,
            // Lighter than the scrim: once frosted, the blur itself carries part
            // of the "hidden" signal, so the dim can ease off.
            frostTintAlpha = 110)
        // Scrim needs more weight here than feels natural: a pale wash over the
        // already-pale positron basemap barely registers (white roads stay
        // white), so unexplored ground leaked through during pans and the frost
        // seemed to appear from nothing at settle. Darker + more opaque puts the
        // moving-camera state in the same perceived band as the frost.
        private val FOG_LIGHT = FogTheme(r = 222, g = 228, b = 236, scrimAlpha = 205, frostTintAlpha = 120)

        /** A trace as the draw pass wants it: decimated, with the box the cull tests. */
        private class Corridor(val points: List<LatLon>, val box: LatLonBox)

        /** [trace] decimated and boxed, or null when it holds nothing to draw. */
        private fun corridorOf(trace: List<LatLon>): Corridor? {
            val points = decimate(trace)
            return FogGeometry.boxOf(points)?.let { Corridor(points, it) }
        }

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
