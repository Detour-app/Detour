package com.jellemax.detour.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.jellemax.detour.R
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NamedMemberFix
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.drive.FriendPosition
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
private const val SRC_CAMERAS_REDLIGHT = "mr-cameras-redlight"
private const val SRC_FRIENDS = "mr-friends"
private const val SRC_CIRCLE_MEMBERS = "mr-circle-members"
private const val IMG_DEST = "mr-img-dest"
private const val IMG_POSITION = "mr-img-position"
private const val IMG_CAMERA = "mr-img-camera"
private const val IMG_CAMERA_REDLIGHT = "mr-img-camera-redlight"
private const val IMG_FRIEND = "mr-img-friend"
private const val IMG_CIRCLE_MEMBER = "mr-img-circle-member"
private const val LAYER_ROUTE = "mr-route-line"
private const val LAYER_ROUTE_DRIVEN = "mr-route-driven-line"
private const val LAYER_ROUTE_TAIL = "mr-route-tail-line"
const val LAYER_CANDIDATES = "mr-candidates-dot"
// Queried by MapScreen's map-tap handler to resolve a tap back to a rider
// (issue #156); each feature carries a "rider" id property for that lookup.
const val LAYER_FRIENDS = "mr-friends"
const val LAYER_CIRCLE_MEMBERS = "mr-circle-members"
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
// The camera marker is a detailed glyph on a 48-unit grid; rasterise it at this
// multiple and scale back with iconSize so it doesn't come out soft. Same
// reasoning as POSITION_ICON_SCALE, minus the rotation — a static marker at 1:1
// is already close, but the lens rings smear without it.
private const val CAMERA_ICON_SCALE = 2
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
            // Rasterised at 2x and scaled back by iconSize on the layer, so the
            // detailed glyph stays crisp at marker size — same trick as the
            // own-position icon (see POSITION_ICON_SCALE).
            style.addImage(IMG_CAMERA, it.toBitmap(
                it.intrinsicWidth * CAMERA_ICON_SCALE,
                it.intrinsicHeight * CAMERA_ICON_SCALE))
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_camera_redlight)?.let {
            style.addImage(IMG_CAMERA_REDLIGHT, it.toBitmap(
                it.intrinsicWidth * CAMERA_ICON_SCALE,
                it.intrinsicHeight * CAMERA_ICON_SCALE))
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_friend)?.let {
            style.addImage(IMG_FRIEND, it.toBitmap())
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_circle_member)?.let {
            style.addImage(IMG_CIRCLE_MEMBER, it.toBitmap())
        }
        listOf(SRC_REACH, SRC_WEDGE, SRC_ROUTE, SRC_ROUTE_DRIVEN, SRC_ROUTE_TAIL,
            SRC_CANDIDATES, SRC_DEST, SRC_POSITION, SRC_CAMERAS, SRC_CAMERAS_REDLIGHT,
            SRC_FRIENDS, SRC_CIRCLE_MEMBERS)
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
        style.addLayer(SymbolLayer(LAYER_FRIENDS, SRC_FRIENDS).withProperties(
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
        style.addLayer(SymbolLayer(LAYER_CIRCLE_MEMBERS, SRC_CIRCLE_MEMBERS).withProperties(
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
            PropertyFactory.iconSize(1f / CAMERA_ICON_SCALE),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)
        ).also { it.setMinZoom(SPEED_CAMERA_MIN_ZOOM) })
        // Red-light (and combined) cameras: same feed, a distinct icon so a
        // rider can tell "measures speed" from "photographs the signal" before
        // CameraWarner ever has to say so out loud.
        style.addLayer(SymbolLayer("mr-cameras-redlight", SRC_CAMERAS_REDLIGHT).withProperties(
            PropertyFactory.iconImage(IMG_CAMERA_REDLIGHT),
            PropertyFactory.iconSize(1f / CAMERA_ICON_SCALE),
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
        val (redLight, plain) = cameras.partition { it.kind != SpeedCameras.CameraKind.SPEED }
        setData(SRC_CAMERAS, FeatureCollection.fromFeatures(
            plain.map { Feature.fromGeometry(Point.fromLngLat(it.at.lon, it.at.lat)) }))
        setData(SRC_CAMERAS_REDLIGHT, FeatureCollection.fromFeatures(
            redLight.map { Feature.fromGeometry(Point.fromLngLat(it.at.lon, it.at.lat)) }))
    }

    /** Replace the convoy friend markers. Fed on its own cadence by
     *  [ConvoyLiveClient]'s peer flow, not [render] — same reasoning as
     *  [setCameras]: this refreshes on a completely different rhythm than the
     *  spin/route state [render] pushes. */
    fun setFriends(friends: Collection<NamedFriendPosition>) {
        setData(SRC_FRIENDS, FeatureCollection.fromFeatures(
            friends.map { f ->
                Feature.fromGeometry(Point.fromLngLat(f.fix.lon, f.fix.lat)).apply {
                    addStringProperty("rider", f.fix.riderId.value)
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
                    addStringProperty("rider", f.fix.riderId.value)
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
