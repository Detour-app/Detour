# Shared convoy relay — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the convoy live relay's wire protocol and state machine into `shared/commonMain`, leaving each platform only its WebSocket implementation — replacing 693 lines of Kotlin and 600 of Swift with one implementation plus two small sockets.

**Architecture:** A pure codec (`RelayProtocol`), a state machine (`ConvoyRelay`) whose `run()` owns the connect/receive/backoff loop, and a two-implementation `RelaySocket` interface. `run()` observes an explicit `stop()` rather than relying on cancellation, because cancelling a Swift `Task` does not cancel the Kotlin coroutine behind an exported suspend function.

**Tech Stack:** Kotlin Multiplatform (`:shared`), kotlinx-coroutines `StateFlow`/`SharedFlow`, okio (base64 for PCM chunks), `kotlin.test`; OkHttp WebSocket (Android), `URLSessionWebSocketTask` (iOS).

**Spec:** `docs/superpowers/specs/2026-08-26-shared-convoy-relay-design.md`

## Global Constraints

- **All tooling runs inside the devcontainer.** `devcontainer-exec ./gradlew …`. NEVER on the host — its JDK is 26 and it has no Android SDK. The AVD `detour-api35` is running in container `great_panini` as `emulator-5554`.
- **`commonMain` has no `Dispatchers`, no logger, no `java.*`.** The relay gets its coroutine from whichever platform calls `run()`. Verified by `:shared:compileCommonMainKotlinMetadata`.
- **`org.json` does not exist in `commonMain`.** Both existing clients use it heavily; port to `Json.kt`'s `opt*` helpers, which is the same substitution `docs/IOS_PORT.md` records.
- **`android.util.Base64` does not exist either.** PCM chunks use okio `ByteString.base64()` / `decodeBase64()`, unpadded where the existing wire format is unpadded — **check the existing frames before assuming**.
- **No ambient clock.** Peer expiry takes `nowMs` as a parameter so a test can assert it, the shape `GeofenceEvaluator` and `RouteGpx.parseGpx(text, nowMs)` already use. `nowMs()` exists (`Angles.kt`) but is `internal` and must not steer a decision a test needs to reproduce.
- **Every exported `suspend` function needs `@Throws(Exception::class)`** — without it a Kotlin/Native suspend function propagates only `CancellationException` and everything else **terminates the Swift process**. See the canonical comment in `SyncClient.kt`.
- **Every generic `catch (e: Exception)` is preceded by `catch (e: CancellationException) { throw e }`.** House pattern in `SpinPicker.kt`, and it has been broken twice in this work already.
- **`stop()` is a flag, never a cancellation assumption.** Cancelling a Swift `Task` does not reach the Kotlin coroutine. `run()` checks a `MutableStateFlow<Boolean>` at every await boundary *and* closes the socket so a blocking receive returns. The loop must also be cancellation-safe for Android, where the job genuinely is cancelled.
- **Wire compatibility is absolute.** The relay is deployed; every frame this produces must be byte-identical in shape to what the current clients send, and every frame it parses must be one the current relay sends. A field renamed is a broken convoy. Read both existing clients for each frame before writing its builder.
- **Do not change tuning constants.** `LOCATION_SEND_INTERVAL_MS` 2000, `MIN_BACKOFF_MS` 1000, `MAX_BACKOFF_MS` 30000, `PEER_PRUNE_INTERVAL_MS` 5000, `FALLBACK_PEER_TTL_MS` 20000. They move; they do not change.
- **Every user-facing string byte-identical.** This slice moves logic, not copy.
- **iOS cannot be compiled here.** No Xcode, Apple targets `SKIPPED`, and this branch stacks on two unmerged slices whose Swift has also never compiled. Never claim Swift compiles; read it back and say what you checked.
- Swift 5.9 / iOS 17. `MainActor.assumeIsolated` (5.10) must not appear. `DetourShared.Group` stays qualified.
- **No `Co-Authored-By` and no `Claude-Session` trailer, ever.** Conventional-commits, subjects under ~72 chars.
- Do not bump `versionName` — Task 6 owns it.
- **Branch:** `feat/shared-convoy-relay`, stacked on slice B. Spec committed as `1d2e537`.

---

### Task 1: The codec

