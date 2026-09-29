package com.jellemax.detour.data

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
}
