package com.jellemax.detour.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.CardData
import com.jellemax.detour.data.CardPoint
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripCardFile
import com.jellemax.detour.data.TripCardGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.IOException

/** The card's fixed pixel size — see the design spec's "one 1080x1350 portrait
 *  card covers most social targets". Independent of the device's own density:
 *  the card is a fixed-size export, not a screen layout. */
private const val CARD_WIDTH_PX = 1080
private const val CARD_HEIGHT_PX = 1350

/** The card's alternate visual layouts. [label] is what the picker in
 *  [TripCardShareDialog] shows — kept on the enum rather than derived from
 *  `.name` so it can read as a proper word without a `.lowercase()` dance. */
enum class CardLayout(val label: String) {
    STANDARD("Standard"),
    MINIMAL("Minimal"),
    POSTER("Poster"),
}

private const val ROUTE_SNAPSHOT_SOURCE_ID = "trip-card-route"
private const val ROUTE_SNAPSHOT_LAYER_ID = "trip-card-route-line"

/** A completed [MapSnapshotter] result: the bitmap ready to draw, plus the
 *  raw [MapSnapshot] for [MapSnapshot.pixelForLatLng] (placing the
 *  destination dot in exact alignment with the rendered streets) and
 *  [MapSnapshot.attributions] (the required OSM credit text). */
data class TripCardMapSnapshot(val image: ImageBitmap, val raw: MapSnapshot)

/** Strips the HTML the style's TileJSON attribution strings carry (e.g. an
 *  `<a href=...>` wrapper) down to plain text for the card's footer. */
private fun plainAttribution(snapshot: MapSnapshot): String =
    snapshot.attributions.joinToString(" · ") { it.replace(Regex("<[^>]*>"), "") }

/**
 * Renders a real basemap snapshot for [cardData]'s trimmed route at
 * [widthPx]x[heightPx], with the route drawn as a native GeoJSON line layer
 * inside the style (so it's pixel-aligned to the real streets MapLibre
 * renders, not drawn separately against a possibly-different projection).
 * `null` while loading or on error — callers fall back to a flat
 * placeholder, same as [rememberTripCardBitmap] gates the Share button on
 * its own bitmap being non-null.
 */
@Composable
fun rememberTripCardMapSnapshot(
    cardData: CardData,
    dark: Boolean,
    routeColorHex: String,
    widthPx: Int,
    heightPx: Int,
): State<TripCardMapSnapshot?> {
    val context = LocalContext.current
    val state = remember(cardData, dark, routeColorHex, widthPx, heightPx) {
        mutableStateOf<TripCardMapSnapshot?>(null)
    }

    DisposableEffect(cardData, dark, routeColorHex, widthPx, heightPx) {
        val points = cardData.trimmedLatLon
        if (points.isEmpty()) {
            // No trace to show a map for — same "stats-only" case the
            // abstract-line renderer already handled; leave state null.
            return@DisposableEffect onDispose {}
        }

        val lats = points.map { it.lat }
        val lons = points.map { it.lon }
        // 20% padding on each side so the snapshot shows surrounding
        // streets, not just a tight crop of the route itself.
        val latSpan = (lats.max() - lats.min()).coerceAtLeast(0.0005)
        val lonSpan = (lons.max() - lons.min()).coerceAtLeast(0.0005)
        // LatLngBounds's own primary constructor is `internal`; `.from(...)`
        // is the public factory (confirmed by decompiling the SDK jar —
        // `world()` calls `from(90.0, 180.0, -90.0, -180.0)`), same
        // (latNorth, lonEast, latSouth, lonWest) field order.
        val bounds = LatLngBounds.from(
            lats.max() + latSpan * 0.2, lons.max() + lonSpan * 0.2,
            lats.min() - latSpan * 0.2, lons.min() - lonSpan * 0.2,
        )

        val routeLine = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
        val styleBuilder = Style.Builder()
            .fromUri(openFreeMapStyleUrl(dark))
            .withSource(GeoJsonSource(ROUTE_SNAPSHOT_SOURCE_ID, routeLine))
            .withLayer(
                LineLayer(ROUTE_SNAPSHOT_LAYER_ID, ROUTE_SNAPSHOT_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(AndroidColor.parseColor(routeColorHex)),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        val options = MapSnapshotter.Options(widthPx, heightPx)
            .withStyleBuilder(styleBuilder)
            .withRegion(bounds)
            .withPixelRatio(1f) // exact widthPx/heightPx output, not device-density-scaled
        val snapshotter = MapSnapshotter(context, options)
        snapshotter.start(
            object : MapSnapshotter.SnapshotReadyCallback {
                override fun onSnapshotReady(snapshot: MapSnapshot) {
                    state.value = TripCardMapSnapshot(snapshot.bitmap.asImageBitmap(), snapshot)
                }
            },
            object : MapSnapshotter.ErrorHandler {
                override fun onError(error: String) {
                    state.value = null // falls back to the loading/flat placeholder
                }
            },
        )
        onDispose { snapshotter.cancel() }
    }

    return state
}

/** Desaturated + slightly darkened so the route color (and any card text
 *  drawn over it) stays the focal element against real map imagery. */
private val mapMutedFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.4f) })

