import Foundation
import CoreLocation
import DetourShared

struct FriendPosition: Equatable {
    let username: String
    let lat: Double
    let lon: Double
    let headingDeg: Double?
    let speedKmh: Double?
    let tsMs: Int64
    /// When this fix stops being worth drawing, as local elapsed time.
    ///
    /// Per peer rather than one constant, because a convoy rider and a circle
    /// member now arrive on the same socket at wildly different cadences —
    /// seconds against minutes. One staleness window for both either flickers
    /// circle members off the map between their updates or leaves a dropped
    /// convoy rider frozen on it. The relay knows which tier a sender is on.
    let expiresAtMs: Int64
}

/// One `spin_offer` candidate, wire shape — see the relay protocol
/// comment near `_valid_spin_offer`. `distanceM` / `durationS` are whatever
/// the sharer's own spin already knew; a member receiving them has no route
/// of its own until it commits and asks for one.
struct SpinCandidate: Equatable {
    let lat: Double
    let lon: Double
    let distanceM: Double?
    let durationS: Double?
    let name: String?
}

/// A convoy's shared spin, either just sent by this device or just received
/// from a peer's.
///
/// A **one-candidate** offer is not a sheet to vote on, it's the sharer
/// announcing the winner: every device that sees one commits it. That is why
/// `fromMe` exists — only the device that opened the round decides when it is
/// over and sends that closing offer, so a member whose view of who is still
/// live differs (a peer gone quiet for 20 s is pruned from `peers` on one
/// phone and not another) cannot resolve the same votes into a different
/// destination. Identical rule to the Android client's, deliberately: the two
/// have to agree or a convoy splits across two destinations.
struct GroupSpin: Equatable {
    let candidates: [SpinCandidate]
    let fromMe: Bool
}

/// The convoy live-location / push-to-talk WebSocket.
///
/// Speaks the same protocol as the Android client verbatim — join, location,
/// ptt_start / ptt_audio / ptt_end, and the server's `joined` / `error`
/// replies — because the relay on the other end is one server serving both.
/// Nothing is persisted, matching the relay's in-memory-only design.
///
/// `URLSessionWebSocketTask` replaces OkHttp. The one real difference: OkHttp
/// has a `pingInterval` that keeps NAT and the Cloudflare tunnel from idling a
/// quiet connection closed; URLSession has no such setting, so the ping is
/// scheduled here instead.
///
/// The relay is multi-group on the wire (docs/CIRCLES_AND_CONVOYS.md section 6):
/// every frame carries a `groupId`, and one socket can hold several
/// memberships at once — `join` adds one rather than replacing what the
/// socket already had. Circles use exactly that: they still post fixes over
/// plain HTTP at a much lower cadence (see `CircleFixes.postFix`) and never
/// send `location`/`ptt_*`/`spin_*` frames here — the relay only ever
/// relays those for a convoy group (server-side kind check) — but a circle
/// with arrival notifications on joins this same socket purely to *receive*
/// `place_event` pushes (see `wantedCircleIds` / `setNotifyingCircles`
/// below). That's also why the socket now has to exist even with no convoy
/// active: a circle-only user still needs it for the live path.
@MainActor
final class ConvoyLiveClient: NSObject, ObservableObject {

    static let shared = ConvoyLiveClient()

    private static let locationSendIntervalMs: Int64 = 2_000
    private static let minBackoff: Duration = .seconds(1)
    private static let maxBackoff: Duration = .seconds(30)
    private static let pingInterval: Duration = .seconds(20)
    /// Used only for a peer whose frame carried no usable `ttl` — an older or
    /// broken relay. What this client used to apply to everyone: ~10 missed 2 s
    /// updates, generous enough that a normal gap in GPS fixes doesn't flicker a
    /// marker, short enough that someone who dropped off stops being shown live.
    private static let fallbackPeerTtlMs: Int64 = 20_000
    /// How often expiry is swept, matching the Android client's
    /// `PEER_PRUNE_INTERVAL_MS` (`net/ConvoyLiveClient.kt:116`). A timer, not a
    /// side effect of receiving someone else's frame: the case that matters is
    /// the convoy where nobody is transmitting any more, where an inbound-only
    /// sweep never runs and every peer sits frozen on the map.
    private static let peerPruneInterval: Duration = .seconds(5)

