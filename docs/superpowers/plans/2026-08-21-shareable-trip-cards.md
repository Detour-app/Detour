# Shareable Trip Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a rider share a finished trip as a PNG stat card (route shape + headline
stats), alongside the existing `.gpx` export, on both Android and iOS.

**Architecture:** A shared `commonMain` module computes card geometry (bounding-box fit,
500 m endpoint trim) and the mode-driven stat set from an existing `Trip`. Each platform
renders that data declaratively and captures it to an image — Compose `GraphicsLayer` on
Android, SwiftUI `ImageRenderer` on iOS — then hands the PNG to the same share-sheet /
`FileProvider` machinery the `.gpx` export already uses.

**Tech Stack:** Kotlin Multiplatform (`shared/commonMain`, `commonTest`), Jetpack Compose
(BOM `2024.09.02`, Compose UI 1.7 — `rememberGraphicsLayer()`/`GraphicsLayer.toImageBitmap()`
available), SwiftUI (`ImageRenderer`).

**Spec:** `docs/superpowers/specs/2026-08-21-shareable-trip-cards-design.md`

## Global Constraints

- Endpoint trim is fixed at 500 m (cumulative distance from each end), not user-configurable
  as a setting — only as a per-share "include full route" toggle.
- Card covers `MOTO` and `CAR` only — `TravelMode` has no other entries on this branch. No
  "neither lean nor g" fallback layout.
- Stat nullability is driven by `TravelMode.tracksLean` / `TravelMode.tracksGForce`
  (`shared/src/commonMain/kotlin/com/jellemax/detour/data/TravelMode.kt`), never by
  hardcoding `MOTO`/`CAR` checks in card code.
- Card aspect ratio: 1080×1350 portrait, fixed.
- No basemap tiles under the polyline. Route line color must read `RouteColors`
  (`shared/src/commonMain/kotlin/com/jellemax/detour/data/RouteColors.kt`) the same way the
  map does — not a hardcoded color.
