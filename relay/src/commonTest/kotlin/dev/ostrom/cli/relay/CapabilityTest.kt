// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityTest {
    @Test
    fun tokensCompareWholly() {
        assertTrue(equalToken("abcd", "abcd"))
        assertFalse(equalToken("abce", "abcd"), "Same length, last character differs")
        assertFalse(equalToken("abc", "abcd"), "Shorter")
        assertFalse(equalToken("abcde", "abcd"), "Longer")
        assertFalse(equalToken("", "abcd"), "Empty")
        assertFalse(equalToken(null, "abcd"), "Absent")
    }

    @Test
    fun theCapabilityIsAcceptedInEachOfItsThreeHeaders() {
        assertTrue(hasCapability(headersOf(HttpHeaders.Authorization, "Bearer $TOKEN"), TOKEN))
        assertTrue(hasCapability(headersOf("x-api-key", TOKEN), TOKEN))
        assertTrue(hasCapability(headersOf(LOCAL_TOKEN_HEADER, TOKEN), TOKEN))
        assertTrue(
            hasCapability(
                headersOf(HttpHeaders.Authorization to listOf("Bearer oauth"), LOCAL_TOKEN_HEADER to listOf(TOKEN)),
                TOKEN,
            ),
            "Claude Code keeps its own login in Authorization",
        )
        assertFalse(hasCapability(headersOf(), TOKEN))
        assertFalse(hasCapability(headersOf(HttpHeaders.Authorization, "Bearer nope"), TOKEN))
    }

    @Test
    fun onlyLoopbackHostsAreLocal() {
        val local = listOf("127.0.0.1", "127.0.0.1:4100", "localhost", "LOCALHOST:80", "[::1]", "[::1]:4100", null)
        for (host in local) {
            assertTrue(isLoopbackHost(host), "$host")
        }
        val foreign = listOf("evil.example", "evil.example:80", "127.0.0.2", "[::2]:4100", "", "localhost.evil.example")
        for (host in foreign) {
            assertFalse(isLoopbackHost(host), host)
        }
    }

    @Test
    fun aRequestWithAnOriginIsNotLocal() {
        assertTrue(isLocalRequest(headersOf(HttpHeaders.Host, "127.0.0.1:4100")))
        val browser = headersOf(HttpHeaders.Host to listOf("127.0.0.1"), HttpHeaders.Origin to listOf("null"))
        assertFalse(isLocalRequest(browser))
        assertFalse(isLocalRequest(headersOf(HttpHeaders.Host, "evil.example")))
    }
}
