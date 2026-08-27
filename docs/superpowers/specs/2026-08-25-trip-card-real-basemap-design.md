# Trip card real basemap

Follow-up to `2026-08-21-shareable-trip-cards-design.md` (the original card renderer) and the
Standard/Minimal/Poster layout picker added on top of it this session. The abstract-line route
("just a line on the screen") doesn't let anyone recognize where a ride happened; this replaces
it with a real map snapshot the route is drawn onto.

## Scope

In scope:

- Standard, Minimal, and Poster layouts (`TripCardRenderer.kt`, Android only) each get a real
  MapLibre basemap snapshot behind the route, in place of today's flat gradient + abstract
  `drawPath` line.
- Muted/desaturated map treatment so the route color still reads as the focal element.
- Required OpenStreetMap attribution baked into the exported image.
- One additive field on the shared `CardData` (raw trimmed lat/lon points) so the map's region
  matches the existing 500 m privacy trim exactly.

Out of scope:

- iOS (`TripCardRenderer.swift`). It keeps drawing the abstract line; a real-map port there is a
  separate follow-up, not attempted this session.
- Any change to the trim distance, the trim toggle, or which stats appear per mode — untouched.
- A custom-authored vector map style. The "muted" look is a post-process color filter on the
  rendered snapshot, not a forked/hosted style (see Architecture).

## Architecture

Three approaches considered:

1. **Chosen.** MapLibre's `MapSnapshotter` (`org.maplibre.gl:android-sdk:11.8.0`, already a
   dependency — used elsewhere in the app for the live map, unused so far for offscreen
   snapshots). Verified against the actual SDK classes in the AAR
   (`MapSnapshotter$Options.withStyleBuilder(Style.Builder)`, `.withRegion(LatLngBounds)`,
   `MapSnapshot.pixelForLatLng(LatLng): PointF`, `MapSnapshot.attributions: String[]` all exist
   as needed — this was the one unverified assumption going in). The route is added as a native
   GeoJSON `LineLayer` inside the `Style.Builder` *before* snapshotting, so MapLibre renders it
   in the same Mercator projection as the real streets — pixel-perfect aligned, not drawn
   separately by us and hoping the projections match.
2. A static raster-map HTTP API (fetch one PNG by URL). Rejected: OpenFreeMap (the app's
   existing, keyless, free tile provider — see `MapLibreMap.kt:51`) serves vector tiles only, no
   raster-snapshot endpoint. Getting one would mean standing up new self-hosted infra or paying
   for a third-party static-maps API (Mapbox Static, Google Static Maps) — worse on cost, ToS,
   and consistency with the rest of the app for no benefit over the SDK feature built for exactly
   this.
3. Fetch and stitch raw XYZ tiles ourselves, skip MapLibre's snapshotter entirely. Rejected:
   reinvents projection math, tile compositing, and label rendering that the SDK already does
   correctly; more code for a worse result.

## Data model (shared/commonMain)

`shared/src/commonMain/kotlin/com/jellemax/detour/data/TripCard.kt` — one additive field, nothing
removed or renamed (iOS keeps compiling and behaving exactly as today):

```kotlin
data class CardData(
    val trip: Trip,
    val points: List<CardPoint>,       // unchanged: normalized, trimmed, card-space
    val destination: CardPoint?,       // unchanged
    val trimmedLatLon: List<LatLon>,   // NEW: raw trimmed points, for the snapshot's region
)
```

`TripCardGeometry.build` already computes `keptPoints` (the trimmed, pre-normalization list) —
`trimmedLatLon` is that list, returned alongside the existing normalized output instead of
discarded.

## Rendering (Android, `TripCardRenderer.kt`)

New composable:

```kotlin
@Composable
fun rememberTripCardMapSnapshot(
    cardData: CardData, dark: Boolean, routeColorHex: String, widthPx: Int, heightPx: Int,
): State<TripCardMapSnapshot?>  // wraps the MapSnapshot bitmap + its pixelForLatLng + attributions
```

