package com.jellemax.detour.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One piece of work at a time, and a call made from *inside* that work is a
 * no-op rather than a wait.
 *
 * The debounce half is the ordinary reason for a gate, and the same one
 * `ConvoysStore.refreshGate` and `Auth.refreshLock` are bare [Mutex]es for: a
 * burst of callers that all want the same answer should open one request, not
 * one each. Those two stay bare deliberately — neither re-enters itself, and
 * `Coverage.writeLock` must serialise writes rather than drop them, which is
 * the opposite of what this type does.
 *
 * The reentrancy half is why this one is a type. `Auth.resolveRiderId` fetches
 * the rider id with an authenticated request, and every authenticated request
 * resolves the rider id — so the work re-enters itself one frame down. A
 * `kotlinx` [Mutex] is not reentrant, so that second entry waited forever on a
 * lock its own caller was holding, wedging every authenticated call of a fresh
 * sign-in with no error and no timeout. Skipping the nested call is correct
 * rather than merely safe: it is the same work, already running, and its result
 * lands in the same place.
 *
 * [inFlight] is read once outside the lock as a fast path. A stale `false`
 * there costs a caller the wait on [gate] and the second check inside it; a
 * stale `true` skips a resolve that a later call makes again. Neither is a
 * correctness problem, and the nested case — the one that matters — is always
 * the same coroutine that set the flag.
 */
internal class SingleFlight {

    private val gate = Mutex()
    private var inFlight = false

    suspend fun runOnce(block: suspend () -> Unit) {
        if (inFlight) return
        gate.withLock {
            if (inFlight) return
            inFlight = true
            try {
                block()
            } finally {
                inFlight = false
            }
        }
    }
}
