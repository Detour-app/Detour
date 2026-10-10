package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which refused refresh signs the rider out, in Auth.kt (#467).
 *
 * Clearing the session is the expensive answer — the rider has to notice and
 * sign in again, and every account-scoped store is dropped meanwhile — so it is
 * reserved for the realm saying the grant is dead. Everything else a token
 * endpoint can answer with keeps the session.
 */
class RefreshRefusalTest {

    @Test
    fun invalidGrantEndsTheSession() {
        assertTrue(
            Auth.refreshRefusalEndsSession(
                400,
                """{"error":"invalid_grant","error_description":"Token is not active"}""",
            )
        )
    }

    @Test
    fun invalidGrantOnA401StillEndsTheSession() {
        assertTrue(Auth.refreshRefusalEndsSession(401, """{"error":"invalid_grant"}"""))
    }

    @Test
    fun aClientMisconfigurationKeepsTheSession() {
        assertFalse(Auth.refreshRefusalEndsSession(401, """{"error":"invalid_client"}"""))
        assertFalse(Auth.refreshRefusalEndsSession(400, """{"error":"unauthorized_client"}"""))
    }

    @Test
    fun somethingInFrontOfTheRealmKeepsTheSession() {
        assertFalse(Auth.refreshRefusalEndsSession(401, "<html><body>Sign in to the gateway</body></html>"))
        assertFalse(Auth.refreshRefusalEndsSession(400, ""))
    }

    @Test
    fun invalidGrantOnAnyOtherStatusKeepsTheSession() {
        // A 5xx is the realm failing, whatever its body says.
        assertFalse(Auth.refreshRefusalEndsSession(503, """{"error":"invalid_grant"}"""))
    }
}
