// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.ClosedByteChannelException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Named like the engines' own exceptions: the family is read from class names and messages.
private class CurlIllegalStateException(message: String) : IllegalStateException(message)

private class DarwinHttpRequestException(message: String) : IllegalStateException(message)

private class WinHttpIllegalStateException(message: String) : IllegalStateException(message)

private fun curl(reason: String) = CurlIllegalStateException("Connection failed for request. Reason: $reason")

class NetworkFailuresTest {
    @Test
    fun namedExceptionsMapToTheirFamily() {
        val cases = listOf(
            ClosedByteChannelException(IOException("closed")) to NetworkFailure.CLOSED,
            ConnectTimeoutException("Connect timeout has expired", null) to NetworkFailure.TIMEOUT,
            SocketTimeoutException("socket", null) to NetworkFailure.TIMEOUT,
            HttpRequestTimeoutException("https://x.example/", 5) to NetworkFailure.TIMEOUT,
        )
        for ((error, family) in cases) assertEquals(family, networkFailure(error), error.toString())
        assertTrue(NetworkFailure.entries.all { it.cause.isNotEmpty() })
    }

    @Test
    fun engineMessagesMapToTheirFamily() {
        val cases = listOf(
            curl("Could not resolve hostname") to NetworkFailure.DNS,
            curl("Could not resolve proxy") to NetworkFailure.PROXY,
            CurlIllegalStateException("TLS verification failed for request") to NetworkFailure.TLS,
            curl("Timeout was reached") to NetworkFailure.TIMEOUT,
            curl("Couldn't connect to server") to NetworkFailure.REFUSED,
            curl("Send failure") to NetworkFailure.OTHER,
            CurlIllegalStateException("Header line length limit exceeded") to NetworkFailure.HEADERS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1004 \"No server\"") to
                NetworkFailure.REFUSED,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1200 \"x\"") to NetworkFailure.TLS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1003 \"x\"") to NetworkFailure.DNS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1001") to NetworkFailure.TIMEOUT,
            WinHttpIllegalStateException("Error 12029: A connection with the server could not be established") to
                NetworkFailure.REFUSED,
            WinHttpIllegalStateException("Error 12007") to NetworkFailure.DNS,
            WinHttpIllegalStateException("Error 12002") to NetworkFailure.TIMEOUT,
            WinHttpIllegalStateException("Error 12175") to NetworkFailure.TLS,
            WinHttpIllegalStateException("the web proxy refused") to NetworkFailure.PROXY,
            WinHttpIllegalStateException("Error 120071") to NetworkFailure.OTHER,
            // a port number is not an engine code, and a host name saying tls is not a TLS failure
            CurlIllegalStateException("https://releases.example:12002/ failed") to NetworkFailure.OTHER,
            curl("https://tls.example.com: Could not connect to server") to NetworkFailure.REFUSED,
            DarwinHttpRequestException("Code=-1004") to NetworkFailure.REFUSED,
            DarwinHttpRequestException("Code=-1004x") to NetworkFailure.OTHER,
            DarwinHttpRequestException("Code=-1003 ") to NetworkFailure.DNS,
        )
        for ((error, family) in cases) assertEquals(family, networkFailure(error), error.toString())
    }

    @Test
    fun aLocalFailureIsNotANetworkOne() {
        assertNull(networkFailure(IOException("No space left on device")))
        assertNull(networkFailure(IllegalStateException("bug")))
    }

    @Test
    fun theFamilyIsFoundInTheCauses() {
        val dns = CurlIllegalStateException("Could not resolve host")
        assertEquals(NetworkFailure.DNS, networkFailure(IOException("wrapped", dns)))
        assertEquals(NetworkFailure.DNS, ServerUnreachableException("u", dns).family)
    }

    @Test
    fun theDetailIsShortAndKeepsNoSecret() {
        val signed = IOException("GET https://user:pw@cdn.example:8443/a/b?sig=SECRET failed\r\nUserInfo={secret}")
        assertEquals("IOException: GET https://cdn.example:8443/… failed", releaseFailureDetail(signed))
        assertEquals("IOException", releaseFailureDetail(IOException()))
        val proxy = IOException("via bob:hunter2@proxy.example")
        assertEquals("IOException: via proxy.example", releaseFailureDetail(proxy))
        val chain = IOException("outer", IllegalStateException("inner", IOException("outer")))
        assertEquals("IOException: outer < IllegalStateException: inner", releaseFailureDetail(chain))
        val long = releaseFailureDetail(IOException("x".repeat(500)))
        assertEquals(200, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("Exception", releaseFailureDetail(object : Exception() {}))
    }
}
