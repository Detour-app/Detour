package com.jellemax.detour.drive

/**
 * The one platform seam [ConvoyRelay] runs against: opening a connection,
 * reading text frames off it, writing them back, and closing it again.
 * Android implements this directly in Kotlin over OkHttp's `WebSocket`; iOS
 * implements it in Swift over `URLSessionWebSocketTask`.
 *
 * That Swift implementation is exactly why [connect] and [receive] are
 * `@Throws`. A Kotlin `interface`'s suspend member, implemented in Swift,
 * compiles to an Objective-C completion-handler bridge with no cancellation
 * path either way across it - an unmarked throw on that bridge does not
 * surface as a Swift `throws`, it terminates the process. See
 * [ConvoyRelay.stop]'s doc for the other half of the same constraint: since
 * cancelling the Swift `Task` that awaits [ConvoyRelay.run] cannot cancel the
 * coroutine underneath, this interface's [close] is what a blocked [receive]
 * has to unblock through instead.
 *
 * One instance is reused across every reconnect attempt of a single
 * [ConvoyRelay.run] call - [connect] may be called again after a previous
 * attempt ended (gracefully or not), each time opening a fresh underlying
 * connection. Resolving the URL and any auth headers beyond the bearer
 * itself (Cloudflare Access, in this codebase) is deliberately left to
 * whoever constructs the implementation, not something this interface
 * carries - that resolution differs by platform (Android's needs a
 * `Context`; iOS's does not) in a way [ConvoyRelay] itself must stay free of,
 * per `Platform.kt`'s module-boundary rules.
 */
interface RelaySocket {

    /**
     * Opens a connection, presenting [bearer] however the transport needs to
     * (an `Authorization` header, alongside whatever else the implementation
     * already resolved before it was constructed). Throws on failure to open
     * at all - unreachable host, TLS, a non-101 response - rather than
     * leaving [receive] to report it once a loop is already spinning on it.
     *
     * A non-101 response should fold its status code into the thrown
     * exception's message - Android's own wording is
     * `"Live server refused the connection (${response.code})"`. [ConvoyRelay]'s
     * `lastError` surfaces `e.message` more or less verbatim (see
     * `unreachableMessage`), so an implementation that omits the code makes a
     * 401 read identically to a host that could not be reached at all.
     */
    @Throws(Exception::class)
    suspend fun connect(bearer: String)

    /**
     * Suspends for the next text frame. Returns `null` once the connection
     * has closed with nothing further to deliver - the far end closed
     * gracefully, or [close] was called to unblock this very call - so a
     * caller can tell "closed" from "still open, nothing yet" without a
     * second signal. Throws for an abnormal failure (a reset connection, a
     * timeout, a non-text frame) instead of also returning `null` for that,
     * so [ConvoyRelay] can still tell a graceful close from one worth
     * reporting through `lastError`.
     */
    @Throws(Exception::class)
    suspend fun receive(): String?

    /** Fire-and-forget; silently dropped if nothing is currently connected. */
    fun send(text: String)

    /**
     * Closes the current connection, if any, so a [receive] blocked on it
     * returns (with `null`, or throws) instead of hanging forever. Safe to
     * call more than once, and safe to call with nothing connected.
     *
     * **Must be safe to call from any thread, including while [receive] is
     * suspended on another.** Every removal path and [ConvoyRelay.stop]
     * calls this from whatever thread its caller happens to be on - never
     * the thread [ConvoyRelay.run]'s own coroutine is parked in `receive()`
     * on. A Swift implementation backed by `URLSessionWebSocketTask` that
     * lands on a `@MainActor` type has to honour this deliberately: a
     * same-actor-only [close] either isolation-hazards the Kotlin-side
     * caller or, worse, silently no-ops and leaves the device joined to
     * whatever it was just told to leave - see [ConvoyRelay]'s class doc for
     * the exact shape of leak that becomes.
     */
    fun close()
}