Built on `LaunchedEffect`/`produceState`, not the `rememberGraphicsLayer` offscreen-compose trick
`rememberTripCardBitmap` uses — `MapSnapshotter` is a plain async SDK call against a `Context`,
no view attachment or composition needed. `DisposableEffect` calls `.cancel()` on the
`MapSnapshotter` if the composable leaves before the callback fires (dialog dismissed mid-fetch).

Per request:

1. `LatLngBounds` from `cardData.trimmedLatLon`, padded ~20% on each side for surrounding-street
   context.
2. `Style.Builder().fromUri(openFreeMapStyleUrl(dark))` — same day/night styles `MapScreen` /
   `TripDetailScreen` already use — plus a `GeoJsonSource` (the trimmed points as a LineString)
   and a `LineLayer` styled to `routeColorHex`, matching today's line width proportions.
3. `MapSnapshotter.Options(widthPx, heightPx).withStyleBuilder(...).withRegion(bounds)`, `start()`.
4. On success: the destination dot is drawn as a Compose overlay on top of the returned bitmap,
   positioned via `snapshot.pixelForLatLng(destinationLatLng)` — exact match to the real
   projection, no separate coordinate math to keep in sync. Same halo+dot styling as today.
5. Muted look: a `ColorMatrix` `ColorFilter` (desaturate + slight darken, values tuned on-device)
   applied to the `Image` composable drawing the snapshot bitmap — not a new style asset.
6. Attribution: `snapshot.attributions` (HTML-ish strings from the style's TileJSON) stripped of
   markup and rendered as small text near the `DETOUR` wordmark in the footer. Using the SDK's
   own attribution string rather than a hardcoded "© OpenStreetMap contributors" keeps it correct
   if the underlying style/provider ever changes.

Per layout:

- **Standard / Poster**: the map box changes from `weight(1f)` back to a **fixed height** (tuned
  on-device, ballpark 600–750dp) so the snapshot can be requested at a known pixel size before
  layout runs. This is a deliberate walk-back of the most recent "map bigger, fill leftover
  space" change — that ask was solving *empty canvas* around a thin abstract line; a real map has
  no empty-space problem, so a generous fixed size reads as "big" without needing the box
  measured before an async network fetch can be sized. Flagged in chat and accepted.
- **Minimal**: full-bleed snapshot (`widthPx`/`heightPx` = the full 1080×1350 card) as the entire
  background; stats overlaid on a dark linear-gradient scrim at the bottom for legibility over
  variable map brightness.
- Each layout requests its own correctly-sized snapshot rather than one shared bitmap cropped
  three ways (simpler, and avoids clipping the route when a shorter box crops a taller shared
  image). Switching layouts in the picker re-fetches; MapLibre's tile cache makes the second and
  third fetch for the same trip noticeably faster than the first.
- Dialog preview shows today's "Loading route…"-style placeholder (flat gradient, no route) until
  the snapshot resolves.

## Privacy

The 500 m endpoint trim is unchanged and still the only thing gating what's shown — the map's
region is computed from `trimmedLatLon`, the same set the line was already drawn from. Worth
recording explicitly: a real basemap can reveal neighborhood/city context via street labels that
an abstract line couldn't, even with the same trimmed endpoints. That's inherent to this feature
("see where it actually happened" was the ask), not a silent regression of the existing trim
guarantee.

## Testing

- No new unit-testable geometry — `trimmedLatLon` is a direct return of an already-computed list,
  covered incidentally by existing `TripCardGeometry.build` trim tests.
- Rendered output is manual/on-device only, same as the original card spec: verify a snapshot
  renders for Standard, Minimal, and Poster, in both light and dark, that the route aligns with
  real streets, that the destination dot lands in the right place, that attribution text is
  present and legible, and that switching layouts/theme in the dialog picker re-renders correctly
  — via `detour-adb`/`capture-state.sh`, pulling the exported PNG with `run-as`, the same method
  used to verify the layout picker this session.

## Acceptance

- Sharing any trip with a trace shows real streets/terrain behind the route, in all three
  layouts.
- The route and destination dot align with real geography (not offset from the actual streets).
- Attribution text is present on every exported card that has a map.
- A trip with no trace still falls back to a stats-only card (unaffected — no map to fetch).
- iOS card export is unchanged.
