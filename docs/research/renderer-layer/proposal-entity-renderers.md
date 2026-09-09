# A game-engine-shaped renderer layer for the map — proposal and risk analysis

Written against commit `efa6c9ad` (2026-09-09). Every `file:line` below was
read directly from the tree at that commit; anything not directly read is
marked **unverified**.

## Scope and independence

Not the `docs/research/ui-packaging/` work, which moves files between
packages with zero logic change. This proposal changes how rendering code is
*organised internally* — entity objects and a factory, as the owner asked —
and can land before, after, or without the packaging work; neither depends on
the other, and either reverts independently.

Covers `ui/MapLibreMap.kt`'s `MapOverlays` (622 lines), and more briefly
`ui/FogView.kt` (614) and `car/CarMapRenderer.kt` (946). Proposes nothing for
any `@Composable` — that exclusion is load-bearing, not an oversight.

## The two rendering models, and the rule that separates them

| | Retained-mode / imperative | Declarative Compose |
|---|---|---|
| Where | `MapOverlays`, `FogView`, `car/CarMapRenderer.kt` | every `@Composable` in `ui/`, `car/` |
| Shape | Hold a mutable field, mutate it, call something named `render()`/`onDraw()` that reads current fields | Take a `State`, recompose on change; nothing survives a draw that Compose doesn't own |
| Example | `MapOverlays.render()` (`:482-532`) reads `routeLine`, pushes GeoJSON | `SpeedHud(state: SpeedHudState)` (`ui/MapHud.kt:121`) — already correct |
| Fits entity/factory? | Yes, natively | No — an entity with a `render()` method is a second retained layer sitting under Compose's own |

**The rule: if a thing survives across draws by living in a field you wrote,
it's retained-mode and the game-engine model applies. If it exists only
because Compose remembered it, an entity/factory pair duplicates state
ownership Compose already provides** — the exact shape of bug
`.claude/skills/detour-compose-state-hazards/SKILL.md` warns about: a
`remember`, an effect key, or a `rememberUpdatedState` guards against stale
reads in a way a hand-rolled entity does not reproduce for free. Nothing
below touches a composable.

## What already exists — don't rebuild it

1. **Render-side types, separate from shared-core types, already exist.**
   `NamedFriendPosition` (`ui/MapLibreMap.kt:115`), `CandidatePin` (`:536`),
   `PositionMarker` (`:547`) are render-only, distinct from `RiderRef`
   (`shared/.../data/RiderId.kt:37`) and `object Rider`
   (`shared/.../data/Social.kt:89`). `NamedMemberFix` follows the same split
   against its shared counterpart (`shared/.../data/CircleFixes.kt:73`). This
   is already the boundary the owner asked for — don't rebuild it.
2. **`MapOverlays` already has entity-shaped setters**, just no entity
   *objects*: `setRouteColor` (`:316`), `setDrivenFraction` (`:354`),
   `setCameras` (`:411`), `setFriends` (`:420`), `setCircleMembers` (`:438`),
   `setPositionIcon` (`:454`), `setPosition` (`:470`), one `render()` (`:482`).

## The coupling and z-order analysis

### 1. Cross-entity coupling of the retained fields

`MapOverlays` has six private mutable fields:

| Field | Line | Read by | Written by |
|---|---|---|---|
| `routeLine` | `:328` | `setDrivenFraction`, `pushRouteHalves` | `render` (`:509`) |
| `routeMeters` | `:329` | `setDrivenFraction`, `pushRouteHalves` | `render` (`:510`) |
| `drawnDrivenMeters` | `:330` | `setDrivenFraction`, `pushRouteHalves` | `setDrivenFraction` (`:361,368`), `render` reset (`:511`) |
| `cutAt` | `:331` | `pushTail` | `pushRouteHalves` (`:382,388`) |
| `drawnTailAt` | `:332` | `pushTail` (compared) | `pushRouteHalves` reset (`:379`), `pushTail` (`:403`) |
| `lastPositionBearing` | `:464` | `setPosition` | `setPosition` (`:471`) |

Five of six are read/written across three methods plus `render()` itself,
which writes three of them directly on route-identity change (`:508-512`).
**This is one entity, not three.** "Whole route", "driven/ahead split" and
"tail seam" cannot be split further without threading all five fields
between the pieces — exactly the state-ownership problem `boundaries.md`
§8.4 describes, not a concern boundary.

Everything else is trivially separable, for the opposite reason:
`setCameras`, `setFriends`, `setCircleMembers`, the reach/wedge/candidates/
destination branches of `render()`, and `setPositionIcon` touch **no shared
field** — each reads only its own parameters, writes only its own
`GeoJsonSource`. `setRouteColor` (`:316-323`) never touches the five route
fields, only paint properties, but is conceptually part of "Route."

