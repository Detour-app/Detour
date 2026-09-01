import Foundation
import CoreLocation
import DetourShared

/// The single app-wide `ConvoyRelay` — see its class doc's "exactly one
/// instance app-wide, and exactly one live run() at a time" paragraph — plus
/// the platform glue `ConvoyRelay` deliberately does not provide:
/// `Features.liveRelay`/"no live server configured" guards, launching its
/// `run()` loop, and the stable surface `MapScreen`, `ConvoyBar`,
/// `FriendsScreen`, `CirclesScreen`, `CircleSync` and `CircleNotifications`
/// read or send through, none of which should reach into `ConvoyRelay`
/// directly. Swift-side counterpart of `app/.../net/ConvoyLiveClient.kt`
/// (224 lines) — read that file's own doc first; this one only calls out
/// where Swift's shape differs from it.
///
/// **Why `@MainActor` makes the single-`run()` guard *simpler* than
/// Android's, not more awkward.** Android needs `runLock` because
/// `ConvoyLiveService` (main thread) and `CircleNotifyService`
/// (`Dispatchers.IO`) can both call `ensureRunning()` concurrently — an
/// unguarded check-then-launch race could let both reach `ConvoyRelay.run()`,
/// which throws on the second concurrent call. Every entry point here —
/// `join`, `leave`, `addNotifyingCircle`, `removeNotifyingCircle`,
/// `setNotifyingCircles` — is a synchronous method on this `@MainActor`
/// class, so Swift's actor isolation already serialises every caller onto
/// the same thread before `ensureRunning()` ever runs; there is no second
/// caller for a lock to be needed against, and `running` below is a plain
/// `Bool`, not a `Mutex`/`NSLock`-guarded one.
///
/// **The session-epoch teardown is inherited, not just preserved.**
/// `ConvoyRelay.run()` now watches `Auth.sessionEpoch` itself, and once it
/// moves calls its own `clearMembershipForSessionChange()` — see `run()`'s
/// doc in `ConvoyRelay.kt` — which clears the shared relay's own membership
/// (`_convoyId`, `_notifyingCircleIds`) and convoy-scoped display state
/// (`peers`, `talking`, `spinOffer`, `spinVotes`) before closing the socket,
/// rather than the plain `stop()` this class used to trigger by hand: a
/// session change is not a reconnect, so leaving `_convoyId` in place — the
/// way an ordinary `stop()` deliberately does, for a caller-initiated
/// reconnect — is what let a departed rider's convoy id survive into the
/// next rider's session and get rejoined the moment `setNotifyingCircles`
/// made the socket wanted again. This class *also* keeps its own
/// `sessionEpoch` watcher below, purely to reset `activeConvoyId`/
/// `wantedCircleIds` — this class's own local mirror of membership, which
/// `ConvoyRelay` has no getter for and so cannot reset on this side itself
/// (see `activeConvoyId`'s own doc) — so a departed rider's convoy does not
/// linger in this screen's own "am I online" state even though the socket
/// and the shared relay's own membership are already correctly cleared
/// without it.
@MainActor
final class ConvoyLiveClient: ObservableObject {

    static let shared = ConvoyLiveClient()

    /// The one `ConvoyRelay` every convoy/circle caller shares, and the one
    /// `UrlSessionRelaySocket` it runs against — two of either would mean two
    /// location broadcasts, per `ConvoyRelay`'s class doc.
    private let relay = ConvoyRelay()
    private let socket = UrlSessionRelaySocket()
    private let watchers: ConvoyRelayWatchers

