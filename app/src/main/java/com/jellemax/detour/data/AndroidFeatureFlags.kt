package com.jellemax.detour.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Unstructured for the reason `syncScope` is: the callers are a cold start, a
 * sign-in redirect and a sign-out that navigates away the moment it returns —
 * none has a scope whose lifetime should decide whether the answer is cached.
 */
private val flagsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Fire-and-forget [FeatureFlags.refresh] (#815); never throws, never blocks the
 * caller. Reads the cache first, on IO, so [FeatureFlags.readings] shows the last
 * answer while the request is out — `ensureLoaded` reads JSON off disk and must
 * not run on the main thread during a cold start.
 *
 * Android-side for the same reason [syncQuietly] is: a threading convenience, not
 * logic. iOS awaits `refresh()` from a Task (#816).
 */
fun FeatureFlags.refreshQuietly() {
    flagsScope.launch {
        ensureLoaded()
        try {
            refresh()
        } catch (e: Exception) {
            // refresh() already swallows everything but cancellation.
        }
    }
}
