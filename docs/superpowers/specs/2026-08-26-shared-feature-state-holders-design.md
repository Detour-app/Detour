# Shared feature state holders

Slice B of four moving account, friends, circles and shared location toward feature parity on
iOS. Slice A (`2026-08-26-ios-signin-shared-oidc-design.md`) made the account-gated features
reachable on iOS at all. This slice removes the duplication behind them.

The API layer is already shared — `Friends`, `Groups`, `CirclePlaces`, `CircleEvents` all live in
`shared/commonMain`. What is written twice is the *bookkeeping* around it: load, busy, error,
reload-counter, act-then-refetch. On Android it lives inside composables; on iOS the same logic
is written again in Swift `ObservableObject`s.

This is the pattern `docs/refactor/mapscreen/13-surface-independence-audit.md` identified as the
thing that actually decides parity: **statefulness, not domain relevance, is what keeps a feature
off iOS.** A rule inside a `@Composable` cannot be shared; the same rule in an object can.

## What is duplicated, measured

Occurrences of `remember` / `LaunchedEffect` / `produceState` / `withContext` / `scope.launch`,
per composable:

| Android composable | Occurrences |
|---|---|
| `CirclesScreen.kt` `CircleDetailSection` | 15 |
| `CirclesScreen.kt` `CirclesScreen` | 14 |
| `FriendsScreen.kt` `ConvoysSection` | 13 |
| `FriendsScreen.kt` `FriendsSection` | 12 |
| `FriendsScreen.kt` `AddFriendDialog` | 7 |

iOS counterparts doing the same work again: `FriendsModel` (`FriendsScreen.swift:268`),
`CirclesModel` (`CirclesScreen.swift:168`).

Both platforms independently arrived at the same two idioms, which is the tell that they belong
in one place:

- **reload-by-counter**: Android bumps a `reloads` int that a `LaunchedEffect` keys on; iOS calls
  `await model.reload()`.
- **act-then-refetch**: run the mutation, then re-read the server's view rather than patching the
  local copy, so a request that crossed with someone else's cannot leave the two disagreeing.
  Android's `act(scope) { }` and iOS's `act(_:)` are the same function written twice.

## The constraint that shapes the design

`commonMain` has **no `Dispatchers`** — verified, zero occurrences, and unavailable because the
module has an androidTarget plus iOS targets and no jvm∩native intermediate source set. So a
shared store **cannot own a coroutine scope** and cannot launch its own reload.

That does not block the move; it decides the interface. Actions are `suspend`, the platform
supplies the coroutine, and the store owns everything else:

```
platform:  scope.launch { FriendsStore.reload() }        // owns the coroutine
store:     sets busy, calls the shared API, sets loaded or error   // owns the state
platform:  renders store.state                                     // owns the pixels
```

The bookkeeping moves. The launching does not. That is the whole of what is shareable here, and
it is the ~60 occurrences above.

## Scope

In scope:

- Three new files in `shared/src/commonMain/kotlin/com/jellemax/detour/data/`: `FriendsStore.kt`,
  `ConvoysStore.kt`, `CirclesStore.kt`.
- Three new `Watcher` subclasses in `shared/src/iosMain/.../FlowWatcher.kt`, plus a factory
  object beside `SettingsFlows` and `StoreFlows`.
- `app/.../ui/FriendsScreen.kt` and `app/.../ui/CirclesScreen.kt` reduced to rendering plus
  `scope.launch { store.action() }`.
- `iosApp/Detour/FriendsScreen.swift` and `CirclesScreen.swift`: `FriendsModel` and `CirclesModel`
  deleted, the views bound to the shared state instead.
- First `commonTest` coverage of any of this logic.
- `versionName` minor bump: iOS gains the leaderboard's own-stats row (see below), which is a
  feature.

Out of scope, deliberately:

- **The convoy live relay.** `ConvoyLiveClient` (`app/net/`, 693 lines) and its Swift twin (557)
  are slice C. `ConvoysStore` covers membership only; see "Where the boundary falls".
- **Splitting the two Android files.** They shrink substantially as a side effect of the state
  leaving, which is the honest way to shrink them. A further mechanical split, if still wanted
  afterwards, is its own change under `detour-file-split`'s rules.
- **Circle presence and notification policy** (`CircleSync`, `CircleNotifyService`). Slice D.
- **Dialog-local form state.** `AddFriendDialog`'s name/busy/error/status,
  `CreateCircleDialog`'s name, `InviteTo*Dialog`'s name, `SharePlaceDialog`'s picked/radius all
  stay where they are. They are transient form state, not shared domain state, and moving them
  would mean `StateFlow`s for text fields.

## Naming: why `*Store`

`*Store` looks like it collides with the persistence convention (`TripStore`, `RouteStore`,
`TraceStore`, `BadgeStore`, `RecentSearchStore`) — but it does not, because that convention is
not about persistence. `RouteStore.routes` and `TraceStore.version` are already `StateFlow`s that
screens observe. `*Store` in this repo means **the thing that owns this data and publishes it**:
file-backed for those, server-backed for these. Same role, same suffix.