Pure string-to-object and object-to-string. No state, no I/O, no socket. Deliverable: the whole wire protocol, tested, with nothing using it yet.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/RelayProtocol.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/RelayProtocolTest.kt`

**Interfaces:**
- Consumes: `Json.kt`'s `jsonObjectOf`/`optString`/`optDouble`/`optInt`/`optLong`/`optArray`/`optObject`, `placeEventFromRelayFrame` (`CircleEvents.kt`), `LatLon`.
- Produces, relied on by Task 2: a sealed `RelayEvent` hierarchy, `RelayProtocol.decode(text: String, nowMs: Long): RelayEvent?`, and one builder per outbound frame.

- [ ] **Step 1: Read both existing implementations, frame by frame**

`app/src/main/java/com/jellemax/detour/net/ConvoyLiveClient.kt` and `iosApp/Detour/ConvoyLiveClient.swift`. For each of the 9 inbound and 7 outbound frames, write down the exact field names and types **from both files**, and note any place they disagree — a disagreement is a bug in one of them and needs reporting, not silently picking a side.

The inbound set: `joined`, `left`, `positions`, `ptt_start`, `ptt_end`, `ptt_audio`, `place_event`, `spin_offer`, `spin_vote`. The outbound set: `join`, `location`, `ptt_start`, `ptt_end`, `ptt_audio`, `spin_offer`, `spin_vote`.

Note `place_event` already has a shared parser — `placeEventFromRelayFrame` in `CircleEvents.kt`. Use it rather than writing a second one.

- [ ] **Step 2: Write the failing tests**

`RelayProtocolTest.kt`, house style: plain `kotlin.test`, a class KDoc saying what contract it covers and why, sentence-shaped camelCase names, private fixture builders, a comment above any assertion whose point is not obvious.

Cover at least:
- each inbound frame decodes to its event, with every field carried through
- an unknown `type` decodes to `null` rather than throwing — a relay may be newer than the client
- a `positions` frame with a blank `u`, a NaN `lat`, or a missing `ttl`: the first two drop that peer and keep the rest, the third falls back to `FALLBACK_PEER_TTL_MS`
- peer `expiresAtMs` is computed from the passed-in `nowMs`, **not** the frame's `ts` — assert with two different `nowMs` values that the result moves with `nowMs` and not with `ts`, because that is the clock-skew defence and the reason the field exists
- each outbound builder produces the exact field names the relay expects
- a malformed frame (not JSON at all) decodes to `null`

- [ ] **Step 3: Run to verify failure**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*RelayProtocolTest*'
```

Expected: FAIL at compilation, `Unresolved reference: RelayProtocol`.

- [ ] **Step 4: Write the codec**

`RelayProtocol.kt`. A sealed `RelayEvent` with one subtype per inbound frame, `decode(text, nowMs)` returning it or null, and seven builders. Keep the existing types where they already exist and are shared-safe — `FriendPosition`, `SpinCandidate`, `GroupSpin`, `IncomingAudioChunk` currently live in the Android client and must move here; check whether iOS's structs agree field-for-field first.

Carry over the comments that explain *why*, not just what — in particular the `positions` batching note ("one packet a round instead of seven, and the packet count is what a phone's radio actually pays for") and the arrival-anchored expiry note. Those are the reasons the code is shaped as it is and they belong with it.

- [ ] **Step 5: Run to verify pass, and check the intersection**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

Expected `BUILD SUCCESSFUL`, and 231 pre-existing tests still green.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/RelayProtocol.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/RelayProtocolTest.kt
git commit -m "feat(shared): one codec for the convoy relay's wire protocol

Sixteen frame types were parsed and built twice, in Kotlin and again in Swift,
with the same field names typed out on both sides. The codec is pure string work,
so it belongs where a test can reach it — and until now neither copy had one.

The clock-skew defence gets a test it never had: a peer's expiry is anchored to
arrival rather than to the sender's timestamp, because a phone whose clock is
minutes out would otherwise vanish immediately or linger forever."
```

---

### Task 2: The state machine and the socket seam

Deliverable: `ConvoyRelay` runs a whole convoy against a fake socket, tested, with neither platform wired to it.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/RelaySocket.kt`
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt`
- Create: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/ConvoyRelayTest.kt`

**Interfaces:**
- Consumes: Task 1's codec, `Backoff` (`drive/Backoff.kt`), `Auth.bearer()`, `Auth.sessionEpoch`, `RoutingServer`.
- Produces, relied on by Tasks 3-5: the `RelaySocket` interface and the `ConvoyRelay` surface in the spec.