### 2. Layer ordering / z-order

Everything writes into one `Style` (`:124`). MapLibre draws bottom-to-top in
`addLayer` call order — no explicit ordering argument is used anywhere. That
order is set entirely by one `init` block (`:133-283`):

| Order | Layer(s) | Source | Line |
|---|---|---|---|
| 1 | `mr-reach-fill`, `mr-reach-line` | `SRC_REACH` | `:168-172` |
| 2 | `mr-wedge-fill` | `SRC_WEDGE` | `:173-174` |
| 3 | route casings | `SRC_ROUTE`, `SRC_ROUTE_DRIVEN` | `:179-186` |
| 4 | `LAYER_ROUTE_DRIVEN` | `SRC_ROUTE_DRIVEN` | `:191-195` |
| 5 | `LAYER_ROUTE` | `SRC_ROUTE` | `:205-209` |
| 6 | `LAYER_ROUTE_TAIL` | `SRC_ROUTE_TAIL` | `:214-218` |
| 7 | `mr-position` | `SRC_POSITION` | `:223-228` |
| 8 | `mr-dest` | `SRC_DEST` | `:229-231` |
| 9 | `LAYER_FRIENDS` | `SRC_FRIENDS` | `:234-243` |
| 10 | `LAYER_CIRCLE_MEMBERS` | `SRC_CIRCLE_MEMBERS` | `:248-255` |
| 11 | `mr-cameras` | `SRC_CAMERAS` | `:258-262` |
| 12 | `LAYER_CANDIDATES` | `SRC_CANDIDATES` | `:265-269` |

Comment `:166-167`: "fills, then the route … then markers, with the tappable
candidates on top." **If a split changes which class's `init` runs first, it
silently changes what draws on top of what — no compiler signal, no test.**
This is the single biggest behavioural risk here. Any split must preserve
this exact sequence via one explicit, ordered list a reviewer can diff
against the table above — never "whatever order the constructor calls
things in."

Sources must exist before a referencing `addLayer` (all added once, `:152-155`)
— an entity adding its own source immediately before its own layer preserves
this per-entity, so that part decomposes safely. Images (`IMG_POSITION`,
`IMG_CAMERA`, etc.) are loaded before their layers today, but MapLibre only
needs an image to exist before first render, not before `addLayer` —
unverified across every targeted MapLibre version, so keep "image before
layer" as a rule anyway; nothing is gained by testing the boundary.

### 3. `car/` blast radius

`CarMapRenderer` is a second **caller**, not a copy. It imports `MapOverlays`
and friends (`car/CarMapRenderer.kt:40-44`), builds a fresh `MapOverlays` per
surface (`:405`), and calls the same public methods `MapScreen` does:
`setPositionIcon` (`:138`), `setRouteColor` (`:143`), `setFriends`
(`:163,329`), `setCircleMembers` (`:172,181,334`), `setCameras` (`:324`),
`setPosition` (`:319`), `render`+`setDrivenFraction` via `pushRoute`
(`:452-467`). It also keeps a shadow copy of nearly every value
(`routePolyline`, `drivenFraction`, `destination`, `position`,
`positionBearing`, `cameras`, `friends`, `circleMembers`, `:210-217`) so a
replacement `MapOverlays` can be refilled after a surface swap (`:407-423`).
One `CarAppService` serves both Android Auto and Automotive OS — two hosts'
worth of blast radius, neither reachable by the phone GPS-replay rig; the car
screens consume the same `lastFix`, but the head-unit *drawing* needs a DHU
or real unit to inspect.

**Consequence:** as long as a split keeps `MapOverlays`' public method names
and signatures identical, `CarMapRenderer.kt` needs zero changes and zero
re-testing beyond what the phone gets. Changing that public surface forces
re-verification on both car hosts on top of the phone — a real cost the
sequencing below defers.

### 4. Does §8.4 apply here?

Literally, no — plain classes, not composables, and every entity below needs
at most two constructor parameters. But the *principle* bites once: `render()`
already takes **eight** parameters (`myLocation, destination, routePolyline,
reachMeters, directionDeg, candidates, positionMarker, positionBearingDeg`,
`:482-491`) — over the gate, today, before any split. `state-holders.md`
§14.2's answer applies directly: bundle these eight into one small per-frame
holder passed once to the facade, rather than threading them into every
entity's own method. Pre-existing condition this proposal should fix, not
create.

### Tension with an existing guideline ruling

`boundaries.md` §8.8 already ruled on this file: *"`car/CarMapRenderer.kt`
… and `ui/MapLibreMap.kt` … are correct as they are. Splitting them would
interleave draw order across files and buy nothing."* (Its line counts —
889/846 — are stale against today's 946/622; the reasoning doesn't depend on
the count.) That objection is about splitting **across files**. Stage 1
below keeps every entity in the same file, so z-order stays exactly as
visible as today and §8.8's objection doesn't apply. Stage 2 (separate files)
is precisely what §8.8 warns against, which is why it is optional and last.

