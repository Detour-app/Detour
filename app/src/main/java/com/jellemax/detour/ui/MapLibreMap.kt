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
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.jellemax.detour.R
import com.jellemax.detour.data.FogGeometry
import com.jellemax.detour.data.FogSegment
import com.jellemax.detour.data.FogTransform
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.LatLonBox
import com.jellemax.detour.data.NamedMemberFix
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.Perf
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.drive.FriendPosition
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Projection
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
private const val SRC_ROUTE_DRIVEN = "mr-route-driven"
private const val SRC_ROUTE_TAIL = "mr-route-tail"
private const val SRC_CANDIDATES = "mr-candidates"
private const val SRC_DEST = "mr-dest"
private const val SRC_POSITION = "mr-position"
private const val SRC_CAMERAS = "mr-cameras"
private const val SRC_FRIENDS = "mr-friends"
private const val SRC_CIRCLE_MEMBERS = "mr-circle-members"
private const val IMG_DEST = "mr-img-dest"
private const val IMG_POSITION = "mr-img-position"
private const val IMG_CAMERA = "mr-img-camera"
private const val IMG_FRIEND = "mr-img-friend"
private const val IMG_CIRCLE_MEMBER = "mr-img-circle-member"
private const val LAYER_ROUTE = "mr-route-line"
private const val LAYER_ROUTE_DRIVEN = "mr-route-driven-line"
private const val LAYER_ROUTE_TAIL = "mr-route-tail-line"
const val LAYER_CANDIDATES = "mr-candidates-dot"
// Every symbol layer that carries a text label must name this font stack.
// MapLibre's spec default is ["Open Sans Regular", "Arial Unicode MS Regular"]
// and OpenFreeMap serves neither - both 404 on its glyph endpoint, and it is
// the only font server we use for both the light and dark basemaps. A symbol
// layer whose glyphs never load is not merely unlabelled: text and icon are
// laid out in one bucket, so the *whole* layer, markers included, silently
// disappears. That is what hid every convoy peer and circle member on the map
// while the label-free layers (position, destination, cameras) drew fine.
private val GLYPH_FONT = arrayOf("Noto Sans Regular")
// The own-position marker is rasterised at this multiple of its intrinsic size
// and scaled back down by iconSize. A vector drawn 1:1 into a bitmap is only
// sharp while the map holds still; the marker is the one icon that spends its
// life being rotated and zoomed under the camera, and at 1:1 the resampling is
// exactly what you see. Two is enough — four quadruples the texture for no
// visible gain.
private const val POSITION_ICON_SCALE = 2
// Below city zoom the speed-camera icons pile up into an unreadable blob, and
// at loop-planning zoom they're just noise — hide them until zoomed past this.
private const val SPEED_CAMERA_MIN_ZOOM = 11f
// Recutting the route into its driven and undriven halves costs two GeoJSON
// pushes the size of the route, so the cut advances in steps rather than on
// every frame: a phone at a red light pushes nothing at all, and at speed this
// lands at roughly the GPS's own once a second. What glides between two cuts is
// the tail (see [MapOverlays.setDrivenFraction]), which is two points.
private const val DRIVEN_STEP_METERS = 12.0
// Below this there is nothing worth drawing: a stub of driven line at the very
// start of a route reads as a rendering glitch, not as progress.
private const val DRIVEN_MIN_METERS = 20.0

/**
 * A convoy peer's live position plus the handle to draw beside it — the
 * convoy-side counterpart of [NamedMemberFix], but built here rather than in
 * shared/: nothing server-side joins a peer's position to its name the way
 * [CircleFixes.othersFixes] does for a circle, because a convoy position
 * rides the live relay socket and its membership is a separate, slower HTTP
 * list — there is no one call that already has both. [MapScreen] is what
 * holds a peer's fix and current convoy membership at once to build this,
 * resolving unnamed ids as [ConvoysStore.watchPeers] fills them in.
 */
data class NamedFriendPosition(val fix: FriendPosition, val username: String)