- GPX export (`Gpx.kt`, `TripDetailScreen.swift`'s `buildGpx()`) is untouched — the card share
  is a second, additive action, never a replacement.
- Route cards (`RoutesScreen`) and lifetime stats/badges on the card are out of scope.

---

## File Structure

- **Create** `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt` — `CardPoint`,
  `CardData`, `TripCardGeometry.build`. Pure geometry/data, no I/O, no platform dependency.
- **Create** `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripCardTest.kt` — tests
  for the above.
- **Create** `app/src/main/java/com/jellemax/detour/data/TripCardFile.kt` — Android PNG file
  write + `FileProvider` URI, mirroring `Gpx.kt`'s `writeForShare`.
- **Create** `app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt` — the Compose
  card layout, its `GraphicsLayer` capture, and the shared "Share trip card" confirm dialog
  used by both `TripDetailScreen` and `HistoryScreen`.
- **Modify** `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt` — second toolbar
  icon that opens the confirm dialog and launches the card share.
- **Modify** `app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt` — second `⋮` menu
  item on `TripCard`, same dialog.
- **Create** `iosApp/Detour/TripCardRenderer.swift` — the SwiftUI card layout and its
  `ImageRenderer` capture + temp-file write.
- **Modify** `iosApp/Detour/TripDetailScreen.swift` — second toolbar share item and the
  confirm dialog (`.confirmationDialog`/`.sheet`).

---

## Task 1: Shared card geometry and data model

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripCardTest.kt`

**Interfaces:**
- Consumes: `Trip` (`TripStore.kt`), `LatLon` (`RoadRoulette.kt`), `TravelMode.tracksLean` /
  `tracksGForce` (`TravelMode.kt`).
- Produces (used by Tasks 2–7):
  ```kotlin
  data class CardPoint(val x: Float, val y: Float)

  data class CardData(
      val trip: Trip,
      val points: List<CardPoint>,
      val destination: CardPoint?,
  ) {
      val peakLeanDeg: Double?
      val peakGForce: Double?
  }

  object TripCardGeometry {
      const val TRIM_METERS: Double = 500.0
      fun build(trip: Trip, points: List<LatLon>, full: Boolean = false): CardData
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TripCardTest {

    private fun trip(mode: TravelMode) = Trip(
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_000_000L + 3_600_000L,
        distanceMeters = 20_000.0,
        topSpeedMps = 30.0,
        maxLeanAngleDeg = 42.0,
        maxGForce = 0.8,
        destinationLat = 51.0,
        destinationLon = 3.4,
        mode = mode,
    )

    // A straight line running due north for 2000 m at roughly 22.24 m per
    // degree-thousandth of latitude near this longitude — long enough that
    // trimming 500 m off each end leaves a clearly shorter middle section,
    // short enough the test stays fast.
    private fun straightLinePoints(count: Int = 41): List<LatLon> =
        (0 until count).map { LatLon(50.8 + it * 0.00045, 3.2) }

    @Test
    fun emptyPointsProduceEmptyCardWithoutThrowing() {
        val card = TripCardGeometry.build(trip(TravelMode.CAR), emptyList())
        assertTrue(card.points.isEmpty())
    }

    @Test
    fun trimmedByDefaultRemovesPointsNearBothEnds() {
        val full = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints(), full = true)
        val trimmed = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints(), full = false)
        assertTrue(trimmed.points.size < full.points.size)
    }

    @Test
    fun fullSkipsTrimming() {
        val points = straightLinePoints()
        val full = TripCardGeometry.build(trip(TravelMode.CAR), points, full = true)
        // Every input point maps to a normalized output point when nothing is trimmed.
        assertEquals(points.size, full.points.size)
    }

    @Test
    fun normalizedPointsStayWithinUnitBox() {
        val card = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints(), full = true)
        for (p in card.points) {
            assertTrue(p.x in 0f..1f)
            assertTrue(p.y in 0f..1f)
        }
    }

    @Test
    fun destinationTrimmedAwayWhenInsideTheTrimmedSpan() {
        // The destination in `trip()` (51.0, 3.4) is nowhere near the drawn
        // line, so it never collides with the trim window in these fixtures —
        // this test only pins the "present when not trimmed" half; the
        // "trimmed away" half is exercised by construction: a destination
        // equal to one of the trimmed endpoints must come back null.
        val points = straightLinePoints()
        val nearStart = points.first()
        val cardWithDestAtStart = TripCardGeometry.build(
            trip(TravelMode.CAR).copy(destinationLat = nearStart.lat, destinationLon = nearStart.lon),
            points,
            full = false,
        )
        assertNull(cardWithDestAtStart.destination)
    }

    @Test
    fun destinationPresentWhenFull() {
        val points = straightLinePoints()
        val nearStart = points.first()
        val card = TripCardGeometry.build(
            trip(TravelMode.CAR).copy(destinationLat = nearStart.lat, destinationLon = nearStart.lon),
            points,
            full = true,
        )
        assertNotNull(card.destination)
    }

    @Test
    fun moduTripExposesBothPeakLeanAndPeakG() {
        val card = TripCardGeometry.build(trip(TravelMode.MOTO), straightLinePoints())
        assertEquals(42.0, card.peakLeanDeg)
        assertEquals(0.8, card.peakGForce)
    }

    @Test
    fun carTripExposesOnlyPeakG() {
        val card = TripCardGeometry.build(trip(TravelMode.CAR), straightLinePoints())
        assertNull(card.peakLeanDeg)
        assertEquals(0.8, card.peakGForce)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.TripCardTest"`
Expected: FAIL — `TripCard.kt` (the class/object under test) doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.jellemax.detour.data

import kotlin.math.cos
import kotlin.math.sqrt

/** One point in card space: 0..1 on both axes, top-left origin. */
data class CardPoint(val x: Float, val y: Float)

/**
 * Everything a trip card renderer needs, already geometry-normalized and
 * privacy-trimmed. [points] and [destination] are empty/null when the trip
 * has no matched trace, or when a point/the destination fell inside the
 * trimmed span — the renderer draws a stats-only layout in that case, not
 * an error (see the design spec's "trips with no trace" note).
 */
data class CardData(
    val trip: Trip,
    val points: List<CardPoint>,
    val destination: CardPoint?,
) {
    /** Non-null only for a mode that actually records lean — a car's number
     *  would be the phone sliding in its cradle, not the vehicle. */
    val peakLeanDeg: Double? get() = if (trip.mode.tracksLean) trip.maxLeanAngleDeg else null
    val peakGForce: Double? get() = if (trip.mode.tracksGForce) trip.maxGForce else null
}

/**
 * Builds a [CardData] from a trip's reassembled trace. This is geometry only
 * — callers pass in whatever `loadTripTrace`/`matchTripPoints` already
 * produced; this function does no I/O and knows nothing about TraceStore.
 */
object TripCardGeometry {

    /** Distance trimmed from each end of the drawn polyline by default — a
     *  route card is a picture of where you live, and the driveway is the
     *  first and last thing on it. */
    const val TRIM_METERS: Double = 500.0

    fun build(trip: Trip, points: List<LatLon>, full: Boolean = false): CardData {
        if (points.isEmpty()) return CardData(trip, emptyList(), null)

        val cumulative = cumulativeDistances(points)
        val total = cumulative.last()
        val trimStart = if (full) 0.0 else TRIM_METERS
        val trimEnd = if (full) total else (total - TRIM_METERS)

        // A trip shorter than 2x the trim distance has no untrimmed middle
        // left — draw nothing rather than a negative-length span.
        if (!full && trimEnd <= trimStart) return CardData(trip, emptyList(), null)

        val kept = points.indices.filter { cumulative[it] in trimStart..trimEnd }
        if (kept.isEmpty()) return CardData(trip, emptyList(), null)

        val keptPoints = kept.map { points[it] }
        val minLat = keptPoints.minOf { it.lat }
        val maxLat = keptPoints.maxOf { it.lat }
        val minLon = keptPoints.minOf { it.lon }
        val maxLon = keptPoints.maxOf { it.lon }

        // Longitude degrees shrink with latitude; without this a route
        // running mostly east-west would look stretched. Cheap enough for a
        // card-sized box that a full projection isn't worth it.
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-9)
        val lonSpan = ((maxLon - minLon) * cos(Math.toRadians((minLat + maxLat) / 2))).coerceAtLeast(1e-9)
        val span = maxOf(latSpan, lonSpan)

        fun normalize(p: LatLon): CardPoint {
            val nx = ((p.lon - minLon) * cos(Math.toRadians((minLat + maxLat) / 2))) / span
            val ny = 1f - ((p.lat - minLat) / span).toFloat() // screen y grows downward
            return CardPoint(nx.toFloat(), ny)
        }

        val normalizedPoints = keptPoints.map(::normalize)
        val destLat = trip.destinationLat
        val destLon = trip.destinationLon
        val destination = if (destLat != null && destLon != null) {
            val destCumulative = nearestCumulativeDistance(points, cumulative, destLat, destLon)
            if (destCumulative in trimStart..trimEnd) normalize(LatLon(destLat, destLon)) else null
        } else null

        return CardData(trip, normalizedPoints, destination)
    }

    /** Running distance (meters) at each point, index-aligned with [points]. */
    private fun cumulativeDistances(points: List<LatLon>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + haversineMeters(points[i - 1], points[i])
        }
        return out
    }

    /** The trimmed span uses distance *along the trace*, so the destination
     *  (which isn't necessarily on the trace) is placed at the cumulative
     *  distance of the trace point nearest to it — the trim check then
     *  reuses the exact same [trimStart]..[trimEnd] window as the polyline. */
    private fun nearestCumulativeDistance(
        points: List<LatLon>, cumulative: DoubleArray, lat: Double, lon: Double,
    ): Double {
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in points.indices) {
            val d = haversineMeters(points[i], LatLon(lat, lon))
            if (d < bestDist) { bestDist = d; bestIdx = i }
        }
        return cumulative[bestIdx]
    }

    private fun haversineMeters(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sinSq(dLat / 2) + cos(la1) * cos(la2) * sinSq(dLon / 2)
        return 2 * r * kotlin.math.asin(sqrt(h))
    }

    private fun sinSq(x: Double): Double = kotlin.math.sin(x).let { it * it }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.TripCardTest"`