/**
 * Renders [cardData] offscreen and returns the captured bitmap once ready.
 * `null` on the first frame (nothing captured yet) — callers gate the actual
 * share on this becoming non-null.
 */
@Composable
fun rememberTripCardBitmap(
    cardData: CardData,
    routeColorHex: String,
    dark: Boolean,
    trimmed: Boolean,
    layout: CardLayout,
): State<ImageBitmap?> {
    val bitmapState = remember(cardData, routeColorHex, dark, trimmed, layout) { mutableStateOf<ImageBitmap?>(null) }
    val graphicsLayer = rememberGraphicsLayer()
    val density = Density(density = 1f) // 1px == 1px: the card is exported at a fixed pixel size.

    // Zero-size, clipped parent: the child below is still placed and drawn
    // (which is what lets graphicsLayer.record capture it) but nothing of it
    // is visible on screen, since the parent clips to an empty box.
    //
    // CompositionLocalProvider(LocalDensity provides density) below is what
    // actually makes the export density-independent: a Dp value doesn't
    // carry the density it was computed with, so the with(density){...toDp()}
    // conversions above would otherwise be resolved against the real
    // LocalDensity.current (the device's own screen density) once measured
    // — silently exporting a 3x/2x-scaled bitmap on a real phone instead of
    // the fixed 1080x1350px card this whole function promises. Installing
    // `density` as the ambient LocalDensity for this subtree makes both the
    // Dp *computation* and its later *measurement* use the same density=1f,
    // so 1dp really does measure out to 1px here.
    CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.size(0.dp).clipToBounds()) {
            Box(
                Modifier
                    // requiredSize, not size: the outer Box above forces this
                    // subtree's *incoming* constraints to a fixed 0x0 (that's how
                    // it reports zero footprint to its own parent). A plain
                    // size() coerces its target into whatever incoming range it's
                    // given, which would collapse this box to 0x0 right along
                    // with its parent. requiredSize() ignores the incoming
                    // constraints and forces the real 1080x1350 card size on the
                    // content below regardless — the overflow beyond the outer
                    // Box's 0x0 bounds is exactly what clipToBounds() above hides
                    // from the real screen, while the graphicsLayer capture below
                    // (which runs in this box's own draw phase, before that outer
                    // clip is applied) still sees the full-size content.
                    .requiredSize(with(density) { CARD_WIDTH_PX.toDp() }, with(density) { CARD_HEIGHT_PX.toDp() })
                    .drawWithContent {
                        graphicsLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(graphicsLayer)
                    },
            ) {
                TripCardContent(cardData, routeColorHex, dark, trimmed, layout)
            }
        }
    }

    LaunchedEffect(cardData, routeColorHex, dark, trimmed, layout) {
        // The draw phase above needs at least one composition/layout/draw
        // pass to have run before the layer holds anything; a single
        // withFrameNanos wait is enough for that first capture. But
        // StandardCardContent's map (rememberTripCardMapSnapshot) loads
        // asynchronously — a MapSnapshotter fetch that's usually near-instant
        // since TripDetailScreen's own map already warmed this route's tile
        // cache before the share dialog can even be opened, but is never
        // guaranteed to land inside that single frame. A one-shot capture
        // would freeze the preview/export on the loading placeholder forever
        // once the real map arrives a frame or two later, since nothing
        // triggers a second capture. Re-poll for a few seconds instead: each
        // recapture just reads whatever the draw phase most recently
        // recorded (a no-op if nothing changed), so this is cheap and picks
        // up the map the moment it's ready.
        androidx.compose.runtime.withFrameNanos { }
        bitmapState.value = graphicsLayer.toImageBitmap()
        val deadlineNanos = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            delay(200)
            bitmapState.value = graphicsLayer.toImageBitmap()
        }
    }

    return bitmapState
}

