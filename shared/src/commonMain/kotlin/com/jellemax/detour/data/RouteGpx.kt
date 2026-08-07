package com.jellemax.detour.data

import kotlin.math.abs
import kotlin.math.round

/**
 * GPX 1.1 import/export for planned routes: the stops as an `<rte>` and, when
 * routed, the road-following geometry as a `<trk>` alongside it — consistent
 * in spirit with the recorded-trip export in app/'s Gpx.kt, but for a route's
 * waypoints rather than a trace, and pure commonMain (no java.text, no XML
 * parser) so it also compiles for iOS.
 */
object RouteGpx {

    /** Prefix + suffix for a suggested export file name; see [fileName]. */
    const val FILE_PREFIX = "detour-route-"
    const val FILE_EXTENSION = ".gpx"
    const val MIME_TYPE = "application/gpx+xml"

    private const val MAX_STOPS = 25
    private const val DEFAULT_NAME = "Imported route"

    /** Suggested export file name for [route]'s current name. */
    fun fileName(route: SavedRoute): String = FILE_PREFIX + slug(route.name) + FILE_EXTENSION

    /**
     * Renders [route] as GPX 1.1. No `<time>` elements anywhere: a planned
     * route (as opposed to a recorded trip) has no timestamps, which sidesteps
     * needing date formatting in commonMain.
     */
    fun buildGpx(route: SavedRoute): String {
        val sb = StringBuilder(200 + route.stops.size * 100 + route.polyline.size * 40)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Detour\"")
        sb.append(" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>").append(escape(route.name)).append("</name></metadata>\n")
        sb.append("  <rte>\n")
        sb.append("    <name>").append(escape(route.name)).append("</name>\n")
        for (s in route.stops) {
            sb.append("    <rtept lat=\"").append(fixed(s.at.lat))
                .append("\" lon=\"").append(fixed(s.at.lon)).append("\">")
            if (s.name.isNotEmpty()) sb.append("<name>").append(escape(s.name)).append("</name>")
            sb.append("</rtept>\n")
        }
        sb.append("  </rte>\n")
        if (route.polyline.isNotEmpty()) {
            // The routed geometry, kept alongside the stops, so a tool that
            // draws <trk> lines shows the real road-following path rather
            // than straight hops between waypoints.
            sb.append("  <trk>\n    <trkseg>\n")
            for (p in route.polyline) {
                sb.append("      <trkpt lat=\"").append(fixed(p.lat))
                    .append("\" lon=\"").append(fixed(p.lon)).append("\"/>\n")
            }
            sb.append("    </trkseg>\n  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Reads a GPX from any tool, not just [buildGpx]'s own output: `<rte>`
     * stops when present, else `<trk>` trackpoints thinned to at most
     * [MAX_STOPS], else a plain `<wpt>` list. Null when fewer than two usable
     * points come out of any of those.
     *
     * There is no XML parser in commonMain (org.w3c/javax.xml are JVM-only,
     * NSXMLParser is iOS-only-and-not-exposed-here), so this is a tolerant
     * regex scan rather than a real parser. It only needs lat/lon attributes
     * on rtept/trkpt/wpt tags and an optional inner `<name>`, and real-world
     * GPX exporters disagree on attribute order and quote style, so both are
     * matched.
     */
    fun parseGpx(text: String, nowMs: Long): SavedRoute? {
        val rtept = points(text, "rtept")
        val trkpt = points(text, "trkpt")
        val stops: List<RouteStop> = when {
            rtept.size >= 2 -> rtept
            trkpt.size >= 2 -> thin(trkpt, MAX_STOPS)
            else -> points(text, "wpt")
        }
        if (stops.size < 2) return null
        return SavedRoute(
            id = nowMs,
            name = routeName(text),
            createdMs = nowMs,
            mode = TravelMode.CAR,
            stops = stops,
            // Present only when the file carried a <trk>; a bare rte/wpt file
            // has no routed geometry until the caller re-routes it.
            polyline = trkpt.map { it.at },
            distanceMeters = null,
            timeMs = null,
        )
    }

    /** Sniffs the file kind so callers don't need to know which one they got:
     *  JSON export (see [SavedRoute.toJson]) starts with `{`, everything else
     *  is handed to [parseGpx]. */
    fun parseRouteFile(text: String, nowMs: Long): SavedRoute? {
        return if (text.trimStart().startsWith("{")) {
            try {
                routeFromJson(jsonObjectOf(text))
            } catch (e: Exception) {
                null
            }
        } else {
            parseGpx(text, nowMs)
        }
    }

    // --- GPX writing --------------------------------------------------------

    /**
     * Fixed-point formatting for a coordinate, without java.lang.String.format
     * (not available in commonMain) and without Double.toString's scientific
     * notation near zero — a longitude near the prime meridian renders as
     * "1.0E-5" otherwise, which no GPX reader parses. Seven places is ~1 cm,
     * well past what routing or a phone fix knows.
     */
    private fun fixed(value: Double, decimals: Int = 7): String {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        val scaled = round(abs(value) * scale).toLong()
        val whole = scaled / scale.toLong()
        val frac = (scaled % scale.toLong()).toString().padStart(decimals, '0')
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return "$sign$whole.$frac"
    }

    /** The one part of the document that carries free-form user text. */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Lowercased, non-alphanumerics collapsed to a single `-`; used for the
     *  exported file name so a route named "Sunday loop!" becomes a name every
     *  filesystem accepts. */
    private fun slug(name: String): String {
        val dashed = name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        return Regex("-+").replace(dashed, "-").trim('-').ifEmpty { "route" }
    }

    // --- GPX reading ---------------------------------------------------------

    private val LAT_ATTR = Regex("""lat=(["'])(-?[0-9.]+)\1""")
    private val LON_ATTR = Regex("""lon=(["'])(-?[0-9.]+)\1""")
    // GPX puts newlines inside these elements freely, so every wildcard below
    // has to cross them. `[\s\S]` rather than RegexOption.DOT_MATCHES_ALL:
    // that option only exists on the JVM, and this is common code.
    private val NAME_TAG = Regex("""<name>([\s\S]*?)</name>""")

    /** All `<tag .../>` or `<tag ...>...</tag>` elements, in document order,
     *  with lat/lon read from the opening tag's attributes and an optional
     *  name from any inner `<name>`. Points missing lat or lon are skipped. */
    private fun points(text: String, tag: String): List<RouteStop> {
        val point = Regex("""<$tag\b([^>]*?)(?:/>|>([\s\S]*?)</$tag>)""")
        return point.findAll(text).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val lat = LAT_ATTR.find(attrs)?.groupValues?.get(2)?.toDoubleOrNull()
                ?: return@mapNotNull null
            val lon = LON_ATTR.find(attrs)?.groupValues?.get(2)?.toDoubleOrNull()
                ?: return@mapNotNull null
            val inner = m.groupValues.getOrElse(2) { "" }
            val name = NAME_TAG.find(inner)?.groupValues?.get(1)?.let { unescape(it).trim() } ?: ""
            RouteStop(LatLon(lat, lon), name)
        }.toList()
    }

    /** `<metadata><name>`, else `<trk><name>`, else `<rte><name>`, else the
     *  fallback — matching the order tools most commonly populate. */
    private fun routeName(text: String): String {
        block(text, "metadata")?.let { nameIn(it)?.let { n -> return n } }
        block(text, "trk")?.let { nameIn(it)?.let { n -> return n } }
        block(text, "rte")?.let { nameIn(it)?.let { n -> return n } }
        return DEFAULT_NAME
    }

    private fun block(text: String, tag: String): String? =
        Regex("""<$tag\b[\s\S]*?</$tag>""").find(text)?.value

    private fun nameIn(block: String): String? =
        NAME_TAG.find(block)?.groupValues?.get(1)?.let { unescape(it).trim() }?.ifEmpty { null }

    /** `<trk>` thinned to at most [max] stops: first, last, and evenly spaced
     *  points between — a recorded/routed trace can be thousands of points,
     *  far more than makes sense as routing waypoints. */
    private fun thin(points: List<RouteStop>, max: Int): List<RouteStop> {
        if (points.size <= max) return points
        return (0 until max).map { i -> points[(i * (points.size - 1)) / (max - 1)] }
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
