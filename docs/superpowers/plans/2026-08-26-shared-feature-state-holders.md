# Shared feature state holders — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the load/busy/error/reload-then-refetch bookkeeping for friends, convoys and circles out of Android composables and Swift `ObservableObject`s into three shared stores in `commonMain`, so one implementation serves both platforms.

**Architecture:** Three `object` stores, each with one `MutableStateFlow` over an immutable state `data class` and `suspend` actions. `commonMain` has no `Dispatchers`, so a store cannot own a scope: actions are `suspend`, the platform supplies the coroutine, the store owns the state. Each store gets one `iosMain` `Watcher` subclass, because Kotlin/Native erases generics on the way to Objective-C.

**Tech Stack:** Kotlin Multiplatform (`:shared`), kotlinx-coroutines `StateFlow`, `kotlin.test` (commonTest), Jetpack Compose (Android), SwiftUI (iOS).

**Spec:** `docs/superpowers/specs/2026-08-26-shared-feature-state-holders-design.md`

## Global Constraints

- **All tooling runs inside the devcontainer.** `devcontainer-exec ./gradlew …` (on PATH), or `docker exec -u 1000:1000 great_panini …`. NEVER build or install on the host — the host JDK is 26 and has no Android SDK.
- **`commonMain` has no `Dispatchers`, no logger, no `java.*`.** Zero occurrences today and it must stay zero. Every store action is `suspend` and takes its dispatcher from the caller. Verified by `./gradlew :shared:compileCommonMainKotlinMetadata`, which must pass before the PR.
- **Every exported `suspend` function needs `@Throws(Exception::class)`.** A Kotlin/Native `suspend` function without it propagates only `CancellationException`; every other exception reaching Swift **terminates the process**. Slice A annotated 29 existing functions for exactly this reason. Every new `suspend` action in this slice is a new exported function and must carry the annotation, or an error in this slice crashes iOS instead of showing a banner. Follow the existing pattern: one pointer comment per object, not the paragraph repeated per declaration — see `shared/src/commonMain/kotlin/com/jellemax/detour/data/Groups.kt` and the canonical explanation in `SyncClient.kt`.
- **State classes must be exported types:** public `data class` in `commonMain`, **no generic parameters**. A generic reaches Swift with its type argument erased, which is the problem `FlowWatcher.kt`'s concrete subclasses exist to avoid.
- **One new `Watcher` subclass per new state type, and no more.** Check `shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt` before adding one; reuse an existing element type over introducing a new one.
- **An action never throws for an ordinary failure.** It reports through `state.error` and
  returns a value the caller can branch on: `String?` where it produced a value (null means it
  failed), `Boolean` otherwise. `reload()` and `refreshOwn()` stay `Unit`. This was changed after
  Task 1's review: an earlier revision had actions rethrow, which would have made every
  `scope.launch` call site responsible for catching an error the store had already reported —
  and an uncaught exception in a `launch` crashes an Android app. **Call sites branch on the
  return value; they do not wrap actions in `try`/`catch`.**
- **`CancellationException` is the one thing that still propagates**, and every store must
  `catch (e: CancellationException) { throw e }` ahead of its generic catch — the house pattern
  in `shared/.../data/SpinPicker.kt` and `RoundTripPlanner.kt`. A cancellation is the caller's
  own doing (a screen key changing, a sign-out mid-load) and must never become an error banner.
  This is why the actions keep `@Throws(Exception::class)` even though they no longer throw on
  failure: without it a cancellation crossing to Swift would terminate the process.
- **Every action's contract, identically:** set `busy = true` and clear `error`; call the shared API object; on success re-read the server's view via `reload()` rather than patching the local copy; on failure set `error` from the exception message and **leave the last-known-good data in place**; set `busy = false` on both paths.
- **Do not translate exception messages.** `AuthException` and `HttpStatusException` already carry the server's own wording. Store `e.message`, fall back to a fixed string when it is null.
- **Android:** no behaviour change beyond what the spec names. User-facing copy stays byte-identical. Do not change `ConvoyLiveClient`, `ConvoyLiveService`, the mic-permission plumbing, the battery-optimisation dialog, or the notification-permission launcher — those are platform state and stay (spec, "Where the boundary falls").
- **iOS cannot be compiled here.** No Xcode; the Kotlin/Native Apple compilations are skipped. Slice A is unmerged and unpushed by decision, so `ios.yml` has never run on any of this. Never claim Swift compiles. Read it back deliberately instead, and expect that the eventual CI run surfaces slice A's and slice B's Swift errors together.
- **iOS target is `SWIFT_VERSION: "5.9"` and iOS 17** (`iosApp/project.yml`). `MainActor.assumeIsolated` is Swift 5.10 and must not appear.
- **`DetourShared.Group` collides with SwiftUI's `Group`.** Existing Swift qualifies it as `DetourShared.Group` and `SwiftUI.Group`. Keep doing that.
- **No `Co-Authored-By` and no `Claude-Session` trailer on any commit.** Conventional-commits, subject under about 72 characters.
- **Branch:** `feat/ios-shared-feature-state`, already created, stacked on slice A's `7695187`. Spec committed as `61023f4`.
- `versionName` bump is Task 6's job alone. `versionCode` is CI-stamped — never touch it.

