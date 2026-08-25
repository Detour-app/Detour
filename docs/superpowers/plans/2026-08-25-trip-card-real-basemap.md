# Trip card real basemap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the trip card's abstract route line with a real MapLibre map snapshot (real streets/terrain) behind the route, on all three card layouts (Standard, Minimal, Poster).

**Architecture:** MapLibre's `MapSnapshotter` (already a dependency, unused until now) renders an offscreen bitmap from the same OpenFreeMap style/tiles the live map uses, with the route baked in as a native GeoJSON line layer inside the style so it's pixel-aligned to real streets. The destination dot is placed afterward via the snapshot's own `pixelForLatLng`. A `ColorMatrix` desaturation filter plus a translucent scrim give the "muted" look and keep card text legible over a photographic background.

**Tech Stack:** Kotlin Multiplatform (shared/commonMain), Jetpack Compose, MapLibre Android SDK 11.8.0 (`org.maplibre.gl:android-sdk`), Kotlin `kotlin.test` for the shared unit test.

**Spec:** `docs/superpowers/specs/2026-08-25-trip-card-real-basemap-design.md`

## Global Constraints

- iOS (`TripCardRenderer.swift`) is untouched — this plan is Android-only.
- The 500 m privacy trim is unchanged; the map's region is derived from the same trimmed points
  the line already used, never the full untrimmed trace.
- `CardData`'s existing fields (`points`, `destination`) keep their exact current meaning — only
  an additive field is added, so iOS keeps compiling against the same shared module.
- No new hosted/forked map style — the muted look is a post-process color filter on the rendered
  snapshot.
- `MapLibre.getInstance(context)` is already called once in `MainActivity.onCreate` — no new SDK
  init code is needed; the snapshot composable can assume it's already initialized by the time a
  trip detail/history screen (which requires `MainActivity` to be running) opens the share dialog.

---

### Task 1: `CardData.trimmedLatLon` (shared)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripCardTest.kt`

**Interfaces:**
- Produces: `CardData.trimmedLatLon: List<LatLon>` — the same trimmed/kept points `points`
  (normalized `CardPoint`s) already comes from, but as raw `LatLon` before normalization. Same
  order, same length, same trim window as `points`. Task 2 consumes this to build the
  `MapSnapshotter` region and the GeoJSON route line.

- [ ] **Step 1: Write the failing tests**

Add to `shared/src/commonTest/kotlin/com/jellemax/detour/data/TripCardTest.kt`, after
`fullSkipsTrimming`:

```kotlin
    @Test
    fun trimmedLatLonMatchesThePolylinePointCount() {
        val points = straightLinePoints()
        val full = TripCardGeometry.build(trip(TravelMode.CAR), points, full = true)
        val trimmed = TripCardGeometry.build(trip(TravelMode.CAR), points, full = false)
        assertEquals(full.points.size, full.trimmedLatLon.size)
        assertEquals(trimmed.points.size, trimmed.trimmedLatLon.size)
        assertTrue(trimmed.trimmedLatLon.size < full.trimmedLatLon.size)
    }

    @Test
    fun trimmedLatLonHoldsTheActualKeptCoordinates() {
        val points = straightLinePoints()
        val full = TripCardGeometry.build(trip(TravelMode.CAR), points, full = true)
        assertEquals(points.first(), full.trimmedLatLon.first())
        assertEquals(points.last(), full.trimmedLatLon.last())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.TripCardTest"`
Expected: compile error — `trimmedLatLon` is unresolved on `CardData`.

- [ ] **Step 3: Add the field**

In `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt`, change the `CardData`
class:

```kotlin
data class CardData(
    val trip: Trip,
    val points: List<CardPoint>,
    val destination: CardPoint?,
    /** Same trimmed/kept span as [points], before normalization — the raw
     *  coordinates a map snapshot's region and route line are built from.
     *  Empty exactly when [points] is empty. */
    val trimmedLatLon: List<LatLon>,
) {
    val peakLeanDeg: Double? get() = if (trip.mode.tracksLean) trip.maxLeanAngleDeg else null
    val peakGForce: Double? get() = if (trip.mode.tracksGForce) trip.maxGForce else null
}
```

