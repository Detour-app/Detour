package com.jellemax.detour.data

/**
 * A north/south/east/west box in degrees.
 *
 * [east] may exceed +180 and [west] may fall below -180: that is how MapLibre
 * reports a viewport straddling the antimeridian (west 170, east 190) rather
 * than swapping the two, and [FogGeometry.culled] has to answer against that
 * form. A box built from points by [FogGeometry.boxOf] never does — its
 * longitudes are whatever the points carried.
 */
data class LatLonBox(val north: Double, val south: Double, val east: Double, val west: Double)

/** Two points, used as a reference span whose image under a camera fixes a [FogTransform]. */
data class FogSegment(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

/**
 * A 2x3 affine, row-major: `x' = a*x + b*y + tx`, `y' = c*x + d*y + ty`.
 *
 * Laid out to drop straight into `android.graphics.Matrix.setValues`, which
 * takes exactly these six in this order followed by `0, 0, 1`.
 */
data class FogTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val tx: Double,
    val ty: Double,
)

/**
 * The arithmetic the fog overlay runs per drawn frame, kept pure so it can be
 * checked without a device. #213.
 *
 * The overlay is an Android `View` over the GL surface: it projects the driven
 * corridor into an off-screen mask and, while the camera moves, re-uses that
 * mask through a transform instead of re-projecting every point of the rider's
 * history. Three decisions sit in that loop and none of them is visible when it
 * is wrong — a corridor drawn thirty metres off still looks like a corridor —
 * so they live here with tests rather than inline in the draw pass.
 */
object FogGeometry {

    /** A whole turn of longitude. */
    private const val TURN_DEG = 360.0

    /**
     * The shortest reference span, squared, that still fixes a transform.
     *
     * The two reference points are picked half a buffer apart, so hundreds of
     * pixels; anything at or below a pixel means the projection has collapsed
     * and the only safe answer is to re-project rather than scale a mask by a
     * ratio of two near-zero numbers.
     */
    private const val MIN_SPAN_SQ = 1.0

    /** Bounding box of [points], or null when there is nothing to bound. */
    fun boxOf(points: List<LatLon>): LatLonBox? {
        if (points.isEmpty()) return null
        var north = points[0].lat
        var south = north
        var east = points[0].lon
        var west = east
        for (p in points) {
            if (p.lat > north) north = p.lat
            if (p.lat < south) south = p.lat
            if (p.lon > east) east = p.lon
            if (p.lon < west) west = p.lon
        }
        return LatLonBox(north, south, east, west)
    }

    /**
     * True when [trace] cannot touch [view], so the trace need not be projected
     * at all. [view] is expected to carry the corridor width as padding already.
     *
     * Longitude is tested three times because [view] may be the wrapped form
     * described on [LatLonBox]: a trace at -175 lies inside a view spanning
     * 170..190 once a whole turn is added to it. A trace that itself straddles
     * the antimeridian gets a box spanning nearly the whole world and so is
     * never culled — wasteful, never wrong, and one trace in the rider's set.
     */
    fun culled(trace: LatLonBox, view: LatLonBox): Boolean {
        if (trace.south > view.north || trace.north < view.south) return true
        return !overlapsLon(trace, view)
    }

    private fun overlapsLon(trace: LatLonBox, view: LatLonBox): Boolean =
        spans(trace.west, trace.east, view) ||
            spans(trace.west + TURN_DEG, trace.east + TURN_DEG, view) ||
            spans(trace.west - TURN_DEG, trace.east - TURN_DEG, view)

    private fun spans(west: Double, east: Double, view: LatLonBox): Boolean =
        east >= view.west && west <= view.east

    /**
     * The similarity that carries [from] onto [to], or null when [from] is too
     * short to derive one from.
     *
     * Translation, rotation and one uniform scale — which is the *exact* screen
     * transform between two cameras over the same Mercator projection at zero
     * tilt, whatever the latitude, because a change of centre, zoom or bearing
     * is a translation, scale and rotation of projected space and nothing else.
     * It stops being exact the moment the map is tilted, which is why the caller
     * re-projects instead of transforming when tilt is not zero.
     *
     * Derived as the complex ratio `v/u` of the two spans rather than through
     * `atan2` and a square root: same answer, no trigonometry, and the identity
     * case comes out exactly 1 and 0 instead of within a rounding error of it.
     */
    fun transform(from: FogSegment, to: FogSegment): FogTransform? {
        val ux = from.x1 - from.x0
        val uy = from.y1 - from.y0
        val spanSq = ux * ux + uy * uy
        if (spanSq < MIN_SPAN_SQ) return null
        val vx = to.x1 - to.x0
        val vy = to.y1 - to.y0
        // scale*cos and scale*sin of the rotation, from (vx + i*vy) / (ux + i*uy).
        val cosPart = (ux * vx + uy * vy) / spanSq
        val sinPart = (ux * vy - uy * vx) / spanSq
        return FogTransform(
            a = cosPart,
            b = -sinPart,
            c = sinPart,
            d = cosPart,
            tx = to.x0 - (cosPart * from.x0 - sinPart * from.y0),
            ty = to.y0 - (sinPart * from.x0 + cosPart * from.y0),
        )
    }

    /**
     * True when a mask of [maskW] x [maskH], placed by [t], still covers every
     * pixel of the [bufW] x [bufH] buffer being drawn.
     *
     * False is the signal to re-project: the mask is rendered with a margin
     * around the viewport precisely so the camera can move a little without
     * this going false, and the moment a buffer corner falls outside it the
     * uncovered strip would draw as fog over ground the rider has driven.
     *
     * Answered by mapping the buffer's corners back through the inverse of [t],
     * so the mask's own rotation is handled without a polygon test.
     */
    fun covers(t: FogTransform, maskW: Double, maskH: Double, bufW: Double, bufH: Double): Boolean {
        val det = t.a * t.d - t.b * t.c
        if (det == 0.0) return false
        val ia = t.d / det
        val ib = -t.b / det
        val ic = -t.c / det
        val id = t.a / det
        val itx = -(ia * t.tx + ib * t.ty)
        val ity = -(ic * t.tx + id * t.ty)
        fun outside(x: Double, y: Double): Boolean {
            val mx = ia * x + ib * y + itx
            val my = ic * x + id * y + ity
            return mx !in 0.0..maskW || my !in 0.0..maskH
        }
        val topCovered = !outside(0.0, 0.0) && !outside(bufW, 0.0)
        val bottomCovered = !outside(0.0, bufH) && !outside(bufW, bufH)
        return topCovered && bottomCovered
    }
}