    @Published private(set) var activeConvoyId: String?
    @Published private(set) var connected = false
    @Published private(set) var peers: [String: FriendPosition] = [:]
    @Published private(set) var talking: Set<String> = []
    /// The convoy's current group spin, if any candidate set is on the table —
    /// set locally the moment this device shares one (the relay never echoes a
    /// sender's own frame back), or when a peer's `spin_offer` arrives. Nil
    /// once nobody has shared a spin, once the convoy is left, or across a
    /// disconnect: a stale vote is worse than no vote.
    @Published private(set) var spinOffer: GroupSpin?
    /// username → candidate index, tallied client-side only from every
    /// `spin_vote` this device has sent or received for the current
    /// `spinOffer`. The server holds none of it. Reset whenever `spinOffer`
    /// changes.
    @Published private(set) var spinVotes: [String: Int] = [:]
    /// Set when the relay can't be reached at all (misconfigured server, not
    /// signed in) or rejects a join. Cleared on a successful join, so a
    /// permanently-failing connection surfaces something instead of retrying
    /// silently forever.
    @Published private(set) var lastError: String?

    private var socket: URLSessionWebSocketTask?
    private var connectionTask: Task<Void, Never>?
    private var lastLocationSentMs: Int64 = 0
    private let session = URLSession(configuration: .default)

    /// Circles currently wanted joined for live arrival/departure pushes —
    /// disjoint from `activeConvoyId`, and the reason this class is no
    /// longer purely "the convoy socket" (see the class doc). Populated by
    /// `CircleNotifications`/`CircleSync`, never read by anything UI-facing.
    private var wantedCircleIds: Set<String> = []

    /// Ties this socket's whole lifetime to the session rather than to any
    /// one caller — see `sessionEnded()`. `Auth.sessionEpoch` bumps on
    /// sign-out, a 401 and a server switch alike (`Auth.clear`'s three call
    /// sites), which is exactly the set of paths a single "Sign out" button
    /// handler cannot cover; Android's `ConvoyLiveService.stop(context)` on
    /// its own sign-out button has the same gap for the same reason.
    private let sessionEpoch = AuthFlows.shared.sessionEpoch()

    private override init() {
        super.init()
        sessionEpoch.watch { [weak self] in self?.sessionEnded() }
    }

    // MARK: Membership

    func join(convoyId: String) {
        // The relay is back, so this normally passes; the flag stays as the one
        // switch that turns every live feature off on both platforms at once.
        guard Features.shared.liveRelay else { return }
        guard activeConvoyId != convoyId else { return }
        // A convoy switch has to fully reconnect, not just add a second
        // join: the relay has no client "leave one group" frame (only a
        // whole-socket close), so simply joining the new id on top of the
        // old one would leave this device receiving both convoys' traffic.
        disconnect()
        activeConvoyId = convoyId
        startConnectionIfNeeded()
    }

    /// Leaves the convoy only. A circle notification join on this same
    /// socket (see `setNotifyingCircles`) has nothing to do with a convoy
    /// ending, so the socket stays up for it rather than being torn down
    /// here — only `disconnect()` when nothing wants it any more.
    func leave() {
        guard activeConvoyId != nil else { return }
        disconnect()
        activeConvoyId = nil
        startConnectionIfNeeded()
    }

    /// Adds one circle to the live push set — joined on the socket at once
    /// if it's already open, or the socket is started purely to carry it if
    /// nothing else has one open (the common case for a circle-only user:
    /// no convoy, so nothing else would ever connect this socket at all).
    func addNotifyingCircle(_ id: String) {
        guard !wantedCircleIds.contains(id) else { return }
        wantedCircleIds.insert(id)
        if connected {
            send(groupId: id, ["type": "join"])
        } else {
            startConnectionIfNeeded()
        }
    }

    /// Drops one circle from the live push set. Not an active "un-join" —
    /// the wire protocol has none (see the class doc) — so `place_event`
    /// frames for it may still arrive until the next reconnect;
    /// `CircleNotifications` filters those client-side in the meantime.
    /// Tears the socket down if that was the only reason it was open.
    func removeNotifyingCircle(_ id: String) {
        guard wantedCircleIds.remove(id) != nil else { return }
        if activeConvoyId == nil && wantedCircleIds.isEmpty { disconnect() }
    }