---

### Task 1: FriendsStore and ConvoysStore

Both live in the Friends screen and share one shape, so they land together. Deliverable: two stores plus tests, with nothing consuming them yet.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/FriendsStore.kt`
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/ConvoysStore.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/data/StoresTest.kt`

**Interfaces:**
- Consumes: `Friends.lists/stats/request/respond`, `Groups.create/list/invite/respond/leave`, `FriendLists`, `FriendStats`, `Group`, `Coverage.compute()`, `BadgeStore.stats/refresh`.
- Produces, relied on by Tasks 3, 4 and 5 exactly as spelled here:
  - `FriendsState(lists, leaderboard, own, busy, error)` — all `val`, defaults `null`/`emptyList()`/`false`/`null`
  - `FriendsStore.state: StateFlow<FriendsState>`
  - `suspend FriendsStore.reload()`, `refreshOwn(username: String)`, `request(username: String): String?`, `respond(username: String, accept: Boolean): Boolean`
  - `ConvoysState(convoys, busy, error)`
  - `ConvoysStore.state: StateFlow<ConvoysState>`
  - `suspend ConvoysStore.reload()`, `create(name: String): Boolean`, `invite(groupId: String, username: String): String?`, `respond(groupId: String, accept: Boolean): Boolean`, `leave(groupId: String): Boolean`

- [ ] **Step 1: Read the code being replaced**

Read these before writing anything, because the stores must reproduce their behaviour and not invent new behaviour:

- `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt` — `FriendsSection` (the `lists`/`stats`/`busy`/`error`/`reloads` machine, the `own` `produceState`, and the local `act` helper) and `ConvoysSection`'s equivalent.
- `iosApp/Detour/FriendsScreen.swift` — `FriendsModel.reload()` and `act(_:)`, the same logic in Swift.
- `shared/src/commonMain/kotlin/com/jellemax/detour/data/Social.kt` and `Groups.kt` — the API objects being called, and the `@Throws` comment style to match.

- [ ] **Step 2: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/StoresTest.kt`. These drive the reducer, never the network — `Http` is a concrete Ktor client and not injectable, so the tests exercise the state transitions only, by calling the internal reducer helpers the stores expose for exactly this reason.

The stores must therefore expose their transitions as `internal` pure functions over state, in the same shape and for the same stated reason as `Auth.tokenFailureMessage` is `internal` ("because the test for it is the point"). Write the tests first and let them dictate those signatures:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the state transitions in FriendsStore.kt, ConvoysStore.kt and
 * CirclesStore.kt — the load/busy/error bookkeeping that used to be written
 * once per platform inside a composable and a Swift ObservableObject.
 *
 * These test the reducer, not the network: `Http` is a concrete Ktor client
 * with no seam, so what is testable here is exactly the part that was
 * duplicated. The properties below are the ones a screen visibly gets wrong
 * when they are missing — a spinner that never stops, and a list that blanks
 * itself because a refresh failed.
 */
class StoresTest {

    private fun lists() = FriendLists(
        friends = listOf("ada", "grace"),
        incoming = listOf("linus"),
        outgoing = emptyList(),
    )

    private fun stats(name: String) = FriendStats(
        username = name,
        stats = RiderStats(
            totalDistanceMeters = 1_234.0,
            topSpeedKmh = 98.0,
            longestTripMeters = 800.0,
            maxLeanDeg = 32.0,
            municipalitiesVisited = 3,
            bestCoveragePercent = 12.5,
            tripCount = 7,
        ),
        badgeIds = listOf("first-ride"),
    )

    // --- FriendsStore -----------------------------------------------------

    @Test
    fun aStartedActionIsBusyAndHasNoStaleErrorOnIt() {
        val before = FriendsState(error = "the last attempt failed")
        val started = before.starting()
        assertTrue(started.busy)
        // A new attempt must not show the previous attempt's error underneath
        // its own spinner.
        assertNull(started.error)
    }

    @Test
    fun aFailedActionKeepsTheDataItAlreadyHad() {
        val loaded = FriendsState(lists = lists(), leaderboard = listOf(stats("ada")))
        val failed = loaded.starting().failed(RuntimeException("no route to host"))
        // The whole point: an error banner over the last-known-good list, not
        // an empty screen. A refresh that fails must not destroy what is on
        // screen.
        assertEquals(loaded.lists, failed.lists)
        assertEquals(loaded.leaderboard, failed.leaderboard)
        assertEquals("no route to host", failed.error)
    }

    @Test
    fun busyClearsOnTheFailurePathToo() {
        // The bug both platforms' hand-rolled `act` helpers were written to
        // avoid, and which neither of them had a test for: a spinner that
        // never stops because only the success path cleared it.
        val failed = FriendsState().starting().failed(RuntimeException("nope"))
        assertTrue(!failed.busy)
    }

    @Test
    fun anExceptionWithNoMessageStillSaysSomething() {
        val failed = FriendsState().starting().failed(RuntimeException())
        assertEquals(FriendsStore.FALLBACK_ERROR, failed.error)
    }

    @Test
    fun aSuccessfulLoadClearsBusyAndError() {
        val loaded = FriendsState(error = "stale")
            .starting()
            .loaded(lists(), listOf(stats("ada")))
        assertTrue(!loaded.busy)
        assertNull(loaded.error)
        assertEquals(2, loaded.lists!!.friends.size)
    }

    @Test
    fun reloadingDoesNotDiscardAnOwnRowAlreadyComputed() {
        // `own` comes from Coverage.compute(), which reads every trace on
        // disk. A list reload after every mutation must not throw it away and
        // make the leaderboard's "me" row flicker.
        val withOwn = FriendsState(own = stats("me"))
        val reloaded = withOwn.starting().loaded(lists(), listOf(stats("ada")))
        assertSame(withOwn.own, reloaded.own)
    }

    // --- ConvoysStore -----------------------------------------------------

    @Test
    fun aFailedConvoyActionKeepsTheConvoyList() {
        val loaded = ConvoysState(convoys = listOf(convoy("c1", "Sunday run")))
        val failed = loaded.starting().failed(RuntimeException("500"))
        assertEquals(loaded.convoys, failed.convoys)
        assertEquals("500", failed.error)
        assertTrue(!failed.busy)
    }

    private fun convoy(id: String, name: String) = Group(
        id = id, name = name, kind = "convoy", status = "accepted", members = emptyList(),
    )
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*StoresTest*'
```

