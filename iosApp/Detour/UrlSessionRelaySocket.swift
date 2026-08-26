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
    ///
    /// **`task` and `pinger` live behind the same lock as everything else
    /// here now** — they did not always. Locking `closedFlag` while leaving
    /// `task`/`pinger` as plain unguarded `var`s was no exclusion at all: a
    /// `close()` landing between `connect()` taking this session and
    /// assigning its task used to set `closedFlag` with no task yet to
    /// cancel, `awaitOpen` (below) would resolve the pending open with an
    /// error right on schedule — but `connect()`'s own `task.resume()` ran
    /// on the very next line regardless, opening an authenticated socket
    /// nobody wanted any more. `setTask`/`setPinger` below are what close
    /// that: both check the same `closedFlag` under the same lock the field
    /// write itself takes, so a caller can tell whether it is still safe to
    /// call `resume()` at all.
    private final class Session {
        private let lock = NSLock()
        private var closedFlag = false
        private var openResolved = false
        private var pendingOpen: CheckedContinuation<Void, Error>?
        private var _task: URLSessionWebSocketTask?
        private var _pinger: Task<Void, Never>?

        /// Set by `forceClose()` so a `connect` still in flight does not hand
        /// back a live socket nobody wants, and so `receive()` can tell "the
        /// far end closed" from "we closed it ourselves" — see
        /// `RelaySocket.receive`'s own doc on why those read back
        /// differently. Also set by a `didCloseWith` delegate callback (a
        /// graceful close the *far end* initiated), which reuses this same
        /// flag rather than a second one: `receive()` does not need to know
        /// which side actually closed, only that it did.
        var closed: Bool {
            lock.lock(); defer { lock.unlock() }
            return closedFlag
        }

        var task: URLSessionWebSocketTask? {
            lock.lock(); defer { lock.unlock() }
            return _task
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

        /// Registers this attempt's task under the same lock `forceClose()`
        /// reads it with, and reports whether the caller should still call
        /// `resume()` on it: false once `forceClose()` already landed, since
        /// the pending open has already been (or is about to be) resolved
        /// with an error by then, and resuming the task regardless would
        /// only open a connection nobody wants — see this class's own doc.
        func setTask(_ task: URLSessionWebSocketTask) -> Bool {
            lock.lock()
            _task = task
            let stillWanted = !closedFlag
            lock.unlock()
            return stillWanted
        }

        /// Same shape as `setTask` - registers the ping loop under the lock,
        /// and cancels it immediately if this session was already closed by
        /// the time it starts, rather than leaving it to tick forever on a
        /// task nobody wants pinged.
        func setPinger(_ pinger: Task<Void, Never>) {
            lock.lock()
            _pinger = pinger
            let alreadyClosed = closedFlag
            lock.unlock()
            if alreadyClosed { pinger.cancel() }
        }

        /// Marks this session closed, cancels its task and ping loop, and
        /// resolves a *genuinely pending* open with an error — safe from any
        /// thread, including before the task has even finished opening.
        /// Idempotent, and safe to call with no task yet.
        ///
        /// **Only resolves an open that `awaitOpen` has actually registered**
        /// (`pendingOpen != nil`) — not "mark the whole open resolved
        /// unconditionally", which would race `awaitOpen` itself: a
        /// `forceClose()` landing *before* `connect()` ever calls `awaitOpen`
        /// must leave `openResolved` false, so `awaitOpen`'s own `closedFlag`
        /// check (above) is what resolves the continuation it is actually
        /// handed - flipping `openResolved` here too early would make that
        /// check silently drop it instead, parking `connect()` forever
        /// despite this very function's own purpose. Once a continuation
        /// genuinely is pending, though - `connect()` suspended past
        /// `awaitOpen`, waiting on a delegate callback that a terminal
        /// callback carrying no error (or none at all) would otherwise never
        /// resolve - resolving it here is exactly what lets `stop()` break a
        /// parked `connect()` out, which nothing did before.
        func forceClose() {
            lock.lock()
            closedFlag = true
            let t = _task
            let p = _pinger
            var continuation: CheckedContinuation<Void, Error>?
            if !openResolved, pendingOpen != nil {
                openResolved = true
                continuation = pendingOpen
                pendingOpen = nil
            }
            lock.unlock()
            t?.cancel(with: .goingAway, reason: nil)
            p?.cancel()
            continuation?.resume(throwing: relayError("Can't reach the live server"))
        }
    }

    /// One `URLSession` for the life of this socket, its delegate self —
    /// `URLSessionWebSocketTask` has no `onOpen` callback of its own the way
    /// OkHttp's `WebSocketListener` does, so confirming the upgrade actually
    /// succeeded (rather than leaving that to the first `receive()`, which
    /// `RelaySocket.connect`'s own doc says not to do) needs this session's
    /// delegate methods below.
    ///
    /// `delegate: self` makes the session retain this socket for as long as
    /// the session itself is alive, and nothing here ever calls
    /// `invalidateAndCancel()` to break that - so this type can never
    /// actually be deallocated. Bounded today: `ConvoyLiveClient` constructs
    /// exactly one `UrlSessionRelaySocket` for the app's process lifetime
    /// (see `RelaySocket`'s own "one socket, many groups" doc), so the leak
    /// is real but never grows. Worth fixing before a second instance is
    /// ever constructed anywhere.
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

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            attempt.awaitOpen(continuation)
            // setTask() takes the same lock a concurrent forceClose() reads
            // `_task` under, and says whether resuming below is still worth
            // doing at all — see Session's own doc for the race this closes:
            // a close() landing here used to resolve the continuation above
            // with an error while this still opened the connection anyway.
            if attempt.setTask(task) {
                task.resume()
            }
        }

        attempt.setPinger(Task { [weak self] in await self?.keepAlive(attempt) })
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
        // forceClose() cancels the pinger itself now (see Session's own
        // doc), so there is nothing left for this to do beyond finding the
        // current attempt.
        currentSession()?.forceClose()
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
    ///
    /// **A `nil` `error` still resolves.** This used to return early on a
    /// `nil` error and do nothing at all — but a terminal callback with no
    /// error is still terminal: the task will not call anything else, so a
    /// `connect()` still waiting on `awaitOpen` at that point would park
    /// forever, with no `lastError` set and no way for `stop()` (which
    /// reaches this same session only through `close()`/`forceClose()`) to
    /// break it out either. `resolveFailed` is the same no-op it always was
    /// once the open has already resolved one way or the other, so this
    /// costs nothing on the ordinary paths.
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let webSocketTask = task as? URLSessionWebSocketTask,
              let s = currentSession(), s.task === webSocketTask else { return }
        guard let error else {
            s.resolveFailed(relayError("Can't reach the live server"))
            return
        }
        let response = task.response as? HTTPURLResponse
        let message = response.map { "Live server refused the connection (\($0.statusCode))" }
            ?? "Can't reach the live server"
        s.resolveFailed(relayError(message))
    }

    /// A close frame arriving - ours or the far end's, `closeCode` does not
    /// distinguish which - is exactly the graceful close
    /// `RelaySocket.receive`'s own doc describes: nothing further to
    /// deliver, and not a failure worth reporting through `lastError`.
    /// Reusing `forceClose()` here is what actually makes that true: without
    /// this method at all, only *our own* `close()` call ever set `closed`,
    /// so a far-end-initiated close fell through `receive()`'s `catch` block
    /// with `s.closed` still false and was rethrown as an abnormal failure
    /// instead of returned as `nil`, same shape as `OkHttpRelaySocket`'s own
    /// `ClosedReceiveChannelException` handling gets right.
    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        guard let s = currentSession(), s.task === webSocketTask else { return }
        s.forceClose()
    }
}

/// Words a failure for `RelaySocket`'s own contract: "the exception's message
/// reaches the rider as `lastError` verbatim" — every throw site above builds
/// its own message here rather than letting `URLSession`'s own wording
/// through.
private func relayError(_ message: String) -> NSError {
    NSError(domain: "com.jellemax.detour.relay", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
}