    /// Reconciles the whole wanted set at once — what `CircleSync`'s
    /// periodic loop and `CircleNotifications.runCatchUpSweep` use, since
    /// they recompute "which circles want live pushes" from scratch each
    /// time rather than tracking a diff themselves.
    func setNotifyingCircles(_ ids: Set<String>) {
        for id in ids.subtracting(wantedCircleIds) { addNotifyingCircle(id) }
        for id in wantedCircleIds.subtracting(ids) { removeNotifyingCircle(id) }
    }

    // MARK: Connection

    /// Tears the socket down and forgets every membership — the whole
    /// footprint this class has — the moment the session that owns it ends.
    ///
    /// Without this, the socket outlives the rider: it stays joined to the
    /// departed rider's convoy and circles (`disconnect()` deliberately
    /// leaves `wantedCircleIds` alone, and `activeConvoyId` with it, for the
    /// legitimate reconnect case), `handle`'s `place_event` branch keeps
    /// raising their circles' arrivals as this device's own notifications,
    /// and — the serious half — `forwardLocation()` has no `signedIn` or
    /// convoy gate of its own, so it keeps broadcasting whichever rider is
    /// live on this device now onto the *departed* rider's convoy and circle
    /// members for as long as the connection loop keeps reconnecting.
    ///
    /// Driven off `Auth.sessionEpoch` rather than the "Sign out" button
    /// (`FriendsScreen.swift`'s `Account.shared.signOut()`), so a 401 and a
    /// server switch tear this down too — see `sessionEpoch`'s own doc.
    /// Fires once more on the *next* sign-in as well, since establishing a
    /// session bumps the epoch the same way clearing one does (see
    /// `Auth.sessionEpoch`'s doc in Auth.kt); harmless there, since nothing
    /// has joined anything for a brand-new session yet.
    ///
    /// Must not restart the connection afterward the way `leave()` does for
    /// a lingering notify-circle join — a session that just ended has
    /// nothing left worth staying connected for.
    private func sessionEnded() {
        disconnect()
        activeConvoyId = nil
        wantedCircleIds = []
    }

    /// Tears down the socket and every convoy-scoped published value, but
    /// never touches `wantedCircleIds` — see `leave()`.
    private func disconnect() {
        connectionTask?.cancel()
        connectionTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        connected = false
        peers = [:]
        talking = []
        spinOffer = nil
        spinVotes = [:]
    }

    /// Starts the connection loop if something actually wants a socket open
    /// and one isn't running already — a convoy, at least one notifying
    /// circle, or both at once (the relay's "one socket, many groups"
    /// design; see the class doc).
    private func startConnectionIfNeeded() {
        guard connectionTask == nil, activeConvoyId != nil || !wantedCircleIds.isEmpty else { return }
        connectionTask = Task { await self.connectionLoop() }
    }

    /// Reconnects with exponential backoff, reset whenever an attempt actually
    /// got as far as being joined — a connection that worked and then dropped
    /// should come back promptly, unlike one that never authenticated.
    private func connectionLoop() async {
        // Runs for as long as anything wants a socket, across reconnects and
        // backoff waits, the way Android launches `prunePeers()` alongside its
        // connection job (`net/ConvoyLiveClient.kt:313-315`) rather than per
        // attempt: peers held while a dropped connection is backing off go
        // stale just the same. Every exit from this function — cancellation
        // from `disconnect()`, or the self-clearing "nothing wants a socket"
        // path below — runs the `defer`.
        let pruner = Task { await self.prunePeersPeriodically() }
        defer { pruner.cancel() }
        var backoff = Self.minBackoff
        while !Task.isCancelled {
            let everJoined = await connectAndAwaitClose()
            // Cancellation only ever comes from `disconnect()`, which has
            // already cleared `connectionTask` itself — touching it again
            // here could stomp a newer task `startConnectionIfNeeded()`
            // assigned in the same synchronous call that cancelled this one.
            if Task.isCancelled { return }
            connected = false
            // A tally against a socket that is no longer relaying anyone's
            // frames is already wrong by the time it reconnects — drop it
            // rather than leave a stale offer on screen looking live.
            spinOffer = nil
            spinVotes = [:]
            // Nothing wants a socket any more (convoy left, no circle asking
            // for pushes either) — stop instead of reconnecting forever, and
            // self-clear since nobody else initiated this shutdown.
            guard activeConvoyId != nil || !wantedCircleIds.isEmpty else {
                connectionTask = nil
                return
            }
            backoff = everJoined ? Self.minBackoff : min(backoff * 2, Self.maxBackoff)
            try? await Task.sleep(for: backoff)
        }
    }