The alternative considered and rejected was reusing iOS's `FriendsModel` / `CirclesModel` names,
so the Swift deletion would read as a rename. Rejected because a Kotlin `FriendsModel` exported
to Swift would be ambiguous against the Swift class of the same name for as long as both exist,
and `Model` says less than `Store` does.

## The three stores

Each is an `object` with one `MutableStateFlow`, an immutable state `data class`, and `suspend`
actions. One `object`, not a class — the house pattern, 33 of them against one interface, and
these have exactly one instance each.

### `FriendsStore`

```kotlin
data class FriendsState(
    val lists: FriendLists? = null,
    val leaderboard: List<FriendStats> = emptyList(),
    /** This device's own totals, so "me" appears in my own leaderboard. */
    val own: FriendStats? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

object FriendsStore {
    val state: StateFlow<FriendsState>
    suspend fun reload()
    suspend fun refreshOwn(username: String)
    suspend fun request(username: String): String
    suspend fun respond(username: String, accept: Boolean)
    suspend fun remove(username: String)
}
```

**`own` is a parity gain, not a refactor.** Android computes it at `FriendsScreen.kt:216-220`
through `Coverage.compute()` → `BadgeStore.stats()` → `BadgeStore.refresh()`, because the server
sends a rider's totals to their friends and never back to them. iOS's leaderboard has **no own
row at all** today. Sharing the computation gives iOS the row for free — which is the pattern the
audit predicted: the logic was already shareable, it was just sitting in a `produceState`.

It stays a separate `refreshOwn` rather than folding into `reload`, because `Coverage.compute()`
reads every trace on disk and the friend lists do not. A screen that reloads on every mutation
must not recompute coverage each time.

### `ConvoysStore`

```kotlin
data class ConvoysState(
    val convoys: List<Group> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

object ConvoysStore {
    val state: StateFlow<ConvoysState>
    suspend fun reload()
    suspend fun create(name: String)
    suspend fun invite(groupId: String, username: String): String
    suspend fun respond(groupId: String, accept: Boolean)
    suspend fun leave(groupId: String)
}
```

### `CirclesStore`

```kotlin
data class CirclesState(
    val circles: List<Group> = emptyList(),
    val selectedId: String? = null,
    val places: List<CirclePlace> = emptyList(),
    val events: List<PlaceEvent> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    // Detail gets its own pair. See the correction below.
    val detailBusy: Boolean = false,
    val detailError: String? = null,
)

object CirclesStore {
    val state: StateFlow<CirclesState>
    suspend fun reload()
    suspend fun select(groupId: String?)
    suspend fun create(name: String)
    suspend fun invite(groupId: String, username: String): String
    suspend fun respond(groupId: String, accept: Boolean)
    suspend fun leave(groupId: String)
    suspend fun setSharing(groupId: String, sharing: Boolean)
    suspend fun sharePlace(groupId: String, place: SavedPlace, radiusM: Double)
    suspend fun unsharePlace(serverId: String)
}
```

> **Correction, after the Android screen was rewired.** This state originally carried a single
> `busy`/`error` pair for the whole store, on the reasoning that one coarse state object per
> feature keeps the iOS `FlowWatcher` cost at one subclass. That reasoning was sound about the
> watcher and wrong about the state: the screen being replaced had **two** independent pairs —
> `busy`/`error` for list operations and `placesBusy`/`placesError` for the detail pane — and
> collapsing them caused three visible regressions. Opening a circle disabled the Invite, Leave,
> sharing and notify controls, because the detail load set the same flag those gated on. Refresh
> flashed the empty state, because `selecting` cleared the detail even on a same-circle
> reselect. And a single failure could render twice, because the screen's two error sites both
> read one field.
>
> The economy was false: `FlowWatcher`'s cost is one subclass per *state type*, not per field, so
> adding a second pair to the same `data class` costs nothing in interop. `FriendsStore` and
> `ConvoysStore` keep one pair each correctly — they have one concern each.

`unsharePlace` takes a `serverId` and not a `groupId` because that is what
`CirclePlaces.delete(serverId)` takes — a shared place is addressed by its own server identifier,
not by the circle it was shared into (`CirclePlace.serverId`, and the Android call site at
`CirclesScreen.kt:518`). The store still reloads the selected circle afterwards, so the asymmetry
does not leak into the caller.

Detail state (`places`, `events`) lives in the same store keyed by `selectedId` rather than in a
fourth store. A four-store split was considered — `CircleDetailSection` is the largest single
state machine in either screen — and rejected because selection would then have to be kept
consistent across two stores, which is more coupling than it removes.

## Where the boundary falls, and why it is not tidy

`ConvoysSection` and `CircleDetailSection` each mix shareable state with genuinely platform
state. The split is per-value, not per-composable:

