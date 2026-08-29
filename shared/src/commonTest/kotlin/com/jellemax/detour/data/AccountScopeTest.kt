package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [AccountScope]'s key derivation. The key names a directory that ends
 * up inside a Google Drive backup, so "does it contain a rider identifier" is
 * part of what these assert, not just "is it stable".
 */
class AccountScopeTest {

    @Test
    fun theSubjectIsPreferredOverTheUsername() {
        val key = AccountScope.keyFrom(subject = "9f2b1a44-3c7e", username = "andre")
        assertEquals(AccountScope.keyFrom(subject = "9f2b1a44-3c7e", username = "someone-else"), key)
    }

    @Test
    fun theUsernameIsTheFallbackWhenThereIsNoSubject() {
        val key = AccountScope.keyFrom(subject = "", username = "andre")
        assertTrue(key.isNotEmpty())
        assertEquals(AccountScope.keyFrom(subject = "", username = "andre"), key)
    }

    @Test
    fun noSubjectAndNoUsernameYieldsNoKey() {
        assertEquals("", AccountScope.keyFrom(subject = "", username = ""))
    }

    @Test
    fun theKeyIsSixteenLowercaseHexCharacters() {
        val key = AccountScope.keyFrom(subject = "9f2b1a44-3c7e", username = "")
        assertEquals(16, key.length)
        assertTrue(key.all { it in "0123456789abcdef" }, "not hex: $key")
    }

    @Test
    fun theKeyIsAHashAndNotAnEncodingOfTheIdentifier() {
        // A golden value, not a property. The assertion this replaces checked that
        // the key does not contain "andre" — true of *any* hex string, since n and r
        // are not hex digits, so it passed even with the hash removed entirely.
        // Getting that wrong matters here specifically: an un-hashed key is the
        // rider's own handle, reversible, in a directory name that reaches a Google
        // Drive backup.
        assertEquals("bd01b0b648c2c64e", AccountScope.keyFrom(subject = "", username = "andre"))
        assertNotEquals(
            "616e647265",
            AccountScope.keyFrom(subject = "", username = "andre"),
            "the identifier was hex-encoded rather than hashed",
        )
    }

    @Test
    fun differentSubjectsGetDifferentKeys() {
        assertNotEquals(
            AccountScope.keyFrom(subject = "rider-a", username = ""),
            AccountScope.keyFrom(subject = "rider-b", username = ""),
        )
    }

    @Test
    fun currentIsTheAnonymousBucketUntilAKeyIsSet() {
        AccountScope.clear()
        assertEquals("_local", AccountScope.current())
    }

    @Test
    fun currentIsTheKeyOnceSetAndTheBucketAgainAfterClear() {
        AccountScope.set("a3f1c8e29b4d7061")
        assertEquals("a3f1c8e29b4d7061", AccountScope.current())
        AccountScope.clear()
        assertEquals("_local", AccountScope.current())
    }

    @Test
    fun aSecondKeyReplacesTheFirstRatherThanBeingIgnored() {
        // Not a session switch — nothing here goes near Auth. It pins the one
        // choice set() documents: a key arriving while another is already
        // held replaces it, because ignoring it would leave the previous
        // rider's key naming the directory the new rider writes into.
        AccountScope.set("aaaaaaaaaaaaaaaa")
        AccountScope.set("bbbbbbbbbbbbbbbb")
        assertEquals("bbbbbbbbbbbbbbbb", AccountScope.current())
    }

    @Test
    fun aBlankKeyIsRefusedRatherThanBecomingADirectoryNamedNothing() {
        AccountScope.set("a3f1c8e29b4d7061")
        AccountScope.set("")
        assertEquals("_local", AccountScope.current())
    }
}

class SubjectFromTokenTest {

    // header.payload.signature. The payload segment is verified base64url of
    // {"sub":"9f2b1a44","preferred_username":"andre"} — decoded and checked
    // while writing this plan, so it does not need re-deriving. okio's
    // decodeBase64() accepts url-safe input, which is what a JWT uses.
    private val token =
        "e30." +
            "eyJzdWIiOiI5ZjJiMWE0NCIsInByZWZlcnJlZF91c2VybmFtZSI6ImFuZHJlIn0" +
            ".sig"

    @Test
    fun readsTheSubjectClaim() {
        assertEquals("9f2b1a44", Auth.subjectFrom(token))
    }

    @Test
    fun aTokenWithNoPayloadSegmentYieldsNothing() {
        assertEquals("", Auth.subjectFrom("notatoken"))
    }

    @Test
    fun anUndecodablePayloadYieldsNothingRatherThanThrowing() {
        assertEquals("", Auth.subjectFrom("e30.!!!not-base64!!!.sig"))
    }
}

/**
 * Covers exactly one of the five things this task does: the in-memory half of
 * a session change, [Auth.resetAccountScopedStores]. Not the on-disk half
 * ([AccountFilesTest] has that), not `Auth.store`/`Auth.clear`'s
 * [AccountScope] moves, and not the persisted `auth_scope_key` — all three
 * of those go through [Settings], which needs platform prefs this module's
 * test target does not have, so nothing here can reach them. The function is
 * `internal` and called directly for that same reason.
 *
 * Each store is asserted separately rather than through one combined flag, so
 * a deleted call names which store stopped being cleared instead of just
 * failing. The two caching stores are asserted by **value** as well as by
 * their `loaded` latch: clearing the latch without emptying the list leaves
 * the previous rider's places and routes on screen until something reloads,
 * and a latch-only assertion passes with that line deleted.
 */
class SessionSwitchTest {

    @Test
    fun aSessionChangeDropsEveryStoreThatHoldsARidersFilesInMemory() {
        // Set the cached state by hand — populating these for real writes to
        // disk, and this module's tests have no file system.
        SavedPlaces.loaded = true
        SavedPlaces._places.value = listOf(
            SavedPlace(1L, "Previous rider's home", LatLon(51.05, 3.72)),
        )
        RouteStore.loaded = true
        RouteStore._routes.value = listOf(
            SavedRoute(
                id = 1L,
                name = "Previous rider's Ardennes loop",
                createdMs = 1L,
                mode = TravelMode.MOTO,
                stops = listOf(
                    RouteStop(LatLon(50.5, 5.5)),
                    RouteStop(LatLon(50.6, 5.6)),
                ),
                polyline = emptyList(),
                distanceMeters = null,
                timeMs = null,
            ),
        )
        MunicipalityStore.cache = emptyList()
        MunicipalityStore.misses = setOf(1L)
        val traceVersionBefore = TraceStore.version.value

        Auth.resetAccountScopedStores()

        assertFalse(SavedPlaces.loaded, "SavedPlaces kept the previous rider's places")
        assertEquals(
            emptyList(),
            SavedPlaces.places.value,
            "the shortcut chips still hold the previous rider's places",
        )
        assertFalse(RouteStore.loaded, "RouteStore kept the previous rider's routes")
        assertEquals(
            emptyList(),
            RouteStore.routes.value,
            "the routes list still holds the previous rider's routes",
        )
        assertNull(MunicipalityStore.cache, "MunicipalityStore kept the previous rider's learned boundaries")
        assertEquals(emptySet(), MunicipalityStore.misses, "MunicipalityStore kept the previous rider's misses")
        assertEquals(
            traceVersionBefore + 1,
            TraceStore.version.value,
            "the fog layer was not told the ground moved, so it keeps drawing the previous rider's territory",
        )
    }
}