- [ ] **Step 1: Read what the loop currently does on both sides**

The Android `connectAndAwaitClose` / reconnect loop, and the Swift equivalent. Note especially: which failures reconnect and which give up, what `everJoined` is for, when `lastError` is set and cleared, and the prune cadence. The spec says behaviour does not change; that means matching this, not improving it.

- [ ] **Step 2: Write the failing tests**

`ConvoyRelayTest.kt`, driven against a fake `RelaySocket` you write in the test file — this is the first time either platform's relay logic can be exercised without two phones and a relay, and the fake is what makes it possible.

Cover at least:
- joining emits a `join` frame carrying the group id
- a `positions` frame populates `peers`; a later one for the same peer replaces rather than duplicates
- pruning removes exactly the expired peers and leaves the rest, driven by a passed-in `nowMs`
- `ptt_start` / `ptt_end` add and remove from `talking`
- **the spin rule, which is the point**: a one-candidate offer resolves to a commit on every device; a multi-candidate offer tallies votes; and two relays given the *same* offer but *different* peer sets resolve to the *same* destination. Neither existing implementation tests this, and both document that a convoy splits in half if they disagree.
- `stop()` makes `run()` return, **without** cancelling its coroutine — assert this specifically, because cancellation is exactly what cannot be relied on from Swift
- a socket that closes unexpectedly reconnects with backoff, and `connected` reflects it
- `lastError` distinguishes "cannot reach the relay" from "joined but nobody is sending", which is the distinction it exists for

- [ ] **Step 3: Run to verify failure**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*ConvoyRelayTest*'
```

- [ ] **Step 4: Write `RelaySocket` and `ConvoyRelay`**

The interface as the spec gives it. `ConvoyRelay` as the spec's surface, with:

- `run(groupId, socket, bearer)` owning connect → receive → backoff → reconnect, returning only after `stop()`.
- A `MutableStateFlow<Boolean>` stop flag checked at every await boundary, and `stop()` also closing the socket so a blocking receive returns.
- `CancellationException` rethrown ahead of every generic catch, and the socket closed on that path too — Android's job is genuinely cancellable even though iOS's is not.
- `@Throws(Exception::class)` on every exported suspend function.
- The five tuning constants, moved unchanged.
- Peer pruning on `PEER_PRUNE_INTERVAL_MS`.

`sendLocation(lat, lon, headingDeg, speedKmh)` takes the fix as arguments — the core is handed things and never reaches for them, and `Platform.kt`'s three-concern ceiling forbids a location `expect`.

- [ ] **Step 5: Verify**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest
```

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/RelaySocket.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/ConvoyRelayTest.kt
git commit -m "feat(shared): one convoy relay state machine, behind a socket seam

Peers, pruning, backoff, push-to-talk membership and the spin vote were written
twice. They now run once, against a RelaySocket the platforms implement — which
also means the relay can be tested against a fake socket instead of two phones
and a running server.

stop() is a flag rather than a cancellation, deliberately: cancelling a Swift
Task does not cancel the Kotlin coroutine behind an exported suspend function,
which is how a socket previously outlived a sign-out and kept broadcasting the
next rider's position to the previous rider's convoy.

The spin rule finally has a test proving two devices with different peer sets
resolve one offer to the same destination — the property both old copies
documented and neither checked."
```

---

### Task 3: The Android socket, and reducing the client

Deliverable: Android runs on the shared relay; behaviour unchanged.

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/net/OkHttpRelaySocket.kt`
- Modify: `app/src/main/java/com/jellemax/detour/net/ConvoyLiveClient.kt` — reduced to whatever remains
- Modify: `app/src/main/java/com/jellemax/detour/convoy/ConvoyLiveService.kt` — launches `run()`
- Modify call sites: `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt`, `ui/MapScreen.kt`, `car/CarMapRenderer.kt`, `notif/CircleNotifyService.kt`, `audio/PushToTalk.kt` as needed

- [ ] **Step 1: Extract the socket**

`OkHttpRelaySocket` implements `RelaySocket` over the existing `OkHttpClient` — **keeping its `pingInterval(20, SECONDS)`**, which is what stops NAT and the Cloudflare tunnel idling a quiet socket closed. Move the Cloudflare Access header handling with it.

- [ ] **Step 2: Point `ConvoyLiveService` at `run()`**