    @Published private(set) var activeConvoyId: String?
    @Published private(set) var connected = false
    @Published private(set) var peers: [String: FriendPosition] = [:]
    @Published private(set) var talking: Set<String> = []
    @Published private(set) var spinOffer: GroupSpin?
    @Published private(set) var spinVotes: [String: Int] = [:]
    /// `relay.lastError`, overlaid with `guardMessage` — the "no live
    /// server configured" refusal below, which never calls `relay.run()` at
    /// all, so `relay` never sees that case itself (matching `ConvoyRelay`'s
    /// own class doc: URL guards are entirely the caller's job). Same shape
    /// as Android's `ConvoyLiveClient.lastError`/`_guardError`: `guardMessage`
    /// wins whenever it is set, falls through to the relay's own
    /// `lastError` otherwise - see `updateLastError()`. `FriendsScreen`'s
    /// `convoysSection` is what actually reads this, mirroring Android's
    /// `FriendsScreen.kt` `liveStatus`.
    @Published private(set) var lastError: String?

    /// What `join`/`applyNotifyingCircles` refused to even start `relay.run()`
    /// over, if anything - see `lastError`'s own doc. Cleared the moment a
    /// guard passes, same discipline Android's `_guardError` uses, so a
    /// stale refusal from a previous tap cannot outlive the attempt it
    /// belonged to.
    private var guardMessage: String? {
        didSet { updateLastError() }
    }

    private func updateLastError() {
        lastError = guardMessage ?? lastErrorWatcher.value
    }

    /// Circles currently wanted joined for live arrival/departure pushes —
    /// disjoint from `activeConvoyId`, mirrored locally because
    /// `ConvoyRelay.setNotifyingCircles` replaces the whole set at once and
    /// has no per-id add/remove of its own, unlike `CirclesScreen`'s call
    /// sites below.
    private var wantedCircleIds: Set<String> = []

    /// Guards against a second concurrent `ensureRunning()` launching a
    /// second `relay.run()` — see this class's own doc for why `@MainActor`
    /// makes a plain `Bool` enough here, unlike Android's `runLock`. Cleared
    /// from inside `ensureRunning()`'s own `Task` right after `relay.run()`
    /// returns — see that function's own doc for why a caller landing in the
    /// gap before that write actually happens must not be the one left
    /// responsible for noticing.
    private var running = false

    private let connectedWatcher: BoolWatcher
    private let peersWatcher: FriendPositionsWatcher
    private let talkingWatcher: StringSetWatcher
    private let spinOfferWatcher: GroupSpinWatcher
    private let spinVotesWatcher: SpinVotesWatcher
    private let lastErrorWatcher: OptionalStringWatcher
    private let audioChunkWatcher: AudioChunkWatcher
    private let placeEventWatcher: PlaceEventWatcher

    /// Ties this socket's whole lifetime to the session rather than to any
    /// one caller — see this class's own doc. `Auth.sessionEpoch` bumps on
    /// sign-out, a 401 and a server switch alike.
    private let sessionEpoch = AuthFlows.shared.sessionEpoch()

    private var locationTask: Task<Void, Never>?

