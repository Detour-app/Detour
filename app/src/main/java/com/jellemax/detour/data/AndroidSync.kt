package com.jellemax.detour.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Unstructured on purpose, and only barely: the callers are a Switch's
 * onCheckedChange and a trip ending in a Service — neither has a scope whose
 * lifetime should decide whether a sync completes. A SupervisorJob keeps one
 * failed sync from poisoning the next.
 */
private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Fire-and-forget sync; never throws.
 *
 * This lived inside SyncClient as a bare `Thread { … }` before the core moved
 * to commonMain. It stays behind on the Android side rather than being ported
 * because it is a threading convenience, not logic: iOS callers await
 * `SyncClient.sync()` from a Task and there is nothing to share.
 */
fun SyncClient.syncQuietly() {
    if (!configured() || !Account.signedIn) return
    syncScope.launch {
        try {
            sync()
        } catch (e: Exception) {
            // Offline, server down, or signed out; the next sync catches up.
        }
    }
}
