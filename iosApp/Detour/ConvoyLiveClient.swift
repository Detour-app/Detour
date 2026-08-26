import Foundation
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
/// `ConvoyRelay.run()` now watches `Auth.sessionEpoch` itself and calls its
/// own `stop()` the moment it moves — see `run()`'s doc in `ConvoyRelay.kt` —
/// which is what actually closes the socket and ends `sendLocation`'s
/// broadcast on a sign-out, a 401, or a server switch, the exact leak this
/// class used to guard against by hand. This class *also* keeps its own
/// `sessionEpoch` watcher below, calling `relay.stop()` again (idempotent)
/// and clearing `activeConvoyId`/`wantedCircleIds` — state `ConvoyRelay` has
/// no knowledge of and cannot reset itself — so a departed rider's convoy
/// does not linger in this screen's own "am I online" state even though the
/// socket itself is already correctly down without it.
@MainActor
final class ConvoyLiveClient: NSObject, ObservableObject {

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
    /// `relay.lastError` only — the "no live server"/"feature disabled"
    /// refusals below never call `relay.run()` at all, so `relay` never sees
    /// those cases (matching `ConvoyRelay`'s own class doc: URL and flag
    /// guards are entirely the caller's job). Unlike Android's
    /// `ConvoyLiveClient.lastError`, there is no overlay for that here — no
    /// SwiftUI screen reads this today (Android's exists mainly for a rider-
    /// facing message no iOS screen currently shows either), so the refusal
    /// itself, not its wording, is what this class guarantees.
    @Published private(set) var lastError: String?

    /// Circles currently wanted joined for live arrival/departure pushes —
    /// disjoint from `activeConvoyId`, mirrored locally because
    /// `ConvoyRelay.setNotifyingCircles` replaces the whole set at once and
    /// has no per-id add/remove of its own, unlike `CirclesScreen`'s call
    /// sites below.
    private var wantedCircleIds: Set<String> = []

    /// Guards against a second concurrent `ensureRunning()` launching a
    /// second `relay.run()` — see this class's own doc for why `@MainActor`
    /// makes a plain `Bool` enough here, unlike Android's `runLock`.
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

    private override init() {
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
        super.init()

        connectedWatcher.watch { [weak self] in self?.connected = self?.connectedWatcher.value ?? false }
        peersWatcher.watch { [weak self] in self?.peers = self?.peersWatcher.value ?? [:] }
        talkingWatcher.watch { [weak self] in self?.talking = self?.talkingWatcher.value ?? [] }
        spinOfferWatcher.watch { [weak self] in self?.spinOffer = self?.spinOfferWatcher.value }
        spinVotesWatcher.watch { [weak self] in
            guard let self else { return }
            // Map<String, Int>'s Int values arrive boxed (KotlinInt), same
            // reason a suspend fun returning Int does elsewhere in this app
            // — `.intValue` is what actually unwraps it to Int.
            self.spinVotes = self.spinVotesWatcher.value.mapValues { $0.intValue }
        }
        lastErrorWatcher.watch { [weak self] in self?.lastError = self?.lastErrorWatcher.value }
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
                    speedKmh: max(0, fix.speed) * 3.6)
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
    /// exactly as `net/ConvoyLiveClient.kt`'s `join` does.
    func join(convoyId: String) {
        guard Features.shared.liveRelay else { return }
        guard !(activeConvoyId == convoyId && running) else { return }
        guard !BuildDefaults.shared.liveUrl.isEmpty else { return }
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
        guard !BuildDefaults.shared.liveUrl.isEmpty else { return }
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

    // MARK: Running

    /// Launches `ConvoyRelay.run()` if it is not already running — see
    /// `running`'s own doc for why `@MainActor` needs no lock here, unlike
    /// Android's `ensureRunning`.
    ///
    /// The `bearer` supplier must never let a throw cross back into Kotlin:
    /// `ConvoyRelay.run`'s `bearer: suspend () -> String` parameter carries no
    /// `@Throws` (that annotation applies to a declared function, not a bare
    /// function-type parameter), so an unmarked throw here would be the same
    /// hazard `RelaySocket`'s own doc warns terminates the process — and
    /// `Auth.bearer` is explicitly one of the eighteen still-unannotated
    /// suspend functions `docs/IOS_PORT.md`'s "Not done" section lists as
    /// able to "take the app down". The old client never called it at all
    /// (it read `SettingsValues.shared.authToken` directly); this is the
    /// first call site that does, per `ConvoyRelay`'s own class doc ("the
    /// real call site... passes `Auth::bearer`"). `signedIn` closes the one
    /// throw this path hits on *every* signed-out attempt — `ConvoyRelay`'s
    /// own doc says a blank return is already treated as "not signed in",
    /// which is exactly what this guard, and the `try?` below for a signed-in
    /// refresh failing for some other reason, both turn a throw into. The
    /// residual risk of `bearer()`'s own `refresh()` throwing for a different
    /// reason while signed in is the same pre-existing, documented gap every
    /// other unannotated suspend call in this app already carries — fixing it
    /// means annotating `Auth.kt` itself, outside this task's `shared/`
    /// boundary.
    private func ensureRunning() {
        guard !running else { return }
        running = true
        Task { [weak self] in
            guard let self else { return }
            _ = try? await self.relay.run(socket: self.socket, bearer: {
                guard Auth.shared.signedIn else { return "" }
                return (try? await Auth.shared.bearer()) ?? ""
            })
            self.running = false
        }
    }

    /// Tears down this screen's own membership state — `activeConvoyId`,
    /// `wantedCircleIds` — the moment the session that owns them ends. The
    /// socket itself is already closed by `ConvoyRelay.run()`'s own epoch
    /// watcher (see this class's doc); `relay.stop()` here is a harmless,
    /// idempotent second call, not what actually does the work.
    private func sessionEnded() {
        relay.stop()
        activeConvoyId = nil
        wantedCircleIds = []
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