    private init() {
        let watchers = ConvoyRelayWatchers(relay: relay)
        self.watchers = watchers
        connectedWatcher = watchers.connected()
        peersWatcher = watchers.peers()
        talkingWatcher = watchers.talking()
        spinOfferWatcher = watchers.spinOffer()
        spinVotesWatcher = watchers.spinVotes()
        lastErrorWatcher = watchers.lastError()
        audioChunkWatcher = watchers.audioChunks()
        placeEventWatcher = watchers.placeEvents()

        // `self?.x = self?.watcher.value ?? default` used to read as a nil
        // guard here and wasn't one: optional chaining on the assignment's
        // left side already skips the whole statement when `self` is nil,
        // so the `?? default` on the right could never actually run - `guard
        // let self else { return }` says the same thing without the dead
        // fallback.
        connectedWatcher.watch { [weak self] in
            guard let self else { return }
            self.connected = self.connectedWatcher.value
        }
        peersWatcher.watch { [weak self] in
            guard let self else { return }
            self.peers = self.peersWatcher.value
        }
        talkingWatcher.watch { [weak self] in
            guard let self else { return }
            self.talking = self.talkingWatcher.value
        }
        spinOfferWatcher.watch { [weak self] in self?.spinOffer = self?.spinOfferWatcher.value }
        spinVotesWatcher.watch { [weak self] in
            guard let self else { return }
            // Map<String, Int>'s Int values arrive boxed (KotlinInt), same
            // reason a suspend fun returning Int does elsewhere in this app
            // — `.intValue` is what actually unwraps it to Int.
            self.spinVotes = self.spinVotesWatcher.value.mapValues { $0.intValue }
        }
        lastErrorWatcher.watch { [weak self] in self?.updateLastError() }
        audioChunkWatcher.watch { [weak self] in
            guard let chunk = self?.audioChunkWatcher.value else { return }
            PttAudio.shared.play(chunk.pcm.toData(), from: chunk.username)
        }
        placeEventWatcher.watch { [weak self] in
            guard let relayEvent = self?.placeEventWatcher.value else { return }
            CircleNotifications.shared.handleLiveEvent(groupId: relayEvent.groupId, event: relayEvent.event)
        }
        sessionEpoch.watch { [weak self] in self?.sessionEnded() }

        // Location rides on the trip recorder's fixes rather than opening a
        // second GPS listener, same as before — `ConvoyRelay.sendLocation` is
        // a cheap no-op while nothing is connected, so there is no cost to
        // leaving this running for the life of the process versus starting/
        // stopping it around every join/setNotifyingCircles cycle.
        locationTask = Task { [weak self] in
            for await fix in LocationBroadcast.shared.stream() {
                guard let self else { return }
                self.relay.sendLocation(
                    lat: fix.coordinate.latitude,
                    lon: fix.coordinate.longitude,
                    headingDeg: fix.course >= 0 ? KotlinDouble(value: fix.course) : nil,
                    speedKmh: max(0, fix.speed) * 3.6,
                    // The fix's own time, not the moment this loop iteration
                    // runs - same conversion CircleSync.swift's fixTsMs
                    // already uses for the same CLLocation.timestamp.
                    tsMs: Int64(fix.timestamp.timeIntervalSince1970 * 1000))
            }
        }
    }

    deinit {
        [connectedWatcher, peersWatcher, talkingWatcher, spinOfferWatcher, spinVotesWatcher, lastErrorWatcher,
         audioChunkWatcher, placeEventWatcher, sessionEpoch].forEach { $0.cancel() }
        locationTask?.cancel()
    }

    // MARK: Membership

    /// Joins `convoyId`'s live relay — see `ConvoyRelay.setConvoy`. Refuses
    /// when `Features.liveRelay` is off or no live server is configured, the
    /// two guards `ConvoyRelay` deliberately does not apply — see its class
    /// doc, and this class's own doc for why iOS needs them supplied here
    /// exactly as `net/ConvoyLiveClient.kt`'s `join` does. The blank-URL
    /// refusal sets `guardMessage`, matching Android's own wording, so the
    /// rider sees *why* nothing connects rather than the refusal going
    /// silent.
    func join(convoyId: String) {
        guard Features.shared.liveRelay else { return }
        guard !(activeConvoyId == convoyId && running) else { return }
        guard !BuildDefaults.shared.liveUrl.isEmpty else {
            guardMessage = "No live server configured"
            return
        }
        guardMessage = nil
        activeConvoyId = convoyId
        relay.setConvoy(groupId: convoyId)
        ensureRunning()
    }

    /// Leaves the convoy only — a notify-circle join on this same socket has
    /// nothing to do with a convoy ending, so `ConvoyRelay.setConvoy`'s own
    /// removal-reopens-the-socket handling is what keeps that connection up
    /// for it; nothing here needs to.
    func leave() {
        guard activeConvoyId != nil else { return }
        activeConvoyId = nil
        relay.setConvoy(groupId: nil)
    }

    /// Adds one circle to the live push set — see `ConvoyRelay.setNotifyingCircles`.
    func addNotifyingCircle(_ id: String) {
        guard !wantedCircleIds.contains(id) else { return }
        wantedCircleIds.insert(id)
        applyNotifyingCircles()
    }

