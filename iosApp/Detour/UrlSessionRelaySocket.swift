import Foundation
import DetourShared

/// [RelaySocket] over `URLSessionWebSocketTask` — the iOS half of the seam
/// `ConvoyRelay` runs against, replacing the connect/receive/close logic
/// `ConvoyLiveClient.swift` used to hand-roll. One instance is constructed
/// once, by `ConvoyLiveClient`, and reused for every reconnect attempt of a
/// single `ConvoyRelay.run()` call — `RelaySocket`'s own doc requires
/// exactly that, and it is also what lets `close()` reach whatever attempt
/// is currently live without `ConvoyLiveClient` having to track one itself.
///
/// **Keeps the hand-scheduled ping** the old `ConvoyLiveClient.swift`'s
/// `keepAlive` ran — `URLSessionWebSocketTask` has no `pingInterval`, the one
/// deliberate divergence from OkHttp `docs/IOS_PORT.md` records, and OkHttp's
/// `pingInterval` is what keeps NAT and the Cloudflare tunnel from idling a
/// quiet connection closed.
///
/// **Not `@MainActor`.** `RelaySocket.close()`'s own doc is explicit that a
/// `@MainActor` implementation "either isolation-hazards the Kotlin-side
/// caller or, worse, silently no-ops" — `ConvoyRelay` calls `close()` from
/// whatever thread ran `stop()` or a membership change, which is Kotlin's
/// own dispatcher, not necessarily Swift's main actor. So every mutable field
/// below is guarded by a plain `NSLock` instead, the Swift-side equivalent of
/// the `@Volatile` flag `OkHttpRelaySocket.Session.closed` uses for the same
/// "a close() lands mid-upgrade" window.
final class UrlSessionRelaySocket: NSObject, RelaySocket, URLSessionWebSocketDelegate {

    private static let pingInterval: Duration = .seconds(20)

    /// One connect attempt's live wiring, replaced wholesale by every
    /// `connect` — mirrors `OkHttpRelaySocket.Session`: a frame, a delegate
    /// callback or a `close()` arriving for a previous, already-superseded
    /// attempt must never be mistaken for this one's.
    private final class Session {
        private let lock = NSLock()
        private var closedFlag = false
        private var openResolved = false
        private var pendingOpen: CheckedContinuation<Void, Error>?

        var task: URLSessionWebSocketTask?
        var pinger: Task<Void, Never>?

        /// Set by `forceClose()` so a `connect` still in flight does not hand
        /// back a live socket nobody wants, and so `receive()` can tell "the
        /// far end closed" from "we closed it ourselves" — see
        /// `RelaySocket.receive`'s own doc on why those read back
        /// differently.
        var closed: Bool {
            lock.lock(); defer { lock.unlock() }
            return closedFlag
        }

        /// Registers `continuation` to be resumed once this attempt's open
        /// outcome is known. Resolves at once, with a "closed before opening"
        /// error, if `forceClose()` already landed by the time this runs —
        /// the exact race `RelaySocket.close`'s doc calls out: a close()
        /// between session creation and the socket actually opening.
        func awaitOpen(_ continuation: CheckedContinuation<Void, Error>) {
            lock.lock()
            guard !openResolved else { lock.unlock(); return }
            if closedFlag {
                openResolved = true
                lock.unlock()
                continuation.resume(throwing: relayError("Can't reach the live server"))
                return
            }
            pendingOpen = continuation
            lock.unlock()
        }

        func resolveOpened() {
            lock.lock()
            guard !openResolved else { lock.unlock(); return }
            openResolved = true
            let continuation = pendingOpen
            pendingOpen = nil
            lock.unlock()
            continuation?.resume()
        }

        func resolveFailed(_ error: Error) {
            lock.lock()
            guard !openResolved else { lock.unlock(); return }
            openResolved = true
            let continuation = pendingOpen
            pendingOpen = nil
            lock.unlock()
            continuation?.resume(throwing: error)
        }

        /// Marks this session closed and cancels its task — safe from any
        /// thread, including before the task has even finished opening.
        /// Idempotent, and safe to call with no task yet.
        func forceClose() {
            lock.lock()
            closedFlag = true
            let t = task
            lock.unlock()
            t?.cancel(with: .goingAway, reason: nil)
        }
    }