/**
 * The entire "Share trip card" flow: layout/theme pick, live preview, trim
 * toggle, render, write, launch the share intent. [points] is null while the
 * caller is still loading the trace (HistoryScreen's row menu only holds a
 * capped thumbnail, so it loads the full trace lazily after the dialog
 * opens) — shown as a loading state rather than delaying the dialog's
 * appearance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripCardShareDialog(trip: Trip, points: List<LatLon>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullRoute by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf(CardLayout.STANDARD) }
    var error by remember { mutableStateOf<String?>(null) }
    val routeColor by Settings.routeColor.collectAsStateWithLifecycle()
    // isAppDarkTheme (Theme.kt, same package) resolves the app's own Theme
    // setting (AUTO/LIGHT/DARK/SYSTEM) — not bare isSystemInDarkTheme() —
    // which is what makes the card default to "amber at night, blue at day"
    // matching what TripDetailScreen/MapScreen/etc already show, per the
    // spec. darkOverride lets the picker below flip it for this export only
    // — null means "follow the app", not "light".
    val themePref by Settings.theme.collectAsStateWithLifecycle()
    var darkOverride by remember { mutableStateOf<Boolean?>(null) }
    val dark = darkOverride ?: isAppDarkTheme(themePref)
    val routeColorHex = RouteColors.hex(routeColor, dark)

    val cardData = points?.let { pts -> remember(pts, fullRoute) { TripCardGeometry.build(trip, pts, full = fullRoute) } }
    val bitmap = cardData?.let { rememberTripCardBitmap(it, routeColorHex, dark = dark, trimmed = !fullRoute, layout = layout).value }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share trip card") },
        text = {
            // verticalScroll: preview + two pickers + the trim checkbox runs
            // taller than a small phone's dialog height allows unclipped.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    points == null -> Text("Loading route…")
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Trip card preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(CARD_WIDTH_PX.toFloat() / CARD_HEIGHT_PX)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            CardLayout.entries.forEachIndexed { index, l ->
                                SegmentedButton(
                                    selected = layout == l,
                                    onClick = { layout = l },
                                    shape = SegmentedButtonDefaults.itemShape(index, CardLayout.entries.size),
                                    label = { Text(l.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("Light" to false, "Dark" to true).forEachIndexed { index, (label, isDark) ->
                                SegmentedButton(
                                    selected = dark == isDark,
                                    onClick = { darkOverride = isDark },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                    label = { Text(label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!fullRoute) Text("Route trimmed near start/end for privacy.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = fullRoute, onCheckedChange = { fullRoute = it })
                            Text("Include full route")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bitmap != null,
                onClick = {
                    val bmp = bitmap ?: return@TextButton
                    scope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                TripCardFile.writeForShare(context, trip, bmp.asAndroidBitmap())
                            }
                            context.startActivity(Intent.createChooser(
                                TripCardFile.shareIntent(uri), "Share trip card"))
                            onDismiss()
                        } catch (e: ActivityNotFoundException) {
                            error = "No app to receive an image"
                        } catch (e: IOException) {
                            error = "Card export failed: ${e.message}"
                        }
                    }
                },
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Matches iOS's `#0B1220` dark / white light pair (`TripCardRenderer.swift`),
 *  itself `RouteColors.DRIVEN_TOWARDS_DARK`/`DRIVEN_TOWARDS_LIGHT` — reused
 *  here for consistency rather than inventing a new pair for this screen. */
private val CARD_BACKGROUND_DARK = Color(0xFF0B1220)
private val CARD_BACKGROUND_DARK_BOTTOM = Color(0xFF141E33)
private val CARD_BACKGROUND_LIGHT = Color.White
private val CARD_BACKGROUND_LIGHT_BOTTOM = Color(0xFFF3F4F7)
private val CARD_TEXT_DARK = Color.White
private val CARD_TEXT_LIGHT = Color.Black

