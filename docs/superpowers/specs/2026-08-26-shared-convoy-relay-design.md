# Shared convoy relay

Slice C of four moving account, friends, circles and shared location toward parity on iOS. Slice A
made the account-gated features reachable there; slice B moved their request/response bookkeeping
into shared stores. This slice takes the largest single duplication in the project: the convoy
live relay.

## What is duplicated, measured

| | Lines |
|---|---|
| `app/src/main/java/com/jellemax/detour/net/ConvoyLiveClient.kt` | 693 |
| `iosApp/Detour/ConvoyLiveClient.swift` | 600 |

Two independent implementations of one wire protocol — 9 inbound frame types (`joined`, `left`,
`positions`, `ptt_start`, `ptt_end`, `ptt_audio`, `place_event`, `spin_offer`, `spin_vote`) and 7
outbound (`join`, `location`, `ptt_start`, `ptt_end`, `ptt_audio`, `spin_offer`, `spin_vote`), plus
peer TTL pruning, reconnect backoff, a location send cadence, and the spin-offer voting rule.

Both files carry the same tuning constants and, in the Swift case, say so in a comment. This is
the same pattern `docs/refactor/mapscreen/13-surface-independence-audit.md` found in
`TripRecorder.swift` — nineteen thresholds copied verbatim with a header admitting it.

**The spin rule is the reason this matters beyond line count.** Both implementations document that
a one-candidate offer is not a sheet to vote on but the sharer announcing the winner, and that
every device seeing one must commit — because a member whose view of who is still live differs
(a peer gone quiet for 20 s is pruned on one phone and not another) would otherwise resolve the
same votes into a *different destination*. Two implementations of a rule whose whole purpose is
that both sides agree is a standing invitation to a convoy splitting in half.

## The transport question, and why the socket is not shared

Ktor's WebSockets plugin would let the socket live in `commonMain` too, and its `pingInterval` is
common code — which would close the keep-alive divergence `docs/IOS_PORT.md` records (OkHttp has
`pingInterval`; `URLSessionWebSocketTask` does not, so iOS schedules the ping by hand).

That was investigated and rejected, and the reasoning is a judgement rather than a fact:
`ktor-client-core`'s iOS variants do pull `ktor-websocket-serialization` → `ktor-websockets`, so
the protocol types are present for iOS targets — but the `ktor-client-darwin` module declares no
websocket dependency, engine WebSocket support lives *inside* the engine rather than in a
dependency, and nothing in this environment can compile an iOS target to find out. Staking the
iOS half of a 1,290-line slice on an unverified engine capability, on top of two slices of Swift
that have never been through a compiler, is a bad trade for removing 60 lines per platform.

So the socket stays platform, behind a seam:

```kotlin
/** The one thing the relay cannot do for itself. Two implementations, which is
 *  what earns an interface here (CONTRIBUTING.md:40). */
interface RelaySocket {
    /** Opens the socket. Returns when it closes, however it closes. */
    suspend fun open(url: String, headers: Map<String, String>, onText: (String) -> Unit)
    suspend fun send(text: String)
    fun close()
}
```

Android implements it over the existing `OkHttpClient` with its `pingInterval`; iOS over the
existing `URLSessionWebSocketTask` with its hand-rolled ping. Both already work and neither
changes behaviour. The ping divergence stays, documented, and becomes a one-file follow-up if
Darwin's support is ever confirmed.

## The shape that carries the state machine

Slice B's answer — actions are `suspend`, the platform supplies the coroutine — covers
request/response and not a receive loop. A relay needs one long-lived coroutine.

```kotlin
object ConvoyRelay {
    val peers: StateFlow<Map<String, FriendPosition>>
    val talking: StateFlow<Set<String>>
    val connected: StateFlow<Boolean>
    val lastError: StateFlow<String?>
    val spinOffer: StateFlow<GroupSpin?>
    val spinVotes: StateFlow<Map<String, Int>>
    val audioChunks: SharedFlow<IncomingAudioChunk>
    val placeEvents: SharedFlow<RelayPlaceEvent>

    /** Stays connected — reconnecting with [Backoff] — for as long as anything
     *  wants the socket, and suspends for the life of it. */
    suspend fun run(socket: RelaySocket, bearer: suspend () -> String)

    /** The convoy this device is in, or null. Joining or leaving one re-joins
     *  on the live socket rather than reopening it. */
    fun setConvoy(groupId: String?)

    /** The circles that want live arrival events. Additive with [setConvoy]:
     *  one socket serves a convoy and any number of circles at once. */
    fun setNotifyingCircles(ids: Set<String>)

    fun stop()
    suspend fun sendLocation(lat: Double, lon: Double, headingDeg: Double?, speedKmh: Double?)
    fun sendPttStart(); fun sendPttEnd(); fun sendAudioChunk(pcm: ByteArray)
    fun sendSpinOffer(candidates: List<SpinCandidate>); fun sendSpinVote(index: Int)
}
```