Expected: PASS, all 8 tests.

- [ ] **Step 5: Run the full shared suite to check for regressions**

Run: `./gradlew :shared:testDebugUnitTest`
Expected: PASS (no existing test touches `TripCard.kt`, but this catches a build break in
the module).

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/TripCardTest.kt
git commit -m "feat: shared trip card geometry — bbox fit, 500m trim, mode-driven stats"
```

---

## Task 2: Android PNG write + FileProvider hand-off

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/data/TripCardFile.kt`

**Interfaces:**
- Consumes: `Gpx.SHARE_DIR` (`Gpx.kt:26`, already `internal` for this exact reuse),
  `BuildConfig.APPLICATION_ID`, `Trip`.
- Produces (used by Task 4/5):
  ```kotlin
  object TripCardFile {
      fun writeForShare(context: Context, trip: Trip, bitmap: Bitmap): Uri
      fun shareIntent(uri: Uri): Intent
  }
  ```

This task has no independent unit test — it's a thin wrapper around
`Bitmap.compress`/`FileProvider`, exactly mirroring `Gpx.writeForShare` (`Gpx.kt:63-73`),
which itself has no unit test for the same reason (file I/O + Android `Context`, verified by
the manual pass in Task 5).

- [ ] **Step 1: Write the file**

```kotlin
package com.jellemax.detour.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.jellemax.detour.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a rendered trip card PNG into the same FileProvider cache dir
 * [Gpx] uses, and hands back the same `ACTION_SEND` shape — a card share is
 * additive to the `.gpx` export, not a replacement, so it deliberately reuses
 * every part of that path except the file extension and mime type.
 */
object TripCardFile {

    fun writeForShare(context: Context, trip: Trip, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, Gpx.SHARE_DIR).apply { mkdirs() }
        val file = File(dir, fileName(trip))
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun fileName(trip: Trip): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(trip.startTimeMs))
        return "detour-card-${trip.mode.name.lowercase(Locale.US)}-$stamp.png"
    }
}
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No runnable test at this layer — this is I/O glue verified end
to end in Task 5.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/data/TripCardFile.kt
git commit -m "feat: android trip card file write, reusing the gpx FileProvider cache"
```

