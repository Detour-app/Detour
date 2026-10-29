package com.jellemax.detour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteSpeed
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Teal (slowest) to amber (fastest), one per [RouteSpeed] step (#834). */
private val SPEED_STEP_HEX = listOf("#14B8A6", "#4CB27F", "#85AB58", "#BDA432", "#F59E0B")

internal val SPEED_STEP_COLORS: List<Color> =
    SPEED_STEP_HEX.map { Color(android.graphics.Color.parseColor(it)) }

/** `#RRGGBB`, the form MapLibre's colour properties take. */
internal fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private const val SRC_SPEED_ROUTE = "mr-speed-route"
private const val SRC_ROUTE_ENDS = "mr-route-ends"

/**
 * The trip detail's own route layers: a surface casing, the route in the
 * colour [setSpeedRoute] gives each stretch, and a hollow start dot and solid
 * end dot. Added once per style load, before the highlight and replay layers
 * so those draw on top.
 */
internal fun addSpeedRouteLayers(style: Style, casingHex: String, dotHex: String) {
    style.addSource(GeoJsonSource(SRC_SPEED_ROUTE))
    style.addSource(GeoJsonSource(SRC_ROUTE_ENDS))
    style.addLayer(LineLayer("mr-speed-route-casing", SRC_SPEED_ROUTE).withProperties(
        PropertyFactory.lineColor(casingHex), PropertyFactory.lineWidth(8f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("mr-speed-route-line", SRC_SPEED_ROUTE).withProperties(
        PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(5f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(CircleLayer("mr-route-ends", SRC_ROUTE_ENDS).withProperties(
        PropertyFactory.circleRadius(5f),
        PropertyFactory.circleColor(
            Expression.switchCase(Expression.get("end"), Expression.literal(dotHex), Expression.literal(casingHex)),
        ),
        PropertyFactory.circleStrokeColor(
            Expression.switchCase(Expression.get("end"), Expression.literal(casingHex), Expression.literal(dotHex)),
        ),
        PropertyFactory.circleStrokeWidth(2.5f)))
}

/** Puts [points] on the layers [addSpeedRouteLayers] made: one line per run of
 *  one speed step, or one [inkHex] line when [steps] is null. */
internal fun setSpeedRoute(style: Style, points: List<LatLon>, steps: List<Int>?, inkHex: String) {
    val lines = if (points.size < 2) {
        emptyList()
    } else {
        val runs = steps?.let(RouteSpeed::runs) ?: listOf(RouteSpeed.Run(step = -1, from = 0, to = points.lastIndex))
        runs.map { run ->
            Feature.fromGeometry(LineString.fromLngLats(
                points.subList(run.from, run.to + 1).map { Point.fromLngLat(it.lon, it.lat) },
            )).apply { addStringProperty("color", SPEED_STEP_HEX.getOrNull(run.step) ?: inkHex) }
        }
    }
    (style.getSource(SRC_SPEED_ROUTE) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(lines))
    val ends = if (points.size < 2) emptyList() else listOf(points.first() to false, points.last() to true)
    (style.getSource(SRC_ROUTE_ENDS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(
        ends.map { (p, end) ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addBooleanProperty("end", end) }
        },
    ))
}

/** "Slow ▬ Fast" under a speed-coloured card map: the colours are this ride's
 *  own range, so the legend names no numbers. */
@Composable
internal fun SpeedLegend(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.history_speed_slow), style = style, color = color)
        Box(
            Modifier
                .width(48.dp)
                .height(6.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(SPEED_STEP_COLORS)),
        )
        Text(stringResource(R.string.history_speed_fast), style = style, color = color)
    }
}
