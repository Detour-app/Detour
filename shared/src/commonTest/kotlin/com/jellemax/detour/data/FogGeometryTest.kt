package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers FogGeometry.kt: the three answers the fog overlay's draw pass needs
 * per frame. #213.
 *
 * The overlay itself is an Android `View` with no test harness in this repo (no
 * Robolectric, no `compose-ui-test`), which is exactly why these three moved out
 * of it: culling a trace that should have been drawn, or placing the cached
 * corridor mask a few metres out, produces a picture that still looks like fog
 * of war. Nobody would catch either by looking, so they are checked here
 * instead — arithmetic against known answers, with no map and no device.
 */
class FogGeometryTest {

    // --- fixtures ---------------------------------------------------------

    private fun box(north: Double, south: Double, east: Double, west: Double) =
        LatLonBox(north = north, south = south, east = east, west = west)

    /** A viewport over the Netherlands, roughly one screen at city zoom. */
    private val amsterdam = box(north = 52.40, south = 52.30, east = 4.95, west = 4.80)

    // --- boxOf ------------------------------------------------------------

    @Test
    fun boxOfEmptyIsNull() {
        assertNull(FogGeometry.boxOf(emptyList()))
    }

    @Test
    fun boxOfSpansEveryPoint() {
        val b = FogGeometry.boxOf(
            listOf(LatLon(52.35, 4.90), LatLon(52.31, 4.99), LatLon(52.39, 4.85)),
        )
        assertNotNull(b)
        assertEquals(52.39, b.north)
        assertEquals(52.31, b.south)
        assertEquals(4.99, b.east)
        assertEquals(4.85, b.west)
    }

    @Test
    fun boxOfOnePointIsThatPoint() {
        val b = FogGeometry.boxOf(listOf(LatLon(52.35, 4.90)))
        assertNotNull(b)
        assertEquals(b.north, b.south)
        assertEquals(b.east, b.west)
    }

    // --- culled -----------------------------------------------------------

    @Test
    fun traceInsideTheViewportIsDrawn() {
        assertFalse(FogGeometry.culled(box(52.36, 52.34, 4.92, 4.88), amsterdam))
    }

    @Test
    fun traceOverlappingOneCornerIsDrawn() {
        assertFalse(FogGeometry.culled(box(52.50, 52.38, 5.10, 4.93), amsterdam))
    }

    @Test
    fun traceEntirelyNorthIsCulled() {
        assertTrue(FogGeometry.culled(box(53.10, 53.00, 4.90, 4.85), amsterdam))
    }

    @Test
    fun traceEntirelyEastIsCulled() {
        assertTrue(FogGeometry.culled(box(52.36, 52.34, 6.90, 6.85), amsterdam))
    }

    /** The case the whole cull exists for: a rider's history in another country. */
    @Test
    fun traceOnAnotherContinentIsCulled() {
        assertTrue(FogGeometry.culled(box(37.80, 37.70, -122.35, -122.50), amsterdam))
    }

    /**
     * MapLibre reports a viewport across the antimeridian as west 170, east 190
     * rather than swapping the two, so a trace at -175 has to be recognised as
     * being inside it. Tested straight, because getting it wrong culls every
     * trace on one side of the seam and the fog closes over roads that were
     * driven.
     */
    @Test
    fun traceEastOfTheAntimeridianIsDrawnInAWrappedViewport() {
        val wrapped = box(north = -36.7, south = -37.1, east = 190.0, west = 170.0)
        assertFalse(FogGeometry.culled(box(-36.8, -36.9, -174.7, -174.8), wrapped))
    }

    @Test
    fun traceWestOfTheAntimeridianIsDrawnInAWrappedViewport() {
        val wrapped = box(north = -36.7, south = -37.1, east = 190.0, west = 170.0)
        assertFalse(FogGeometry.culled(box(-36.8, -36.9, 174.8, 174.7), wrapped))
    }