@Composable
private fun TripCardContent(cardData: CardData, routeColorHex: String, dark: Boolean, trimmed: Boolean, layout: CardLayout) {
    val trip = cardData.trip
    val routeColor = Color(android.graphics.Color.parseColor(routeColorHex))
    val backgroundTop = if (dark) CARD_BACKGROUND_DARK else CARD_BACKGROUND_LIGHT
    val backgroundBottom = if (dark) CARD_BACKGROUND_DARK_BOTTOM else CARD_BACKGROUND_LIGHT_BOTTOM
    val textColor = if (dark) CARD_TEXT_DARK else CARD_TEXT_LIGHT
    val mutedColor = textColor.copy(alpha = 0.6f)
    // Pin the text color explicitly rather than letting Text() inherit
    // ambient LocalContentColor: MainActivity wraps the whole app in a
    // Surface{}, so without this the captured subtree would pick up
    // onSurface (near-white in the app's dark theme) regardless of what
    // background this card itself decided to paint.
    CompositionLocalProvider(LocalContentColor provides textColor) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(backgroundTop, backgroundBottom))),
        ) {
            when (layout) {
                CardLayout.STANDARD -> StandardCardContent(cardData, routeColor, routeColorHex, backgroundTop, textColor, mutedColor, trimmed)
                CardLayout.MINIMAL -> MinimalCardContent(cardData, routeColor, textColor, mutedColor)
                CardLayout.POSTER -> PosterCardContent(cardData, routeColor, backgroundTop, textColor, mutedColor, trimmed)
            }
        }
    }
}

// Explicit fontSize throughout every layout below, not the bare Material
// type scale: this subtree is pinned to density=1f for a fixed-pixel export
// (see rememberTripCardBitmap), which makes sp resolve 1:1 to px —
// MaterialTheme.typography's values were calibrated for real screen density
// and render far too small on a 1080px-wide image at density 1.

/** Today's default: eyebrow, a route map with room to breathe, a 3-up hero
 *  stat row, a smaller secondary row, and a footer pinned to the bottom via
 *  the map's weight(1f). */
@Composable
private fun StandardCardContent(
    cardData: CardData, routeColor: Color, routeColorHex: String, backgroundTop: Color, textColor: Color, mutedColor: Color, trimmed: Boolean,
) {
    val trip = cardData.trip
    val dark = textColor == CARD_TEXT_DARK
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        CardEyebrow(trip, routeColor, mutedColor)
        // Declared unconditionally (not inside the `if` below) so the
        // footer at the bottom of this function can also read its
        // attribution — rememberTripCardMapSnapshot itself no-ops (state
        // stays null forever, no fetch) when cardData.trimmedLatLon is
        // empty, so this is cheap even for a trip with no trace.
        val mapSnapshot = rememberTripCardMapSnapshot(
            cardData, dark = dark, routeColorHex = routeColorHex,
            widthPx = 1080 - 96, heightPx = 700,
        ).value
        if (cardData.points.isNotEmpty()) {
            // Fixed height, not weight(1f): the map snapshot is an async
            // network fetch that needs a known pixel size *before* layout
            // runs. 700dp is generous (Standard's text stack below — hero
            // row, secondary row, footer — sums to well under the
            // remaining ~650px on a 1350px card even with the trim
            // caption present) — tune this on-device if the real render
            // clips or leaves excess slack.
            Box(Modifier.fillMaxWidth().height(700.dp).padding(top = 20.dp, bottom = 28.dp)) {
                if (mapSnapshot != null) {
                    Image(
                        bitmap = mapSnapshot.image, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        colorFilter = mapMutedFilter,
                    )
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (dark) 0.25f else 0.10f)))
                    MapSnapshotDestinationDot(cardData, mapSnapshot, routeColor, backgroundTop, Modifier.matchParentSize())
                } else {
                    Box(Modifier.matchParentSize().background(textColor.copy(alpha = 0.06f)))
                }
            }
        }
        if (trimmed) TrimmedCaption(mutedColor, Modifier.padding(bottom = 20.dp))
        // Hero row: distance / duration / top speed, big and bold — the
        // three numbers someone actually screenshots a ride for.
        Row(Modifier.fillMaxWidth()) {
            heroStat("DISTANCE", formatDistanceKm(trip.distanceMeters), mutedColor, Modifier.weight(1f))
            heroStat("DURATION", formatDuration(trip.durationMs), mutedColor, Modifier.weight(1f))
            heroStat("TOP SPEED", formatSpeedKmh(trip.topSpeedMps), mutedColor, Modifier.weight(1f))
        }
        SecondaryStatsRow(cardData, textColor, mutedColor, Modifier.padding(top = 24.dp))
        CardFooter(routeColor, textColor, mapSnapshot?.let { plainAttribution(it.raw) }, Modifier.padding(top = 28.dp))
    }
}

