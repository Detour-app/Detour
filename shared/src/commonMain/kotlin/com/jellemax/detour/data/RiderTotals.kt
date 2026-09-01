package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How much a stored [TripTotals] can still be trusted for.
 *
 * The three-way split is what keeps the full fold off the rider's path:
 * [STALE] is served as-is and refreshed behind them, and only [INVALID] — a
 * record that would be *wrong*, not merely old — is worth making them wait for.
 */
enum class Freshness { FRESH, STALE, INVALID }

/**
 * The trip-derived half of [RiderStats], kept as a running record instead of
 * being refolded out of `trips.json` on every read (#83).
 *
 * Speed is stored in m/s, the unit [Trip.topSpeedMps] uses; [RiderStats]
 * converts to km/h at the boundary. Storing the display unit here would mean
 * two units for one number and a conversion to get wrong on the way in.
 *
 * The coverage-derived fields of [RiderStats] are deliberately absent:
 * `Coverage.compute()` already caches its own result, so holding a second copy
 * here would be a second thing to invalidate for no gain.
 */
data class TripTotals(
    val totalDistanceMeters: Double = 0.0,
    val topSpeedMps: Double = 0.0,
    val longestTripMeters: Double = 0.0,
    val maxLeanDeg: Double = 0.0,
    val tripCount: Int = 0,
    val schemaVersion: Int = RiderTotals.SCHEMA_VERSION,
    /** When the record was last agreed with the full history — set by a fold,
     *  carried forward by an increment. What the TTL is measured against. */
    val computedAtMs: Long = 0L,
)

object RiderTotals {

    /** Bumped whenever a field is added or its meaning changes, so a record
     *  written by an older build is recomputed rather than half-read. */
    const val SCHEMA_VERSION = 1

    val EMPTY = TripTotals()

    /**
     * Folds one more trip in. Sums add; maxima take the larger.
     *
     * Correct only for an *addition*. A removal can lower a maximum, and no
     * arithmetic on the record alone can recover the new one — which is why
     * `TripStore.delete` and `TripStore.replaceRaw` invalidate rather than
     * adjust.
     */
    fun including(totals: TripTotals, trip: Trip): TripTotals = totals.copy(
        totalDistanceMeters = totals.totalDistanceMeters + trip.distanceMeters,
        topSpeedMps = maxOf(totals.topSpeedMps, trip.topSpeedMps),
        longestTripMeters = maxOf(totals.longestTripMeters, trip.distanceMeters),
        maxLeanDeg = maxOf(totals.maxLeanDeg, trip.maxLeanAngleDeg),
        tripCount = totals.tripCount + 1,
        // computedAtMs deliberately carried forward, not stamped: it means
        // "last agreed with the whole history", and one trip is not that.
        // Stamping here would let a rider who rides daily never recompute.
    )

    /** The full-history fold — the answer every increment is measured against.
     *  Replaces the one that used to live in `BadgeStore.stats`. */
    fun foldAll(trips: List<Trip>, nowMs: Long): TripTotals =
        trips.fold(EMPTY) { acc, t -> including(acc, t) }.copy(computedAtMs = nowMs)

    /**
     * The record after a trip is persisted, or `null` to leave it absent.
     *
     * Absent stays absent on purpose. `EMPTY + oneTrip` is indistinguishable
     * from a complete record for a rider who has ridden exactly once, so an
     * install whose record has not been folded yet would have its whole
     * lifetime replaced by its next ride. Leaving it absent makes the next
     * read fold the real history.
     */
    fun afterSave(existing: TripTotals?, trip: Trip): TripTotals? =
        existing?.let { including(it, trip) }

    /**
     * How long a record may go without being agreed against the full history.
     *
     * A day, because the increments are exact arithmetic and this is a
     * belt-and-braces against a mutation path that forgot to invalidate — not
     * a correctness mechanism anything is allowed to lean on. Short enough
     * that a drift is not carried for a season; long enough that a rider who
     * opens the Hub ten times in a day pays the fold once.
     */
    const val TTL_MS = 24L * 60L * 60L * 1_000L

    fun freshness(totals: TripTotals?, nowMs: Long): Freshness = when {
        totals == null || totals.schemaVersion != SCHEMA_VERSION -> Freshness.INVALID
        // The house idiom elsewhere is `nowMs - stamp > WINDOW`
        // (CircleNotifyPolicy.kt:61, SectionAverageTracker.kt:141). A range
        // rather than a `>` because this stamp outlives a process: a clock that
        // moved backwards (a manual change, a device correcting itself after a
        // flat battery) leaves a record dated in the future, and `>` reads that
        // as freshest of all — pinning a possibly-wrong total until the clock
        // catches up. Refolding costs one pass.
        nowMs - totals.computedAtMs !in 0L..TTL_MS -> Freshness.STALE
        else -> Freshness.FRESH
    }

    fun encode(totals: TripTotals): JsonObject = buildJsonObject {
        put("schemaVersion", totals.schemaVersion)
        put("computedAtMs", totals.computedAtMs)
        put("totalDistanceMeters", totals.totalDistanceMeters)
        put("topSpeedMps", totals.topSpeedMps)
        put("longestTripMeters", totals.longestTripMeters)
        put("maxLeanDeg", totals.maxLeanDeg)
        put("tripCount", totals.tripCount)
    }