`run()` owns the connect → receive → backoff → reconnect loop and returns only when `stop()` has
been called. Each platform launches it from whatever already keeps it alive: Android's
`ConvoyLiveService`, iOS's `Task` in `ConvoyLiveClient`.

> **Correction, after Task 2.** An earlier revision of this document gave `run()` a required
> `groupId` while also listing `setNotifyingCircles`, and never reconciled the two. That shape
> cannot express what both existing clients actually do: the socket is **additive**, and
> `CircleNotifyService` holds it open with **no convoy joined at all** — `setNotifyCircles` opens
> the connection whenever `circleIds.isNotEmpty() || activeConvoyId != null`, and one socket
> serves a convoy plus any number of circles simultaneously. A one-group-per-call `run()` would
> have left circle arrival notifications with no transport on either platform.
>
> So what the socket is joined to is **state**, not a parameter: a nullable convoy id and a set of
> circle ids, either of which can change while connected, and `run()` stays up while either is
> non-empty and re-joins on change rather than reopening. `shouldStayConnected()` in the Android
> client is the existing expression of that rule and is what the shared version reproduces.

### `stop()` is a flag, not a cancellation, and that is load-bearing

`run()` must observe an explicit stop signal rather than relying on its coroutine being cancelled,
because **cancelling a Swift `Task` does not cancel the Kotlin coroutine behind an exported
`suspend` function** — the Objective-C completion-handler bridge has no cancellation path. This was
established empirically during slice B, and it is exactly what produced the leak where the relay
socket outlived a sign-out and kept broadcasting the *next* rider's GPS to the previous rider's
circle. A shared relay that assumed cancellation would reintroduce that with a wider blast radius.

Consequences, all deliberate:

- `stop()` sets a `MutableStateFlow<Boolean>` the loop checks at every await boundary, and closes
  the socket so a blocking receive returns.
- The loop is also cancellation-*safe* — a genuinely cancelled Kotlin job on Android must not
  leave the socket open — so it needs both, and `CancellationException` is rethrown ahead of every
  generic catch as it is in the slice B stores.
- `stop()` is wired to `Auth.sessionEpoch` as well as to the user's own "go offline", because slice
  B found the button-only trigger misses the 401 and server-switch paths.

## Scope

In scope:

- `shared/…/drive/RelayProtocol.kt` — the codec: frame string → sealed `RelayEvent`, and the seven
  outbound builders. Pure, no I/O, no state.
- `shared/…/drive/ConvoyRelay.kt` — the state machine above, plus peer pruning and backoff.
- `shared/…/drive/RelaySocket.kt` — the interface.
- `app/…/net/OkHttpRelaySocket.kt` and `iosApp/Detour/UrlSessionRelaySocket.swift` — the two
  implementations, lifted from the existing clients.
- `app/…/net/ConvoyLiveClient.kt` reduced to whatever Android still needs on top (mostly nothing),
  and `iosApp/Detour/ConvoyLiveClient.swift` likewise.
- First tests over any of this: the codec and the state machine are pure and have never had one.

Out of scope, deliberately:

- **Push-to-talk audio capture and playback.** `app/…/audio/PushToTalk.kt` (153) and
  `iosApp/Detour/PttAudio.swift` (169) are `AudioRecord`/`AudioTrack` against `AVAudioEngine` and
  share nothing. The *framing* of `ptt_audio` — base64 of a PCM chunk — is shared; the PCM is not.
- **`app/…/convoy/ConvoyLiveService.kt`** (231). A foreground service with audio focus, a
  notification and a microphone permission type. Platform by construction.
- **Circle presence and notification policy.** Slice D.
- **The keep-alive ping.** Stays per-platform with the transport, as above.
- **The `spin_offer` UI.** `SpinCards`/`SpinResultHolder` on Android and `SpinModel` on iOS render
  the offer; only the protocol and the vote resolution move.

