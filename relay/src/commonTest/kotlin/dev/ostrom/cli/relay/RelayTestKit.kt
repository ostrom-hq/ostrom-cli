// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The relay's capability in these tests (64 hexadecimal characters, like a real one). */
const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
const val AGENT_TOKEN = "agent-token-of-launch-1"
const val SERVER = "https://api.ostrom.example"
const val LAUNCH = "launch-1"
const val USER_AGENT = "ostrom/0.1.0-test"

/** A relay configuration; the trailing slash of the server is dropped by the relay. */
fun relayConfig(inference: Boolean = false, mcpServers: Set<String> = setOf("github")) = RelayConfig(
    serverUrl = "$SERVER/",
    agentToken = AGENT_TOKEN,
    userAgent = USER_AGENT,
    launchId = LAUNCH,
    mcpServers = mcpServers,
    inference = inference,
)

/** Ostrom as the relay sees it: a mock engine that records every request it gets and answers with [answer]. */
class FakeOstrom(private val answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    /** Every request the relay sent, in order. */
    val requests = mutableListOf<HttpRequestData>()

    private val engine = MockEngine { request ->
        requests.add(request)
        answer(request)
    }

    /** A client on the mock, as the relay's upstream factory builds one per call. */
    fun client() = HttpClient(engine) { expectSuccess = false }
}

/** Runs [block] against a relay forwarding to [ostrom]; [reports] receives what the user would be told. */
fun relayTest(
    ostrom: FakeOstrom,
    config: RelayConfig = relayConfig(),
    hookTimeout: Duration = 5.seconds,
    reports: MutableList<String> = mutableListOf(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        val timeouts = RelayTimeouts(hook = hookTimeout, longPoll = 5.seconds)
        relayModule(config, TOKEN, ostrom::client, FailureNotices { reports.add(it) }, timeouts)
    }
    block()
}
