package com.jellemax.detour.presentation

import com.jellemax.detour.data.AuthException
import com.jellemax.detour.data.HttpStatusException
import com.jellemax.detour.data.ServerConfig
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServersSyncStateTest {

    private val now = 1_700_000_000_000L

    private fun status(
        custom: ServerConfig? = null,
        builtInAvailable: Boolean = false,
        authUsername: String = "",
        lastSyncMs: Long = 0L,
    ) = serversSyncStatusFrom(custom, builtInAvailable, authUsername, lastSyncMs, now)

    @Test
    fun `no custom address falls back to the built-in server or to nothing`() {
        assertEquals("Built-in server", status(builtInAvailable = true).server)
        assertEquals("Public servers only", status(builtInAvailable = false).server)
        assertEquals("Built-in server", status(custom = ServerConfig(), builtInAvailable = true).server)
    }

    @Test
    fun `a custom address shows as its host`() {
        assertEquals("nas.local:8989", status(custom = ServerConfig(url = "https://nas.local:8989")).server)
        assertEquals(
            "detour.example.com",
            status(custom = ServerConfig(url = "https://detour.example.com/")).server,
        )
        assertEquals("nas.local", status(custom = ServerConfig(url = "nas.local")).server)
    }

    @Test
    fun `credentials pasted into an address never reach the status row`() {
        assertEquals(
            "detour.example.com",
            status(custom = ServerConfig(url = "https://rider:hunter2@detour.example.com/x")).server,
        )
    }

    @Test
    fun `a split deployment with no general address is still configured`() {
        // The bug this guards: reading only `url` reports "Built-in server" to
        // someone who filled in the per-service addresses instead.
        val split = ServerConfig(
            apiUrl = "https://api.example.com",
            geocoderUrl = "https://search.example.com",
        )
        assertEquals("api.example.com", status(custom = split, builtInAvailable = true).server)
    }

    @Test
    fun `sync says who is signed in and how long ago it ran`() {
        assertEquals("Not signed in", status().sync)
        assertEquals("Signed in as rider · never synced", status(authUsername = "rider").sync)
        assertEquals(
            "Signed in as rider · synced 5m ago",
            status(authUsername = "rider", lastSyncMs = now - 5 * 60_000L).sync,
        )
    }

    @Test
    fun `backup reports whether there is an address to export`() {
        assertEquals("No address to export yet", status().backup)
        assertEquals("Server address ready to export", status(builtInAvailable = true).backup)
        assertEquals(
            "Server address ready to export",
            status(custom = ServerConfig(url = "https://nas.local")).backup,
        )
    }

    @Test
    fun `a failure never carries the exception's own text`() {
        val leaky = listOf(
            AuthException("token 4f3a rejected by realm https://idp.internal/realms/detour"),
            HttpStatusException(502, "<html>nginx</html>"),
            IOException("Unable to resolve host nas.local: No address associated with hostname"),
            IllegalArgumentException("Unexpected JSON token at offset 41"),
        )
        for (e in leaky) {
            val text = failureText("Sync", e)
            assertTrue(text.startsWith("Sync failed: "), text)
            assertFalse(text.contains(e.message.orEmpty()), "leaked the exception text: $text")
        }
    }

    @Test
    fun `each failure names something the rider can act on`() {
        assertEquals(
            "Sync failed: the sign-in has expired. Sign in again.",
            failureText("Sync", AuthException("401")),
        )
        assertEquals(
            "Sync failed: the server refused the sign-in.",
            failureText("Sync", HttpStatusException(403, "")),
        )
        assertEquals(
            "Sync failed: the server has nothing at that address.",
            failureText("Sync", HttpStatusException(404, "")),
        )
        assertEquals(
            "Sync failed: the server hit a problem. Try again later.",
            failureText("Sync", HttpStatusException(503, "")),
        )
        assertEquals(
            "Sync failed: the server answered 418.",
            failureText("Sync", HttpStatusException(418, "")),
        )
        assertEquals(
            "Export failed: it could not be opened. Check your connection and try again.",
            failureText("Export", IOException("EACCES")),
        )
        assertEquals(
            "Import failed: the contents were not what this app expected.",
            failureText("Import", IllegalStateException("boom")),
        )
    }
}