    /// One `URLSession` for the life of this socket, its delegate self —
    /// `URLSessionWebSocketTask` has no `onOpen` callback of its own the way
    /// OkHttp's `WebSocketListener` does, so confirming the upgrade actually
    /// succeeded (rather than leaving that to the first `receive()`, which
    /// `RelaySocket.connect`'s own doc says not to do) needs this session's
    /// delegate methods below.
    private lazy var urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: nil)

    private let stateLock = NSLock()
    private var current: Session?

    private func currentSession() -> Session? {
        stateLock.lock(); defer { stateLock.unlock() }
        return current
    }

    func connect(bearer: String) async throws {
        let liveUrl = BuildDefaults.shared.liveUrl
        guard !liveUrl.isEmpty, let url = URL(string: liveUrl) else {
            throw relayError("No live server configured")
        }

        var request = URLRequest(url: url)
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        // The relay sits behind the same Cloudflare Access service token as
        // the routing server.
        let cf = RoutingServer.shared.load()
        if !cf.clientId.isEmpty {
            request.setValue(cf.clientId, forHTTPHeaderField: "CF-Access-Client-Id")
            request.setValue(cf.clientSecret, forHTTPHeaderField: "CF-Access-Client-Secret")
        }

        let attempt = Session()
        stateLock.lock()
        current = attempt
        stateLock.unlock()

        let task = urlSession.webSocketTask(with: request)
        attempt.task = task

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            attempt.awaitOpen(continuation)
            task.resume()
        }

        attempt.pinger = Task { [weak self] in await self?.keepAlive(attempt) }
    }

    func receive() async throws -> String? {
        guard let s = currentSession(), let task = s.task else { return nil }
        do {
            let message = try await task.receive()
            switch message {
            case .string(let text):
                return text
            case .data:
                // The relay never sends a binary frame; a non-text frame is
                // exactly the "abnormal failure" RelaySocket.receive's own
                // doc enumerates, not a silent skip.
                throw relayError("Unexpected binary frame from the live server")
            @unknown default:
                throw relayError("Unexpected frame from the live server")
            }
        } catch {
            // "We closed it" reads back as a graceful nil, matching a
            // far-end close — see RelaySocket.receive's own doc. Anything
            // else is a genuine abnormal failure and is rethrown as-is.
            if s.closed { return nil }
            throw error
        }
    }

    func send(text: String) {
        guard let task = currentSession()?.task else { return }
        task.send(.string(text)) { _ in }
    }

    func close() {
        guard let s = currentSession() else { return }
        s.pinger?.cancel()
        s.forceClose()
    }

    private func keepAlive(_ session: Session) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: Self.pingInterval)
            guard !Task.isCancelled, !session.closed, let task = session.task else { return }
            task.sendPing { _ in }
        }
    }

    // MARK: URLSessionWebSocketDelegate

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        guard let s = currentSession(), s.task === webSocketTask else { return }
        s.resolveOpened()
    }

    /// Fires for a rejected upgrade as well as an ordinary drop after being
    /// open — `resolveFailed` is a no-op past the open moment (see
    /// `Session.openResolved`), so this only ever affects an attempt still
    /// waiting on `connect()`. `task.response` carries the HTTP response even
    /// on a failed upgrade, which is what puts the status code in the
    /// message — `RelaySocket.connect`'s own doc requires naming it, the same
    /// as `OkHttpRelaySocket`'s `onFailure` does for a refused upgrade.
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let webSocketTask = task as? URLSessionWebSocketTask,
              let s = currentSession(), s.task === webSocketTask,
              error != nil else { return }
        let response = task.response as? HTTPURLResponse
        let message = response.map { "Live server refused the connection (\($0.statusCode))" }
            ?? "Can't reach the live server"
        s.resolveFailed(relayError(message))
    }
}

/// Words a failure for `RelaySocket`'s own contract: "the exception's message
/// reaches the rider as `lastError` verbatim" — every throw site above builds
/// its own message here rather than letting `URLSession`'s own wording
/// through.
private func relayError(_ message: String) -> NSError {
    NSError(domain: "com.jellemax.detour.relay", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
}
