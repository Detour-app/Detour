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
/// every frame carries a `groupId`, and one socket could in principle hold
/// several memberships at once. This client deliberately stays single-convoy —
/// circles never touch this socket at all, they post fixes over plain HTTP at a
/// much lower cadence (see `CircleFixes.postFix`) — so `send` just stamps every
/// outgoing frame with whichever convoy is currently joined, mirroring
/// `ConvoyLiveClient.kt`'s `send()`.
@MainActor
final class ConvoyLiveClient: NSObject, ObservableObject {

    static let shared = ConvoyLiveClient()

    private static let locationSendIntervalMs: Int64 = 2_000
    private static let minBackoff: Duration = .seconds(1)
    private static let maxBackoff: Duration = .seconds(30)
    private static let pingInterval: Duration = .seconds(20)
    /// ~10 missed 2 s location updates: generous enough that a normal gap in
    /// GPS fixes doesn't flicker a peer's marker, but a peer who actually
    /// dropped off stops being shown as live rather than sitting frozen on the
    /// map forever.
    private static let stalePeerMs: Int64 = 20_000

    @Published private(set) var activeConvoyId: Int32?
    @Published private(set) var connected = false
    @Published private(set) var peers: [String: FriendPosition] = [:]
    @Published private(set) var talking: Set<String> = []
    /// Set when the relay can't be reached at all (misconfigured server, not
    /// signed in) or rejects a join. Cleared on a successful join, so a
    /// permanently-failing connection surfaces something instead of retrying
    /// silently forever.
    @Published private(set) var lastError: String?

    private var socket: URLSessionWebSocketTask?
    private var connectionTask: Task<Void, Never>?
    private var lastLocationSentMs: Int64 = 0
    private let session = URLSession(configuration: .default)

    // MARK: Membership

    func join(convoyId: Int32) {
        guard activeConvoyId != convoyId else { return }
        leave()
        activeConvoyId = convoyId
        connectionTask = Task { await self.connectionLoop(convoyId: convoyId) }
    }

    func leave() {
        connectionTask?.cancel()
        connectionTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        activeConvoyId = nil
        connected = false
        peers = [:]
        talking = []
    }

    // MARK: Connection

    /// Reconnects with exponential backoff, reset whenever an attempt actually
    /// got as far as being joined — a connection that worked and then dropped
    /// should come back promptly, unlike one that never authenticated.
    private func connectionLoop(convoyId: Int32) async {
        var backoff = Self.minBackoff
        while !Task.isCancelled {
            let everJoined = await connectAndAwaitClose(convoyId: convoyId)
            if Task.isCancelled { return }
            connected = false
            backoff = everJoined ? Self.minBackoff : min(backoff * 2, Self.maxBackoff)
            try? await Task.sleep(for: backoff)
        }
    }

    private func connectAndAwaitClose(convoyId: Int32) async -> Bool {
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

        send(["type": "join"])
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

    /// Stamps `payload` with the currently joined convoy's id — every frame
    /// needs one now, see the class doc — and sends it, if a convoy is
    /// actually joined.
    private func send(_ payload: [String: Any]) {
        guard let groupId = activeConvoyId else { return }
        var stamped = payload
        stamped["groupId"] = Int(groupId)
        guard let socket,
              let data = try? JSONSerialization.data(withJSONObject: stamped),
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
            send(payload)
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

        case "location":
            guard let user = msg["user"] as? String, !user.isEmpty else { break }
            peers[user] = FriendPosition(
                username: user,
                lat: msg["lat"] as? Double ?? 0,
                lon: msg["lon"] as? Double ?? 0,
                headingDeg: msg["headingDeg"] as? Double,
                speedKmh: msg["speedKmh"] as? Double,
                tsMs: Int64(msg["ts"] as? Int ?? 0)
            )
            pruneStalePeers()

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

        default:
            break
        }
        return false
    }

    private func pruneStalePeers() {
        let cutoff = nowMs() - Self.stalePeerMs
        peers = peers.filter { $0.value.tsMs >= cutoff }
    }
}