/** No map — for a trip where the route isn't the point. Hero stats stack
 *  full-width and bigger than Standard's 3-up row, centered between the
 *  eyebrow and the footer via a flex spacer on each side. */
@Composable
private fun MinimalCardContent(cardData: CardData, routeColor: Color, textColor: Color, mutedColor: Color) {
    val trip = cardData.trip
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        CardEyebrow(trip, routeColor, mutedColor)
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            heroStat("DISTANCE", formatDistanceKm(trip.distanceMeters), mutedColor, fontSize = 72.sp)
            heroStat("DURATION", formatDuration(trip.durationMs), mutedColor, fontSize = 72.sp)
            heroStat("TOP SPEED", formatSpeedKmh(trip.topSpeedMps), mutedColor, fontSize = 72.sp)
        }
        SecondaryStatsRow(cardData, textColor, mutedColor, Modifier.padding(top = 32.dp))
        Spacer(Modifier.weight(1f))
        CardFooter(routeColor, textColor)
    }
}

/** Route as the whole card: a large title instead of the muted eyebrow, the
 *  map filling almost everything, and the stats condensed to one line. */
@Composable
private fun PosterCardContent(
    cardData: CardData, routeColor: Color, backgroundTop: Color, textColor: Color, mutedColor: Color, trimmed: Boolean,
) {
    val trip = cardData.trip
    Column(Modifier.fillMaxSize().padding(40.dp)) {
        Text(
            trip.mode.label + " · " + formatDate(trip.startTimeMs),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        if (cardData.points.isNotEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp)) {
                RouteCanvas(cardData, routeColor, backgroundTop, textColor, Modifier.fillMaxSize())
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (trimmed) TrimmedCaption(mutedColor, Modifier.padding(bottom = 8.dp))
        // One condensed line rather than Standard's hero/secondary split —
        // the route did the talking, these are a caption underneath it.
        val line = listOfNotNull(
            formatDistanceKm(trip.distanceMeters),
            formatDuration(trip.durationMs),
            formatSpeedKmh(trip.topSpeedMps),
        ).joinToString(" · ")
        Text(line, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = mutedColor)
        CardFooter(routeColor, textColor, modifier = Modifier.padding(top = 24.dp))
    }
}

@Composable
private fun CardEyebrow(trip: Trip, routeColor: Color, mutedColor: Color) {
    // Route-color dot + "MODE · DATE", small and muted — the hero stat(s)
    // below carry the card's visual weight, so this line only needs to
    // orient the reader, not compete with it.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(routeColor, shape = CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(
            (trip.mode.label + " · " + formatDate(trip.startTimeMs)).uppercase(),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            color = mutedColor,
        )
    }
}

@Composable
private fun TrimmedCaption(mutedColor: Color, modifier: Modifier = Modifier) {
    // Design spec: "a small caption under the route" — this is what
    // actually lands in the exported PNG, distinct from the dialog's own
    // (pre-share) caption shown above the picker/checkbox.
    Text("Route trimmed near start/end for privacy.", fontSize = 20.sp, color = mutedColor, modifier = modifier)
}

@Composable
private fun SecondaryStatsRow(cardData: CardData, textColor: Color, mutedColor: Color, modifier: Modifier = Modifier) {
    val secondaryStats = buildList {
        add("AVG SPEED" to formatSpeedKmh(cardData.trip.avgSpeedMps))
        cardData.peakLeanDeg?.let { add("PEAK LEAN" to formatLeanAngle(it)) }
        cardData.peakGForce?.let { add("PEAK G" to formatGForce(it)) }
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp).height(1.dp).background(textColor.copy(alpha = 0.12f)))
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            secondaryStats.forEach { (label, value) -> secondaryStat(label, value, mutedColor) }
        }
    }
}

