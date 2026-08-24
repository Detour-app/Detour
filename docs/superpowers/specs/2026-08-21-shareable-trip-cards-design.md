# Shareable trip cards

Closes maxke24/Detour#16.

## Scope

In scope, per the issue:

- Render one finished trip to a PNG: route shape, headline stats, mode, date.
- A **Share** action on `TripDetailScreen` and on `HistoryScreen`'s ⋮ menu, alongside — not
  instead of — the existing `.gpx` export.
- Android and iOS, same card content and layout on both.
- Endpoint trimming (500 m, fixed) on by default, with a one-tap "include full route" toggle.

Out of scope, resolving the issue's three open questions:

- **Lifetime stats / badges on the card** — per-trip only. `Badges.kt` / `RiderStats` are
  lifetime state; mixing them into a per-trip artifact is a separate feature.
- **Route cards (`RoutesScreen`, planned-but-undriven)** — trips only. A route has no drawn
  trace or real stats, so its card would need a different layout; not this issue.
- **Trim distance: fixed vs following the fog reveal-radius setting** — fixed 500 m. The fog
  radius is a user-tunable privacy setting for a different feature (map fog-of-war); linking the
  two couples two independently-changeable settings for no benefit the issue asks for.

Also out of scope because the underlying mode no longer exists on this branch: WALK/BIKE.
`TravelMode` (`shared/src/commonMain/kotlin/com/jellemax/detour/data/TravelMode.kt`) now has
only `MOTO` and `CAR` (dropped in `32dc241`, `b67b069`, `27b9933`, `d326002`, `3242e2a`,
`fabc714` on this same branch). The issue's "walk and bike record neither, card reflows to four
stats" case is dead code for a mode that can't be recorded. The card's stat set falls out
directly from `TravelMode.tracksLean` / `tracksGForce`, which already only ever produce the
MOTO (lean + g) and CAR (g only) combinations — no third "neither" layout is written.

## Architecture

Three approaches considered:

1. **Chosen.** Shared `CardGeometry`/`CardData` in `shared/commonMain` (bounding-box fit,
   normalize, 500 m endpoint trim, extending the existing `matchTripPoints` reassembly logic).
   Each platform renders declaratively and captures to an image: Compose `Canvas` →
   `ImageBitmap` → `Bitmap` on Android, a SwiftUI view → `ImageRenderer` on iOS. Same tier of
   abstraction on both platforms, and matches how `HistoryScreen.kt:271` already draws
   thumbnail polylines in Compose.
2. Fully native drawing (`android.graphics.Canvas` directly, iOS Core Graphics directly) — more
   manual text/layout code on both sides for no benefit; rejected.
3. Server-side render (send trip data to a backend, get a PNG back) — means uploading location
   data off-device to render an image, which directly contradicts the issue's own privacy
   requirement ("this never leaves the device unless you say so"). Rejected outright.

## Data model (shared/commonMain)

New file, `shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt`:

```kotlin
data class CardPoint(val x: Float, val y: Float) // normalized 0..1, card-space

data class CardData(
    val trip: Trip,
    /** Normalized, trimmed polyline in card space. Empty when the trip has no
     *  matched trace (HistoryEntry.thumbnail == null) — renderer draws a
     *  stats-only layout in that case, not an error. */
    val points: List<CardPoint>,
    /** Same trim rule applied to the destination pin; null if trimmed away
     *  or the trip has no destination. */
    val destination: CardPoint?,
) {
    val peakLeanDeg: Double? get() = if (trip.mode.tracksLean) trip.maxLeanAngleDeg else null
    val peakGForce: Double? get() = if (trip.mode.tracksGForce) trip.maxGForce else null
}

object TripCardGeometry {
    const val TRIM_METERS = 500.0

    /** Drops [TRIM_METERS] of trace from each end (by cumulative distance, not
     *  point count), fits the remainder to a 0..1 bounding box preserving
     *  aspect ratio, and maps the destination through the same trim+fit.
     *  [full] = true skips the trim (the "include full route" toggle). */
    fun build(trip: Trip, points: List<LatLon>, full: Boolean = false): CardData
}
```

`build` reuses `matchTripPoints`'s already-reassembled point list (callers pass in what
`loadTripTrace` already produces) rather than re-reading `TraceStore` itself — geometry math
only, no I/O, so it stays a plain function in `commonMain` with no platform dependency.

## Rendering