    @Test
    fun traceAwayFromAWrappedViewportIsStillCulled() {
        val wrapped = box(north = -36.7, south = -37.1, east = 190.0, west = 170.0)
        assertTrue(FogGeometry.culled(box(-36.8, -36.9, 4.95, 4.85), wrapped))
    }

    /** The mirror case: an ordinary viewport must not pull in the far seam. */
    @Test
    fun traceAtTheSeamIsCulledFromAnOrdinaryViewport() {
        assertTrue(FogGeometry.culled(box(52.36, 52.34, 179.9, 179.8), amsterdam))
    }

    // --- transform --------------------------------------------------------

    private val span = FogSegment(100.0, 400.0, 300.0, 400.0)

    @Test
    fun anUnmovedCameraGivesTheIdentity() {
        val t = assertNotNull(FogGeometry.transform(span, span))
        assertEquals(1.0, t.a, 1e-9)
        assertEquals(0.0, t.b, 1e-9)
        assertEquals(0.0, t.c, 1e-9)
        assertEquals(1.0, t.d, 1e-9)
        assertEquals(0.0, t.tx, 1e-9)
        assertEquals(0.0, t.ty, 1e-9)
    }

    @Test
    fun aPannedCameraGivesAPureTranslation() {
        val moved = FogSegment(140.0, 375.0, 340.0, 375.0)
        val t = assertNotNull(FogGeometry.transform(span, moved))
        assertEquals(1.0, t.a, 1e-9)
        assertEquals(0.0, t.b, 1e-9)
        assertEquals(0.0, t.c, 1e-9)
        assertEquals(1.0, t.d, 1e-9)
        assertEquals(40.0, t.tx, 1e-9)
        assertEquals(-25.0, t.ty, 1e-9)
    }

    @Test
    fun aZoomedCameraGivesAUniformScaleAboutTheAnchor() {
        // Same start, twice the span: everything doubles away from (100, 400).
        val zoomed = FogSegment(100.0, 400.0, 500.0, 400.0)
        val t = assertNotNull(FogGeometry.transform(span, zoomed))
        assertEquals(2.0, t.a, 1e-9)
        assertEquals(2.0, t.d, 1e-9)
        assertPoint(100.0, 400.0, t, 100.0, 400.0)
        assertPoint(300.0, 400.0, t, 500.0, 400.0)
        // A point off the reference line scales about the anchor too.
        assertPoint(100.0, 500.0, t, 100.0, 600.0)
    }

    @Test
    fun aRotatedCameraGivesAQuarterTurnAboutTheAnchor() {
        // The span turns from pointing right to pointing down: +90° on screen.
        val turned = FogSegment(100.0, 400.0, 100.0, 600.0)
        val t = assertNotNull(FogGeometry.transform(span, turned))
        assertEquals(0.0, t.a, 1e-9)
        assertEquals(-1.0, t.b, 1e-9)
        assertEquals(1.0, t.c, 1e-9)
        assertEquals(0.0, t.d, 1e-9)
        assertPoint(300.0, 400.0, t, 100.0, 600.0)
        assertPoint(100.0, 300.0, t, 200.0, 400.0)
    }

    @Test
    fun rotationAndZoomTogetherStillLandBothReferencePoints() {
        val both = FogSegment(220.0, 130.0, 70.0, 430.0)
        val t = assertNotNull(FogGeometry.transform(span, both))
        assertPoint(100.0, 400.0, t, 220.0, 130.0)
        assertPoint(300.0, 400.0, t, 70.0, 430.0)
        // Similarity, not a general affine: no shear, one scale on both axes.
        assertEquals(t.a, t.d, 1e-9)
        assertEquals(t.b, -t.c, 1e-9)
    }

    @Test
    fun aCollapsedReferenceSpanHasNoTransform() {
        val collapsed = FogSegment(100.0, 400.0, 100.4, 400.3)
        assertNull(FogGeometry.transform(collapsed, span))
    }