## A simplified example, before any real code

Toy names, `ChartSurface` standing in for MapLibre's `Style`.

**Before** — state and drawing interleaved:

```kotlin
class ChartOverlay(private val surface: ChartSurface) {
    private var buoyPositions: List<Point> = emptyList()
    init { surface.addLayer("buoys") }
    fun setBuoys(points: List<Point>) {
        buoyPositions = points
        surface.draw("buoys", points)
    }
}
```

**After** — one entity per marker kind, a factory that fixes construction
order, a facade with the same public surface:

```kotlin
/** Owns exactly one layer and its state. Nothing outside touches it directly. */
class BuoyEntity(private val surface: ChartSurface) {
    init { surface.addLayer("buoys") }
    fun set(points: List<Point>) = surface.draw("buoys", points)
}

/** The order these are constructed in is the z-order — the one thing this
 *  class must get right. */
class ChartEntityFactory(private val surface: ChartSurface) {
    fun buoys() = BuoyEntity(surface)
    fun ship() = ShipEntity(surface)   // after buoys => drawn on top
}

class ChartOverlay(
    surface: ChartSurface,
    factory: ChartEntityFactory = ChartEntityFactory(surface),
) {
    private val buoys = factory.buoys()
    private val ship = factory.ship()
    fun setBuoys(points: List<Point>) = buoys.set(points)
    fun setShip(at: Point) = ship.set(at)
}
```

Call site, unchanged either way: `chartOverlay.setBuoys(listOf(Point(1.0, 2.0)))`.

## The real proposal, worked

