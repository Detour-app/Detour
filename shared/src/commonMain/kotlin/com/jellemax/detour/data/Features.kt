package com.jellemax.detour.data

/**
 * Features that have no server behind them right now.
 *
 * The backend was rebuilt (`backend/`) and identity moved to the realm; the live
 * relay is the one surface that was deliberately not rebuilt with it, and the
 * server that used to answer it is gone. Anything that needed a socket is
 * therefore off — and off *visibly*, which is the point of this file: a button
 * that silently does nothing reads as a bug, while "temporarily disabled" reads
 * as a plan.
 *
 * Flipping [liveRelay] back on is the whole client-side change once the relay
 * exists again. Both apps read these, so neither can quietly disagree with the
 * other about what works.
 */
object Features {

    /**
     * Convoy live location, push-to-talk, the shared destination vote and live
     * arrival notifications. All four ride on one WebSocket.
     *
     * Plain vals rather than `const`: a const in a Kotlin object is exported to
     * Swift as a class member, an ordinary val as a property on `Features.shared`
     * — and the second is what reads the same on both sides of the boundary.
     */
    val liveRelay = false

    /** Shown wherever one of those controls used to be. */
    val liveRelayNotice = "Feature is temporarily disabled"

    /** The sentence under it, for the one screen that has room to explain. */
    val liveRelayReason =
        "Live location, push-to-talk and arrival alerts need the relay, which is " +
            "being rebuilt along with the rest of the server. Everything else — " +
            "sync, friends, circles, shared places — works."
}
