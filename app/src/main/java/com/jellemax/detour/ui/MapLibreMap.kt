package com.jellemax.detour.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.MemberFix
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.net.FriendPosition
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
private const val SRC_ROUTE_DRIVEN = "mr-route-driven"
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
// Redrawing the driven part of the route costs a GeoJSON push the size of that
// part, so it advances in steps rather than on every fix: a phone at a red
// light pushes nothing at all, and at speed this lands at roughly the GPS's own
// once a second. Twelve metres is under a car length at map scale — the line
// still creeps forward smoothly.
private const val DRIVEN_STEP_METERS = 12.0
// Below this there is nothing worth drawing: a stub of driven line at the very
// start of a route reads as a rendering glitch, not as progress.
private const val DRIVEN_MIN_METERS = 20.0

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
        listOf(SRC_REACH, SRC_WEDGE, SRC_ROUTE, SRC_ROUTE_DRIVEN, SRC_CANDIDATES, SRC_DEST,
            SRC_POSITION, SRC_CAMERAS, SRC_FRIENDS, SRC_CIRCLE_MEMBERS)
            .forEach { style.addSource(GeoJsonSource(it)) }

        // Whatever the user picked in Settings > Route line; the default,
        // THEME, is the app accent — amber on the dark basemap, blue on the
        // light one — so navigation matches the chrome instead of always being
        // amber. Sampled for the layers this style starts with; [setRouteColor]
        // carries a later change onto them, the same way [setPositionIcon]
        // does for the marker.
        val routeColor = Settings.routeColor.value

        // Bottom-to-top: fills, then the route (dark casing under the colored
        // line, and the driven part over it), then markers, with the tappable
        // candidates on top.
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
        style.addLayer(LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(RouteColors.hex(routeColor, darkTheme)),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        // The part already driven, laid over the live line in the dimmed
        // colour so the road ahead is the bright one. On top rather than
        // underneath, and opaque rather than translucent: the line it has to
        // hide is the one immediately below it (see [RouteColors.drivenHex]).
        // Empty until [setDrivenFraction] says otherwise, so a route that is
        // merely drawn — a spin result, a saved trip — is bright end to end.
        style.addLayer(LineLayer(LAYER_ROUTE_DRIVEN, SRC_ROUTE_DRIVEN).withProperties(
            PropertyFactory.lineColor(RouteColors.drivenHex(routeColor, darkTheme)),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
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
    }

    private fun setData(sourceId: String, fc: FeatureCollection) {
        (style.getSource(sourceId) as? GeoJsonSource)?.setGeoJson(fc)
    }

    private fun empty() = FeatureCollection.fromFeatures(emptyList())

    /** Recolour both route layers. Cheap enough to call on every change of the
     *  setting — two paint properties, no source or layer rebuild — for the
     *  same reason [setPositionIcon] is. */
    fun setRouteColor(color: Settings.RouteColor) {
        // Guarded like [setPositionIcon]: a theme flip can leave this Style
        // behind mid-load, and a style call thrown from a flow collector ends
        // the process rather than skipping a frame.
        runCatching {
            (style.getLayer(LAYER_ROUTE) as? LineLayer)?.setProperties(
                PropertyFactory.lineColor(RouteColors.hex(color, darkTheme)))
            (style.getLayer(LAYER_ROUTE_DRIVEN) as? LineLayer)?.setProperties(
                PropertyFactory.lineColor(RouteColors.drivenHex(color, darkTheme)))
        }
    }

    // The route as last pushed, its length, and how far along it the driven
    // overlay currently reaches (NaN = nothing drawn). Kept so progress can be
    // given as a fraction — the caller measures the same polyline with
    // NavEngine's arithmetic, and ratios agree where absolute metres need not.
    private var routeLine: List<LatLon>? = null
    private var routeMeters = 0.0
    private var drawnDrivenMeters = Double.NaN

    /**
     * How much of the drawn route is already behind you (0..1, or null when not
     * navigating): that much of it is redrawn in the dimmed colour, so the road
     * ahead is the one that stands out.
     *
     * Throttled to [DRIVEN_STEP_METERS] of travel. Rewriting the driven part
     * costs a GeoJSON push proportional to its length, and this is called once
     * per GPS fix from both the phone map and the car screen — where a
     * route-sized push per fix is exactly what [setPosition] exists to avoid.
     */
    fun setDrivenFraction(fraction: Double?) {
        val line = routeLine
        // No fraction, no route, or not far enough along it to draw: whatever
        // was there comes off. Once, not on every fix that clears nothing.
        val meters = fraction?.times(routeMeters) ?: -1.0
        if (line == null || meters < DRIVEN_MIN_METERS) {
            if (!drawnDrivenMeters.isNaN()) {
                drawnDrivenMeters = Double.NaN
                setData(SRC_ROUTE_DRIVEN, empty())
            }
            return
        }
        if (!drawnDrivenMeters.isNaN() && abs(meters - drawnDrivenMeters) < DRIVEN_STEP_METERS) return
        drawnDrivenMeters = meters
        val driven = NavEngine.prefix(line, meters / routeMeters)
        setData(SRC_ROUTE_DRIVEN, if (driven.size >= 2)
            FeatureCollection.fromFeature(Feature.fromGeometry(
                LineString.fromLngLats(driven.map { Point.fromLngLat(it.lon, it.lat) })))
        else empty())
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
    fun setFriends(friends: Collection<FriendPosition>) {
        setData(SRC_FRIENDS, FeatureCollection.fromFeatures(
            friends.map { f ->
                Feature.fromGeometry(Point.fromLngLat(f.lon, f.lat)).apply {
                    addStringProperty("name", f.username)
                    addNumberProperty("bearing", f.headingDeg ?: 0.0)
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
    fun setCircleMembers(fixes: Collection<MemberFix>) {
        val now = System.currentTimeMillis()
        setData(SRC_CIRCLE_MEMBERS, FeatureCollection.fromFeatures(
            fixes.map { f ->
                Feature.fromGeometry(Point.fromLngLat(f.lon, f.lat)).apply {
                    val ageMin = ((now - f.tsMs).coerceAtLeast(0) / 60_000L)
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
        // Guarded for the same reason the car renderer wraps its overlay calls:
        // a style call thrown from a flow collector doesn't skip a frame, it
        // ends the process — and a theme flip leaves this Style behind mid-load.
        runCatching {
            style.addImage(IMG_POSITION, drawable.toBitmap(
                drawable.intrinsicWidth * POSITION_ICON_SCALE,
                drawable.intrinsicHeight * POSITION_ICON_SCALE))
        }
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
        showPosition: Boolean,
        positionBearingDeg: Double? = null,
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

        // A different line means progress along the old one is meaningless —
        // that is a reroute, or a new destination. Compared by identity on
        // purpose: this runs on every fix on the phone map, and re-measuring an
        // unchanged route (or worse, clearing the driven part under it) once a
        // second is the bug this guard exists to prevent.
        if (routePolyline !== routeLine) {
            routeLine = routePolyline
            routeMeters = routePolyline?.let { NavEngine.lengthMeters(it) } ?: 0.0
            drawnDrivenMeters = Double.NaN
            setData(SRC_ROUTE_DRIVEN, empty())
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

        setPosition(myLocation.takeIf { showPosition }, positionBearingDeg)
    }
}

/** A spin candidate rendered as a colored map dot. */
data class CandidatePin(val at: LatLon, val colorArgb: Int)

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
    // The in-progress trace, kept out of [traces] because it grows with every
    // GPS fix — folding it in re-decimated the whole stored set once a second.
    // This one small list is decimated alone instead.
    var liveTrace: List<LatLon> = emptyList()
        set(value) { field = decimate(value) }
    var currentLocation: LatLon? = null
    // Everyone else the map is drawing: circle members and convoy peers. The
    // scrim sits over the GL surface, so a marker on ground you have never
    // driven is simply invisible under it — and a circle exists precisely to
    // show someone standing somewhere you haven't been. Cleared like the
    // corridor is, so the person is visible without lifting the fog anywhere
    // they aren't.
    var peers: List<LatLon> = emptyList()
    var corridorMeters: Float = 200f
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
        val theme = fogTheme
        buf.eraseColor(Color.argb(theme.scrimAlpha, theme.r, theme.g, theme.b))
        if (frost != null) {
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
        for (trace in traces + listOf(liveTrace)) {
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
        for (loc in listOfNotNull(currentLocation) + peers) {
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
        // Screen-space feather for the corridor edges via RenderEffect (GPU).
        private const val FEATHER_RADIUS_PX = 6f
        // Idle fires in bursts while the follow loop eases the camera; one
        // snapshot a second is plenty for a static frost.
        private const val SNAPSHOT_MIN_INTERVAL_MS = 1_000L
        // ~25 m in degrees of latitude; used as the decimation floor for traces.
        private const val DECIMATE_DEG = 2.25e-4

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
