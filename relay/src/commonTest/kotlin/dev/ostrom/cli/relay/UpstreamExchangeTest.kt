// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.url
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class UpstreamExchangeTest {
    @Test
    fun invokeWithCancellationPropagatesException() = runTest {
        val reports = mutableListOf<String>()
        val exchange = UpstreamExchange(FailureNotices { reports.add(it) })
        val client = HttpClient(MockEngine { throw CancellationException("the agent hung up") })
        assertFailsWith<CancellationException> {
            exchange.attempt(client, 5.seconds, { url("$SERVER/api/v1/gates") }) { }
        }
        assertEquals(emptyList(), reports, "A cancellation is not an outage")
    }

    @Test
    fun theUnreachableBodyIsTheErrorShapeOfTheContract() {
        assertEquals(
            """{"error":{"type":"unreachable","message":"Ostrom is unreachable (timed out)"}}""",
            unreachableBody("timed out"),
        )
    }
}