    /// Drops one circle from the live push set. Unlike the old
    /// `removeNotifyingCircle`, this is no longer a purely-local forget: a
    /// dropped id reopens the socket so the relay actually stops treating
    /// this device as a member — see `ConvoyRelay.setNotifyingCircles`'s own
    /// doc for why staying joined server-side to a circle just left is the
    /// same shape of leak as the convoy case, and why there is no cheaper
    /// wire path than a reconnect.
    func removeNotifyingCircle(_ id: String) {
        guard wantedCircleIds.remove(id) != nil else { return }
        applyNotifyingCircles()
    }

    /// Reconciles the whole wanted set at once — what `CircleSync`'s
    /// periodic loop and `CircleNotifications.runCatchUpSweep` use.
    func setNotifyingCircles(_ ids: Set<String>) {
        guard wantedCircleIds != ids else { return }
        wantedCircleIds = ids
        applyNotifyingCircles()
    }

    private func applyNotifyingCircles() {
        guard Features.shared.liveRelay else { return }
        relay.setNotifyingCircles(ids: wantedCircleIds)
        guard activeConvoyId != nil || !wantedCircleIds.isEmpty else { return }
        guard !BuildDefaults.shared.liveUrl.isEmpty else {
            guardMessage = "No live server configured"
            return
        }
        guardMessage = nil
        ensureRunning()
    }

    // MARK: Sending

    func sendPttStart() { relay.sendPttStart() }
    func sendPttEnd() { relay.sendPttEnd() }
    func sendAudioChunk(_ pcm: Data) { relay.sendAudioChunk(pcm: pcm.toKotlinByteArray()) }

    func sendSpinOffer(_ candidates: [SpinCandidate]) { relay.sendSpinOffer(candidates: candidates) }

    /// Casts this device's vote — `username` is read here, not inside
    /// `ConvoyRelay`, which takes it as a parameter rather than reaching for
    /// `Settings.authUsername` itself; see `ConvoyRelay.sendSpinVote`'s own
    /// doc.
    func sendSpinVote(_ index: Int) {
        relay.sendSpinVote(username: SettingsValues.shared.authUsername, index: Int32(index))
    }

    func clearSpinOffer() { relay.clearSpinOffer() }

    /// Delegates to `ConvoyRelay.currentLeadIndex` - see its own doc. What
    /// `MapScreen`'s "Go with the lead" button, and its own `resolveGroupSpin`,
    /// call in place of the hand-rolled `leadingSpinIndex(of:)` this used to
    /// carry.
    func currentLeadIndex(candidateCount: Int) -> Int {
        Int(relay.currentLeadIndex(candidateCount: Int32(candidateCount)))
    }

    /// Delegates to `ConvoyRelay.spinRoundIsReadyToClose` - see its own doc
    /// for why iOS reads this `Bool` rather than switching over
    /// `ConvoyRelay.spinRoundOutcome`'s own `SpinRoundOutcome` directly, the
    /// way `net/ConvoyLiveClient.kt`'s Android counterpart now does.
    func spinRoundIsReadyToClose(myUsername: String) -> Bool {
        relay.spinRoundIsReadyToClose(myUsername: myUsername)
    }

    // MARK: Running