Then in `TripCardGeometry.build`, thread `keptPoints` (already computed, currently only used to
derive `normalizedPoints`) through to the new field. Change both `return CardData(...)` call
sites:

```kotlin
        if (points.isEmpty()) return CardData(trip, emptyList(), null, emptyList())
```

and (further down, the "no untrimmed middle left" / empty-`kept` early returns) — there are two
more `return CardData(trip, emptyList(), null)` lines earlier in the function; update all three
to `return CardData(trip, emptyList(), null, emptyList())`. Then the final return:

```kotlin
        return CardData(trip, normalizedPoints, destination, keptPoints)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.jellemax.detour.data.TripCardTest"`
Expected: PASS, all tests in the class including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt shared/src/commonTest/kotlin/com/jellemax/detour/data/TripCardTest.kt
git commit -m "$(cat <<'EOF'
feat: expose CardData.trimmedLatLon for the trip card's map snapshot

Additive field alongside the existing normalized points — the raw,
already-trimmed coordinates a MapLibre snapshot's region and route
line will be built from, without re-deriving the trim window.
EOF
)"
```

---

### Task 2: Map snapshot pipeline + Standard layout

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt`

**Interfaces:**
- Consumes: `CardData.trimmedLatLon: List<LatLon>` (Task 1), `Trip.destinationLat/destinationLon:
  Double?` (existing), `openFreeMapStyleUrl(dark: Boolean): String` (existing,
  `MapLibreMap.kt:54`), `CardData.destination: CardPoint?` (existing — used only to decide
  *whether* to place a destination dot, not for its coordinates).
- Produces:
  - `data class TripCardMapSnapshot(val image: ImageBitmap, val raw: MapSnapshot)`
  - `@Composable fun rememberTripCardMapSnapshot(cardData: CardData, dark: Boolean, routeColorHex: String, widthPx: Int, heightPx: Int): State<TripCardMapSnapshot?>`
    — `null` while loading/on error; callers treat `null` as "show the loading placeholder."
  - `fun plainAttribution(snapshot: MapSnapshot): String` — HTML-stripped attribution text.
  Tasks 3 and 4 call `rememberTripCardMapSnapshot` and `plainAttribution` with their own
  width/height and layout.

- [ ] **Step 1: Add the new imports**

At the top of `TripCardRenderer.kt`, add:

```kotlin
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
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
```

(`LocalContext` is already imported — `TripCardShareDialog` uses it. None of this task's new code
spells out the shared `LatLon` type by name — `cardData.trimmedLatLon.map { it.lat }` etc. infer
it — so it doesn't need its own import. Skip any of the above that turn out to already be present
rather than duplicating.)

- [ ] **Step 2: Write the snapshot data holder and composable**

Add near the top of the file, after the `CardLayout` enum:

```kotlin
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
        val bounds = LatLngBounds(
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
```

`mutableStateOf`/`remember`/`State` are already imported (used by `rememberTripCardBitmap`
above) — only `DisposableEffect` (added in Step 1) is new.

- [ ] **Step 3: Draw the destination dot on top of the snapshot**

Add this composable near `RouteCanvas`:

```kotlin
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
```

- [ ] **Step 4: Wire it into `StandardCardContent`**

Replace the current `RouteCanvas` call inside `StandardCardContent`'s map `Box` (currently
`Box(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp, bottom = 28.dp)) { RouteCanvas(...) }`)
with a fixed-height box showing the real snapshot:

