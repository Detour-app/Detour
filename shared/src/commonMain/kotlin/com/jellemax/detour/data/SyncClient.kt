package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bidirectional sync with the rider's own server (see backend/README.md). One
 * POST uploads local trips, fog-of-war traces, badges and the aggregate stats
 * friends are allowed to see; the server merges them with its copy and returns
 * the union, which replaces the local stores. Deleting and reinstalling the app
 * therefore restores everything on the first sync.
 *
 * The server keys everything on the signed-in rider, so syncing requires a
 * session ([Auth]). Traces and trips are only ever returned to their owner.
 */
object SyncClient {

    data class SyncResult(val trips: Int, val traces: Int, val badges: Int)

    /** Below this age, [syncIfDue] treats the last sync as still fresh and
     *  skips the round trip — the launch-time auto-sync is the only caller
     *  gated this way; manual "sync now" and the post-toggle/post-trip syncs
     *  call [sync] directly. */
    private const val AUTO_SYNC_MIN_INTERVAL_MS = 5 * 60_000L

    /** Effective API base: the one server address the user set (Settings) →
     *  baked default. The custom address still wins, which is what lets someone
     *  point a release APK at their own server. */
    fun url(): String? =
        RoutingServer.apiBase(RoutingServer.loadCustom()).ifBlank { null }

    fun configured(): Boolean = url() != null

    /** Same as [sync], but skipped if a sync succeeded within the last five
     *  minutes — the launch-time auto-sync is a full-history round trip
     *  ([sync]'s doc comment), and re-paying that on every cold start when
     *  nothing has changed is exactly the unconditional cost this exists to
     *  avoid. Returns null when skipped. */
    suspend fun syncIfDue(): SyncResult? {
        Settings.init()
        if (nowMs() - Settings.lastSyncMs() < AUTO_SYNC_MIN_INTERVAL_MS) return null
        return sync()
    }