The service already owns a `CoroutineScope`; launch `ConvoyRelay.run(...)` there and call `ConvoyRelay.stop()` where it currently stops the client. Keep the audio-focus handling, the notification and the foreground type exactly as they are.

- [ ] **Step 3: Repoint every reader**

`grep -rn "ConvoyLiveClient" app/src/main/java/` and repoint each to `ConvoyRelay`. There are readers in the map, the car renderer, the Friends screen and the circle-notify service — do not miss the car one, which slice B's leak audit found had been missed twice before.

Slice B wired the iOS socket teardown to `Auth.sessionEpoch` and deliberately left Android on the button-only trigger, because Android already refuses to open a *new* socket without a valid bearer. Now that the loop is shared, check whether the shared `stop()`-on-epoch covers Android for free; if it does, say so and remove the note from the follow-ups.

- [ ] **Step 4: Verify**

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug
```

Report the line count of `ConvoyLiveClient.kt` before and after.

- [ ] **Step 5: Device pass, and know what is out of reach**

Read `.claude/skills/detour-adb/SKILL.md` first. The installed package is `io.github.maxke24.detour.debug`, **not** the Kotlin package. **Never** `adb uninstall` or `pm clear`.

A real convoy needs two signed-in devices and a relay; this environment has neither, and slice B established the signed-in path is unreachable here at all. So verify only: the app builds, installs and runs on `emulator-5554`; the Friends screen's convoy section renders as before when signed out; and `adb logcat` shows no exception mentioning `ConvoyRelay`, `RelaySocket` or `ConvoyLiveService` across a launch-and-navigate cycle. Capture with `.claude/skills/detour-adb/scripts/capture-state.sh <scratch>/ emulator-5554`.

Say plainly that convoy behaviour itself was not exercised. `ConvoyRelayTest` against the fake socket is the coverage of record.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/net/ app/src/main/java/com/jellemax/detour/convoy/ \
        app/src/main/java/com/jellemax/detour/ui/ app/src/main/java/com/jellemax/detour/car/ \
        app/src/main/java/com/jellemax/detour/notif/ app/src/main/java/com/jellemax/detour/audio/
git commit -m "refactor(convoy): run Android on the shared relay

ConvoyLiveClient keeps the OkHttp socket and its pingInterval and hands the rest
to shared ConvoyRelay. The service, the audio focus and the notification are
untouched — they are platform by construction."
```

---

### Task 4: The iOS socket, and reducing the client

Deliverable: iOS runs on the shared relay.

**Files:**
- Create: `iosApp/Detour/UrlSessionRelaySocket.swift`
- Modify: `iosApp/Detour/ConvoyLiveClient.swift` — reduced
- Modify: `iosApp/Detour/MapScreen.swift`, `FriendsScreen.swift`, `PttAudio.swift`, `CircleNotifications.swift` as needed

- [ ] **Step 1: Extract the socket**

`UrlSessionRelaySocket` conforming to the Kotlin `RelaySocket` protocol as it is exported to Swift, over the existing `URLSessionWebSocketTask` — **keeping the hand-scheduled ping**, since `URLSessionWebSocketTask` has no `pingInterval` and `docs/IOS_PORT.md` records that as a deliberate divergence.

Implementing a Kotlin interface from Swift means conforming to the generated protocol; the suspend method arrives as a completion-handler shape. Check how the generated header spells it before writing the conformance, and if the shape is awkward, say so in your report rather than fighting it silently.

- [ ] **Step 2: Launch `run()` and keep the session teardown**

The Swift client already watches `Auth.sessionEpoch` and calls `sessionEnded()` — slice B added that to close a leak where the socket outlived a sign-out and kept publishing the next rider's GPS to the previous rider's convoy. **That behaviour must survive**: whatever replaces it calls the shared `stop()` on an epoch change. Do not let it regress; it is the most serious defect fixed in this work so far.

- [ ] **Step 3: Repoint every reader**

`grep -rn "ConvoyLiveClient" iosApp/Detour/` and repoint each. `MapScreen` holds it as an `@ObservedObject`, so whatever exposes `peers` to SwiftUI needs a watcher — check `FlowWatcher.kt` for an element type that already exists before adding a subclass, and note `Map<String, FriendPosition>` and `Set<String>` are new element types if so.