    /// Launches `ConvoyRelay.run()` if it is not already running — see
    /// `running`'s own doc for why `@MainActor` needs no lock here, unlike
    /// Android's `ensureRunning`.
    ///
    /// `bearer` is `AuthBearerSource()`, not a closure — `ConvoyRelay.run`'s
    /// `bearer` parameter is a `BearerSource`, a `fun interface` rather than
    /// the bare `suspend () -> String` function type it used to be, which
    /// could not be implemented from Swift at all (see `BearerSource`'s own
    /// doc). `AuthBearerSource` below is what actually calls `Auth.bearer`;
    /// see its own doc for why it can let a throw cross straight through
    /// rather than swallowing one to a blank string the way this used to.
    ///
    /// **Rechecks whether anything is still wanted immediately after
    /// clearing `running`, rather than trusting whoever set `running` true
    /// to still be the one who notices.** `relay.run()` suspends across an
    /// exported Kotlin `suspend fun`, so when it finally returns, resuming
    /// this `Task`'s body on the main actor is itself an async hop — other
    /// main-actor work already queued (a `join`/`setNotifyingCircles` call
    /// landing right in that window) can run *before* `self.running = false`
    /// below actually executes, see `running` still `true`, and silently
    /// no-op, since that is exactly what this function's own top guard is
    /// for. Without the recheck, nothing ever launches a fresh `run()` again
    /// after that: the UI shows the convoy joined with no socket underneath,
    /// self-healing only whenever some *later*, unrelated membership change
    /// happens to call this again. Android does not need this — it checks
    /// `runJob?.isActive` instead of a hand-set flag, so there is no window
    /// where the job has finished but the flag has not caught up yet.
    private func ensureRunning() {
        guard !running else { return }
        running = true
        Task { [weak self] in
            guard let self else { return }
            _ = try? await self.relay.run(socket: self.socket, bearer: AuthBearerSource())
            self.running = false
            if self.activeConvoyId != nil || !self.wantedCircleIds.isEmpty {
                self.ensureRunning()
            }
        }
    }

    /// Tears down this screen's own local mirror of membership —
    /// `activeConvoyId`, `wantedCircleIds` — the moment the session that owns
    /// them ends. The socket, the shared relay's own membership and its
    /// convoy-scoped display state are all already cleared by
    /// `ConvoyRelay.run()`'s own epoch watcher (see this class's doc); this
    /// only resets what genuinely remains platform-local — the two fields
    /// this class keeps for itself because `ConvoyRelay` exposes no getter
    /// for either.
    private func sessionEnded() {
        activeConvoyId = nil
        wantedCircleIds = []
    }
}

/// Conforms to the generated `BearerSource` protocol — `ConvoyRelay.kt`'s
/// `fun interface BearerSource`, whose one method carries its own
/// `@Throws` — the same shape `UrlSessionRelaySocket` already conforms to
/// `RelaySocket` with: Kotlin/Native lowers a `fun interface` to an
/// ordinary protocol with a real `async throws` method, not the
/// completion-handler-only `KotlinSuspendFunction0` a bare `suspend () ->
/// String` function type used to generate — see `BearerSource`'s own doc
/// for why only the interface shape can be implemented from Swift at all.
///
/// Because `bearer()` really can throw across this boundary now,
/// `ConvoyRelay.attempt` catching it is what actually turns a failure into
/// `lastError` — see its own comment — so there is nothing left for this
/// type to swallow itself: no `signedIn` guard, no `try?`. The one that
/// used to be here worked around the missing annotation slot on the old
/// function-type parameter, not around anything about `Auth.bearer` itself.
private final class AuthBearerSource: BearerSource {
    func bearer() async throws -> String {
        try await Auth.shared.bearer()
    }
}

private extension KotlinByteArray {
    /// Kotlin/Native maps `ByteArray` to `KotlinByteArray`, which no Swift
    /// `Data` bridge fills in — same gap `SignIn.entropy()` works around in
    /// the other direction. A ptt_audio chunk is 640 bytes at 16 kHz/25 Hz,
    /// so a per-byte `get(index:)` loop here is a real but small cost, once
    /// per 40 ms while transmitting or receiving — not a new hot path, the
    /// old client converted the same bytes itself, just via `Data` directly.
    func toData() -> Data {
        var out = Data(capacity: Int(size))
        for i in 0..<size {
            out.append(UInt8(bitPattern: get(index: i)))
        }
        return out
    }
}

private extension Data {
    /// The write-direction counterpart of `KotlinByteArray.toData()` above,
    /// for `sendAudioChunk` handing this device's own captured PCM to
    /// `ConvoyRelay.sendAudioChunk(pcm: ByteArray)`.
    func toKotlinByteArray() -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            out.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return out
    }
}
