package com.jellemax.detour.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One remote feature flag: the [key] the server knows it by, and the value it has when no
 *  server has said otherwise. */
data class FlagDef(val key: String, val default: Boolean)

/**
 * Every flag the app knows. A flag reaches the server's answer only once it is listed here: a
 * key the server sends that is not in [all] is ignored.
 *
 * Plain vals rather than `const`, for the reason [Features] gives: a const in a Kotlin object
 * is exported to Swift as a class member, an ordinary val as a property on `Flag.shared`.
 */
object Flag {

    /** Guards nothing. It exists so the whole path — admin switch, `GET /api/flags`, cache,
     *  the Diagnostics list — can be checked before a real feature depends on it (#810). */
    val SELFTEST = FlagDef("flags-selftest", false)

    val all: List<FlagDef> = listOf(SELFTEST)
}

/** Where a [FlagReading]'s value came from. */
enum class FlagSource {
    /** The compiled [FlagDef.default]: the server never sent this key, or there is no server. */
    DEFAULT,

    /** The server's default for everyone. */
    SERVER,

    /** An override for the signed-in account. */
    ACCOUNT,
}

/** One flag as the app currently reads it. */
data class FlagReading(val key: String, val enabled: Boolean, val source: FlagSource)

/** One entry of `GET /api/flags`. */
data class ServerFlag(val enabled: Boolean, val source: FlagSource)

/**
 * Remote feature flags (#810): the compiled defaults in [Flag], overlaid with the last answer
 * from `GET /api/flags` and cached in the account's bucket, so a flag switched on for one
 * account stays on across a restart without a network round trip.
 *
 * Separate from [Features], which holds compiled kill switches no server can change. A flag
 * here is for a feature that ships off and is switched on from the backend.
 *
 * Nothing reads a flag yet; the refresh on start and sign-in, and the Diagnostics list, are
 * the app sub-issues of #810.
 */
object FeatureFlags {

    /** `const` so `detour-trip-data`'s precondition sees this file resolved through
     *  [accountFile] and checks it is in [AccountFiles.SCOPED_NAMES]. */
    internal const val FILE_NAME = "flags.json"

    // internal, not private, so the session-switch test can set an account's value and watch
    // [Auth.resetAccountScopedStores] drop it — the same reason `SavedPlaces._places` is. The
    // backing-property name is what detekt objects to; SavedPlaces and RouteStore sit in the
    // baseline for it, a new file says so here instead.
    @Suppress("ObjectPropertyNaming")
    internal val _readings = MutableStateFlow(overlay(Flag.all, emptyMap()))

    /** Every flag in [Flag.all], in that order, with its current value and source. Holds the
     *  compiled defaults until [ensureLoaded] has read the cache. */
    val readings: StateFlow<List<FlagReading>> = _readings.asStateFlow()

    /** Whether the cache has been read into [readings] for the current bucket. `@Volatile` for
     *  the reason `SavedPlaces.loaded` is: [reset] runs on whichever thread moved the session. */
    @Volatile
    internal var loaded = false

