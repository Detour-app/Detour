package com.jellemax.detour.data

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two on-disk formats a saved route can round-trip through: the
 * flat-polyline JSON shape in Routes.kt, and the GPX import/export in
 * RouteGpx.kt (which has to tolerate files this app didn't write).
 */
class RoutesTest {

    private fun route() = SavedRoute(
        id = 42L,
        name = "Sunday loop",
        createdMs = 1_700_000_000_000L,
        mode = TravelMode.MOTO,
        stops = listOf(
            RouteStop(LatLon(50.8, 3.2), "Home"),
            RouteStop(LatLon(50.9, 3.3)),
            RouteStop(LatLon(51.0, 3.4), "Café"),
        ),
        polyline = (0..20).map { LatLon(50.8 + it * 0.01, 3.2 + it * 0.01) },
        distanceMeters = 15_234.5,
        timeMs = 3_600_000L,
        sharedBy = "",
    )

    @Test
    fun jsonRoundTripsEveryField() {
        val r = route()
        val back = routeFromJson(r.toJson())!!
        assertEquals(r.id, back.id)
        assertEquals(r.name, back.name)
        assertEquals(r.createdMs, back.createdMs)
        assertEquals(r.mode, back.mode)
        assertEquals(r.stops, back.stops)
        assertEquals(r.polyline, back.polyline)
        assertEquals(r.distanceMeters, back.distanceMeters)
        assertEquals(r.timeMs, back.timeMs)
        assertEquals(r.sharedBy, back.sharedBy)
    }

    @Test
    fun polylineIsEncodedAsAFlatNumberArrayNotObjects() {
        val json = route().toJson()
        val polyline = json.optArray("polyline")!!
        // Two numbers per point, not one object per point.
        assertEquals(route().polyline.size * 2, polyline.size)
        assertTrue(json["polyline"].toString().none { it == '{' })
    }

    @Test
    fun sharedByRoundTripsWhenPresent() {
        val shared = route().copy(sharedBy = "alice")
        val back = routeFromJson(shared.toJson())!!
        assertEquals("alice", back.sharedBy)
    }

    @Test
    fun fewerThanTwoStopsIsRejected() {
        val one = buildJsonObject {
            put("id", 1L)
            put("name", "x")
            put("createdMs", 1L)
            put("mode", "CAR")
            putJsonArray("stops") {
                addJsonObject { put("lat", 50.0); put("lon", 3.0) }
            }
        }
        assertNull(routeFromJson(one))
    }

    @Test
    fun nanCoordinatesAreRejected() {
        val bad = buildJsonObject {
            put("id", 1L)
            put("name", "x")
            put("createdMs", 1L)
            put("mode", "CAR")
            putJsonArray("stops") {
                addJsonObject { put("lat", 50.0); put("lon", 3.0) }
                addJsonObject { put("lat", "not-a-number"); put("lon", 3.1) }
            }
        }
        assertNull(routeFromJson(bad))
    }

    @Test
    fun routeWithNoPolylineIsStillUsable() {
        val bare = route().copy(polyline = emptyList())
        val back = routeFromJson(bare.toJson())!!
        assertTrue(back.polyline.isEmpty())
        assertEquals(3, back.stops.size)
    }

    @Test
    fun buildGpxHasOneRteptPerStopAndOneTrkptPerPolylinePoint() {
        val r = route()
        val gpx = RouteGpx.buildGpx(r)
        assertEquals(r.stops.size, Regex("<rtept ").findAll(gpx).count())
        assertEquals(r.polyline.size, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun buildGpxEscapesAnAmpersandInTheName() {
        val r = route().copy(name = "Café & back")
        val gpx = RouteGpx.buildGpx(r)
        assertTrue(gpx.contains("Café &amp; back"))
        assertFalse(gpx.contains("Café & back"))
    }

    @Test
    fun parseGpxRoundTripsOurOwnExport() {
        val r = route()
        val gpx = RouteGpx.buildGpx(r)
        val back = RouteGpx.parseGpx(gpx, nowMs = 999L)!!
        assertEquals(r.name, back.name)
        assertEquals(r.stops.size, back.stops.size)
        assertEquals(r.stops.map { it.name }, back.stops.map { it.name })
        for (i in r.stops.indices) {
            assertEquals(r.stops[i].at.lat, back.stops[i].at.lat, absoluteTolerance = 1e-6)
            assertEquals(r.stops[i].at.lon, back.stops[i].at.lon, absoluteTolerance = 1e-6)
        }
        assertEquals(r.polyline.size, back.polyline.size)
    }

    @Test
    fun parseGpxAcceptsATrkOnlyFileWithSingleQuotesAndReversedAttributeOrder() {
        // Single-quoted attributes, lon before lat, no <rte> at all — exactly
        // the kind of file a tool other than this app might write.
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1" creator="SomeOtherTool">
              <trk>
                <name>Hand-written</name>
                <trkseg>
                  <trkpt lon='3.10' lat='50.10'></trkpt>
                  <trkpt lon='3.20' lat='50.20'></trkpt>
                  <trkpt lon='3.30' lat='50.30'></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val route = RouteGpx.parseGpx(gpx, nowMs = 1L)
        assertNotNull(route)
        assertEquals("Hand-written", route.name)
        assertEquals(3, route.stops.size)
        assertEquals(50.10, route.stops[0].at.lat, absoluteTolerance = 1e-6)
        assertEquals(3.10, route.stops[0].at.lon, absoluteTolerance = 1e-6)
        assertEquals(3, route.polyline.size)
    }

    @Test
    fun parseGpxOfGarbageReturnsNull() {
        assertNull(RouteGpx.parseGpx("not xml at all", nowMs = 1L))
        assertNull(RouteGpx.parseGpx("<gpx></gpx>", nowMs = 1L))
    }

    @Test
    fun parseRouteFileSniffsJsonVsGpx() {
        val r = route()
        val fromJson = RouteGpx.parseRouteFile(r.toJson().string(), nowMs = 1L)!!
        assertEquals(r.name, fromJson.name)

        val fromGpx = RouteGpx.parseRouteFile(RouteGpx.buildGpx(r), nowMs = 1L)!!
        assertEquals(r.name, fromGpx.name)
    }

    @Test
    fun trackpointsAreThinnedToAtMostTwentyFiveStops() {
        val long = route().copy(polyline = (0..500).map { LatLon(50.0 + it * 0.0001, 3.0) })
        // No <rte> in this hand-built file, so parseGpx falls back to <trk>.
        val gpx = buildString {
            append("<gpx version=\"1.1\"><trk><trkseg>")
            for (p in long.polyline) append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"/>")
            append("</trkseg></trk></gpx>")
        }
        val parsed = RouteGpx.parseGpx(gpx, nowMs = 1L)!!
        assertEquals(25, parsed.stops.size)
        // First and last of the original trace are preserved.
        assertEquals(long.polyline.first().lat, parsed.stops.first().at.lat, absoluteTolerance = 1e-9)
        assertEquals(long.polyline.last().lat, parsed.stops.last().at.lat, absoluteTolerance = 1e-9)
    }

    @Test
    fun coordinateFormattingNearThePrimeMeridianIsNeverScientificNotation() {
        val nearZero = SavedRoute(
            id = 1L,
            name = "Meridian",
            createdMs = 1L,
            mode = TravelMode.CAR,
            stops = listOf(
                RouteStop(LatLon(50.0, 0.00001)),
                RouteStop(LatLon(50.0, -0.00001)),
            ),
            polyline = emptyList(),
            distanceMeters = null,
            timeMs = null,
        )
        val gpx = RouteGpx.buildGpx(nearZero)
        assertFalse(gpx.contains("E-"), "unexpected scientific notation in:\n$gpx")
        assertFalse(gpx.contains("e-"), "unexpected scientific notation in:\n$gpx")
        assertTrue(gpx.contains("lon=\"0.0000100\""))
        assertTrue(gpx.contains("lon=\"-0.0000100\""))
    }

    /**
     * RouteStore.save()/rename()/remove()/byId() must call ensureLoaded()
     * before touching `_routes.value`: a mutation can arrive before any
     * screen has loaded the store (e.g. saving straight from the map on a
     * cold start), and without that ordering, write() would overwrite
     * routes.json with just the new route, silently discarding every
     * previously saved one.
     *
     * This test target has no Android Context (see Platform.android.kt), so
     * it can't drive RouteStore through a real file to observe the surviving
     * routes directly — every disk access throws "initSharedCore(context)
     * has not been called". That failure is exactly what proves the
     * ordering, though: it has to originate from ensureLoaded()'s read(),
     * not from write(). If save() went straight to write() (the bug), the
     * stack would show write() and never reach ensureLoaded()/read() at all.
     */
    @Test
    fun saveCallsEnsureLoadedBeforeItEverWritesSoAnEarlyCallCannotWipeDisk() {
        val error = assertFailsWith<IllegalStateException> {
            RouteStore.save(
                SavedRoute(
                    id = 1L,
                    name = "x",
                    createdMs = 1L,
                    mode = TravelMode.CAR,
                    stops = listOf(RouteStop(LatLon(50.0, 3.0)), RouteStop(LatLon(50.1, 3.1))),
                    polyline = emptyList(),
                    distanceMeters = null,
                    timeMs = null,
                ),
            )
        }
        val frames = error.stackTrace.map { it.methodName }
        assertTrue("ensureLoaded" in frames, "expected ensureLoaded() on the stack: $frames")
        assertTrue("read" in frames, "expected read() on the stack: $frames")
        assertFalse("write" in frames, "save() reached write() before ensureLoaded() completed: $frames")
    }
}
