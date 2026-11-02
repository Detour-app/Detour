package com.jellemax.detour.data

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [SavedPlaces] half of what [RouteStoreLoadOrderTest] pins for
 * [RouteStore]. Same technique, same target, and Android-only for the same
 * reason: it reads `Throwable.stackTrace`, which kotlin.test offers on the JVM
 * and nowhere else.
 *
 * Two stores, two files, because the guard is per method and a shared test
 * would report "a guard is missing" rather than which one.
 */
class SavedPlacesLoadOrderTest {

    /**
     * [SavedPlaces] is an object, and [SavedPlaces.ensureLoaded] arms
     * `loaded` *before* it reads — so the first method here to reach it
     * leaves the latch up even though the read threw, and every later one
     * sails straight past the guard into `write()`. Without this the second
     * and third tests fail for a reason that has nothing to do with the
     * ordering they exist to pin.
     */
    @BeforeTest
    fun startFromAColdStore() {
        SavedPlaces.reset()
    }

    /**
     * [SavedPlaces.add], [SavedPlaces.rename] and [SavedPlaces.remove] must
     * each call [SavedPlaces.ensureLoaded] before they touch `_places.value`.
     *
     * Without it the failure is not an error but silent data loss: `reset()`
     * now runs on every session change (a sign-out, a 401 in Api, an issuer
     * switch), which leaves `loaded == false` with an empty `_places` under a
     * MapScreen that is still composed and whose own ensureLoaded already
     * fired. The next long-press-and-save reads that empty list and `write()`
     * truncates saved_places.json to the one new entry — on a shared device,
     * over the previous rider's home and work addresses. 332d493 added the
     * three guards; nothing pinned them, and deleting all three left the
     * suite green.
     *
     * This target has no Android Context (see Platform.android.kt), so it
     * cannot drive the store through a real file. The throw is what proves
     * the ordering instead: it has to originate from ensureLoaded()'s read(),
     * never from write(). If a mutation went straight to write(), the stack
     * would show write() and never reach ensureLoaded()/read() at all.
     *
     * Which IllegalStateException it is depends on what else has run in this
     * JVM — `accountDir()`'s "AccountFiles.migrate has not run" when the
     * migration flag is down, `appFilesDir()`'s "initSharedCore(context) has
     * not been called" when some earlier test armed it. Both originate on the
     * read path, which is the only thing asserted here, so either serves.
     */
    private fun assertReadsBeforeItWrites(what: String, mutate: () -> Unit) {
        val error = assertFailsWith<IllegalStateException>(what) { mutate() }
        val frames = error.stackTrace.map { it.methodName }
        assertTrue("ensureLoaded" in frames, "$what: expected ensureLoaded() on the stack: $frames")
        assertTrue("read" in frames, "$what: expected read() on the stack: $frames")
        assertFalse(
            "write" in frames,
            "$what reached write() before ensureLoaded() completed, so it would " +
                "truncate saved_places.json to its own entry: $frames",
        )
    }

    @Test
    fun addCallsEnsureLoadedBeforeItEverWritesSoAnEarlyCallCannotWipeDisk() {
        assertReadsBeforeItWrites("add()") {
            SavedPlaces.add("Home", LatLon(51.05, 3.72))
        }
    }

    @Test
    fun renameCallsEnsureLoadedBeforeItEverWritesSoAnEarlyCallCannotWipeDisk() {
        assertReadsBeforeItWrites("rename()") {
            SavedPlaces.rename(1L, "Work")
        }
    }

    @Test
    fun removeCallsEnsureLoadedBeforeItEverWritesSoAnEarlyCallCannotWipeDisk() {
        assertReadsBeforeItWrites("remove()") {
            SavedPlaces.remove(1L)
        }
    }
}