    /**
     * `@Throws(Exception::class)`, here and on every other exported `suspend`
     * function Swift calls directly: Kotlin/Native only turns a thrown
     * `CancellationException` into an `NSError` on its own — anything else a
     * `suspend` function throws aborts the process before a Swift `catch`
     * ever runs (Kotlin/Native's own docs on Objective-C/Swift interop say so
     * plainly). Every `try await` under `iosApp/Detour/` was one network
     * error away from that until this annotation went in.
     *
     * `Exception` rather than [okio.IOException]: what this module's
     * `suspend` functions actually raise is [AuthException] or
     * [HttpStatusException] (both `IOException` — see `Api.kt`/`Http.kt`),
     * but a server that answers something the wire format doesn't expect
     * surfaces as a kotlinx-serialization failure, and that is an
     * `IllegalArgumentException`, not an `IOException`. Naming only
     * `IOException` would leave the app terminating on exactly the response
     * an unfriendly server sends. `Exception` also lets a genuine
     * programming error through, which then reaches the rider as "something
     * went wrong" instead of crashing loudly into a bug report — a
     * deliberate trade, made because every one of these call sites already
     * has a `catch` that reports to the user, and a wrong-ish message on a
     * phone beats an aborted process. A no-op on Android/JVM, where
     * `@Throws` has nothing to attach to.
     *
     * Two more things this choice accepts, worth recording since that's this
     * comment's whole job:
     *
     * `Exception` also covers `CancellationException` (it's an
     * `IllegalStateException`), which is not a gap — it's why a coroutine
     * cancelled out from under a Swift caller (a `.task(id:)` whose key
     * changed mid-await, say) now arrives there as an ordinary `NSError`
     * instead of as Swift's own `CancellationError`. A `catch` on the Swift
     * side that doesn't check `Task.isCancelled` before reporting will treat
     * the rider's own cancellation as a failure worth an alert. See
     * `FriendsScreen.swift`'s `reload()` for where that actually bit, and
     * its comment for the fix; the two comments should be read together.
     *
     * It does **not** cover `Error`/`Throwable` subclasses — only
     * `Exception` and below. `OutOfMemoryError`, `AssertionError`, and the
     * `NotImplementedError` a bare `TODO()` throws all still abort the
     * process before reaching Swift. Deliberate: those are conditions a
     * `catch` reporting "something went wrong" to the rider would only
     * mislabel, not meaningfully recover from.
     *
     * Not repeated at every other site this reasoning applies to — read it
     * here.
     */
    @Throws(Exception::class)
    suspend fun sync(): SyncResult {
        Settings.init()

        // Signed in but with nothing to key a bucket on: the files being read
        // below belong to the anonymous bucket, which is not this session's.
        // Uploading them is precisely #73, so this refuses instead. A sync
        // that does not happen is recoverable; one that puts another rider's
        // history into this account is not.
        if (Account.signedIn && AccountScope.current() == AccountScope.ANONYMOUS) {
            throw AuthException("Sign out and sign in again to link this device's rides to your account.")
        }

        // The check above is point-in-time; the POST below suspends for a full
        // history round trip, and the write-back after it re-resolves
        // accountFile() fresh (deliberately — see Files.kt). Between the two,
        // Auth.clear() or Auth.store() can move the bucket out from under this
        // call: the launch sync runs on a scope that is never cancelled
        // (app/data/AndroidSync.kt), so a sign-out mid-flight would write this
        // rider's entire server-side history into `_local`, and a sign-in
        // would write it into the next rider's bucket for their next sync to
        // upload — #73 again, on the function this guard exists to protect.
        // Same epoch capture FriendsStore.reload and CirclesStore use.
        val epoch = Auth.sessionEpoch.value

        // Coverage is the only stat the server can't derive from the trips it
        // already holds — it needs the boundaries, which only we have.
        val stats = BadgeStore.stats(Coverage.compute())

        val payload = buildJsonObject {
            put("trips", tripsForUpload())
            // Deletions the server has not seen yet. Without them its copy comes
            // back in the merge and only this device's own tombstone filter
            // hides it — every other device would resurrect the trip.
            put("deletedTripStartTimes", buildJsonArrayOfLongs(TripStore.deletedStartTimes()))
            put("traces", buildJsonArrayOfStrings(TraceStore.rawLines()))
            put("badges", jsonObjectOf(BadgeStore.rawJson()))
            put("savedPlaces", jsonArrayOf(SavedPlaces.rawJson()))
            put("stats", stats.toJson())
            put("shareFog", Settings.shareFog.value)
        }

        // Again, immediately before the POST. The capture above guards the
        // write-back; it does not guard the upload, because every field in
        // `payload` was read before Api.request resolves the bearer. A
        // session change in between sends this rider's history under the next
        // rider's token — #73 verbatim, and not a narrow window either:
        // Coverage.compute() walks every trace point against every boundary
        // and TraceStore.rawLines() reads a multi-megabyte file, which on a
        // year of riding is seconds, not instants.
        if (epoch != Auth.sessionEpoch.value) return SyncResult(0, 0, 0)

        val merged = Api.requestJson("POST", "/sync", payload)
        if (epoch != Auth.sessionEpoch.value) {
            // Whoever this response belongs to is no longer who this device is
            // signed in as. Nothing is written — not the stores, and not
            // lastSyncMs, which would otherwise let this discarded round trip
            // suppress the new session's own launch sync for five minutes.
            // Reported as a sync that brought nothing back rather than as a
            // failure: the POST did succeed, the server has the upload, and
            // the only untrue thing to say here would be a count of records
            // that did not land.
            return SyncResult(0, 0, 0)
        }
        val trips = merged.optArray("trips") ?: JsonArrayEmpty
        val traces = merged.optArray("traces") ?: JsonArrayEmpty
        val badges = merged.optObject("badges") ?: jsonObjectOf("{}")

        TripStore.replaceRaw(trips.string())
        TraceStore.replaceLines(traces.indices.map { traces.optString(it) })
        BadgeStore.replaceRaw(badges.string())
        // Absent on an older server: leave the local shortcuts untouched.
        merged.optArray("savedPlaces")?.let {
            SavedPlaces.replaceFromServer(it.string())
        }
        Settings.setLastSyncMs(nowMs())
        return SyncResult(trips.size, traces.size, badges.size)
    }

    /**
     * The stored trips, each with `topSpeedKmh` alongside the `topSpeedMps` this
     * app records in.
     *
     * The server keeps a trip document opaque apart from a handful of fields the
     * read-only dashboard lists, and top speed is one of them — in km/h. Derived
     * here rather than written into the store so that trips recorded before this
     * build also arrive complete.
     */
    private fun tripsForUpload() = buildJsonArray {
        for (trip in jsonArrayOf(TripStore.rawJson()).objects()) {
            add(buildJsonObject {
                trip.forEach { (key, value) -> put(key, value) }
                put("topSpeedKmh", trip.optDouble("topSpeedMps", 0.0) * 3.6)
            })
        }
    }
}