    private func connectAndAwaitClose() async -> Bool {
        guard let url = URL(string: BuildDefaults.shared.liveUrl),
              !BuildDefaults.shared.liveUrl.isEmpty else {
            lastError = "No convoy relay configured"
            return false
        }

        var request = URLRequest(url: url)
        request.setValue("Bearer \(SettingsValues.shared.authToken)",
                         forHTTPHeaderField: "Authorization")
        // The relay sits behind the same Cloudflare Access service token as
        // the routing server.
        let cf = RoutingServer.shared.load()
        if !cf.clientId.isEmpty {
            request.setValue(cf.clientId, forHTTPHeaderField: "CF-Access-Client-Id")
            request.setValue(cf.clientSecret, forHTTPHeaderField: "CF-Access-Client-Secret")
        }

        let task = session.webSocketTask(with: request)
        socket = task
        task.resume()

        // Join everything currently wanted — the active convoy, if any, and
        // every notifying circle — since a fresh connection starts with no
        // memberships at all and the relay only adds, never assumes.
        if let convoyId = activeConvoyId { send(groupId: convoyId, ["type": "join"]) }
        for circleId in wantedCircleIds { send(groupId: circleId, ["type": "join"]) }
        let pinger = Task { await self.keepAlive(task) }
        let forwarder = Task { await self.forwardLocation() }
        defer {
            pinger.cancel()
            forwarder.cancel()
            if socket === task { socket = nil }
        }

        var everJoined = false
        while !Task.isCancelled {
            do {
                let message = try await task.receive()
                guard case let .string(text) = message else { continue }
                if handle(text) { everJoined = true }
            } catch {
                return everJoined
            }
        }
        return everJoined
    }

