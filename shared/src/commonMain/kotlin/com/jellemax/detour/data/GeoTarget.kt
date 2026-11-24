package com.jellemax.detour.data

/** What a `geo:` URI ("Navigate to X" from a head unit or another app) asks for. */
sealed interface GeoTarget {
    /** A point to route to; [label] is the place's name when the URI carried one. */
    data class Point(val at: LatLon, val label: String?) : GeoTarget

    /** Only a free-text place ("geo:0,0?q=Ghent station"): run it as a search. */
    data class Query(val text: String) : GeoTarget
}

private val COORDS_IN_QUERY = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

/**
 * Reads the scheme-specific part of a `geo:` URI, already percent-decoded
 * (Android's `Uri.getSchemeSpecificPart()`), so it stays testable off-device.
 * The forms the navigate intent carries:
 * - `52.1,4.3` and `52.1,4.3?q=Home` — a point, `q` as its name when it is text
 * - `0,0?q=52.1,4.3(Home)` — the point and its label in `q`
 * - `0,0?q=Ghent+station` — text only
 *
 * `0,0` means "no point" (the spec's convention), so it never routes to
 * the Gulf of Guinea. Null when there is nothing to act on.
 */
fun parseGeoUri(schemeSpecificPart: String): GeoTarget? {
    val path = schemeSpecificPart.substringBefore('?').substringBefore(';')
    val q = schemeSpecificPart.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("q=") }?.removePrefix("q=")?.replace('+', ' ')?.trim()
        ?.takeIf { it.isNotEmpty() }
    COORDS_IN_QUERY.find(q.orEmpty())?.let { m ->
        point(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it }
    }
    val (lat, lon) = path.split(',').let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
    val label = q?.takeUnless { COORDS_IN_QUERY.matches(it) }
    return point(lat, lon, label) ?: q?.let { GeoTarget.Query(it) }
}

private fun point(lat: String, lon: String, label: String?): GeoTarget.Point? {
    val la = lat.trim().toDoubleOrNull() ?: return null
    val lo = lon.trim().toDoubleOrNull() ?: return null
    val valid = la in -90.0..90.0 && lo in -180.0..180.0 && (la != 0.0 || lo != 0.0)
    return if (valid) GeoTarget.Point(LatLon(la, lo), label?.trim()?.takeIf { it.isNotEmpty() }) else null
}
