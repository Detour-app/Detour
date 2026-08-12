package com.jellemax.detour.data

/**
 * Features that have no server behind them right now.
 *
 * The backend was rebuilt (`backend/`) and identity moved to the realm. The live
 * relay went with it and is back, so [liveRelay] is on again — everything except
 * voice, which the rebuilt relay accepts off the wire and drops.
 *
 * The file stays because the shape is worth keeping: a button that silently does
 * nothing reads as a bug, while "temporarily disabled" reads as a plan. Both apps
 * read these, so neither can quietly disagree with the other about what works.
 */
object Features {

    /**
     * Convoy live location, the shared destination vote and live arrival
     * notifications. All three ride one WebSocket, per rider rather than per
     * group.
     *
     * Push-to-talk is deliberately *not* covered by this flag: the relay drops
     * voice frames rather than relaying them, so it needs its own decision and
     * its own codec before it comes back. See [pushToTalk].
     *
     * Plain vals rather than `const`: a const in a Kotlin object is exported to
     * Swift as a class member, an ordinary val as a property on `Features.shared`
     * — and the second is what reads the same on both sides of the boundary.
     */
    val liveRelay = true

    /**
     * Push-to-talk. Off until the relay carries voice again — raw 16 kHz PCM
     * base64'd over JSON cost roughly 40 KB/s per talker per listener, so what
     * comes back will be Opus over binary frames, not what this used to send.
     */
    val pushToTalk = false

    /** Shown wherever one of those controls used to be. */
    val liveRelayNotice = "Feature is temporarily disabled"

    /** The sentence under it, for the one screen that has room to explain. */
    val liveRelayReason =
        "Live location, push-to-talk and arrival alerts need the relay, which is " +
            "being rebuilt along with the rest of the server. Everything else — " +
            "sync, friends, circles, shared places — works."
}