- [ ] **Step 4: Verify what can be verified**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest :app:assembleDebug
```

Then read every edited Swift file top to bottom and report per file what you checked: exported Kotlin spellings and argument labels against the Kotlin source, `Int32`/`Int64` conversions, `DetourShared.Group` qualification, nothing newer than Swift 5.9 / iOS 17, watchers cancelled in `deinit`.

**Do not claim the Swift compiles.** Three slices of Swift are now unbuilt and the first Xcode run surfaces all of them together.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Detour/
git commit -m "feat(ios): run the convoy relay from shared code

ConvoyLiveClient.swift keeps the URLSession socket and its hand-scheduled ping —
URLSessionWebSocketTask has no pingInterval — and hands the protocol and the
state machine to shared ConvoyRelay. The session-epoch teardown slice B added is
preserved: it is what stops a socket outliving a sign-out and broadcasting the
next rider's position to the previous rider's convoy."
```

---

### Task 5: Delete what is now dead, and prove nothing else referenced it

Deliverable: no second implementation of anything the shared relay owns.

**Files:** whatever Tasks 3 and 4 left behind.

- [ ] **Step 1: Find the dead code**

Both old clients held types the shared codec now owns — `FriendPosition`, `SpinCandidate`, `GroupSpin`, `IncomingAudioChunk` — plus their parsing and their constants. Anything now unreachable goes.

Grep both platforms for each moved constant and type. **An unused export is not a neutral leftover**: three appeared during slice B (`FriendsStore.remove`, `FriendsStore.request`, and one more) and each had to be found by grep rather than by review.

- [ ] **Step 2: Verify the counts**

Report, for each of the four files this slice set out to shrink, the line count before this branch and after: `app/.../net/ConvoyLiveClient.kt` (was 693), `iosApp/Detour/ConvoyLiveClient.swift` (was 600), and the two new sockets. The spec claims ~1290 duplicated lines become one implementation plus two small sockets — say whether that held, and if it did not, say why rather than restating the claim.

- [ ] **Step 3: Verify**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata :shared:testDebugUnitTest \
  :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(convoy): delete the second relay implementation"
```

---

### Task 6: Version and the port record

- [ ] **Step 1: Bump**

`app/build.gradle.kts`: `versionName = "1.81.0"` → `"1.82.0"`. Minor — the relay is shared for the first time, and iOS behaviour changes where the two implementations had drifted. `versionCode` is CI-stamped; never touch it.

- [ ] **Step 2: Update `docs/IOS_PORT.md`**

Its "Where the two platforms deliberately differ" section names **Convoy keep-alive** as a divergence: OkHttp has `pingInterval`, `URLSessionWebSocketTask` does not, so iOS pings by hand. That is still true and stays — but it is now the *only* convoy divergence, since everything above the socket is shared. Say that.

Add to "Done" that the relay is shared, and note what is still not: push-to-talk capture and playback, which have no common ground.

- [ ] **Step 3: Verify and commit**

```bash
devcontainer-exec ./gradlew :app:assembleDebug
git add app/build.gradle.kts docs/IOS_PORT.md
git commit -m "chore: bump to 1.82.0 and record the shared relay"
```

---

## Self-Review

**Spec coverage.** Codec → Task 1. State machine and seam → Task 2. Android → Task 3. iOS → Task 4. Dead code → Task 5. Version and docs → Task 6. The spec's out-of-scope list is enforced by Tasks 3 and 4 naming what stays (the service, the audio, the ping). The spin-rule test the spec calls the point of the slice is Task 2 Step 2.

**Placeholder scan.** No TBDs. Tasks 1 and 2 specify what to cover rather than pasting whole files, because the authority is the two existing clients and a plan that transcribed them would be a third copy to drift — every step that needs a value names where to read it. Task 1 Step 1 makes reading both sides the first action, and requires reporting disagreements rather than picking a side.

**Type consistency.** `RelayProtocol`, `RelayEvent`, `RelaySocket`, `ConvoyRelay`, `run`/`stop`/`sendLocation`/`sendPttStart`/`sendPttEnd`/`sendAudioChunk`/`sendSpinOffer`/`sendSpinVote`/`setNotifyingCircles` are spelled identically in every task. The five constants are named identically to the existing Android ones so a diff shows a move.

**The risk the executor must carry.** Wire compatibility is absolute and untestable here — the relay is deployed, and a renamed field is a broken convoy with no local test that would catch it. The fake-socket tests prove the client agrees with *itself*, not with the server. Read both existing clients for every frame, and treat any disagreement between them as a finding.