/**
 * Owns the runtime sources and layers drawn on top of the basemap: the reach
 * circle and direction wedge, the route line, the spin candidates, and the
 * destination + own-position markers. Created once per [Style]; [render] only
 * pushes new GeoJSON, so overlays update without rebuilding the map.
 */
class MapOverlays(
    private val style: Style,
    context: Context,
    private val darkTheme: Boolean,
) {

    // Application context: the icons are plain vectors with literal colours, and
    // this outlives the Activity by however long the Style does.
    private val context = context.applicationContext

    init {
        ContextCompat.getDrawable(context, R.drawable.ic_map_pin)?.let {
            style.addImage(IMG_DEST, it.toBitmap())
        }
        setPositionIcon(Settings.mapIcon.value)
        ContextCompat.getDrawable(context, R.drawable.ic_map_camera)?.let {
            style.addImage(IMG_CAMERA, it.toBitmap())
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_friend)?.let {
            style.addImage(IMG_FRIEND, it.toBitmap())
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_circle_member)?.let {
            style.addImage(IMG_CIRCLE_MEMBER, it.toBitmap())
        }
        listOf(SRC_REACH, SRC_WEDGE, SRC_ROUTE, SRC_ROUTE_DRIVEN, SRC_ROUTE_TAIL,
            SRC_CANDIDATES, SRC_DEST, SRC_POSITION, SRC_CAMERAS, SRC_FRIENDS,
            SRC_CIRCLE_MEMBERS)
            .forEach { style.addSource(GeoJsonSource(it)) }

        // Whatever the user picked in Settings > Route line; the default,
        // THEME, is the app accent — amber on the dark basemap, blue on the
        // light one — so navigation matches the chrome instead of always being
        // amber. Sampled for the layers this style starts with; [setRouteColor]
        // carries a later change onto them, the same way [setPositionIcon]
        // does for the marker.
        val routeColor = Settings.routeColor.value

        // Bottom-to-top: fills, then the route (dark casing under both halves
        // of the coloured line, then the halves, then the tail that carries the
        // seam), then markers, with the tappable candidates on top.
        style.addLayer(FillLayer("mr-reach-fill", SRC_REACH).withProperties(
            PropertyFactory.fillColor("#2196F3"), PropertyFactory.fillOpacity(0.09f)))
        style.addLayer(LineLayer("mr-reach-line", SRC_REACH).withProperties(
            PropertyFactory.lineColor("#2196F3"), PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.7f)))
        style.addLayer(FillLayer("mr-wedge-fill", SRC_WEDGE).withProperties(
            PropertyFactory.fillColor("#FF9800"), PropertyFactory.fillOpacity(0.11f)))
        // A casing per half, because the two halves are two geometries: while
        // navigating SRC_ROUTE holds only the road ahead, and the road behind
        // would otherwise lose the dark outline that keeps the line legible
        // over a busy basemap.
        listOf(SRC_ROUTE to "mr-route-casing", SRC_ROUTE_DRIVEN to "mr-route-driven-casing")
            .forEach { (source, id) ->
                style.addLayer(LineLayer(id, source).withProperties(
                    PropertyFactory.lineColor("#0B1220"), PropertyFactory.lineWidth(11f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
            }
        // The road behind, in the dimmed colour. A geometry of its own rather
        // than a copy of the route's first N metres laid over all of it, so a
        // route that rides the same tarmac twice is dimmed only where the rider
        // has actually been.
        style.addLayer(LineLayer(LAYER_ROUTE_DRIVEN, SRC_ROUTE_DRIVEN).withProperties(
            PropertyFactory.lineColor(RouteColors.drivenHex(routeColor, darkTheme)),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        // The road ahead, over the dimmed half and butt-capped where they meet.
        // The two are disjoint, so this only ever covers the other where the
        // route rides its own tarmac twice — and there bright is the honest
        // answer, because that stretch is still to come. Butt because the
        // dimmed half's round cap hangs 3.5 px past the cut, and this is what
        // covers it: the half-disc over the live line is #208's third symptom.
        // Holds the whole route until [setDrivenFraction] cuts it, so a route
        // that is merely drawn — a spin result, a saved trip — is bright end to
        // end; the casings below keep the route's outer tips rounded.
        style.addLayer(LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(RouteColors.hex(routeColor, darkTheme)),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        // The seam itself: the few metres between the last cut and where the
        // rider is right now, dimmed over the road ahead. Butt for the same
        // reason, at the end that matters — a round cap here would put a
        // half-disc of dim past the marker on every frame.
        style.addLayer(LineLayer(LAYER_ROUTE_TAIL, SRC_ROUTE_TAIL).withProperties(
            PropertyFactory.lineColor(RouteColors.drivenHex(routeColor, darkTheme)),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        // Rotated to the last known heading, aligned to the map rather than the
        // viewport: heading-up already turns the camera, so a vehicle icon
        // rotated the same amount ends up pointing up the screen — and stays
        // pointing the right way when the map is north-up instead.
        style.addLayer(SymbolLayer("mr-position", SRC_POSITION).withProperties(
            PropertyFactory.iconImage(IMG_POSITION),
            PropertyFactory.iconSize(1f / POSITION_ICON_SCALE),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        style.addLayer(SymbolLayer("mr-dest", SRC_DEST).withProperties(
            PropertyFactory.iconImage(IMG_DEST), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        // Convoy friends: heading arrow rotated per-feature, username labelled
        // underneath so several friends on screen stay distinguishable.
        style.addLayer(SymbolLayer("mr-friends", SRC_FRIENDS).withProperties(
            PropertyFactory.iconImage(IMG_FRIEND),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(GLYPH_FONT),
            PropertyFactory.textSize(11f), PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
            PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textHaloColor("#000000"),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true)))
        // Circle members: no heading (a circle fix carries no bearing, and
        // even if it did, minutes-old speed/direction isn't worth showing as
        // if it were current) - just a static dot with a "name · age" label,
        // so this reads as "last seen", not "live", next to the convoy arrow.
        style.addLayer(SymbolLayer("mr-circle-members", SRC_CIRCLE_MEMBERS).withProperties(
            PropertyFactory.iconImage(IMG_CIRCLE_MEMBER),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(GLYPH_FONT),
            PropertyFactory.textSize(11f), PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
            PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textHaloColor("#000000"),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true)))
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

        // Issue #174: OpenFreeMap's dark style draws its one-way arrow sprite
        // pointing up (its y-axis), but icon-rotation-alignment:map on a
        // line-placed symbol aligns the icon's x-axis to the line — a fixed
        // 90° mismatch on every street, regardless of that street's own
        // bearing. Not ours to fix at the source (hosted style/sprite), so
        // correct it here with the extra quarter turn once the style has
        // loaded these two layers. Dark theme only: positron doesn't define
        // them at all.
        style.getLayerAs<SymbolLayer>("road_oneway")
            ?.setProperties(PropertyFactory.iconRotate(90f))
        style.getLayerAs<SymbolLayer>("road_oneway_opposite")
            ?.setProperties(PropertyFactory.iconRotate(270f))
    }

    // A theme flip loads a new Style asynchronously (RetainedMap.rememberRetainedMap
    // calls MapLibreMap.setStyle on darkTheme change). MapLibre tears this instance's
    // Style down the moment the new one starts loading - every further
    // style.getSource/getLayer/addImage call throws IllegalStateException from then
    // on, well before the callback that hands MapScreen a fresh MapOverlays over the
    // new Style. Every public mutator below is called from MapScreen's effects and
    // can be reached during that window, so they all route through this one check
    // rather than each catching the throw. A skipped update here is harmless: the
    // replacement MapOverlays redraws current state as soon as it exists.
    private val styleUsable: Boolean
        get() = style.isFullyLoaded

    private fun setData(sourceId: String, fc: FeatureCollection) {
        // render() drives this once per source per GPS fix, which is why the
        // whole class routes through one boolean rather than catching per call.
        if (!styleUsable) return
        (style.getSource(sourceId) as? GeoJsonSource)?.setGeoJson(fc)
    }

    private fun empty() = FeatureCollection.fromFeatures(emptyList())

    /** [points] as a one-feature LineString collection, or nothing when there
     *  are too few of them to be a line. */
    private fun lineFeature(points: List<LatLon>): FeatureCollection =
        if (points.size >= 2) FeatureCollection.fromFeature(Feature.fromGeometry(
            LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })))
        else empty()

    /** Recolour all three route layers. Cheap enough to call on every change of
     *  the setting — three paint properties, no source or layer rebuild — for
     *  the same reason [setPositionIcon] is. */
    fun setRouteColor(color: Settings.RouteColor) {
        if (!styleUsable) return
        (style.getLayer(LAYER_ROUTE) as? LineLayer)?.setProperties(
            PropertyFactory.lineColor(RouteColors.hex(color, darkTheme)))
        val driven = PropertyFactory.lineColor(RouteColors.drivenHex(color, darkTheme))
        (style.getLayer(LAYER_ROUTE_DRIVEN) as? LineLayer)?.setProperties(driven)
        (style.getLayer(LAYER_ROUTE_TAIL) as? LineLayer)?.setProperties(driven)
    }

    // The route as last pushed, its length, how far along it the last cut was
    // made (NaN = uncut, the whole route is drawn ahead), where that cut landed,
    // and where the tail currently ends.
    private var routeLine: List<LatLon>? = null
    private var routeMeters = 0.0
    private var drawnDrivenMeters = Double.NaN
    private var cutAt: LatLon? = null
    private var drawnTailAt: LatLon? = null

    /**
     * How much of the drawn route is already behind you (0..1, or null when not
     * navigating), and optionally where exactly you are on it ([tailAt], the
     * position snapped onto the route).
     *
     * The route is drawn as two **disjoint** geometries cut at [fraction] —
     * behind in the dimmed colour, ahead in the bright one — rather than as a
     * dimmed copy of the first N metres laid over the whole of it. Nothing
     * overlaps, so a round trip, an out-and-back or a self-crossing is dimmed
     * only where the rider has actually been, and there is no cap hanging past
     * the seam.
     *
     * Recutting costs two GeoJSON pushes the size of the route, so it happens
     * on [DRIVEN_STEP_METERS] of travel. What moves in between is [tailAt]: a
     * two-point segment from the last cut to the rider, in the dimmed colour,
     * over the stretch of the ahead line they have just covered. That is small
     * enough to push on every displayed frame, which is what keeps the seam
     * under a marker that glides. Callers with only a per-fix cadence — the car
     * screen — leave it null and get the cut on its own.
     */
    fun setDrivenFraction(fraction: Double?, tailAt: LatLon? = null) {
        val line = routeLine
        // No fraction, no route, or not far enough along it to draw: whatever
        // was there comes off. Once, not on every fix that clears nothing.
        val meters = fraction?.times(routeMeters) ?: -1.0
        if (line == null || meters < DRIVEN_MIN_METERS) {
            if (!drawnDrivenMeters.isNaN()) {
                drawnDrivenMeters = Double.NaN
                pushRouteHalves()
            }
            return
        }
        if (drawnDrivenMeters.isNaN() ||
            abs(meters - drawnDrivenMeters) >= DRIVEN_STEP_METERS) {
            drawnDrivenMeters = meters
            pushRouteHalves()
        }
        pushTail(tailAt)
    }

    /** Cut [routeLine] at [drawnDrivenMeters] and push both halves, or push it
     *  whole when nothing has been driven along it yet. */
    private fun pushRouteHalves() {
        val line = routeLine
        // The tail belongs to the cut it grew from; a new cut starts it over.
        drawnTailAt = null
        setData(SRC_ROUTE_TAIL, empty())
        if (line == null || drawnDrivenMeters.isNaN() || routeMeters <= 0.0) {
            cutAt = null
            setData(SRC_ROUTE, lineFeature(line.orEmpty()))
            setData(SRC_ROUTE_DRIVEN, empty())
            return
        }
        val cut = NavEngine.cut(line, drawnDrivenMeters / routeMeters)
        cutAt = cut.behind.lastOrNull()
        setData(SRC_ROUTE_DRIVEN, lineFeature(cut.behind))
        setData(SRC_ROUTE, lineFeature(cut.ahead))
    }

    /** Dim the stretch of the ahead line between the last cut and [at]. Skipped
     *  when it would redraw what is already there, which is what makes this
     *  affordable to call once per frame.
     *
     *  Two points, so it takes the chord where the route bends: under
     *  [DRIVEN_STEP_METERS] that is a couple of metres of daylight at a sharp
     *  corner, and it is gone at the next cut. Walking the vertices between the
     *  two would close it, at the cost of carrying the cut's segment index. */
    private fun pushTail(at: LatLon?) {
        if (at == drawnTailAt) return
        drawnTailAt = at
        val from = cutAt
        setData(SRC_ROUTE_TAIL,
            if (at != null && from != null) lineFeature(listOf(from, at)) else empty())
    }

    /** Replace the speed-camera markers. Fed by the prefetch loop, not [render],
     *  because cameras refresh only as you near the edge of the fetched area. */
    fun setCameras(cameras: List<SpeedCameras.Camera>) {
        setData(SRC_CAMERAS, FeatureCollection.fromFeatures(
            cameras.map { Feature.fromGeometry(Point.fromLngLat(it.at.lon, it.at.lat)) }))
    }

    /** Replace the convoy friend markers. Fed on its own cadence by
     *  [ConvoyLiveClient]'s peer flow, not [render] — same reasoning as
     *  [setCameras]: this refreshes on a completely different rhythm than the
     *  spin/route state [render] pushes. */
    fun setFriends(friends: Collection<NamedFriendPosition>) {
        setData(SRC_FRIENDS, FeatureCollection.fromFeatures(
            friends.map { f ->
                Feature.fromGeometry(Point.fromLngLat(f.fix.lon, f.fix.lat)).apply {
                    addStringProperty("name", f.username)
                    addNumberProperty("bearing", f.fix.headingDeg ?: 0.0)
                }
            }))
    }

    /** Replace the circle-member markers for whichever circle is currently
     *  being viewed (see [CircleMapState] in CirclesScreen.kt). Fed by
     *  MapScreen's own polling loop, on [CircleFixes]'s minute cadence -
     *  same reasoning as [setFriends] and [setCameras], just far slower. Age
     *  is computed here rather than stored on [MemberFix] so a marker's label
     *  is honest about "how old is this" even between polls, not just at the
     *  instant the fix arrived. */
    fun setCircleMembers(fixes: Collection<NamedMemberFix>) {
        val now = System.currentTimeMillis()
        setData(SRC_CIRCLE_MEMBERS, FeatureCollection.fromFeatures(
            fixes.map { f ->
                Feature.fromGeometry(Point.fromLngLat(f.fix.lon, f.fix.lat)).apply {
                    val ageMin = ((now - f.fix.tsMs).coerceAtLeast(0) / 60_000L)
                    val ageLabel = if (ageMin < 1) "just now" else "${ageMin}m ago"
                    addStringProperty("label", "${f.username} · $ageLabel")
                }
            }))
    }

    /** Swap the artwork under the own-position marker. Cheap enough to call on
     *  every change of the setting: one bitmap, replacing the image the layer
     *  already points at, with no layer or source rebuild. */
    fun setPositionIcon(icon: Settings.MapIcon) {
        val drawable = ContextCompat.getDrawable(context, mapIconDrawable(icon)) ?: return
        if (!styleUsable) return
        style.addImage(IMG_POSITION, drawable.toBitmap(
            drawable.intrinsicWidth * POSITION_ICON_SCALE,
            drawable.intrinsicHeight * POSITION_ICON_SCALE))
    }

    // A GPS bearing goes null the moment you stop, and a car icon that snaps
    // north at every red light is worse than one pointing a few degrees stale.
    private var lastPositionBearing = 0.0

    /** Move just the own-position marker. [render] also sets it, but a following
     *  map only needs *this* once a second — and rewriting the route line's
     *  GeoJSON at that rate to move one point is what makes a car head unit
     *  crawl (see [com.jellemax.detour.car.CarMapRenderer]). */
    fun setPosition(at: LatLon?, bearingDeg: Double? = null) {
        bearingDeg?.let { lastPositionBearing = it }
        setData(SRC_POSITION, if (at != null)
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)).apply {
                    addNumberProperty("bearing", lastPositionBearing)
                })
        else FeatureCollection.fromFeatures(emptyList()))
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
        positionMarker: PositionMarker,
        positionBearingDeg: Double? = null,
    ) {
        setData(SRC_REACH, if (myLocation != null && reachMeters != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(circle(myLocation, reachMeters)))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_WEDGE, if (myLocation != null && reachMeters != null && directionDeg != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(wedge(myLocation, reachMeters, directionDeg)))
        else FeatureCollection.fromFeatures(emptyList()))

        // A different line means progress along the old one is meaningless —
        // that is a reroute, or a new destination. Compared by identity on
        // purpose: this runs on every fix on the phone map, and re-measuring an
        // unchanged route (or worse, re-pushing both halves and clearing the
        // driven one under it) once a second is the bug this guard exists to
        // prevent. The push lives inside the guard for the same reason: an
        // unconditional one would put the whole route back into SRC_ROUTE on
        // every fix, undoing the cut [setDrivenFraction] just made.
        if (routePolyline !== routeLine) {
            routeLine = routePolyline
            routeMeters = routePolyline?.let { NavEngine.lengthMeters(it) } ?: 0.0
            drawnDrivenMeters = Double.NaN
            pushRouteHalves()
        }

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

        when (positionMarker) {
            PositionMarker.Draw -> setPosition(myLocation, positionBearingDeg)
            PositionMarker.Hide -> setPosition(null, positionBearingDeg)
            PositionMarker.CallerDraws -> Unit
        }
    }
}

/** A spin candidate rendered as a colored map dot. */
data class CandidatePin(val at: LatLon, val colorArgb: Int)

/**
 * What [MapOverlays.render] should do with the own-position marker.
 *
 * [CallerDraws] exists because "don't show it" and "don't touch it" are different
 * instructions, and conflating them cost a visible bug: MapScreen interpolates the
 * marker per frame and its render is keyed on the fix, so asking render to hide the
 * dot cleared the source once a second and let the next frame draw it again — which
 * reads as the marker flickering rather than as it being hidden.
 */
enum class PositionMarker {
    /** Draw it at the location passed to [MapOverlays.render]. */
    Draw,

    /** Clear it. For a screen with no live position at all, such as a route editor. */
    Hide,

    /** Leave the source untouched — the caller writes [MapOverlays.setPosition] itself. */
    CallerDraws,
}

/** Artwork for an own-position marker. Every vehicle is drawn nose-up, so the
 *  layer's heading rotation works out the same for all of them. */
@DrawableRes
fun mapIconDrawable(icon: Settings.MapIcon): Int = when (icon) {
    Settings.MapIcon.DOT -> R.drawable.ic_map_dot
    Settings.MapIcon.FRONTERA -> R.drawable.ic_vehicle_frontera
    Settings.MapIcon.SUV -> R.drawable.ic_vehicle_suv
    Settings.MapIcon.SEDAN -> R.drawable.ic_vehicle_sedan
    Settings.MapIcon.RACECAR -> R.drawable.ic_vehicle_racecar
    Settings.MapIcon.MOTORCYCLE -> R.drawable.ic_vehicle_motorcycle
    Settings.MapIcon.PICKUP -> R.drawable.ic_vehicle_pickup
}

fun mapIconLabel(icon: Settings.MapIcon): String = when (icon) {
    Settings.MapIcon.DOT -> "Blue dot"
    Settings.MapIcon.FRONTERA -> "Frontera"
    Settings.MapIcon.SUV -> "SUV"
    Settings.MapIcon.SEDAN -> "Saloon"
    Settings.MapIcon.RACECAR -> "Race car"
    Settings.MapIcon.MOTORCYCLE -> "Motorcycle"
    Settings.MapIcon.PICKUP -> "Pickup"
}

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