## Location is pushed in, not reached for

The Android client reads `TripTrackingService.lastFix`; the Swift one reads `LocationBroadcast`.
Neither can exist in `commonMain`, and `Platform.kt`'s three-concern ceiling forbids adding a
fourth for it. So `sendLocation(lat, lon, headingDeg, speedKmh)` takes the fix as arguments and
each platform's existing collector calls it on the shared cadence constant. That is the same
"the core is handed things, it never reaches for them" rule `docs/IOS_PORT.md` states.

The send cadence itself (2 s) moves into the shared object as a constant, so the two platforms
cannot drift on it.

## One rule worth naming, because it is subtle and currently duplicated

A peer's expiry is anchored to **arrival time, not the sender's timestamp**:

```kotlin
expiresAtMs = now + if (ttlSeconds > 0) ttlSeconds * 1_000L else FALLBACK_PEER_TTL_MS
```

The Android comment explains why — the `ts` in the frame comes off the sender's clock, and a phone
whose clock is minutes out would otherwise vanish immediately or linger forever. Sharing this puts
one clock-skew defence in one place. `FALLBACK_PEER_TTL_MS` (20 s) applies only when the relay
sends no usable `ttl`, i.e. an older server.

## Error handling

`lastError` exists because "live" only means this device is *trying* to stay connected — a relay it
cannot reach, a join the server rejects, and a peer who is simply not sending fixes yet all look
identical without it, which is how a convoy where nobody saw anybody once stayed silent. That
reasoning is already in `ConvoysSection`'s comment and the shared version keeps it.

Frame parsing is lenient by design: an unknown `type` is ignored rather than fatal, and a
malformed field drops that peer rather than the frame. A relay is allowed to be newer than the
client.

## Tests

`shared/src/commonTest/`, plain `kotlin.test`. The codec is a pure string-to-object function, so
this is the best-tested thing in the slice and the first coverage any of it has had:

- Each of the 9 inbound frames decodes to its event, and an unknown `type` decodes to nothing
  rather than throwing.
- A `positions` frame with a missing `u`, a NaN `lat` or no `ttl` drops that peer, keeps the rest,
  and falls back to `FALLBACK_PEER_TTL_MS` for the last case.
- Peer expiry is computed from a passed-in `nowMs`, not an ambient clock, so the test can assert
  it — the shape `GeofenceEvaluator` and `RouteGpx.parseGpx(text, nowMs)` already use.
- Pruning removes exactly the expired peers.
- **The spin rule**: a one-candidate offer resolves to a commit on every device; a multi-candidate
  offer tallies votes; and two devices with *different* peer sets resolve the same offer to the
  same destination — the property both implementations exist to preserve and neither tests.
- Each of the 7 outbound builders produces the frame the relay expects.
- `stop()` ends `run()` without relying on cancellation.

The socket is the seam, so `run()` is testable against a fake `RelaySocket` that replays frames —
which is the first time either platform's relay logic can be exercised without two phones and a
server.

## Verification, and its limits

- `commonTest` via `:shared:testDebugUnitTest`, plus `:shared:compileCommonMainKotlinMetadata`.
- Android: the app builds and the convoy screen still functions on the emulator as far as a
  signed-out device allows — which, as slice B established, is not far. A real convoy needs two
  signed-in devices and a relay, and this environment has neither.
- **iOS: unverified, as in slices A and B.** No Xcode; the Apple targets are `SKIPPED`. This branch
  stacks on two unmerged slices whose Swift has also never compiled, so the first real build
  surfaces all three at once.
- **The end-to-end behaviour of this slice cannot be tested here at all.** Two devices, two
  accounts and a running relay are required, and the fake-socket tests are what stands in for it.
  That is worth stating plainly rather than implying the tests cover the feature.

## Follow-ups this creates

1. Confirm Darwin WebSocket support; if present, collapse the two socket implementations and the
   ping divergence into one shared transport.
2. Slice D: circle presence and notification policy.
3. `ConvoyLiveClient.peers` was judged self-healing on a ~20 s TTL prune during slice B's leak
   audit. That judgement was wrong while the socket outlived the session — the socket kept
   refreshing the entries so the prune never expired them. The shared version's `stop()` on a
   session change is what makes the original judgement true.