```kotlin
        // Declared unconditionally (not inside the `if` below) so the
        // footer at the bottom of this function can also read its
        // attribution — rememberTripCardMapSnapshot itself no-ops (state
        // stays null forever, no fetch) when cardData.trimmedLatLon is
        // empty, so this is cheap even for a trip with no trace.
        val mapSnapshot = rememberTripCardMapSnapshot(
            cardData, dark = textColor == CARD_TEXT_DARK, routeColorHex = routeColorHex,
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
```

This uses `routeColorHex` as a `String` — `TripCardContent` already holds it (it's a direct
parameter, the same one used to compute `routeColor: Color` today), it just isn't threaded down
into `StandardCardContent` yet. Add a `routeColorHex: String` parameter to `StandardCardContent`'s
signature (alongside the existing `routeColor: Color` — cheaper and more direct than round-tripping
`Color` back to a hex string) and update `TripCardContent`'s dispatch line from
`CardLayout.STANDARD -> StandardCardContent(cardData, routeColor, backgroundTop, textColor,
mutedColor, trimmed)` to `CardLayout.STANDARD -> StandardCardContent(cardData, routeColor,
routeColorHex, backgroundTop, textColor, mutedColor, trimmed)`.

Add `import androidx.compose.ui.layout.ContentScale` and
`import androidx.compose.foundation.layout.matchParentSize` to the file's imports.

Also add the attribution text to the footer for Standard. Change `CardFooter`'s signature to
accept an optional attribution string, and `StandardCardContent`'s call site to pass it:

```kotlin
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
```

And in `StandardCardContent`, pass the hoisted `mapSnapshot`'s attribution through: change the
`CardFooter(routeColor, textColor, Modifier.padding(top = 28.dp))` call to `CardFooter(routeColor,
textColor, mapSnapshot?.let { plainAttribution(it.raw) }, Modifier.padding(top = 28.dp))`.

- [ ] **Step 5: Build and install**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: no errors. Fix any import/signature mismatches against the exact current file content
before moving on — this step is written against the file as it stood after the layout-picker
work earlier this session; re-read `StandardCardContent`'s current body first if anything doesn't
line up.

Run: `./gradlew :app:installDebug -q`

- [ ] **Step 6: Manual on-device verification**

Follow the `detour-adb` skill's flow already used earlier this session: open a trip with a trace
(`adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity --el
open_trip_start_ms <ms>`), tap "Share trip card", screenshot the dialog
(`adb exec-out screencap -p > out.png`). Confirm: the Standard layout preview shows real
streets/terrain (not a flat background), the route follows real roads, the destination dot (if
the trip has one) sits on the route rather than floating off it, and the attribution text is
present and legible in the footer. Check both Light and Dark toggle states.

If the map area looks visually too small/cramped or has excess empty text-stack space below it
(700dp was picked from arithmetic, not a live render), adjust the `height(700.dp)` value and
re-verify — this is expected first-pass tuning, not a sign of a bug.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt
git commit -m "$(cat <<'EOF'
feat: real MapLibre basemap behind the Standard trip card layout

Route drawn as a native GeoJSON line layer inside the snapshot's
style so it's pixel-aligned to the real streets MapLibre renders,
rather than a separately-drawn line that could drift from the
snapshot's own projection. Muted via a desaturation filter + a flat
scrim; OSM attribution pulled from the snapshot itself.
EOF
)"
```

---

### Task 3: Poster layout

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt`

**Interfaces:**
- Consumes: `rememberTripCardMapSnapshot`, `TripCardMapSnapshot`, `plainAttribution`,
  `mapMutedFilter`, `routeColorHex` (all from Task 2), `MapSnapshotDestinationDot` (Task 2).

- [ ] **Step 1: Wire the snapshot into `PosterCardContent`**

Same pattern as Task 2 Step 4, but Poster's text footprint is much smaller (a title line, an
optional trim caption, one condensed stat line, the footer) so it can afford a taller map. Replace
the current `RouteCanvas` call inside `PosterCardContent`'s
`Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp))` with:

```kotlin
        // Declared unconditionally, same reasoning as StandardCardContent
        // (Task 2 Step 4) — the footer below also reads its attribution,
        // and the composable itself no-ops for a trace-less trip.
        val mapSnapshot = rememberTripCardMapSnapshot(
            cardData, dark = textColor == CARD_TEXT_DARK, routeColorHex = routeColorHex,
            widthPx = 1080 - 80, heightPx = 900,
        ).value
        if (cardData.points.isNotEmpty()) {
            // Same fixed-height reasoning as Standard (Task 2) — Poster's
            // own text stack is smaller (title + optional caption + one
            // stat line + footer), so it can afford more map: 900dp
            // starting point, tune on-device.
            Box(Modifier.fillMaxWidth().height(900.dp).padding(vertical = 20.dp)) {
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
        } else {
            Spacer(Modifier.weight(1f))
        }
```

(The existing `else -> Spacer(Modifier.weight(1f))` branch for the no-trace case stays as-is —
copy it forward unchanged from the current function body.)

Same `routeColorHex` threading as Task 2: add a `routeColorHex: String` parameter to
`PosterCardContent`'s signature and update `TripCardContent`'s dispatch line from
`CardLayout.POSTER -> PosterCardContent(cardData, routeColor, backgroundTop, textColor,
mutedColor, trimmed)` to `CardLayout.POSTER -> PosterCardContent(cardData, routeColor,
routeColorHex, backgroundTop, textColor, mutedColor, trimmed)`.

Pass the hoisted `mapSnapshot`'s attribution to the existing `CardFooter(routeColor, textColor,
Modifier.padding(top = 24.dp))` call: `CardFooter(routeColor, textColor, mapSnapshot?.let {
plainAttribution(it.raw) }, Modifier.padding(top = 24.dp))`.

- [ ] **Step 2: Build and install**

Run: `./gradlew :app:compileDebugKotlin -q` then `./gradlew :app:installDebug -q`.

- [ ] **Step 3: Manual on-device verification**

Same flow as Task 2 Step 6, switching the layout picker to "Poster." Confirm the map fills most of
the card, the title/caption/stat-line/footer sit correctly around it, and attribution is present.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt
git commit -m "feat: real MapLibre basemap behind the Poster trip card layout"
```

---