---

## Task 3: Android card renderer (Compose → bitmap)

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt`

**Interfaces:**
- Consumes: `CardData`/`TripCardGeometry` (Task 1), `TripCardFile` (Task 2),
  `RouteColors.hex` (`RouteColors.kt`), `Settings.routeColor`/`Settings.theme` (`Settings.kt`),
  `isAppDarkTheme` (`Theme.kt:19`, same `com.jellemax.detour.ui` package — no import needed),
  `formatDistanceKm` / `formatDurationHistory` / `formatSpeedKmh` / `formatLeanAngle` /
  `formatGForce` / `formatDate` (`Format.kt`, same package).
- Produces (used by Task 4/5):
  ```kotlin
  @Composable
  fun rememberTripCardBitmap(cardData: CardData, routeColorHex: String): State<ImageBitmap?>

  @Composable
  fun TripCardShareDialog(trip: Trip, points: List<LatLon>?, onDismiss: () -> Unit)
  ```
  `rememberTripCardBitmap` returns `null` until the offscreen composition has drawn at least
  one frame, then the captured 1080×1350 card.

  `TripCardShareDialog` is the entire "Share trip card" flow — trim toggle, render, write,
  launch the share intent, inline error state — as one reusable composable, so
  `TripDetailScreen` and `HistoryScreen` (Task 4/5) each add only an entry point that opens
  it, not a second copy of the dialog. `points == null` renders a loading state, which is
  what lets `HistoryScreen` open the dialog immediately and load the trace lazily instead of
  blocking the tap.

The capture technique: `androidx.compose.ui.graphics.layer.GraphicsLayer` (via
`rememberGraphicsLayer()`) records real draw calls — including Material `Text` — into an
offscreen layer, then `layer.toImageBitmap()` reads back pixels. The layer only fills in when
something actually calls its `record {}` block from inside an active composition's draw
phase, so the card composable has to be placed in the tree (not just called as a plain
function) — sized to the real 1080×1350px card, wrapped in a zero-size, clipped parent so
nothing is visible on screen while it's captured.

- [ ] **Step 1: Write the renderer**

```kotlin
package com.jellemax.detour.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.CardData
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteColors
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripCardFile
import com.jellemax.detour.data.TripCardGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The card's fixed pixel size — see the design spec's "one 1080x1350 portrait
 *  card covers most social targets". Independent of the device's own density:
 *  the card is a fixed-size export, not a screen layout. */
private const val CARD_WIDTH_PX = 1080
private const val CARD_HEIGHT_PX = 1350

/**
 * Renders [cardData] offscreen and returns the captured bitmap once ready.
 * `null` on the first frame (nothing captured yet) — callers gate the actual
 * share on this becoming non-null.
 */
@Composable
fun rememberTripCardBitmap(cardData: CardData, routeColorHex: String): State<ImageBitmap?> {
    val bitmapState = remember(cardData, routeColorHex) { mutableStateOf<ImageBitmap?>(null) }
    val graphicsLayer = rememberGraphicsLayer()
    val density = Density(density = 1f) // 1px == 1px: the card is exported at a fixed pixel size.

    // Zero-size, clipped parent: the child below is still placed and drawn
    // (which is what lets graphicsLayer.record capture it) but nothing of it
    // is visible on screen, since the parent clips to an empty box.
    Box(Modifier.size(0.dp).clipToBounds()) {
        Box(
            Modifier
                .size(with(density) { CARD_WIDTH_PX.toDp() }, with(density) { CARD_HEIGHT_PX.toDp() })
                .drawWithContent {
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                },
        ) {
            TripCardContent(cardData, routeColorHex)
        }
    }

    LaunchedEffect(cardData, routeColorHex) {
        // The draw phase above needs at least one composition/layout/draw
        // pass to have run before the layer holds anything; a single
        // withFrameNanos wait is enough since the Box is already in the tree.
        androidx.compose.runtime.withFrameNanos { }
        bitmapState.value = graphicsLayer.toImageBitmap()
    }

    return bitmapState
}

/**
 * The entire "Share trip card" flow: trim toggle, render, write, launch the
 * share intent. [points] is null while the caller is still loading the trace
 * (HistoryScreen's row menu only holds a capped thumbnail, so it loads the
 * full trace lazily after the dialog opens) — shown as a loading state
 * rather than delaying the dialog's appearance.
 */
