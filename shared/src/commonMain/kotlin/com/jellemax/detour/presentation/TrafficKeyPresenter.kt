package com.jellemax.detour.presentation

import com.jellemax.detour.data.HereTrafficClient
import com.jellemax.detour.data.HereTrafficClient.Fetch
import com.jellemax.detour.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the HERE traffic key section says under its field (#644). An enum, not
 * strings: the section is EN + NL, and `:shared` has no string resources, so
 * each surface maps a value to its own text.
 */
enum class TrafficKeyStatus {
    /** Nothing tested since the field last changed. */
    IDLE,

    /** The one test request is in flight. */
    TESTING,

    /** HERE answered with data: the key is stored and traffic is on. */
    OK,

    /** 401/403: wrong, revoked, or not entitled to the Traffic API. */
    BAD_KEY,

    /** 429: the key's allocation is spent, or HERE is rate-limiting it. */
    QUOTA,

    /** No answer, a 5xx, or a body that could not be read — most often offline. */
    UNREACHABLE,
}

/** The status a finished test request shows. */
internal fun trafficKeyStatusFor(result: Fetch<*>): TrafficKeyStatus = when (result) {
    is Fetch.Ok -> TrafficKeyStatus.OK
    Fetch.BadKey -> TrafficKeyStatus.BAD_KEY
    Fetch.Quota -> TrafficKeyStatus.QUOTA
    Fetch.Failed -> TrafficKeyStatus.UNREACHABLE
}

/**
 * The HERE traffic key section's Test key button (#644).
 *
 * **A key is stored only once it has passed a test** (owner decision on #637:
 * "the traffic layer stays absent until a key passes the test"). So
 * [Settings.hereTrafficKey] being non-blank *means* "tested and on", and the map
 * slice needs no second flag to tell a typed key from a working one. A key that
 * fails leaves whatever was stored before untouched: a typo while replacing a
 * working key must not switch traffic off.
 *
 * The test is one incidents request for [TEST_BOX], a fixed patch of central
 * Brussels, rather than around the rider: Settings is not navigating, and the
 * test has no reason to tell HERE where the rider is.
 *
 * Screen-lifetime, like the field it serves (§4.2) — created in a `remember`,
 * and [test] runs in the caller's scope (§4.1: commonMain has no dispatcher).
 * [probe] and [store] are parameters so commonTest can drive every outcome with
 * no network and no secure storage; real callers never pass them.
 */
class TrafficKeyPresenter(
    private val probe: suspend (HereTrafficClient) -> Fetch<*> = { it.incidents(TEST_BOX) },
    private val store: (String) -> Unit = Settings::setHereTrafficKey,
) {
    private val _status = MutableStateFlow(TrafficKeyStatus.IDLE)
    val status: StateFlow<TrafficKeyStatus> = _status.asStateFlow()

    /** Bumped by every edit and remove. A test still in flight when either
     *  happens is discarded whole — neither its verdict nor its key lands — so
     *  a Remove tapped mid-test stays removed. The rider tests again. */
    private var edits = 0

    /** Tests [key] with one request and stores it if HERE accepts it. A blank
     *  key is refused before any request, as a bad key. */
    suspend fun test(key: String) {
        val client = HereTrafficClient.forKey(key)
        if (client == null) {
            _status.value = TrafficKeyStatus.BAD_KEY
            return
        }
        _status.value = TrafficKeyStatus.TESTING
        val asked = edits
        val next = trafficKeyStatusFor(probe(client))
        if (asked != edits) return
        if (next == TrafficKeyStatus.OK) store(key)
        _status.value = next
    }

    /** The field changed, so the last result no longer describes what is in it. */
    fun edited() {
        edits++
        _status.value = TrafficKeyStatus.IDLE
    }

    /** Forgets the stored key: traffic goes off and the section empties. */
    fun remove() {
        edits++
        store("")
        _status.value = TrafficKeyStatus.IDLE
    }

    companion object {
        /** About 700 m square around Brussels' Rue de la Loi — inside HERE's
         *  coverage, small enough to be one cheap request. */
        internal val TEST_BOX = HereTrafficClient.Box(west = 4.365, south = 50.842, east = 4.375, north = 50.848)
    }
}