    // --- covers -----------------------------------------------------------

    // A 360x800 buffer under a mask carrying 96 pixels of margin on every side.
    private val bufW = 360.0
    private val bufH = 800.0
    private val margin = 96.0
    private val maskW = bufW + 2 * margin
    private val maskH = bufH + 2 * margin

    private fun placed(dx: Double, dy: Double) =
        FogTransform(1.0, 0.0, 0.0, 1.0, -margin + dx, -margin + dy)

    @Test
    fun aFreshMaskCoversTheBuffer() {
        assertTrue(FogGeometry.covers(placed(0.0, 0.0), maskW, maskH, bufW, bufH))
    }

    @Test
    fun aPanInsideTheMarginStillCovers() {
        assertTrue(FogGeometry.covers(placed(90.0, -90.0), maskW, maskH, bufW, bufH))
    }

    @Test
    fun aPanPastTheMarginDoesNotCover() {
        assertFalse(FogGeometry.covers(placed(100.0, 0.0), maskW, maskH, bufW, bufH))
        assertFalse(FogGeometry.covers(placed(0.0, -100.0), maskW, maskH, bufW, bufH))
    }

    /**
     * Zooming out shrinks the mask against the buffer, so the same mask stops
     * reaching the edges even though the camera never moved. Half scale on a
     * margin of 96 leaves far less than the buffer's own height.
     */
    @Test
    fun zoomingOutStopsTheMaskCovering() {
        val half = FogTransform(0.5, 0.0, 0.0, 0.5, -margin, -margin)
        assertFalse(FogGeometry.covers(half, maskW, maskH, bufW, bufH))
    }

    @Test
    fun zoomingInKeepsCovering() {
        val double = FogTransform(2.0, 0.0, 0.0, 2.0, -margin, -margin)
        assertTrue(FogGeometry.covers(double, maskW, maskH, bufW, bufH))
    }

    /**
     * Heading-up driving rotates the camera continuously, so a rotation the
     * margin can absorb must not force a re-projection — and one it cannot must.
     */
    @Test
    fun aSmallRotationAboutTheMiddleStillCovers() {
        assertTrue(FogGeometry.covers(rotatedAboutBufferCentre(10.0), maskW, maskH, bufW, bufH))
    }

    @Test
    fun aQuarterTurnDoesNotCover() {
        assertFalse(FogGeometry.covers(rotatedAboutBufferCentre(90.0), maskW, maskH, bufW, bufH))
    }

    // --- helpers ----------------------------------------------------------

    /** [t] applied to (x, y), asserted against ([toX], [toY]). */
    private fun assertPoint(x: Double, y: Double, t: FogTransform, toX: Double, toY: Double) {
        assertEquals(toX, t.a * x + t.b * y + t.tx, 1e-9)
        assertEquals(toY, t.c * x + t.d * y + t.ty, 1e-9)
    }

    /**
     * The placement a fresh mask takes on after the camera turns [degrees] about
     * the middle of the buffer, built through [FogGeometry.transform] from where
     * two reference points end up rather than written out by hand.
     */
    private fun rotatedAboutBufferCentre(degrees: Double): FogTransform {
        val cx = bufW / 2 + margin
        val cy = bufH / 2 + margin
        val rad = degrees * PI / 180.0
        val turnCos = cos(rad)
        val turnSin = sin(rad)
        fun turn(x: Double, y: Double): Pair<Double, Double> {
            val dx = x - cx
            val dy = y - cy
            return (cx + dx * turnCos - dy * turnSin - margin) to
                (cy + dx * turnSin + dy * turnCos - margin)
        }
        val a = turn(margin, cy)
        val b = turn(margin + bufW, cy)
        val from = FogSegment(margin, cy, margin + bufW, cy)
        val to = FogSegment(a.first, a.second, b.first, b.second)
        return assertNotNull(FogGeometry.transform(from, to))
    }
}