| Entity | Owns (fields) | Owns (methods) | Separable? |
|---|---|---|---|
| `RouteEntity` | `routeLine`, `routeMeters`, `drawnDrivenMeters`, `cutAt`, `drawnTailAt` | `setRoute` (from `render`'s route branch, `:508-512`), `setDrivenFraction` (`:354-372`), private `pushRouteHalves`/`pushTail` (`:376-407`), `setColor` (`:316-323`) | Yes, as **one** entity only |
| `PositionMarkerEntity` | `lastPositionBearing` | `setIcon` (`:454-460`), `setPosition` (`:470-478`) | Yes |
| `ReachWedgeEntity` | none | reach/wedge branch of `render` (`:492-498`) | Yes |
| `CandidatesEntity` | none | candidates branch (`:515-521`) | Yes |
| `DestinationEntity` | none | destination branch (`:523-525`) | Yes |
| `CamerasEntity` | none | `setCameras` (`:411-414`) | Yes |
| `FriendsEntity` | none | `setFriends` (`:420-429`) | Yes |
| `CircleMembersEntity` | none | `setCircleMembers` (`:438-449`) | Yes |

`MapOverlays` becomes a facade: constructs the eight entities in exactly the
z-order table's order, keeps its current public methods unchanged, each
forwarding to the right entity. `render()`'s eight parameters get bundled
into one small holder (§14.2, not per-entity) before dispatch. `styleUsable`
(`:294-295`) and the one-shot `road_oneway` fixup (`:279-282`) stay on the
facade — neither belongs to a single entity.

### `FogView` — out of scope, different treatment

`FogView` punches every corridor into **one shared raster** (`mask`/
`maskCanvas`, `:220-226`) composited against **one shared buffer**
(`buffer`/`bufferCanvas`, `:213-215`) in a single `onDraw` pass. The
perf-critical machinery — camera-transform re-projection so the mask
survives a pan without re-walking history (issue #213), and the snapshot
throttle for the frost tint (`:150-186`) — is cross-cutting over every input
(traces, live trace, peers). There is no natural per-entity seam: "entity"
here would mean "a class needing the same shared `Bitmap`/`Canvas`/transform
in its constructor," which relocates the coupling rather than removing it.
No split proposed. One raster, one owner, already correct per §8.1.

### `CarMapRenderer` — mostly out of scope, and partly already this pattern

Two-thirds of it isn't rendering: `VirtualDisplay`/`Presentation` lifecycle
(`:346-427,668-699`) and the camera-easing loop (`:490-662`) are motion/
lifecycle code. The physics/drawing split the owner wants **already exists**
here: `stepCamera` computes numbers and calls `setPosition`/`setCamera`, never
touching a `Canvas` or `Style` layer. Its own KDoc reasons in this proposal's
terms already: *"every value it steps is a field on this class … the
seven-parameter gate … never comes into it"* (`:481-482`). `HudOverlay`
(`:738-946`) is a second, already-separate class with its own state and
`onDraw` — an entity in substance, unprompted. Point the owner at both as
proof the pattern already works here. Where `CarMapRenderer` touches
`MapOverlays`, it's only through the public methods above — Stage 1 needs
zero changes to this file.

## Sequencing

| Stage | What | Files | Public API change | Blast radius | Verification |
|---|---|---|---|---|---|
| 0 | Note the physics/drawing and render/shared splits already exist | none | none | none | n/a |
| 1 | Extract 8 entities inside `MapLibreMap.kt`; `MapOverlays` becomes a facade | 1 | none | phone only | GPS replay A/B + screenshots |
| 2 (optional) | Move entities to their own files | up to 9 | none | phone, plus import changes in `car/CarMapRenderer.kt:40-44` | as Stage 1, plus manual z-order recheck |
| 3 (not recommended, no need identified) | Change `MapOverlays`' public surface | `MapLibreMap.kt`, `MapScreen.kt`'s render effect, `CarMapRenderer.kt` | yes | phone **and both car hosts** | phone replay + DHU/real-unit pass |

Each stage reverts independently: Stage 1 is invisible to every caller, so
reverting is a pure file diff. Stage 2 follows `detour-file-split`'s own
discipline — extraction and repackaging never share a commit. Stage 3 has no
identified need today and is not recommended without one.

## What this is bad at

- **Indirection tax.** Today "why is the driven line not fading" is answered
  by reading one file top to bottom. After Stage 1, a reader must first find
  which entity owns the bug, then follow a call through the facade's
  forwarding method. The forwarding methods are pure boilerplate that every
  future public method has to remember to add.
- **Route's coupling wears an entity's name.** `RouteEntity` can read as "a
  clean, independent unit" when its five fields are exactly as entangled as
  today — the class fences the coupling, it doesn't remove it. Same trap
  `detour-compose-state-hazards` §3 names elsewhere: *"extract it as a class
  with the accumulators as fields and characterisation tests over them
  first."*
- **Z-order moves from reading order to construction order** — one level
  more indirect, safe only as far as the pinning comment is trusted, since
  nothing here is compiler-enforced.
- **No test safety net, before or after.** No test references `MapOverlays`,
  `FogView`, or `CarMapRenderer` today (checked: `app/src/test/`). Splitting
  doesn't create testability the current setup can use — whether `Style`/
  `GeoJsonSource` are fakeable in a JVM unit test is **unverified**, and the
  app's own tooling precondition ("no Robolectric, no `androidTest` source
  set") suggests not. Verify this before counting it as a benefit.
- **Eight small classes for a file already judged the right size.**
  `boundaries.md` §8.8 already calls this file "correct as it is." Stage 1
  renames "private var + private fun" into "private class" — a readability
  preference, not a size or correctness fix. Worth it only if the owner
  values the per-entity mental model over the indirection cost — a judgment
  call this analysis can't settle for them.

## Verification

**A green build proves nothing here.** The compiler and unit tests check
neither pixels nor z-order. Per `.claude/skills/detour-gps-replay/SKILL.md`,
replay the same fixed route before and after, and compare a **named**
quantity — never "behaviour looked unchanged."

Concrete evidence to demand:

1. **Paired screenshots/recording of the same route segment**, before and
   after — same replay file, same `intervalMs`, same starting camera —
   covering a driven-cut moment (exercises `setDrivenFraction`/
   `pushRouteHalves`/`pushTail` together) and a moment with candidates,
   friends, circle members and a camera all on screen (exercises the z-order
   table: is the destination pin still under the friends label? candidates
   still on top?).
2. **The recorded trip's `distanceMeters`/`topSpeedMps`** from `trips.json` —
   should not move at all for a Stage 1 change; if it does, the split
   touched the fix pipeline, not just drawing.
3. **Stage 2/3 only:** the same replay on the car surface (DHU or real head
   unit) — car builds its own `Style` from scratch per surface (`:404-405`),
   a second independent chance for a construction-order mistake.

Screenshots are the right evidence for the z-order question specifically,
since "is X drawn under Y" doesn't show in a diff. Use
`.claude/skills/c7-github-workflow/attach-visual-evidence` to get before/after
images into the PR so they actually render. State both named quantities and
the route file used, per the GPS-replay skill's own reporting convention.

## Verdict

Splitting `MapOverlays`' seven stateless/independent concerns into entity
classes behind an unchanged facade (Stage 1) is safe: zero blast radius on
`car/`, zero public-API change, and the one real risk — z-order — is fully
preserved by keeping construction order identical to today's `init` order in
one file. Splitting "Route" further is not safe — its five fields are one
coupled state machine. Moving entities to separate files (Stage 2) or
changing the public API (Stage 3) are optional, separately risky steps not
recommended without a concrete reason, given §8.8's existing verdict on this
file's size and shape.