    /** `null` for anything unreadable — same contract as `RelayProtocol.decode`
     *  and `TripStore.load`'s catch: a truncated file recomputes, rather than
     *  throwing at a screen or reading as a rider who has never ridden. */
    fun decode(text: String): TripTotals? = try {
        val o = jsonObjectOf(text)
        TripTotals(
            totalDistanceMeters = o.optDouble("totalDistanceMeters", 0.0),
            topSpeedMps = o.optDouble("topSpeedMps", 0.0),
            longestTripMeters = o.optDouble("longestTripMeters", 0.0),
            maxLeanDeg = o.optDouble("maxLeanDeg", 0.0),
            tripCount = o.optInt("tripCount", 0),
            // -1 rather than the current version when the key is missing: a
            // record that does not say what it is cannot be vouched for, and
            // -1 never matches, so freshness() sends it back through a fold.
            schemaVersion = o.optInt("schemaVersion", -1),
            computedAtMs = o.optLong("computedAtMs", 0L),
        )
    } catch (e: Exception) {
        null
    }

    // --- the stored record ------------------------------------------------

    private const val FILE_NAME = "rider_totals.json"

    /** The record as last read or written, so the six call sites across
     *  screens, sync and the badge check don't each re-read and re-parse the
     *  file. Read-through on first use, the same shape as
     *  `MunicipalityStore.load`.
     *
     *  internal, not private, so the session-switch test can set it and watch
     *  [Auth.resetAccountScopedStores] clear it again — same reason
     *  `MunicipalityStore.cache` is. Clearing it there is what stops one
     *  rider's lifetime totals reaching the next; the *file* is already
     *  per-account, being an [accountFile]. */
    @Volatile
    internal var memo: TripTotals? = null

    /** Drops the in-memory copy. The file stays: it belongs to the account
     *  whose directory it sits in, and that rider's next session wants it. */
    fun reset() {
        memo = null
    }

    /** Re-entry guard for [refreshIfStale], the same shape as
     *  `ConvoyRelay.running` — but dropping rather than throwing, because a
     *  skipped refresh costs nothing and the caller has no way to handle one.
     *  Hub, Badges and Friends can each land on this within a second of one
     *  another; without it they fold the same history three times over. */
    @Volatile
    private var refreshing: Boolean = false

    private fun stored(): TripTotals? {
        memo?.let { return it }
        val f = accountFile(FILE_NAME)
        if (!f.exists()) return null
        return decode(f.readText())?.also { memo = it }
    }

    private fun store(totals: TripTotals) {
        memo = totals
        accountFile(FILE_NAME).writeText(encode(totals).string())
    }

    /**
     * Throws the record away, so the next [current] folds the real history.
     *
     * For the two mutations an increment cannot express: `TripStore.delete`,
     * where the removed trip may have held a maximum, and
     * `TripStore.replaceRaw`, where the /sync merge replaces the whole store
     * and the delta is not knowable from the call at all.
     */
    fun invalidate() {
        memo = null
        accountFile(FILE_NAME).deleteIfExists()
    }

    /** Folds a newly saved trip in, or leaves the record absent — see
     *  [afterSave]. Called by `TripStore.save` once the write has landed. */
    fun recordSaved(trip: Trip) {
        store(afterSave(stored(), trip) ?: return)
    }

    /**
     * The rider's totals, without folding history unless there is no honest
     * alternative.
     *
     * A stale record is returned as-is: it is arithmetically consistent, only
     * older than [TTL_MS], and making the rider wait on a fold to confirm what
     * is almost certainly the same number is the cost #83 exists to remove.
     * [refreshIfStale] is what settles it, on whatever background context the
     * caller already has.
     *
     * Only an INVALID record folds here — no file yet, an unreadable one, a
     * schema this build cannot vouch for, or one [invalidate] threw away. There
     * is nothing to serve in those cases, and a wrong lifetime total is worse
     * than a slow correct one.
     *
     * **Blocks on disk.** Non-suspending because Swift calls it straight
     * through (`iosApp/Detour/BadgesScreen.swift`, `TripRecorder.swift`) and
     * because `commonMain` has no dispatcher to hop off — see
     * `FriendsStore.refreshOwn`. Callers keep the `withContext(Dispatchers.IO)`
     * they already had.
     */
    fun current(): TripTotals {
        val held = stored()
        if (freshness(held, nowMs()) != Freshness.INVALID) return held!!
        // Only the INVALID path is measured, and under its own label: this is the
        // fold happening on the rider's critical path, which is the thing #83
        // moved off it. Sharing a label with [refreshIfStale] would hide whether
        // that separation is still holding.
        val t = Perf.start()
        val trips = TripStore.load()
        return foldAll(trips, nowMs()).also {
            store(it)
            Perf.end(t, "RiderTotals.current.fold") { listOf("trips" to trips.size) }
        }
    }

    /**
     * Brings a stale record back into agreement with the full history.
     *
     * The expensive call, deliberately separate from [current] so it happens
     * *after* the rider has their numbers rather than before. Callers are the
     * places already off the main thread — `HubScreen`, `BadgesScreen`,
     * `FriendsStore.refreshOwn`, and the trip-end badge check in
     * `TripTrackingService`.
     *
     * A no-op when the record is fresh, and when another caller is already
     * folding.
     */
    fun refreshIfStale() {
        if (freshness(stored(), nowMs()) == Freshness.FRESH) return
        // Check-then-set, not a compare-and-swap: `synchronized` is JVM-only
        // and a Mutex would make this suspend, which the Swift call sites
        // cannot have. The race it leaves loses nothing — two threads fold the
        // same history and write the same answer.
        if (refreshing) return
        refreshing = true
        try {
            val t = Perf.start()
            val trips = TripStore.load()
            store(foldAll(trips, nowMs()))
            Perf.end(t, "RiderTotals.refreshIfStale") { listOf("trips" to trips.size) }
        } finally {
            refreshing = false
        }
    }
}
