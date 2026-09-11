package com.jellemax.detour.tracking

import android.util.Log

/**
 * While [ReplayMode] is on, accepts mock fixes and rejects everything else, so a
 * replay measures the route and not the desk it is being run from.
 *
 * Detour reads Play Services' fused provider, and fused **blends** whatever is
 * enabled: with a real provider live, a mock stream arrives interleaved with the
 * real one. Measured on a CPH2449 during one 20-second replay: 91 mock fixes and
 * 4 real ones, the real ones ~4 km off-route reporting no speed. Four is plenty
 * to do damage. Each calls `resetStartDetector()` and breaks the run of fast
 * fixes the auto-start gate needs — which is why a replay on a real phone never
 * started a trip at all — and once a trip is running, a fix kilometres away is
 * movement well over the 2.0 m/s gate that keeps resetting `lastMovingMs`, so
 * `STATIONARY_END_MS` never elapses and the trip can never end. That is #47.
 *
 * **Two rules this tried before and threw away**, both of which failed on the
 * measured stream rather than in theory:
 *
 * - *Judge fixes by consistency* ("believe a position its neighbours corroborate")
 *   cannot work even in principle — a desk is perfectly consistent — and failed
 *   its own unit test on the interleaved case it was written for.
 * - *Infer the mode from the traffic* (trust the world until a mock fix appears,
 *   distrust it for a few seconds after) leaves every process trusting everything
 *   until the first mock fix lands, which is the position marker blipping between
 *   the route and the desk at the start of every run.
 *
 * What is left guesses nothing: the platform supplies the discriminator — a fix
 * derived from a test provider carries `isMock`, true on all 91 above and false on
 * all 4 — [FusedLocationSource] reads that at the boundary and stamps
 * [FixOrigin.MOCK_PROVIDER] onto the [LocationFix] this gate actually sees (#312),
 * and the rig supplies the mode.
 *
 * **Debug variant only, by source set rather than by an `if`.** The release build
 * compiles `app/src/release/.../ReplayFixGate.kt` instead — same signatures,
 * always accepting — so this is not the filter disabled in a shipped app, it is
 * the filter absent from it. A rider's fix stream must never be second-guessed by
 * a test rig, and `isMock` on a real device means somebody attached a mock
 * provider deliberately, which is their business.
 */
object ReplayFixGate {

    /** How many real fixes this run has thrown away. The number that says whether
     *  a rig is leaking the real world into a measurement. */
    @Volatile
    var rejected: Long = 0L
        private set

    /**
     * How many replayed fixes actually arrived.
     *
     * The measurement a speed ramp needs, and the only honest one available: the
     * harness knows what it pushed, and everything between (four test providers,
     * fused's own blending and thinning, the delivery to this process) can drop
     * fixes on the way. Trace points cannot stand in for it — they are decimated
     * at 25 m and only written to disk when a segment fills or a trip ends, so a
     * run with no trip flushes nothing however many fixes it received.
     */
    @Volatile
    var accepted: Long = 0L
        private set

    /**
     * Whether to believe [fix].
     *
     * Called at the top of the ingest path, so a rejected fix reaches no state at
     * all — not the trip, not the trace, not the start detector. Outside
     * [ReplayMode] this is always `true` and nothing is filtered.
     */
    fun accept(fix: LocationFix): Boolean {
        // Counted for every fix the ingest path sees, not only in mock-only mode,
        // so the port rig (#306) has a delivered count too — it does not use
        // [ReplayMode], having nothing to filter. This is the only place both
        // rigs pass through, which is what makes one counter honest for both.
        accepted++

        // The port rig's half (#306). Measured: a run that pushed 768 fixes saw
        // 777 reach the ingest path — Play Services kept delivering real ones for
        // several seconds after removeLocationUpdates, and a real fix hundreds of
        // metres off-route resets lastMovingMs, so STATIONARY_END_MS never
        // elapses and the trip is never saved. That is #47 again, arriving by a
        // different door, and it is why a port replay recorded no trip at all.
        //
        // Tested by origin rather than by isMock: fixes from ReplayLocationSource
        // are tagged REPLAY_PORT at that source's own boundary (#312) — they are
        // not mock fixes, nothing designated this app.
        if (LocationSources.replay?.armed == true) {
            if (fix.origin == FixOrigin.REPLAY_PORT) return true
            accepted--
            rejected++
            if (rejected % REJECT_LOG_EVERY == 1L) {
                Log.d(TAG, "rejected $rejected platform fixes while the port is armed")
            }
            return false
        }

        if (!ReplayMode.active) return true
        val isMock = fix.origin == FixOrigin.MOCK_PROVIDER
        if (!isMock) {
            accepted--
            rejected++
            // A line per rejection would be a line per second of every replay;
            // the periodic count is what says whether the rig is leaking.
            if (rejected % REJECT_LOG_EVERY == 1L) {
                Log.d(TAG, "rejected $rejected real fixes while replaying")
            }
        }
        return isMock
    }

    /** For the start of a replay, and for a test that wants a clean rig. */
    fun reset() {
        rejected = 0L
        accepted = 0L
    }

    private const val REJECT_LOG_EVERY = 20L
    private const val TAG = "DetourFixGate"
}