@Composable
fun TripCardShareDialog(trip: Trip, points: List<LatLon>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullRoute by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val routeColor by Settings.routeColor.collectAsStateWithLifecycle()
    // isAppDarkTheme (Theme.kt, same package) resolves the app's own Theme
    // setting (AUTO/LIGHT/DARK/SYSTEM) — not bare isSystemInDarkTheme() —
    // which is what makes the card's "amber at night, blue at day" match
    // what TripDetailScreen/MapScreen/etc already show, per the spec.
    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val routeColorHex = RouteColors.hex(routeColor, isAppDarkTheme(themePref))

    val cardData = points?.let { pts -> remember(pts, fullRoute) { TripCardGeometry.build(trip, pts, full = fullRoute) } }
    val bitmap = cardData?.let { rememberTripCardBitmap(it, routeColorHex).value }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share trip card") },
        text = {
            Column {
                when {
                    points == null -> Text("Loading route…")
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> {
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

@Composable
private fun TripCardContent(cardData: CardData, routeColorHex: String) {
    val trip = cardData.trip
    val routeColor = Color(android.graphics.Color.parseColor(routeColorHex))
    Column(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        if (cardData.points.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val path = androidx.compose.ui.graphics.Path()
                    cardData.points.forEachIndexed { i, p ->
                        val offset = Offset(p.x * size.width, p.y * size.height)
                        if (i == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                    }
                    drawPath(path, routeColor, style = Stroke(width = 10f, cap = StrokeCap.Round))
                    cardData.destination?.let { d ->
                        drawCircle(routeColor, radius = 14f, center = Offset(d.x * size.width, d.y * size.height))
                    }
                }
            }
        }
        Text(trip.mode.label + " · " + formatDate(trip.startTimeMs), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.padding(top = 16.dp)) {
            statRow("Distance", formatDistanceKm(trip.distanceMeters))
            statRow("Duration", formatDurationHistory(trip.durationMs))
            statRow("Avg speed", formatSpeedKmh(trip.avgSpeedMps))
            statRow("Top speed", formatSpeedKmh(trip.topSpeedMps))
            cardData.peakLeanDeg?.let { statRow("Peak lean", formatLeanAngle(it)) }
            cardData.peakGForce?.let { statRow("Peak g", formatGForce(it)) }
        }
    }
}

@Composable
private fun statRow(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt
git commit -m "feat: android trip card compose renderer, captured via GraphicsLayer"
```

---

## Task 4: Wire the share action into TripDetailScreen

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt`

**Interfaces:**
- Consumes: `TripCardShareDialog` (Task 3) — the entire dialog, trim toggle, render and share
  live inside it now; this task only needs an entry point that opens it.

`TripDetailScreen` already loads `trace: List<TraceStore.TracePoint>?` (`TripDetailScreen.kt`,
`loadTripPoints`). This task adds a second toolbar `IconButton` beside the existing GPX one
that opens `TripCardShareDialog`. The existing GPX `IconButton` and its own
`scope.launch { ... }` / `exportError` pattern (`TripDetailScreen.kt:420-455`) are untouched —
the card share doesn't reuse that state, since `TripCardShareDialog` now owns its own error
display internally.

- [ ] **Step 1: Add the second toolbar button**

In `TripDetailScreen.kt`, alongside the existing GPX `IconButton` inside
`SubScreenTopBar(...) { ... }` (`TripDetailScreen.kt:432-459`):

```kotlin
var cardDialogOpen by remember { mutableStateOf(false) }

IconButton(
    enabled = points.isNotEmpty(),
    onClick = { cardDialogOpen = true },
) {
    Icon(Icons.Filled.Share, contentDescription = "Share trip card")
}
```

(Keep the existing GPX `IconButton` exactly as-is, right next to this one — two icons, not a
menu, since there are only ever two.)

Then, at the end of the `Scaffold` content (or anywhere else in this composable's body — it
renders as a dialog, not inline layout):

```kotlin
if (cardDialogOpen) {
    TripCardShareDialog(trip, points.map { it.at }, onDismiss = { cardDialogOpen = false })
}
```

No new import needed — `TripCardShareDialog` is in the same `com.jellemax.detour.ui` package
as `TripDetailScreen.kt`.

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification**

Use the `detour-adb` skill / `run` skill to launch the app, open a trip with a recorded trace
from history, tap the new share icon, confirm the dialog shows the trim caption, tap Share,
and confirm a PNG lands in the system share sheet. Toggle "Include full route" and repeat —
confirm the polyline visibly extends further toward the endpoints.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TripDetailScreen.kt
git commit -m "feat: share trip card action on the trip detail screen"
```

---

## Task 5: Wire the share action into HistoryScreen's ⋮ menu

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt`

**Interfaces:**
- Consumes: `TripCardShareDialog` (Task 3), plus `loadTripTrace` (already defined in this
  file, `HistoryScreen.kt:165`) since `HistoryEntry.thumbnail` is the capped 200-point
  *thumbnail*, not the full trace `TripCardShareDialog` needs.

`TripCard` (`HistoryScreen.kt:277`) currently has one `DropdownMenu` with "Change vehicle" and
"Delete". This task adds a "Share trip card" item above the divider. Unlike
`TripDetailScreen`, `TripCard` doesn't have the full trace loaded (only the thumbnail) — load
it lazily once the dialog opens; `TripCardShareDialog`'s `points == null` loading state (Task
3) is exactly what lets the dialog open immediately while that load is in flight.

- [ ] **Step 1: Add the menu item and dialog**

```kotlin
var cardDialogOpen by remember { mutableStateOf(false) }
var cardPoints by remember { mutableStateOf<List<LatLon>?>(null) }
```

alongside the existing `menuOpen`/`vehicleMenuOpen`/`confirmDelete` state in `TripCard`
(`HistoryScreen.kt:277-280`), and a new menu item before the existing `HorizontalDivider()`:

```kotlin
DropdownMenuItem(
    text = { Text("Share trip card") },
    onClick = { menuOpen = false; cardDialogOpen = true },
)
```

Then, alongside the existing `if (vehicleMenuOpen)` / `if (confirmDelete)` blocks at the
bottom of `TripCard`:

```kotlin
if (cardDialogOpen) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (cardPoints == null) {
            cardPoints = withContext(Dispatchers.IO) { loadTripTrace(context, trip) }
        }
    }
    TripCardShareDialog(
        trip, cardPoints,
        onDismiss = { cardDialogOpen = false; cardPoints = null },
    )
}
```

No new imports needed — `LocalContext`, `Dispatchers`, `withContext`, `loadTripTrace`, and
`LatLon` are all already available in this file (`loadTripTrace` is defined here,
`HistoryScreen.kt:165`; the rest are imported for `HistoryScreen`'s own use,
`HistoryScreen.kt:40,51,55,61-63`), and `TripCardShareDialog` is in the same
`com.jellemax.detour.ui` package.

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification**

Launch the app, open History, tap ⋮ on a trip row, tap "Share trip card", confirm the same
flow as Task 4 works from this entry point too — including a trip whose `thumbnail == null`
(no matched trace): the menu item should still open the dialog and the card should render
stats-only rather than erroring.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/HistoryScreen.kt
git commit -m "feat: share trip card action from the history row menu"
```

---

## Task 6: iOS card renderer (SwiftUI → ImageRenderer)

**Files:**
- Create: `iosApp/Detour/TripCardRenderer.swift`

**Interfaces:**
- Consumes: `CardData`/`TripCardGeometry` (Task 1, exposed to Swift automatically as part of
  the `DetourShared` framework — same way `Trip`/`TravelMode` already are, per
  `TripDetailScreen.swift`'s `import DetourShared`), `RouteColors` (same framework),
  `Format.swift`'s formatters.
- Produces (used by Task 7):
  ```swift
  func renderTripCardImage(cardData: CardData, routeColorHex: String) -> UIImage?
  func writeTripCardForShare(trip: Trip, image: UIImage) -> URL
  ```

- [ ] **Step 1: Write the renderer**

```swift
import SwiftUI
import DetourShared

/// The card's fixed pixel size — same 1080x1350 as Android, so a trip shared
/// from either platform produces a card a reader would call the same design.
private let cardWidth: CGFloat = 1080
private let cardHeight: CGFloat = 1350

/// Renders [cardData] to a PNG-ready UIImage using ImageRenderer, SwiftUI's
/// counterpart to Android's GraphicsLayer capture — same technique, same
/// output size, so the two renderers stay easy to compare side by side.
@MainActor
func renderTripCardImage(cardData: CardData, routeColorHex: String) -> UIImage? {
    let renderer = ImageRenderer(content:
        TripCardContent(cardData: cardData, routeColorHex: routeColorHex)
            .frame(width: cardWidth, height: cardHeight)
    )
    renderer.scale = 1 // fixed pixel size, not device-scaled — matches Android's density(1f).
    return renderer.uiImage
}

/// Writes the card into the same temp directory TripDetailScreen's GPX
/// export already uses for its ShareLink item.
func writeTripCardForShare(trip: Trip, image: UIImage) -> URL {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("detour-card-\(trip.startTimeMs).png")
    if let data = image.pngData() {
        try? data.write(to: url, options: .atomic)
    }
    return url
}

private struct TripCardContent: View {
    let cardData: CardData
    let routeColorHex: String

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if !cardData.points.isEmpty {
                GeometryReader { geo in
                    Path { path in
                        for (i, p) in cardData.points.enumerated() {
                            let point = CGPoint(x: CGFloat(p.x) * geo.size.width, y: CGFloat(p.y) * geo.size.height)
                            if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
                        }
                    }
                    .stroke(Color(hex: routeColorHex), style: StrokeStyle(lineWidth: 10, lineCap: .round))
                    if let d = cardData.destination {
                        Circle()
                            .fill(Color(hex: routeColorHex))
                            .frame(width: 28, height: 28)
                            .position(x: CGFloat(d.x) * geo.size.width, y: CGFloat(d.y) * geo.size.height)
                    }
                }
                .frame(maxHeight: .infinity)
            }
            Text("\(cardData.trip.mode.label) · \(formatDate(cardData.trip.startTimeMs))")
                .font(.title)
            VStack(alignment: .leading, spacing: 6) {
                statRow("Distance", formatDistanceKm(cardData.trip.distanceMeters))
                statRow("Duration", formatDurationHistory(cardData.trip.durationMs))
                statRow("Avg speed", formatSpeedKmh(cardData.trip.avgSpeedMps))
                statRow("Top speed", formatSpeedKmh(cardData.trip.topSpeedMps))
                if let lean = cardData.peakLeanDeg?.doubleValue {
                    statRow("Peak lean", formatLeanAngle(lean))
                }
                if let g = cardData.peakGForce?.doubleValue {
                    statRow("Peak g", formatGForce(g))
                }
            }
        }
        .padding(48)
        .frame(width: cardWidth, height: cardHeight, alignment: .topLeading)
        .background(Color(.systemBackground))
    }

    private func statRow(_ label: String, _ value: String) -> some View {
        Text("\(label): \(value)").font(.body)
    }
}

private extension Color {
    /// `#RRGGBB` → SwiftUI Color. RouteColors (shared) only ever hands back
    /// this exact format, so no alpha channel or shorthand form to handle.
    init(hex: String) {
        var s = hex; if s.hasPrefix("#") { s.removeFirst() }
        let v = UInt64(s, radix: 16) ?? 0
        self.init(
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255,
        )
    }
}
```

Note: `cardData.peakLeanDeg`/`peakGForce` are Kotlin `Double?`, which bridges to Swift as
`KotlinDouble?` — hence `.doubleValue` above, the same pattern `RouteRow` already uses for
`route.distanceMeters?.doubleValue` (`RoutesScreen.swift:242`).

- [ ] **Step 2: Build**

Build the iOS app (Xcode or `xcodebuild`, whichever this repo's `run`/CI scripts use for the
iOS target) and confirm it compiles.

- [ ] **Step 3: Commit**

```bash
git add iosApp/Detour/TripCardRenderer.swift
git commit -m "feat: ios trip card SwiftUI renderer via ImageRenderer"
```

---

## Task 7: Wire the share action into iOS TripDetailScreen

**Files:**
- Modify: `iosApp/Detour/TripDetailScreen.swift`

**Interfaces:**
- Consumes: `renderTripCardImage`, `writeTripCardForShare` (Task 6), `TripCardGeometry.build`,
  `RouteColors.hex` (shared, Task 1).

Mirrors Task 4/5: a second toolbar item beside the existing `ShareLink`, opening a confirm
dialog with the trim toggle, then sharing the rendered PNG via `ShareLink` (the existing GPX
`ShareLink` stays exactly as-is).

- [ ] **Step 1: Add the confirm dialog and second toolbar item**

```swift
@State private var cardDialogOpen = false
@State private var fullRoute = false
```

alongside the existing `@State private var route` (`TripDetailScreen.swift`, near line 15).

```swift
.toolbar {
    ShareLink(item: gpxURL()) { Image(systemName: "square.and.arrow.up") }
    Button { cardDialogOpen = true } label: { Image(systemName: "photo.badge.arrow.down") }
}
.confirmationDialog("Share trip card", isPresented: $cardDialogOpen, titleVisibility: .visible) {
    Button("Share trimmed (recommended)") { shareTripCard(full: false) }
    Button("Share full route") { shareTripCard(full: true) }
    Button("Cancel", role: .cancel) {}
} message: {
    Text("Endpoints are trimmed by default to avoid sharing your driveway.")
}
```

(A `.confirmationDialog` rather than a custom `AlertDialog`-equivalent sheet — it's the
platform-idiomatic way to offer 2 exclusive actions + cancel on iOS, and needs no extra state
for a checkbox since each button commits to one choice directly.)

`route` (loaded by `loadRoute()`) is already `[CLLocationCoordinate2D]`, but
`TripCardGeometry.build` wants `[LatLon]`. Rather than re-deriving `LatLon` from
`CLLocationCoordinate2D` (lossy: it's already been converted once from the original `LatLon`
in `TraceStore.parsePoints`), change `loadRoute()` to keep the `[LatLon]` alongside the
`[CLLocationCoordinate2D]` it currently produces:

- [ ] **Step 1a: Keep LatLon alongside the CLLocationCoordinate2D route**

Change:

```swift
@State private var route: [CLLocationCoordinate2D] = []
```

to:

```swift
@State private var route: [CLLocationCoordinate2D] = []
@State private var routeLatLon: [LatLon] = []
```

and in `loadRoute()` (`TripDetailScreen.swift`, `private func loadRoute()`), alongside the
existing `points` accumulation:

```swift
private func loadRoute() {
    var points: [CLLocationCoordinate2D] = []
    var latLons: [LatLon] = []
    for line in TraceStore.shared.rawLines() {
        guard let parsed = TraceStore.shared.parsePoints(line: line) else { continue }
        let inside = parsed.filter {
            $0.timeMs >= trip.startTimeMs && $0.timeMs <= trip.endTimeMs
        }
        points += inside.map {
            CLLocationCoordinate2D(latitude: $0.at.lat, longitude: $0.at.lon)
        }
        latLons += inside.map { $0.at }
    }
    route = points
    routeLatLon = latLons
}
```

Then `shareTripCard` becomes:

```swift
@State private var cardShareURL: URL?
@State private var cardShareSheetOpen = false

private func shareTripCard(full: Bool) {
    let cardData = TripCardGeometry.shared.build(trip: trip, points: routeLatLon, full: full)
    // SettingsValues (not Settings directly) is the synchronous snapshot reader
    // MapView.swift:65 already uses for this same value outside a StateFlow
    // observation — the same read this screen needs for a one-shot render.
    let routeColorHex = RouteColors.shared.hex(
        color: SettingsValues.shared.routeColor,
        darkTheme: UITraitCollection.current.userInterfaceStyle == .dark)
    guard let image = renderTripCardImage(cardData: cardData, routeColorHex: routeColorHex) else { return }
    cardShareURL = writeTripCardForShare(trip: trip, image: image)
    cardShareSheetOpen = true
}
```

and add the presentation, e.g. right after the `.confirmationDialog` block:

```swift
.sheet(isPresented: $cardShareSheetOpen) {
    if let url = cardShareURL {
        ActivityView(activityItems: [url])
    }
}
```

reusing `ActivityView` — it's already `private` to `RoutesScreen.swift`; make it
`internal` (drop `private`) there so `TripDetailScreen.swift` can use the same struct instead
of writing a second `UIViewControllerRepresentable` wrapper.

- [ ] **Step 2: Make ActivityView internal**

In `iosApp/Detour/RoutesScreen.swift`, change:

```swift
private struct ActivityView: UIViewControllerRepresentable {
```

to:

```swift
struct ActivityView: UIViewControllerRepresentable {
```

- [ ] **Step 3: Build**

Build the iOS app and confirm it compiles.

- [ ] **Step 4: Manual verification**

Run the iOS app (simulator or device), open a trip with a trace, tap the new toolbar photo
icon, choose "Share trimmed", confirm the system share sheet opens with a PNG. Repeat with
"Share full route" and confirm the two images differ (full route extends closer to the
endpoints). Open a trip with no matched trace and confirm the card still shares (stats-only),
not an error. Confirm the existing GPX `ShareLink` still works unchanged.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Detour/TripDetailScreen.swift iosApp/Detour/RoutesScreen.swift
git commit -m "feat: share trip card action on ios trip detail screen"
```

---

## Task 8: Cross-platform parity check

**Files:** none (verification only)

- [ ] **Step 1: Share the same trip from both platforms**

Pick one trip that exists on both a test Android device/emulator and an iOS
simulator/device with matching trace data (or record the same short trip on each, or copy a
trip's JSON between test devices if the sync mechanism supports it — whatever this repo's
existing cross-platform QA approach is for comparing a rendered artifact). Generate a trimmed
card from each and place them side by side.

- [ ] **Step 2: Confirm parity per the spec's acceptance criteria**

Same stat set for the same mode, same route line color for the same `RouteColors` setting
and theme, same trim behavior, same aspect ratio. Minor font-rendering differences between
Material and SF fonts are expected and fine — the spec's bar is "a reader would call the
same design," not pixel-identical.

- [ ] **Step 3: File a follow-up issue for anything that doesn't match**

If a real layout or content difference turns up (not just font rendering), it isn't blocking
for the two per-platform tasks above (each already met the issue's acceptance criteria
independently) — file it as its own issue rather than reopening this plan.