| Value | Goes to the store | Stays platform | Why |
|---|---|---|---|
| `convoys`, `busy`, `error`, `reloads` | ✅ | | server-backed, identical on both platforms |
| `liveConvoyId`, `liveConnected`, `livePeers`, `liveError` | | ✅ | read from `ConvoyLiveClient`, which is Android-only until slice C |
| mic permission launcher, `goLive`, `ConvoyLiveService.start` | | ✅ | `Manifest.permission`, `ContextCompat`, a foreground service |
| `circles`, `selectedId`, `places`, `events` | ✅ | | server-backed |
| `notifyEnabled` | | ✅ | already shared *through `Settings`*; both platforms read `Settings.notifyArrivals` and neither needs a store for it |
| `showBatteryPrompt`, `notifPermLauncher` | | ✅ | `PowerManager`, a runtime permission |
| every dialog's form fields | | ✅ | transient, per-platform idiom |

So `FriendsScreen.kt`'s `ConvoysSection` keeps its live-relay reads and its permission plumbing
after this slice. That is not an oversight to tidy later — it is the correct line until slice C
gives the relay a shared home, and drawing it anywhere else now would mean moving
`ConvoyLiveClient` too, which is a 1,250-line change with its own spec.

## Error handling

Every action follows one shape, which is the shape both platforms already independently use:

1. set `busy = true`, clear `error`
2. call the shared API object
3. on success, re-read the server's view (`reload()`), never patch the local copy
4. on failure, set `error` to the exception's message and **leave the last-known-good data in
   place**
5. `busy = false` either way, including on failure

Point 4 is a behaviour change worth naming: Android's `FriendsSection` currently sets `error` and
leaves `lists`/`stats` as they were, which is right; but a failing *reload* there also leaves them
stale with no indication. The store makes "stale plus an error banner" the explicit, tested
outcome rather than an accident of which variable the `catch` happened to touch.

Exceptions arriving from the API objects are `AuthException` and `HttpStatusException`, both
`okio.IOException`, and both already carrying the server's own wording. The store stores
`e.message`; it does not translate. Slice A annotated the exported suspend surface with `@Throws`,
so these reach Swift as `NSError` rather than terminating the process — the store's actions are
new exported suspend functions and **must carry `@Throws` too**, or every error in this slice
crashes iOS instead of showing a banner.

## iOS interop cost

Three new `Watcher` subclasses, one per state type, in `shared/src/iosMain/.../FlowWatcher.kt`:
`FriendsStateWatcher`, `ConvoysStateWatcher`, `CirclesStateWatcher`, handed out by a new
factory object beside `SettingsFlows` and `StoreFlows`.

Three, and not a dozen, is the whole reason the state is one coarse object per feature rather than
a flow per field: Kotlin/Native erases a generic's type argument on the way to Objective-C, so
each distinct element type needs its own concretely-typed subclass (`FlowWatcher.kt`'s own doc
explains this). One state class per store keeps that at one class per store.

`FriendsState` and the others must therefore be exported types — `data class` in `commonMain`,
public, with no generic parameters.

## Tests

`shared/src/commonTest/`, plain `kotlin.test`, house style. The API objects are the seam: these
tests drive the reducer, not the network.

- A successful `reload` moves `busy` false → true → false and populates the lists.
- A failing action sets `error` and **leaves the previous data intact** — the property point 4
  above makes explicit, and the one a screen visibly gets wrong when it is missing.
- `busy` returns to false on the failure path, not just the success path. This is the bug both
  platforms' hand-rolled `act` helpers were written to avoid, and it deserves a test rather than
  a comment.
- `select` on a circle id that is no longer in `circles` clears the selection rather than
  stranding a detail pane pointed at nothing.
- `select(null)` clears `places` and `events`, so a previously-viewed circle's places cannot
  appear under a newly-selected one.
- `refreshOwn` is separate from `reload`: a `reload` must not clear an `own` already computed.

Reaching the network from `commonTest` is not possible (`Http` is a concrete Ktor client and not
injectable), so anything that would need a fake server is out. What is testable is the state
machine, which is exactly the part that was duplicated.

## Verification

- `commonTest` via `:shared:testDebugUnitTest`, plus `:shared:compileCommonMainKotlinMetadata`
  for the `java.*` check.
- Android by hand on the container AVD: friends list loads, a failed action shows a banner
  without blanking the list, circle selection survives a reload.
- **iOS remains uncompilable on this host.** This slice adds three more Swift edits on top of
  slice A's, none of which has ever been through a compiler. Slice A is unmerged and unpushed by
  decision, so `ios.yml` has not run on any of it. Every Swift claim in this slice is a read, not
  a build, and the PR that eventually runs CI may surface errors from A and B together.

## Follow-ups this creates

1. Whether `FriendsScreen.kt` and `CirclesScreen.kt` still want a mechanical split once the state
   has left — decide by measuring, not now.
2. Slices C (convoy live relay) and D (circle presence and notification policy), each its own
   spec.