@Composable
private fun CardFooter(routeColor: Color, textColor: Color, attribution: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("DETOUR", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = textColor.copy(alpha = 0.45f))
            if (attribution != null) {
                Text(attribution, fontSize = 12.sp, color = textColor.copy(alpha = 0.35f))
            }
        }
        Box(Modifier.width(48.dp).height(4.dp).background(routeColor))
    }
}

/** The destination halo+dot, positioned via [MapSnapshot.pixelForLatLng] —
 *  the snapshot's own projection, so it lines up with the real streets
 *  MapLibre rendered rather than a separately-computed pixel position. */
@Composable
private fun MapSnapshotDestinationDot(
    cardData: CardData, snapshot: TripCardMapSnapshot, routeColor: Color, backgroundTop: Color, modifier: Modifier = Modifier,
) {
    val trip = cardData.trip
    val destLat = trip.destinationLat
    val destLon = trip.destinationLon
    if (cardData.destination == null || destLat == null || destLon == null) return
    Canvas(modifier) {
        val pixel = snapshot.raw.pixelForLatLng(LatLng(destLat, destLon))
        val center = Offset(pixel.x, pixel.y)
        drawCircle(backgroundTop, radius = 20f, center = center)
        drawCircle(routeColor, radius = 14f, center = center)
    }
}

/** Draws the trimmed/full route polyline plus a faint "map canvas" dot grid,
 *  a hollow start ring and a haloed destination dot — shared by Standard and
 *  Poster, the two layouts that show a map. */
@Composable
private fun RouteCanvas(cardData: CardData, routeColor: Color, backgroundTop: Color, textColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // cardData.points are already normalized to preserve aspect ratio
        // (shared TripCardGeometry divides both axes by the same larger
        // span). Mapping them onto the full (non-square) measured box here
        // would re-stretch that aspect-corrected shape, so map into a
        // centered *square* sub-region instead.
        val s = minOf(size.width, size.height)
        val offsetX = (size.width - s) / 2f
        val offsetY = (size.height - s) / 2f
        fun toOffset(p: CardPoint) = Offset(offsetX + p.x * s, offsetY + p.y * s)

        // Faint dot grid across the whole box, not just the square
        // sub-region — gives the card a "map canvas" texture instead of a
        // flat void around the route.
        val gridColor = textColor.copy(alpha = 0.06f)
        var gx = 0f
        while (gx <= size.width) {
            var gy = 0f
            while (gy <= size.height) {
                drawCircle(gridColor, radius = 2.5f, center = Offset(gx, gy))
                gy += 44f
            }
            gx += 44f
        }

        val path = androidx.compose.ui.graphics.Path()
        cardData.points.forEachIndexed { i, p ->
            val offset = toOffset(p)
            if (i == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        // Soft glow pass underneath the crisp line: a wider, low-alpha
        // stroke of the same path rather than a real blur filter — cheap,
        // and survives the offscreen GraphicsLayer capture without extra
        // setup.
        drawPath(path, routeColor.copy(alpha = 0.20f), style = Stroke(width = 30f, cap = StrokeCap.Round))
        val lineBrush = Brush.linearGradient(
            listOf(routeColor.copy(alpha = 0.65f), routeColor),
            start = Offset(offsetX, offsetY),
            end = Offset(offsetX + s, offsetY + s),
        )
        drawPath(path, lineBrush, style = Stroke(width = 12f, cap = StrokeCap.Round))

        cardData.points.firstOrNull()?.let { start ->
            drawCircle(routeColor, radius = 9f, center = toOffset(start), style = Stroke(width = 4f))
        }
        cardData.destination?.let { d ->
            val center = toOffset(d)
            // Halo + dot rather than a flat fill: gives the destination a
            // ring of contrast against the line and background alike
            // instead of blending into it.
            drawCircle(backgroundTop, radius = 20f, center = center)
            drawCircle(routeColor, radius = 14f, center = center)
        }
    }
}

@Composable
private fun heroStat(label: String, value: String, mutedColor: Color, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 56.sp) {
    Column(modifier) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = mutedColor)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun secondaryStat(label: String, value: String, mutedColor: Color) {
    Column {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = mutedColor)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    }
}