    private func keepAlive(_ task: URLSessionWebSocketTask) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: Self.pingInterval)
            task.sendPing { _ in }
        }
    }

    // MARK: Sending

    func sendPttStart() { send(["type": "ptt_start"]) }
    func sendPttEnd() { send(["type": "ptt_end"]) }

    func sendAudioChunk(_ pcm: Data) {
        send(["type": "ptt_audio", "chunk": pcm.base64EncodedString()])
    }

    /// Shares a spin with the convoy — or, with a single candidate, closes the
    /// round on the winner (see `GroupSpin`). Sets `spinOffer` locally right
    /// away: the relay excludes the sender from its own broadcast, so waiting
    /// for the frame to come back would mean waiting forever. Outside 1–3
    /// candidates it does nothing, matching the server's own cap.
    func sendSpinOffer(_ candidates: [SpinCandidate]) {
        guard (1...3).contains(candidates.count) else { return }
        spinOffer = GroupSpin(candidates: candidates, fromMe: true)
        spinVotes = [:]
        let wire: [[String: Any]] = candidates.map { c in
            var o: [String: Any] = ["lat": c.lat, "lon": c.lon]
            if let d = c.distanceM { o["distanceM"] = d }
            if let s = c.durationS { o["durationS"] = s }
            if let n = c.name { o["name"] = n }
            return o
        }
        send(["type": "spin_offer", "candidates": wire])
    }

    /// Casts this device's vote and records it locally at once, for the same
    /// reason `sendSpinOffer` does: the relay will not echo it back to us.
    func sendSpinVote(_ index: Int) {
        let me = SettingsValues.shared.authUsername
        if !me.isEmpty { spinVotes[me] = index }
        send(["type": "spin_vote", "index": index])
    }

    /// Drops the current spin locally (a commit landed, or it was dismissed)
    /// without telling anyone — there is nothing to tell, the vote was never
    /// server state.
    func clearSpinOffer() {
        spinOffer = nil
        spinVotes = [:]
    }

    /// Stamps `payload` with `groupId` and sends it — every frame needs one
    /// now, see the class doc. `groupId` defaults to the active convoy: every
    /// convoy-scoped call site (location, ptt, spin) already only makes sense
    /// there, so they call `send` unchanged; only the per-group `join` frames
    /// pass one explicitly. A payload with no resolvable group (no convoy,
    /// none passed) is silently dropped rather than mis-sent.
    private func send(groupId: String? = nil, _ payload: [String: Any]) {
        guard let groupId = groupId ?? activeConvoyId else { return }
        var stamped = payload
        stamped["groupId"] = groupId
        sendUnscoped(stamped)
    }

    /// Sends a frame that names no group.
    ///
    /// Only a position uses this, and that is the point: a fix belongs to the
    /// rider, not to whichever group they happen to have joined. The relay
    /// resolves who may see it from the sender's memberships, which is also why
    /// this needs no convoy — someone sharing a circle and no convoy at all
    /// still has a position worth sending.
    private func sendUnscoped(_ payload: [String: Any]) {
        guard let socket,
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else { return }
        socket.send(.string(text)) { _ in }
    }

    /// Location rides on the trip recorder's fixes rather than opening a second
    /// GPS listener, same as the Android client reads the tracking service's.
    private func forwardLocation() async {
        for await fix in LocationBroadcast.shared.stream() {
            let now = nowMs()
            guard now - lastLocationSentMs >= Self.locationSendIntervalMs else { continue }
            lastLocationSentMs = now
            var payload: [String: Any] = [
                "type": "location",
                "lat": fix.coordinate.latitude,
                "lon": fix.coordinate.longitude,
                "speedKmh": max(0, fix.speed) * 3.6,
                "ts": Int(fix.timestamp.timeIntervalSince1970 * 1000),
            ]
            if fix.course >= 0 { payload["headingDeg"] = fix.course }
            sendUnscoped(payload)
        }
    }

    // MARK: Receiving

    /// Returns true only for "joined", the one message the connection loop
    /// needs to see to know auth worked.
    @discardableResult
    private func handle(_ text: String) -> Bool {
        guard let data = text.data(using: .utf8),
              let msg = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = msg["type"] as? String else { return false }

        switch type {
        case "joined":
            connected = true
            lastError = nil
            return true

        case "error":
            // The server rejects a join it does not close the socket for (e.g.
            // membership was removed) — close it ourselves so this attempt ends
            // and the backoff loop picks it up, rather than sitting
            // connected-but-never-joined forever.
            lastError = msg["message"] as? String
            socket?.cancel(with: .normalClosure, reason: nil)

        // One frame carries every peer the relay had queued for this socket,
        // not one frame per peer: at eight riders that is one packet a round
        // instead of seven, and the packet count is what a phone's radio pays
        // for. Keys are short for the same reason.
        case "positions":
            guard let rows = msg["peers"] as? [[String: Any]] else { break }
            let now = nowMs()
            for row in rows {
                guard let user = row["u"] as? String, !user.isEmpty,
                      let lat = row["lat"] as? Double,
                      let lon = row["lon"] as? Double else { continue }
                let ttlSeconds = row["ttl"] as? Int ?? 0
                peers[user] = FriendPosition(
                    username: user,
                    lat: lat,
                    lon: lon,
                    headingDeg: row["h"] as? Double,
                    speedKmh: row["s"] as? Double,
                    tsMs: Int64(row["ts"] as? Int ?? 0),
                    // Anchored to arrival, not to the fix's own timestamp: that
                    // comes off the sender's clock, and a phone whose clock is
                    // minutes out would otherwise vanish at once or never.
                    expiresAtMs: now + (ttlSeconds > 0
                        ? Int64(ttlSeconds) * 1_000
                        : Self.fallbackPeerTtlMs)
                )
            }
            pruneStalePeers()

        case "left":
            // A rider who left the convoy — or was evicted, or whose socket
            // dropped — is gone now, not in 20 s when the staleness sweep would
            // have caught up. The relay emits this for every one of those cases
            // (`server/sync/sync_server.py:2442`, `:2456`) and Android has
            // always handled it (`net/ConvoyLiveClient.kt:573-576`); without the
            // branch it fell through to `default` and the peer stayed on the map.
            guard let user = msg["user"] as? String, !user.isEmpty else { break }
            peers.removeValue(forKey: user)
            talking.remove(user)

        case "ptt_start":
            if let user = msg["user"] as? String, !user.isEmpty { talking.insert(user) }

        case "ptt_end":
            if let user = msg["user"] as? String, !user.isEmpty { talking.remove(user) }

        case "ptt_audio":
            guard let user = msg["user"] as? String, !user.isEmpty,
                  let chunk = msg["chunk"] as? String,
                  // The server caps chunk length but does not validate that it
                  // is base64; a malformed frame must not take the socket down.
                  let pcm = Data(base64Encoded: chunk) else { break }
            PttAudio.shared.play(pcm, from: user)

        case "spin_offer":
            guard let raw = msg["candidates"] as? [[String: Any]] else { break }
            let candidates: [SpinCandidate] = raw.compactMap { o in
                guard let lat = o["lat"] as? Double, let lon = o["lon"] as? Double else {
                    return nil
                }
                let name = o["name"] as? String
                return SpinCandidate(
                    lat: lat,
                    lon: lon,
                    distanceM: o["distanceM"] as? Double,
                    durationS: o["durationS"] as? Double,
                    name: (name?.isEmpty ?? true) ? nil : name
                )
            }
            // A new offer starts a fresh vote even mid-round: the candidates it
            // names are a different sheet than whatever was being voted on.
            guard !candidates.isEmpty else { break }
            spinOffer = GroupSpin(candidates: candidates, fromMe: false)
            spinVotes = [:]

        case "spin_vote":
            guard let user = msg["user"] as? String, !user.isEmpty,
                  let index = msg["index"] as? Int, (0...2).contains(index) else { break }
            spinVotes[user] = index

        case "place_event":
            // Deliberately hand-parsed here rather than routed through
            // `:shared`'s `placeEventFromRelayFrame(o: JsonObject)` — that
            // function wants a genuine kotlinx.serialization `JsonObject`,
            // which nothing in this file (or anywhere else in iosApp) ever
            // constructs from a Foundation `[String: Any]`, and the framework
            // doesn't `export()` kotlinx-serialization-json, so there is no
            // precedent anywhere in this codebase for building one from
            // Swift to check a guessed spelling against. Every other case in
            // this same switch already hand-parses its frame the same way
            // (see "location" above) — this mirrors that, and mirrors
            // `placeEventFromRelayFrame`'s own field/validity rules exactly
            // (required fields, `kind` must be arrive/depart) so the two
            // don't drift. See the phase-3 report for the full reasoning.
            guard let groupId = msg["groupId"] as? String,
                  let placeId = msg["placeId"] as? Int,
                  let kind = msg["kind"] as? String, kind == "arrive" || kind == "depart",
                  let user = msg["user"] as? String, !user.isEmpty,
                  let tsMs = msg["tsMs"] as? Int else { break }
            let event = PlaceEvent(
                // A live frame addresses nothing, so it carries no stored id.
                id: "",
                placeId: Int64(placeId),
                placeName: msg["placeName"] as? String ?? "",
                username: user,
                kind: kind,
                tsMs: Int64(tsMs)
            )
            CircleNotifications.shared.handleLiveEvent(groupId: groupId, event: event)

        default:
            break
        }
        return false
    }

    /// Sweeps staleness on a timer for the life of the connection loop. The
    /// inbound `location` branch also prunes, which keeps a busy convoy tidy
    /// promptly, but on its own it never fired in the case that matters: a
    /// convoy where everybody has gone quiet is never swept, and the last peer
    /// to go quiet is the interesting one — that is the rider who lost signal or
    /// came off.
    private func prunePeersPeriodically() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: Self.peerPruneInterval)
            guard !Task.isCancelled else { return }
            pruneStalePeers()
        }
    }

    private func pruneStalePeers() {
        let now = nowMs()
        // Each peer expires on its own clock: a circle member updating every
        // couple of minutes and a convoy rider updating every two seconds share
        // this map, and one cutoff for both drops the first between every pair
        // of their updates.
        peers = peers.filter { $0.value.expiresAtMs > now }
    }
}
