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
    fun theKeyDoesNotContainTheIdentifierItCameFrom() {
        val key = AccountScope.keyFrom(subject = "", username = "andre")
        assertTrue(!key.contains("andre"), "the username leaked into the directory name: $key")
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