    /** Reads the cache for the current account, once per session. */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _readings.value = overlay(Flag.all, cached().orEmpty())
    }

    /** Whether [def] is on for the current rider. A [FlagDef] that isn't in [Flag.all] reads
     *  as its own default. */
    fun isOn(def: FlagDef): Boolean {
        ensureLoaded()
        return _readings.value.firstOrNull { it.key == def.key }?.enabled ?: def.default
    }

    /** Drops the previous rider's values. Called from [Auth.resetAccountScopedStores]; the
     *  next [ensureLoaded] reads the new bucket's cache, so one account's overrides never show
     *  under another. */
    internal fun reset() {
        loaded = false
        _readings.value = overlay(Flag.all, emptyMap())
    }

    /**
     * Fetches the flags for the current session and caches them. Signed in, the answer carries
     * the account's overrides; signed out, the defaults.
     *
     * Never throws except for cancellation: a failed refresh keeps whatever the cache held,
     * and the next start or sign-in asks again. `@Throws` because Swift calls it.
     */
    @Throws(Exception::class)
    suspend fun refresh() {
        if (SyncClient.url() == null) return
        val epoch = Auth.sessionEpoch.value
        val outcome = try {
            Result.success(Api.request("GET", "/flags", auth = Auth.signedIn))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        // A start-up refresh sent with one rider's bearer must not land in the next rider's
        // bucket, nor one sent signed out in an account's: the FriendsStore pattern.
        apply(refreshDecision(outcome, sameSession = epoch == Auth.sessionEpoch.value))
    }

    /**
     * Commits [decision] to [readings] and, through [persist], to the cache. A cache that can't
     * be written (a full disk, a store before migration) still updates [readings]: the answer
     * is good for this session, and the next refresh writes it again. `internal` with the
     * [persist] seam so a test can fail the write.
     */
    internal fun apply(decision: FlagsRefresh, persist: (FlagsRefresh) -> Unit = ::persist) {
        val server = when (decision) {
            is FlagsRefresh.Replace -> decision.server
            FlagsRefresh.Clear -> emptyMap()
            FlagsRefresh.Keep -> return
        }
        try {
            persist(decision)
        } catch (e: Exception) {
            // refresh() promises never to throw but for cancellation, and Swift calls it.
        }
        loaded = true
        _readings.value = overlay(Flag.all, server)
    }

    private fun persist(decision: FlagsRefresh) {
        when (decision) {
            is FlagsRefresh.Replace -> accountFile(FILE_NAME).writeText(decision.body)
            FlagsRefresh.Clear -> accountFile(FILE_NAME).deleteIfExists()
            FlagsRefresh.Keep -> Unit
        }
    }

    /** The cached server answer, or null when there is none or it no longer parses. */
    private fun cached(): Map<String, ServerFlag>? = try {
        val f = accountFile(FILE_NAME)
        if (f.exists()) parse(f.readText()) else null
    } catch (e: Exception) {
        // An unreadable cache is no cache: every flag keeps its compiled default until the
        // next refresh, which is what a server without the endpoint gets anyway.
        null
    }

    /**
     * The refresh decision as a pure function of what the request came back with.
     *
     * Only a 404 clears: that is a server without the endpoint (an older build, or one that never
     * had it), where every flag keeping its compiled default is the promise to self-hosters. A
     * 5xx, a network failure, a 401 that survived the token retry or a dead session say nothing
     * about the flags, so dropping the cache for them would switch an account's overrides off for
     * as long as the server is unreachable. A body that isn't a flags document (a gateway's HTML
     * page answering 200) is treated the same way.
     */
    internal fun refreshDecision(outcome: Result<String>, sameSession: Boolean): FlagsRefresh {
        if (!sameSession) return FlagsRefresh.Keep
        val body = outcome.getOrElse { e ->
            return if (e is HttpStatusException && e.code == 404) FlagsRefresh.Clear else FlagsRefresh.Keep
        }
        val server = parse(body) ?: return FlagsRefresh.Keep
        return FlagsRefresh.Replace(server, body)
    }

    /**
     * `GET /api/flags`'s body, by key, or null when [body] isn't a flags document — not JSON, not
     * an object, or no `flags` array. An entry without a key or a boolean `enabled` is skipped.
     * Any `source` other than `account` reads as [FlagSource.SERVER], so a source a later server
     * adds is still the server's value rather than an error.
     */
    internal fun parse(body: String): Map<String, ServerFlag>? {
        val flags = try {
            jsonObjectOf(body).optArray("flags")
        } catch (e: Exception) {
            null
        } ?: return null
        return flags.objects().mapNotNull { o ->
            val key = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null
            val source = if (o.optString("source") == "account") FlagSource.ACCOUNT else FlagSource.SERVER
            key to ServerFlag(enabled, source)
        }.toMap()
    }

    /** [defs] in order, each with the [server]'s value where it sent one and its compiled default
     *  otherwise. A key in [server] that [defs] doesn't list is ignored. */
    internal fun overlay(defs: List<FlagDef>, server: Map<String, ServerFlag>): List<FlagReading> =
        defs.map { def ->
            server[def.key]?.let { FlagReading(def.key, it.enabled, it.source) }
                ?: FlagReading(def.key, def.default, FlagSource.DEFAULT)
        }
}

/** What [FeatureFlags.refresh] does with an answer. */
internal sealed interface FlagsRefresh {
    /** A flags document: replace the overlay and cache [body]. */
    data class Replace(val server: Map<String, ServerFlag>, val body: String) : FlagsRefresh

    /** The server has no flags endpoint: back to the compiled defaults, cache deleted. */
    data object Clear : FlagsRefresh

    /** Anything else: keep the cache and the overlay as they are. */
    data object Keep : FlagsRefresh
}
