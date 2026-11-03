// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The relay's capability in these tests (64 hexadecimal characters, like a real one). */
const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
const val AGENT_TOKEN = "agent-token-of-launch-1"
const val SERVER = "https://api.ostrom.example"
const val LAUNCH = "launch-1"
const val USER_AGENT = "ostrom/0.1.0-test"

/** A relay configuration; the trailing slash of the server is dropped by the relay. */
fun relayConfig(
    inference: Boolean = false,
    mcpServers: Set<String> = setOf("github"),
    gateCatalog: String? = null,
) = RelayConfig(
    serverUrl = "$SERVER/",
    agentToken = AGENT_TOKEN,
    userAgent = USER_AGENT,
    launchId = LAUNCH,
    mcpServers = mcpServers,
    inference = inference,
    gateCatalog = gateCatalog,
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

/** The agent's own capability when a test draws it apart from the hooks' [TOKEN] (S2-25). */
const val CLIENT_TOKEN = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

/**
 * The relay's memory of the signed gate policy in a test.
 *
 * @property freshFor How long it answers from memory.
 * @property clock The test's clock.
 * @property refresh How the relay reads it again in the background: by default, never.
 */
internal class PolicyClock(
    val freshFor: Duration,
    val clock: TimeSource,
    val refresh: PolicyRefreshLoop = PolicyRefreshLoop { awaitCancellation() },
)

/**
 * Runs [block] against a relay forwarding to [ostrom]; [reports] receives what the user would be told. Without
 * [clientToken] the agent's capability is the hooks' [TOKEN]; without [policy] the relay keeps its own defaults.
 */
@Suppress("LongParameterList") // the relay's settings a test may change, all defaulted
internal fun relayTest(
    ostrom: FakeOstrom,
    config: RelayConfig = relayConfig(),
    hookTimeout: Duration = 5.seconds,
    reports: MutableList<String> = mutableListOf(),
    clientToken: String? = null,
    policy: PolicyClock? = null,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        val timeouts = RelayTimeouts(hook = hookTimeout, longPoll = 5.seconds)
        val notices = FailureNotices { reports.add(it) }
        when {
            policy != null -> relayModule(
                config,
                TOKEN,
                ostrom::client,
                notices,
                timeouts,
                policyFreshFor = policy.freshFor,
                clock = policy.clock,
                policyRefresh = policy.refresh,
            )
            clientToken != null -> relayModule(config, TOKEN, ostrom::client, notices, timeouts, clientToken)
            else -> relayModule(config, TOKEN, ostrom::client, notices, timeouts)
        }
    }
    block()
}
