package com.jellemax.detour.ui

import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.Settings
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.math.roundToInt

/** Session cache of rendered thumbnails, sized in bitmap bytes: scrolling back
 *  up the Logbook redraws from here instead of re-running the snapshotter. */
// ponytail: memory only — a cold open re-renders each visible card (tiles come
// from MapLibre's own disk cache); persist the bitmaps if that open is slow.
private const val SNAPSHOT_CACHE_BYTES = 24 * 1024 * 1024

/** Room around the route's bounds, as a fraction of its span — the trip card's margin. */
private const val BOUNDS_PAD = 0.2

/** A span below this (degrees, ~50 m) is a trip that barely moved; widen it so
 *  the snapshotter doesn't zoom into a single building. */
private const val MIN_SPAN_DEG = 0.0005

private data class SnapshotKey(val lines: List<List<LatLon>>, val widthPx: Int, val heightPx: Int, val dark: Boolean)

/** A map image, with each line already projected into its pixels. */
private class RouteSnapshot(val image: ImageBitmap, val lines: List<List<Offset>>)

private val snapshots = object : LruCache<SnapshotKey, RouteSnapshot>(SNAPSHOT_CACHE_BYTES) {
    override fun sizeOf(key: SnapshotKey, value: RouteSnapshot) = value.image.width * value.image.height * 4
}

/**
 * [lines] drawn over a map of where they were driven — a shape alone says little
 * about a ride. Until the snapshot lands, or when it can't (offline, style
 * server down), the shape is drawn on its own, so a card is never blank.
 */
@Composable
fun RouteMapThumbnail(lines: List<List<LatLon>>, modifier: Modifier = Modifier) {
    val theme by Settings.theme.collectAsStateWithLifecycle()
    val dark = isAppDarkTheme(theme)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val heightPx = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val key = SnapshotKey(lines, widthPx, heightPx, dark)
        var snapshot by remember(key) { mutableStateOf(snapshots.get(key)) }
        DisposableEffect(key) {
            val points = lines.flatten()
            val sized = widthPx > 0 && heightPx > 0
            if (snapshot != null || points.size < 2 || !sized) {
                return@DisposableEffect onDispose {}
            }
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val latPad = (maxLat - minLat).coerceAtLeast(MIN_SPAN_DEG) * BOUNDS_PAD
            val lonPad = (maxLon - minLon).coerceAtLeast(MIN_SPAN_DEG) * BOUNDS_PAD
            val bounds = LatLngBounds.Builder()
                .include(LatLng(maxLat + latPad, maxLon + lonPad))
                .include(LatLng(minLat - latPad, minLon - lonPad))
                .build()
            // Logical size × the screen's pixel ratio: the bitmap comes out at the
            // thumbnail's real pixel size with labels at their normal size, and
            // pixelForLatLng answers in those same bitmap pixels.
            val options = MapSnapshotter.Options((widthPx / density).roundToInt(), (heightPx / density).roundToInt())
                .withStyleBuilder(Style.Builder().fromUri(openFreeMapStyleUrl(dark)))
                .withRegion(bounds)
                .withPixelRatio(density)
                .withLogo(false)
            val snapshotter = MapSnapshotter(context, options)
            snapshotter.start(
                { s ->
                    val projected = lines.map { line ->
                        line.map { p -> s.pixelForLatLng(LatLng(p.lat, p.lon)).let { Offset(it.x, it.y) } }
                    }
                    val done = RouteSnapshot(s.bitmap.asImageBitmap(), projected)
                    snapshots.put(key, done)
                    snapshot = done
                },
                { message -> Log.w("DetourThumbnail", "map snapshot failed: $message") },
            )
            onDispose { snapshotter.cancel() }
        }
        val s = snapshot
        if (s == null) {
            TraceThumbnail(lines, Modifier.fillMaxSize())
        } else {
            // No credit of our own: the snapshotter already draws the style's
            // "© OpenFreeMap / OpenMapTiles / OpenStreetMap" into the bitmap.
            RouteOnMap(s, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun RouteOnMap(snapshot: RouteSnapshot, modifier: Modifier = Modifier) {
    val route = MaterialTheme.colorScheme.primary
    val casing = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        drawImage(snapshot.image)
        for (line in snapshot.lines) drawLine(line, casing, 6.dp.toPx())
        for (line in snapshot.lines) drawLine(line, route, 3.dp.toPx())
    }
}

private fun DrawScope.drawLine(points: List<Offset>, color: Color, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Draws one or more traces as simple normalized polylines on a shared
 *  scale — not a map, just a recognizable shape at a glance, for while the map
 *  snapshot is loading or when there is none. Lat/lon are scaled
 *  independently to fill the canvas; at this size the distortion from true
 *  distance doesn't matter and equirectangular projection math would be wasted
 *  precision. */
@Composable
private fun TraceThumbnail(lines: List<List<LatLon>>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val all = lines.flatten()
        if (all.size < 2) return@Canvas
        val latSpan = (all.maxOf { it.lat } - all.minOf { it.lat }).let { if (it > 1e-9) it else 1.0 }
        val lonSpan = (all.maxOf { it.lon } - all.minOf { it.lon }).let { if (it > 1e-9) it else 1.0 }
        val minLat = all.minOf { it.lat }
        val minLon = all.minOf { it.lon }
        val pad = size.minDimension * 0.12f
        // Keep the shape's own aspect: a wide canvas would otherwise stretch
        // a north-south ride into a flat line.
        val scale = minOf((size.width - pad * 2) / lonSpan.toFloat(), (size.height - pad * 2) / latSpan.toFloat())
        val offX = (size.width - lonSpan.toFloat() * scale) / 2
        val offY = (size.height - latSpan.toFloat() * scale) / 2
        fun offsetOf(p: LatLon) = Offset(
            offX + ((p.lon - minLon).toFloat() * scale),
            // Screen y grows downward; north (higher lat) should sit higher.
            offY + ((latSpan - (p.lat - minLat)).toFloat() * scale),
        )
        for (points in lines) drawLine(points.map(::offsetOf), color, 2.5.dp.toPx())
    }
}
