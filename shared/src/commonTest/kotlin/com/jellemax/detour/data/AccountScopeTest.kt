package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
