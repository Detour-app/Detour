package com.jellemax.detour.data

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android-only because it reads `Throwable.stackTrace`, which kotlin.test
 * offers on the JVM and nowhere else — the Kotlin/Native compiler rejects it
 * outright. The behaviour under test is common; only the way of observing it
 * is not, so one JVM run of it is enough.
 */
class RouteStoreLoadOrderTest {

    /** ensureLoaded() arms `loaded` before it reads, so a store left loaded by
     *  an earlier test would send save() straight to write() and fail this for
     *  a reason unrelated to the ordering. Same guard, same reason, as
     *  [SavedPlacesLoadOrderTest]'s. */
    @BeforeTest
    fun startFromAColdStore() {
        RouteStore.reset()
    }

    /**
     * RouteStore.save()/rename()/remove()/byId() must call ensureLoaded()
     * before touching `_routes.value`: a mutation can arrive before any
     * screen has loaded the store (e.g. saving straight from the map on a
     * cold start), and without that ordering, write() would overwrite
     * routes.json with just the new route, silently discarding every
     * previously saved one.
     *
     * This test target has no Android Context (see Platform.android.kt), so
     * it can't drive RouteStore through a real file to observe the surviving
     * routes directly — every disk access throws. That failure is exactly
     * what proves the ordering: it has to originate from ensureLoaded()'s
     * read(), not from write(). If save() went straight to write() (the bug),
     * the stack would show write() and never reach ensureLoaded()/read().
     *
     * *Which* IllegalStateException it is depends on what else has run in
     * this JVM, and the assertions deliberately do not care. `accountDir()`
     * checks AccountFiles.migrated and throws "AccountFiles.migrate has not
     * run" first; once some test has run the migration, that check passes and
     * `appFilesDir()` throws "initSharedCore(context) has not been called"
     * instead. Both originate on the read path, which is the whole claim.
     * (This doc used to name the second as though it were the only one.)
     */
    @Test
    fun saveCallsEnsureLoadedBeforeItEverWritesSoAnEarlyCallCannotWipeDisk() {
        val error = assertFailsWith<IllegalStateException> {
            RouteStore.save(
                SavedRoute(
                    id = 1L,
                    name = "x",
                    createdMs = 1L,
                    mode = TravelMode.CAR,
                    stops = listOf(RouteStop(LatLon(50.0, 3.0)), RouteStop(LatLon(50.1, 3.1))),
                    polyline = emptyList(),
                    distanceMeters = null,
                    timeMs = null,
                ),
            )
        }
        val frames = error.stackTrace.map { it.methodName }
        assertTrue("ensureLoaded" in frames, "expected ensureLoaded() on the stack: $frames")
        assertTrue("read" in frames, "expected read() on the stack: $frames")
        assertFalse("write" in frames, "save() reached write() before ensureLoaded() completed: $frames")
    }
}