### Task 4: Minimal layout (full-bleed) + final cross-layout verification

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt`

**Interfaces:**
- Consumes: same as Task 3.

- [ ] **Step 1: Wire the snapshot into `MinimalCardContent` as a full-bleed background**

`MinimalCardContent` currently renders inside `TripCardContent`'s outer `Box`, which already
paints the flat gradient as its background — that stays as the fallback/loading state. Add the
real map on top of it, full-bleed, before the existing `Column` content.

This adds two new parameters to `MinimalCardContent` that its current signature doesn't have:
`backgroundTop: Color` (needed for `MapSnapshotDestinationDot`'s halo) and `routeColorHex: String`
(needed by `rememberTripCardMapSnapshot`, same reasoning as Tasks 2-3 — cheaper to thread the hex
string `TripCardContent` already holds than round-trip `routeColor: Color` back to one). Standard
and Poster's dispatch calls already pass `backgroundTop` (and, after Tasks 2-3, `routeColorHex`),
but Minimal's doesn't yet (`TripCardContent`'s `when (layout)` block currently has
`CardLayout.MINIMAL -> MinimalCardContent(cardData, routeColor, textColor, mutedColor)`). Change
that line to `CardLayout.MINIMAL -> MinimalCardContent(cardData, routeColor, routeColorHex,
backgroundTop, textColor, mutedColor)` as part of this step.

```kotlin
@Composable
private fun MinimalCardContent(
    cardData: CardData, routeColor: Color, routeColorHex: String, backgroundTop: Color, textColor: Color, mutedColor: Color,
) {
    val trip = cardData.trip
    // Unconditional call, same reasoning as Standard/Poster (Tasks 2-3) —
    // rememberTripCardMapSnapshot no-ops (stays null, no fetch) when
    // cardData.trimmedLatLon is empty, so this is safe for a trace-less trip.
    val mapSnapshot = rememberTripCardMapSnapshot(
        cardData, dark = textColor == CARD_TEXT_DARK, routeColorHex = routeColorHex,
        widthPx = CARD_WIDTH_PX, heightPx = CARD_HEIGHT_PX,
    ).value

    if (mapSnapshot != null) {
        Image(
            bitmap = mapSnapshot.image, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            colorFilter = mapMutedFilter,
        )
        // Stronger than Standard/Poster's flat scrim: Minimal's stats sit
        // directly on top of the map rather than beside/below a smaller
        // map box, so they need a real gradient to stay legible against
        // whatever brightness the underlying street/terrain happens to be.
        Box(
            Modifier.fillMaxSize().background(
                // Fractional colorStops (0..1 relative to the Box's own
                // bounds), not absolute startY/endY pixels — this way it's
                // correct regardless of the box's actual measured size.
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.4f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.75f),
                ),
            ),
        )
        MapSnapshotDestinationDot(cardData, mapSnapshot, routeColor, backgroundTop, Modifier.fillMaxSize())
    }

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
        CardFooter(routeColor, textColor, mapSnapshot?.let { plainAttribution(it.raw) })
    }
}
```

The gradient's transparent zone (the top 40%, per the `0.4f to Color.Transparent` stop) is a
starting value — `CardEyebrow` sits directly on the map there with no scrim, so if it's hard to
read on a bright patch of map on-device, either widen the transparent-to-black transition or add
a third, lighter stop near the top. Tune during Step 3's manual verification, don't
preemptively over-build it.

Remember to update the dispatch line in `TripCardContent` as noted above — this step isn't
complete until `CardLayout.MINIMAL -> MinimalCardContent(cardData, routeColor, routeColorHex,
backgroundTop, textColor, mutedColor)` replaces the current `CardLayout.MINIMAL ->
MinimalCardContent(cardData, routeColor, textColor, mutedColor)` line.

- [ ] **Step 2: Build and install**

Run: `./gradlew :app:compileDebugKotlin -q` then `./gradlew :app:installDebug -q`.

- [ ] **Step 3: Manual on-device verification — Minimal**

Same flow as Task 2 Step 6, switching to "Minimal." Confirm the map fills the whole card, the
gradient scrim keeps the stat numbers and footer readable against it, and the destination dot (if
present) is visible against the scrim.

- [ ] **Step 4: Final cross-layout verification**

Using the same running dialog (no rebuild needed), click through all three layout chips and both
Light/Dark toggle states for one trip with a trace, screenshotting each
(`adb exec-out screencap -p`). For at least one combination, tap Share and pull the exported PNG
via `run-as` + `cat cache/shared/<filename>.png` (same method used earlier this session) to
confirm the full-resolution export matches what the dialog preview showed — this is the check that
caught the earlier stale-tap bug in this session's manual testing, so repeat it rather than
trusting the preview alone.

Confirm across all six combinations: the route follows real streets (not floating disconnected
from them), attribution text is present and legible in both light and dark, and no layout clips
or overlaps text.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/TripCardRenderer.kt
git commit -m "$(cat <<'EOF'
feat: real MapLibre basemap behind the Minimal trip card layout

Full-bleed snapshot as the card's background with a dark gradient
scrim so the stat overlay stays legible. Completes the real-basemap
work across all three layouts (Standard, Poster, Minimal).
EOF
)"
```

- [ ] **Step 6: Version bump**

Per `CLAUDE.md`: new feature, backward compatible. Bump `app/build.gradle.kts`'s `versionName`
from whatever it holds after the layout-picker work lands (`1.78` per this session's prior work,
adjust if that's changed since) to the next minor — same "fold into the feature bump" pattern
already used earlier this session for the layout picker. Commit alongside or as its own small
commit per however the rest of this session's version-bump commits were handled.