**Android** — new `TripCardRenderer.kt` in `app/src/main/java/com/jellemax/detour/ui/`. No
existing code in this app captures a Compose composable to a bitmap —
`replayMarkerBitmap` (`TripDetailScreen.kt:173`) is the only current `Bitmap.createBitmap`
use, and it draws directly with `android.graphics.Canvas`, not Compose — but the project's
Compose BOM (`2024.09.02`, `app/build.gradle.kts:172` → Compose UI 1.7) does carry
`rememberGraphicsLayer()` / `GraphicsLayer.toImageBitmap()`. A `@Composable` lays out the
polyline (`Canvas`, same `drawPath` approach as `HistoryScreen.kt:271`'s thumbnail) and stat
text as real Material `Text` into a fixed 1080×1350 `Box`, wrapped in
`Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }`,
and captured with `layer.toImageBitmap()` — real Compose composition and typography, no
offscreen `ComposeView` or screen attachment needed. Route line color reads `RouteColors` / the
active theme the same way the map does, at render time — a card made at night is amber, one
made at noon is blue, matching the issue's "fine but deliberate" call.

**iOS** — new `TripCardRenderer.swift` in `iosApp/Detour/`. A SwiftUI view with the same layout
(shared proportions, not shared code — SwiftUI and Compose don't share layout code, only the
`CardData` numbers feeding both), captured via `ImageRenderer(content:).uiImage`.

**Layout** (both): 1080×1350 portrait. Route shape (or, if `points` is empty, nothing — the
stats section grows to fill the card) in the upper 2/3, stat grid in the lower third: distance,
duration, avg speed, top speed, date, mode always present; peak lean / peak g as a 5th and 6th
stat only when non-null. When trimmed, a small caption under the route: "Route trimmed near
start/end for privacy."

## Data flow / entry points

`TripDetailScreen` and `HistoryScreen`'s TripCard ⋮ menu both already load `Trip` +
`loadTripTrace`/`loadTripPoints`. Each gets a **second** icon/menu entry, "Share trip card",
next to the existing "Export GPX" — not merged into one share intent, since Android
`ACTION_SEND` carries one stream cleanly and `SEND_MULTIPLE` isn't reliably handled by chat
apps. GPX export (`shareGpxIntent`, `TripDetailScreen.kt:197`; the `RoutesScreen.kt:70`
equivalent is untouched — routes are out of scope) stays exactly as it is today.

Tapping "Share trip card" opens a small confirmation dialog: a preview-less toggle "Include
full route" (default off — trimmed) and a Share button. On Share:

1. `TripCardGeometry.build(trip, points, full = toggleState)` → `CardData`.
2. Render to bitmap/image (platform renderer above).
3. Write PNG into the same FileProvider cache dir + cleanup `Gpx.kt:63` (`Gpx.writeForShare`)
   already uses, under a new `TripCard.writeForShare(context, trip, bitmap): Uri` — same
   `res/xml/file_paths.xml` scope, no new provider. iOS writes to the same temp-file location
   `ShareLink`/`UIActivityViewController` already read from.
4. Hand the `Uri` to a `ACTION_SEND` intent with `type = "image/png"` (Android) /
   `ShareLink`/`UIActivityViewController` (iOS) — same chooser pattern as GPX export, just a
   different mime type and payload.

No trace (`HistoryEntry.thumbnail == null` / `loadTripPoints` empty): `CardData.points` is
empty, renderer draws stats-only. The confirmation dialog's "include full route" toggle is
hidden in this case (nothing to trim).

## Error handling

Mirrors the existing GPX path (`TripDetailScreen.kt:420-455`): the same `exportError` state
surfaces a failed render or a missing share-target app (`ActivityNotFoundException`). Bitmap
generation itself has no expected failure mode (pure in-memory draw over already-loaded data) —
only the file write and the chooser launch are wrapped.

## Testing

- Unit tests on `TripCardGeometry.build` (shared, `commonTest`): bounding-box fit math, 500 m
  trim removes points within that cumulative distance of each end and no more, destination pin
  trimmed consistently with the trace, empty-points input produces empty output rather than
  throwing, `full = true` skips trimming.
- `CardData.peakLeanDeg` / `peakGForce` nullability: a `MOTO` trip exposes both, a `CAR` trip
  exposes only g, matching `TravelMode.tracksLean`/`tracksGForce` — this makes the "no third
  layout for a mode that tracks neither" decision above verifiable rather than just asserted.
- Rendered pixel output is not unit-tested on either platform — manual verification that a moto
  trip, a car trip, and a no-trace trip each produce the expected layout, on both platforms, per
  the issue's acceptance criteria.

## Acceptance (from the issue, unchanged)

- Sharing a moto trip produces a PNG: route shape, distance, duration, avg/top speed, peak lean,
  peak g.
- Sharing a car trip produces the same card with peak g only (no lean slot).
- Sharing a trip with no stored trace produces a stats-only card, not an error.
- Endpoints trimmed by default; untrimmed reachable in one tap (the confirmation dialog's
  toggle).
- Android and iOS cards read as the same design for the same trip.
- `.gpx` sharing still works and is still offered, unchanged, alongside the new card share.
