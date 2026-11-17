package com.jellemax.detour.data

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkFailureTest {

    @Test
    fun `an unreachable host reads as a connection problem and keeps the raw error as cause`() {
        val raw = IOException("Unable to resolve host \"photon.komoot.io\": No address associated with hostname")
        val mapped = networkFailure(raw)
        assertEquals("Can't reach the server — check your connection", mapped.message)
        assertSame(raw, mapped.cause)
    }

    @Test
    fun `timeouts read as a slow server`() {
        listOf(
            HttpRequestTimeoutException("https://example.org/route", 30_000),
            ConnectTimeoutException("connect timeout"),
        ).forEach {
            assertEquals("The server took too long to answer — try again", networkFailure(it).message)
        }
    }

    // --- failureSentence (#990): what a store puts in its error field -----

    @Test
    fun `a network failure keeps its own sentence`() {
        assertEquals(
            "The server took too long to answer — try again",
            failureSentence(networkFailure(SocketTimeoutException("read timed out"))),
        )
        assertEquals(
            "Can't reach the server — check your connection",
            failureSentence(networkFailure(IOException("Unable to resolve host"))),
        )
    }

    @Test
    fun `both 401 paths keep their AUTH-401 code`() {
        // Built the way Api.request builds it for a 401 that survived a refresh.
        val refused = TokenRefusedException("HTTP 401", AuthRefusal.SERVER_REFUSED)
        assertTrue(failureSentence(refused).contains("[AUTH-401-REFUSED]"), failureSentence(refused))
        // Built the way Auth.postRefreshAndStore builds it when the session ends.
        val ended = AuthException(AuthRefusal.message("Session expired — sign in again", AuthRefusal.SESSION_ENDED))
        assertEquals("Session expired — sign in again [AUTH-401-ENDED]", failureSentence(ended))
    }

    @Test
    fun `a parser or bare status failure never reaches the rider as its own text`() {
        val leaky = listOf(
            SerializationException("Unexpected JSON token at offset 41: Expected start of the object '{'"),
            // What Api.request rethrows for a 502 whose body has no problem detail.
            HttpStatusException(502, "<html>Bad Gateway</html>", "HTTP 502"),
        )
        for (e in leaky) {
            val sentence = failureSentence(e)
            assertFalse(sentence.contains(e.message.orEmpty()), "leaked the exception text: $sentence")
            assertFalse(sentence.contains("HTTP"), sentence)
            assertTrue(sentence.first().isUpperCase() && sentence.endsWith("."), sentence)
        }
    }

    @Test
    fun `a 4xx the API explained keeps the API's own words`() {
        assertEquals(
            "No rider goes by ada",
            failureSentence(HttpStatusException(404, "", "No rider goes by ada", detail = "No rider goes by ada")),
        )
    }

    @Test
    fun `no server set up says where to add one`() {
        assertEquals("No server is set up. Add one in Settings → Servers & sync.", failureSentence(NoServerException()))
    }
}