Expected: FAIL at compilation — `Unresolved reference: FriendsState`.

- [ ] **Step 4: Write FriendsStore**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/FriendsStore.kt`. The transitions are `internal` extension functions on the state so the tests above can drive them without a network; the `suspend` actions are the public surface.

```kotlin
package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the Friends screen shows about friends, on both platforms.
 *
 * `lists` is null until the first load finishes, which is not the same as
 * "no friends" — a screen has to tell those apart to avoid claiming someone
 * has nobody when the server simply has not answered yet.
 */
data class FriendsState(
    val lists: FriendLists? = null,
    val leaderboard: List<FriendStats> = emptyList(),
    /** This device's own totals, so "me" appears in my own leaderboard. The
     *  server sends a rider's numbers to their friends and never back to
     *  them, so this is computed locally or not at all. */
    val own: FriendStats? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The friend list and the shared leaderboard, with the load/busy/error
 * bookkeeping that used to be written once per platform.
 *
 * No coroutine of its own: `commonMain` has no `Dispatchers` (see
 * CONTRIBUTING.md and Platform.kt's ceiling), so every action here is
 * `suspend` and the caller supplies the scope — `scope.launch { }` in Compose,
 * a `Task { }` in SwiftUI. The store owns the state and nothing else, which is
 * the only division of labour this module's constraints allow.
 *
 * Actions all follow one shape, and it is the shape both platforms had already
 * arrived at independently: run the mutation, then re-read the server's view
 * rather than patching the local copy, so a request that crossed with somebody
 * else's cannot leave the two disagreeing.
 */
object FriendsStore {

    /** What an exception with no message becomes. Named rather than inlined
     *  because the test for it asserts this exact string. */
    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    /** Both lists in one pass, so a screen never shows friends without their
     *  numbers or the other way round. */
    @Throws(Exception::class)
    suspend fun reload() {
        _state.value = _state.value.starting()
        _state.value = try {
            _state.value.loaded(Friends.lists(), Friends.stats())
        } catch (e: Exception) {
            _state.value.failed(e)
        }
    }

    /**
     * Recomputes the rider's own row. Separate from [reload] on purpose:
     * `Coverage.compute()` reads every trace on disk, and a screen that
     * reloads after every mutation must not pay that each time.
     */
    @Throws(Exception::class)
    suspend fun refreshOwn(username: String) {
        val own = try {
            val coverage = Coverage.compute()
            val riderStats = BadgeStore.stats(coverage)
            val badgeIds = BadgeStore.refresh(riderStats).states
                .filter { it.earned }.map { it.def.id }
            FriendStats(username, riderStats, badgeIds)
        } catch (e: Exception) {
            // A missing own row is worth strictly less than the friend list it
            // sits above, so this failure is not allowed to put an error over
            // the whole screen.
            return
        }
        _state.value = _state.value.copy(own = own)
    }

    /** Returns the resulting status — "pending", or "accepted" when they had
     *  already asked us and this answered theirs. */
    @Throws(Exception::class)
    suspend fun request(username: String): String = act { Friends.request(username) }

    @Throws(Exception::class)
    suspend fun respond(username: String, accept: Boolean) {
        act { Friends.respond(username, accept) }
    }

    /**
     * Runs a mutation, then reloads. Rethrows so a caller that wants to react
     * to the failure itself still can, while the banner is set either way.
     */
    private suspend fun <T> act(block: suspend () -> T): T {
        _state.value = _state.value.starting()
        val result = try {
            block()
        } catch (e: Exception) {
            _state.value = _state.value.failed(e)
            throw e
        }
        reload()
        return result
    }
}

/** Busy, and without the previous attempt's error under the new spinner. */
internal fun FriendsState.starting() = copy(busy = true, error = null)

/** Note what is *not* touched: [FriendsState.own]. It is expensive to compute
 *  and unrelated to the server's answer, so a reload keeps it. */
internal fun FriendsState.loaded(lists: FriendLists, leaderboard: List<FriendStats>) =
    copy(lists = lists, leaderboard = leaderboard, busy = false, error = null)

/** Keeps every data field. An error is a banner over the last known good
 *  screen, never a reason to blank it. */
internal fun FriendsState.failed(e: Exception) =
    copy(busy = false, error = e.message?.ifBlank { null } ?: FriendsStore.FALLBACK_ERROR)
```

- [ ] **Step 5: Write ConvoysStore**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/ConvoysStore.kt`, same shape:

```kotlin
package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConvoysState(
    val convoys: List<Group> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Convoy membership: creating, listing, inviting, responding, leaving.
 *
 * Membership only. A convoy's live location and push-to-talk ride a WebSocket
 * that is still platform code (`app/net/ConvoyLiveClient.kt` and its Swift
 * twin), so whether this device is *connected* is not in this state — the
 * screens read that from the client itself, which is what keeps the button
 * honest about whether the service is actually running.
 *
 * Same no-scope rule as [FriendsStore]: actions are `suspend`, the caller
 * supplies the coroutine.
 */
object ConvoysStore {

    internal const val FALLBACK_ERROR = "Could not reach the server"

    private val _state = MutableStateFlow(ConvoysState())
    val state: StateFlow<ConvoysState> = _state.asStateFlow()

    @Throws(Exception::class)
    suspend fun reload() {
        _state.value = _state.value.starting()
        _state.value = try {
            _state.value.loaded(Groups.list(KIND))
        } catch (e: Exception) {
            _state.value.failed(e)
        }
    }

    @Throws(Exception::class)
    suspend fun create(name: String) {
        act { Groups.create(KIND, name) }
    }

    /** Returns the resulting status, e.g. "invited". Only accepted friends can
     *  be invited; the server enforces that and this surfaces its refusal. */
    @Throws(Exception::class)
    suspend fun invite(groupId: String, username: String): String =
        act { Groups.invite(groupId, username) }

    @Throws(Exception::class)
    suspend fun respond(groupId: String, accept: Boolean) {
        act { Groups.respond(groupId, accept) }
    }

    @Throws(Exception::class)
    suspend fun leave(groupId: String) {
        act { Groups.leave(groupId) }
    }

    private suspend fun <T> act(block: suspend () -> T): T {
        _state.value = _state.value.starting()
        val result = try {
            block()
        } catch (e: Exception) {
            _state.value = _state.value.failed(e)
            throw e
        }
        reload()
        return result
    }

    /** The discriminator [Groups] routes on. "convoy" here, "circle" in
     *  [CirclesStore]; one entity on the server, two kinds. */
    private const val KIND = "convoy"
}

internal fun ConvoysState.starting() = copy(busy = true, error = null)

internal fun ConvoysState.loaded(convoys: List<Group>) =
    copy(convoys = convoys, busy = false, error = null)

internal fun ConvoysState.failed(e: Exception) =
    copy(busy = false, error = e.message?.ifBlank { null } ?: ConvoysStore.FALLBACK_ERROR)
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, `StoresTest` green, and every pre-existing test still green (189 before this task).

If `BadgeStore.refresh(...).states` or `it.def.id` does not resolve, read `shared/src/commonMain/kotlin/com/jellemax/detour/data/Badges.kt` and use the real shape — the plan copied it from `FriendsScreen.kt:216-220` and the Android call site is the authority.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/FriendsStore.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/ConvoysStore.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/StoresTest.kt
git commit -m "feat(shared): own friends and convoy state in commonMain

The friend list, the leaderboard and convoy membership each carried the same
load/busy/error/reload-then-refetch bookkeeping in a Compose composable and
again in a Swift ObservableObject. It is now written once.

No coroutine of its own — commonMain has no Dispatchers, so actions are suspend
and the caller supplies the scope. The store owns the state and nothing else.

Two properties get tests neither platform's hand-rolled version had: busy
clears on the failure path, and a failed refresh keeps the data already on
screen instead of blanking it."
```

---

### Task 2: CirclesStore

Deliverable: the third store plus its tests, still with nothing consuming it.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclesStore.kt`
- Modify: `shared/src/commonTest/kotlin/com/jellemax/detour/data/StoresTest.kt` (add the circle cases)

**Interfaces:**
- Consumes: `Groups.create/list/invite/respond/leave/setSharing`, `CirclePlaces.share/places/delete`, `CircleEvents.events`, `Group`, `CirclePlace`, `PlaceEvent`, `SavedPlace`.
- Produces, relied on by Tasks 4 and 5:
  - `CirclesState(circles, selectedId, places, events, busy, error)`
  - `CirclesStore.state: StateFlow<CirclesState>`
  - `suspend CirclesStore.reload()`, `select(groupId: String?)`, `create(name: String): Boolean`, `invite(groupId: String, username: String): String?`, `respond(groupId: String, accept: Boolean): Boolean`, `leave(groupId: String): Boolean`, `setSharing(groupId: String, sharing: Boolean): Boolean`, `sharePlace(groupId: String, place: SavedPlace, radiusM: Double): Boolean`, `unsharePlace(serverId: String): Boolean`

- [ ] **Step 1: Read the code being replaced**

`app/src/main/java/com/jellemax/detour/ui/CirclesScreen.kt` — the top-level `circles`/`busy`/`error`/`reloads`/`selectedId` machine and `CircleDetailSection`'s `places`/`events`/`placesError`/`placesBusy`/`dataReloads`. And `iosApp/Detour/CirclesScreen.swift`'s `CirclesModel` plus `CircleDetailView.loadPlacesAndEvents()`.

Note which values are **not** yours to move (spec, "Where the boundary falls"): `notifyEnabled` is already shared through `Settings.notifyArrivals`; `showBatteryPrompt` needs `PowerManager`; `notifPermLauncher` is a runtime permission. Leave all three where they are.

- [ ] **Step 2: Write the failing tests**

Append to `StoresTest.kt`:

```kotlin
    // --- CirclesStore -----------------------------------------------------

    private fun circle(id: String, name: String) = Group(
        id = id, name = name, kind = "circle", status = "accepted", members = emptyList(),
    )

    @Test
    fun selectingACircleThatIsNoLongerInTheListClearsTheSelection() {
        // A circle can vanish between the list load and the tap — someone
        // removed you, or you left it on another device. A detail pane
        // pointed at nothing is worse than no detail pane.
        val state = CirclesState(circles = listOf(circle("c1", "Family")), selectedId = "c1")
        val afterReload = state.loaded(listOf(circle("c2", "Riders")))
        assertNull(afterReload.selectedId)
    }

    @Test
    fun aStillPresentSelectionSurvivesAReload() {
        val state = CirclesState(circles = listOf(circle("c1", "Family")), selectedId = "c1")
        val afterReload = state.loaded(listOf(circle("c1", "Family"), circle("c2", "Riders")))
        assertEquals("c1", afterReload.selectedId)
    }

    @Test
    fun clearingTheSelectionDropsThePreviousCirclesPlacesAndEvents() {
        // Otherwise the next circle opened shows the last one's places for as
        // long as its own load takes — someone else's addresses under the
        // wrong heading.
        val viewing = CirclesState(
            circles = listOf(circle("c1", "Family")),
            selectedId = "c1",
            places = listOf(place("p1", "c1")),
            events = listOf(event("e1")),
        )
        val cleared = viewing.selecting(null)
        assertTrue(cleared.places.isEmpty())
        assertTrue(cleared.events.isEmpty())
        assertNull(cleared.selectedId)
    }

    @Test
    fun switchingCirclesDropsThePreviousCirclesDetail() {
        val viewing = CirclesState(
            circles = listOf(circle("c1", "Family"), circle("c2", "Riders")),
            selectedId = "c1",
            places = listOf(place("p1", "c1")),
        )
        val switched = viewing.selecting("c2")
        assertEquals("c2", switched.selectedId)
        assertTrue(switched.places.isEmpty())
    }

    @Test
    fun aFailedCircleActionKeepsTheCircleList() {
        val loaded = CirclesState(circles = listOf(circle("c1", "Family")))
        val failed = loaded.starting().failed(RuntimeException("403"))
        assertEquals(loaded.circles, failed.circles)
        assertEquals("403", failed.error)
        assertTrue(!failed.busy)
    }

    private fun place(serverId: String, groupId: String) = CirclePlace(
        serverId = serverId,
        groupId = groupId,
        owner = "ada",
        radiusM = 150.0,
        createdMs = 1_700_000_000_000L,
        place = SavedPlace(id = 1L, name = "Home", lat = 51.0, lon = 4.0),
    )

    private fun event(id: String) = PlaceEvent(
        id = id,
        placeId = 1L,
        placeName = "Home",
        username = "ada",
        kind = "arrive",
        tsMs = 1_700_000_000_000L,
    )
```

If `SavedPlace`'s constructor does not match, read `shared/src/commonMain/kotlin/com/jellemax/detour/data/SavedPlaces.kt` and use the real one — the fixture must compile against the actual type, not this plan's guess at it.

- [ ] **Step 3: Run to verify failure**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*StoresTest*'
```

Expected: FAIL — `Unresolved reference: CirclesState`.

- [ ] **Step 4: Write CirclesStore**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclesStore.kt`. Same shape as the other two, plus selection. Points the code must get right, each of which a test above pins:

- `loaded` drops a `selectedId` that is not in the new list.
- `selecting` clears `places` and `events` whenever the id changes, including to null.
- `unsharePlace` takes a `serverId`, because that is what `CirclePlaces.delete` takes — a shared place is addressed by its own server identifier, not by the circle it went into. Reload the selected circle's detail afterwards.
- Detail loading (`places` + `events`) happens on `select` and after any detail mutation, and a detail failure sets the same single `error`.
- `CircleEvents.events(groupId, sinceMs = 0L)` is the call the Android screen makes; keep the same argument.

Write it in the register of the other two stores: a KDoc on the object saying what it owns and why it has no scope, and the transitions as `internal` extension functions so the tests can drive them.

- [ ] **Step 5: Run to verify pass**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, all `StoresTest` cases green, pre-existing tests unchanged.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclesStore.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/StoresTest.kt
git commit -m "feat(shared): own circle state, including the detail pane

Circles carried the largest of the duplicated state machines — fifteen
remember/effect sites in CircleDetailSection alone, and the same logic again in
Swift. Selection lives in the same store as the list rather than a fourth one,
so the two cannot disagree about which circle is open.

Two transitions get tests because both are visible when wrong: a selection
whose circle vanished from the list is cleared rather than stranding the detail
pane, and switching circles drops the previous one's places instead of showing
them under the new heading."
```

---

### Task 3: The iOS interop layer

Deliverable: Swift can observe all three stores. No Swift screen uses them yet.

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt`

**Interfaces:**
- Consumes: `FriendsStore.state`, `ConvoysStore.state`, `CirclesStore.state` from Tasks 1 and 2.
- Produces: `FriendsStateWatcher`, `ConvoysStateWatcher`, `CirclesStateWatcher`, and a factory object handing them out.

- [ ] **Step 1: Read the file and its own explanation**

`FlowWatcher.kt` opens with the reason it exists: Swift cannot start a coroutine, and Kotlin/Native erases a generic's type argument on the way to Objective-C, so the callback carries no payload and the value is read off a concretely-typed property. There are nine subclasses plus `SectionReadingWatcher`, and two factory objects (`SettingsFlows`, `StoreFlows`). Match all of it.

- [ ] **Step 2: Add three subclasses**

Following the existing pattern exactly:

```kotlin
class FriendsStateWatcher internal constructor(
    private val flow: StateFlow<FriendsState>,
) : Watcher() {
    var value: FriendsState = flow.value
        private set

    override suspend fun collect(onChange: () -> Unit) =
        flow.collect { value = it; onChange() }
}
```

and the same for `ConvoysStateWatcher` over `StateFlow<ConvoysState>` and `CirclesStateWatcher` over `StateFlow<CirclesState>`.

- [ ] **Step 3: Add the factory**

Beside `SettingsFlows` and `StoreFlows`, with a KDoc saying what it is for:

```kotlin
/**
 * The feature stores a SwiftUI screen binds to.
 *
 * One watcher per store rather than one per field: each distinct element type
 * costs a subclass above (see this file's header), and a coarse state object
 * keeps that at three classes instead of a dozen.
 */
object FeatureFlows {
    fun friends() = FriendsStateWatcher(FriendsStore.state)
    fun convoys() = ConvoysStateWatcher(ConvoysStore.state)
    fun circles() = CirclesStateWatcher(CirclesStore.state)
}
```

- [ ] **Step 4: Verify what can be verified, and say what cannot**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Note in the report that this does **not** type-check `iosMain`: the Apple metadata compilations are `SKIPPED` on this Linux host, so these three classes are unverified until a real Apple build. Slice A's `Enums.oidcEntropyBytes` is in the same position and for the same reason.

- [ ] **Step 5: Commit**

```bash
git add shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt
git commit -m "feat(ios): let Swift observe the three feature stores

One watcher per store, not per field: Kotlin/Native erases a generic's type
argument crossing to Objective-C, so each element type costs a concrete
subclass. A coarse state object per feature is what keeps that at three."
```

---

### Task 4: Android — the Friends screen

Deliverable: `FriendsScreen.kt` renders shared state and launches shared actions; behaviour and copy unchanged.

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt`

**Interfaces:**
- Consumes: `FriendsStore`, `FriendsState`, `ConvoysStore`, `ConvoysState` from Task 1.

- [ ] **Step 1: Replace `FriendsSection`'s state machine**

Delete the `lists`, `stats`, `busy`, `error`, `reloads` `remember`s, the `LaunchedEffect(reloads)`, the `own` `produceState`, and the local `act` helper. In their place:

```kotlin
    val scope = rememberCoroutineScope()
    val state by FriendsStore.state.collectAsStateWithLifecycle()

    LaunchedEffect(username) {
        FriendsStore.reload()
        FriendsStore.refreshOwn(username)
    }
```

Read the rest of the composable and repoint every reference: `lists` → `state.lists`, `stats` → `state.leaderboard`, `own` → `state.own`, `busy` → `state.busy`, `error` → `state.error`. Every `act { … }` becomes `scope.launch { FriendsStore.action(…) }`.

Actions do not throw on failure — they set `state.error` and return `String?`/`Boolean`. So a
`scope.launch { }` needs **no** `try`/`catch`: launch the action, and branch on its return value
only where the UI has something to do differently on success (clearing a text field, closing a
dialog). Do not add a `catch` that discards an error the store has already reported.

- [ ] **Step 2: Replace `ConvoysSection`'s membership state**

Same treatment for `convoys`, `busy`, `error`, `reloads`. **Keep** `createOpen`, `inviteFor`, `liveConvoyId`, `liveConnected`, `livePeers`, `liveError`, `pendingLiveConvoyId`, `micPermissionLauncher` and `goLive` exactly as they are — dialog state and live-relay state are not this slice's (spec, "Where the boundary falls").

- [ ] **Step 3: Confirm the copy did not move**

```bash
git diff -- app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt | grep -E '^[-+].*"' | grep -v '^[-+].*//' 
```

Read every string that appears. Any user-facing sentence that changed is a mistake unless the spec asked for it; none did.

- [ ] **Step 4: Build and test**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Report the line count before and after.

- [ ] **Step 5: Verify on the emulator**

The AVD `detour-api35` runs headless in the devcontainer `great_panini` (start it with the command in `.superpowers/sdd/progress.md` if it is not up). Read `.claude/skills/detour-adb/SKILL.md` first — the installed package is `io.github.maxke24.detour.debug`, **not** the Kotlin package, and `adb uninstall` / `pm clear` are forbidden for any reason.

```bash
devcontainer-exec ./gradlew :app:installDebug
adb shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity
```

There is no reachable server, so what is observable is the failure path — which is the one this slice changed. Capture evidence with `.claude/skills/detour-adb/scripts/capture-state.sh <scratch>/ emulator-5554` for:

1. Signed out: the Friends screen shows what it showed before.
2. With a server URL saved but unreachable: the friends list shows an error, **the spinner stops**, and the screen is not blank. That is `busy` clearing on the failure path and `failed` keeping its data — the two properties Task 1 added tests for, now on a real screen.

Assert only what the artifacts show. Say plainly that a populated-list path could not be exercised without a server.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt
git commit -m "refactor(ui): render friends and convoys from the shared stores

The load/busy/error/reload bookkeeping in FriendsSection and ConvoysSection is
gone; both now collect one StateFlow and launch suspend actions. Live-relay
state and the mic permission stay put — they are platform, and the relay itself
moves in its own change."
```

---

### Task 5: Android — the Circles screen

Deliverable: `CirclesScreen.kt` renders shared state; behaviour and copy unchanged.

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/CirclesScreen.kt`

**Interfaces:**
- Consumes: `CirclesStore`, `CirclesState` from Task 2.

- [ ] **Step 1: Replace the top-level state machine**

Delete `circles`, `busy`, `error`, `reloads`, `selectedId`, the `LaunchedEffect(reloads)`, the `LaunchedEffect(circles, openCircleId)` selection reconciliation, and the local `act`. In their place:

```kotlin
    val scope = rememberCoroutineScope()
    val state by CirclesStore.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { CirclesStore.reload() }
    LaunchedEffect(openCircleId) {
        openCircleId?.let { CirclesStore.select(it) }
    }
```

The selection reconciliation the old `LaunchedEffect(circles, openCircleId)` did is now `CirclesStore.loaded`'s job — it drops a `selectedId` that is not in the new list. Read that old effect carefully before deleting it and confirm the store reproduces what it did; if it did something more, say so rather than dropping the behaviour.

**Keep** `createOpen` and `inviteFor`.

- [ ] **Step 2: Replace `CircleDetailSection`'s state**

Delete `places`, `events`, `placesError`, `placesBusy`, `dataReloads`, the `LaunchedEffect(circle.id, dataReloads)` and the local `act`. Read them from `state` instead.

**Keep** `notifyEnabled` (already shared through `Settings.notifyArrivals`), `showBatteryPrompt`, `notifPermLauncher`, `shareOpen`, and `savedPlaces`.

- [ ] **Step 3: Confirm the copy did not move**

```bash
git diff -- app/src/main/java/com/jellemax/detour/ui/CirclesScreen.kt | grep -E '^[-+].*"' | grep -v '^[-+].*//'
```

- [ ] **Step 4: Build and test**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Report the line count before and after.

- [ ] **Step 5: Verify on the emulator — and know in advance what is out of reach**

Same setup as Task 4. **Task 4 established that the signed-in failure path cannot be exercised
in this environment**, and the same applies here: `CirclesScreen`'s content sits behind a
completed sign-in, and there is no reachable identity provider. Task 4's agent ruled out every
route to a forged session — no debug auth hook exists, `ConfigFile` import/export deliberately
excludes the session per its own doc comment, and hand-forging the Keystore-encrypted
`secure.xml` is neither feasible nor safe. Do not spend time rediscovering that, and do not
manufacture a session to get around it.

So verify what is actually reachable, and say plainly what is not:

1. The app builds, installs and runs; navigating to the Circles tab does not crash.
2. The signed-out state renders as it did before — that copy is untouched by this task.
3. `adb logcat` shows no exception mentioning `CirclesScreen`, `CirclesStore` or
   `collectAsStateWithLifecycle` across a launch-and-navigate cycle.

Capture each with `.claude/skills/detour-adb/scripts/capture-state.sh <scratch>/ emulator-5554`.

The store's own behaviour on failure — spinner clears, error set, last-known-good data kept — is
covered by `StoresTest` in `commonTest` and **that is the coverage of record for it**. Say so in
your report rather than implying a device confirmed it. A wrong device claim in a report gets
cited by later work and costs commits to undo.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/CirclesScreen.kt
git commit -m "refactor(ui): render circles from the shared store

Selection now lives with the list rather than beside it, so a circle that
vanished from the list cannot leave the detail pane pointed at nothing — the
reconciliation the screen did by hand is a tested transition in the store."
```

---

### Task 6: iOS — both screens, and the version

Deliverable: the Swift models are gone, the views bind to shared state, the version reflects the feature.

**Files:**
- Modify: `iosApp/Detour/FriendsScreen.swift` (delete `FriendsModel`)
- Modify: `iosApp/Detour/CirclesScreen.swift` (delete `CirclesModel`)
- Modify: `app/build.gradle.kts` (`versionName`)

**Interfaces:**
- Consumes: `FeatureFlows.friends()/convoys()/circles()` from Task 3, and every store action from Tasks 1 and 2.

- [ ] **Step 1: Replace `FriendsModel`**

Delete the class. Replace it with an `ObservableObject` that owns watchers rather than logic — the same shape `SettingsModel` in `iosApp/Detour/SettingsScreen.swift` already uses: hold the watchers, mirror their values into `@Published`, `cancel()` every one in `deinit`.

Actions become `Task { await FriendsStore.shared.request(username: name) }` — note there is no
`try?`, because an action does not throw on failure. It sets `state.error` and returns
`String?`/`Boolean`. A call site that clears a text field or dismisses a sheet on success must
branch on that return value; one that only needs the banner can ignore it. Read each site rather
than mapping them all the same way.

`CancellationException` **does** still cross, so the exported signature is `async throws` and
Swift still needs `try` — use `try?` and let a cancellation be silently discarded, which is what
a cancellation should be.

`signedIn` and `username` must stay `@Published` fed by the token watcher, exactly as slice A left them — that is what makes the screen react to a mid-session sign-in, and removing it silently reintroduces a bug slice A fixed.

- [ ] **Step 2: Replace `CirclesModel`**

Same treatment. Keep `CircleMapState`, and keep the `Task.isCancelled` guard slice A added to `loadPlacesAndEvents` — or rather, delete that function along with the state it loaded, and make sure whatever replaces it keeps the guard's effect: a cancelled load must not raise "Something went wrong". Say in your report how you preserved it.

`DetourShared.Group` must stay qualified wherever it appears.

- [ ] **Step 3: Bump the version**

`app/build.gradle.kts`: `versionName = "1.80.0"` → `"1.81.0"`. Minor — iOS gains the leaderboard's own-stats row, which is a new feature there. Leave `versionCode` alone.

- [ ] **Step 4: Verify, and be precise about what that means**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata \
  :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

Then read every edited Swift file top to bottom and report, per file, what you checked: exported Kotlin spellings (`FriendsStore.shared`, `FeatureFlows.shared.friends()`, action argument labels), `DetourShared.Group` qualification, nothing newer than Swift 5.9 / iOS 17, no `MainActor.assumeIsolated`, and every watcher cancelled in `deinit`.

**Do not claim the Swift compiles.** It has not been through a compiler and neither has slice A's. State that in the report.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Detour/FriendsScreen.swift iosApp/Detour/CirclesScreen.swift \
        app/build.gradle.kts
git commit -m "feat(ios): bind friends and circles to the shared stores

FriendsModel and CirclesModel are deleted: the reload/act/error logic they held
is the same logic the Compose screens held, and it now lives once in
commonMain. What is left on this side owns watchers, not decisions.

iOS gains the leaderboard's own-stats row, which only Android had, because the
computation moved with the state — hence the minor bump."
```

---

## Self-Review

**Spec coverage.** Three stores → Tasks 1 and 2. Watchers and factory → Task 3. Android rewiring → Tasks 4 and 5. iOS rewiring and the version bump → Task 6. `commonTest` coverage → Tasks 1 and 2. The spec's per-value boundary table is enforced by the explicit "keep" lists in Tasks 4, 5 and 6. The `@Throws` requirement is in Global Constraints and in every store's code. The `own`-row parity gain is Task 1 Step 4 and named in Task 6's commit message.

**Placeholder scan.** No TBDs. Tasks 1 and 3 carry complete code. Tasks 2, 4, 5 and 6 give exact deletions, exact replacements and the properties the result must satisfy rather than reproducing 700-line files — with the authority named in each case (the existing call site, the real type, the old effect) so the implementer resolves ambiguity against the code and not against a guess. Three steps say explicitly what to do if the plan's copied shape does not resolve.

**Type consistency.** `FriendsState`/`ConvoysState`/`CirclesState`, `FriendsStore`/`ConvoysStore`/`CirclesStore`, `starting`/`loaded`/`failed`/`selecting`, `FALLBACK_ERROR`, and `FeatureFlows.friends()/convoys()/circles()` are spelled identically in every task and in the tests. `unsharePlace(serverId:)` matches `CirclePlaces.delete(serverId)`. `CircleEvents.events(groupId, sinceMs = 0L)` matches the Android call site.

**Risk the executor must carry.** Every Swift edit in Task 6 lands on a base whose own Swift has never been compiled. Do not treat a clean read as a build, and expect the eventual CI run to surface slice A's and slice B's Swift errors together.
