// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UpstreamExchangeTest {
    /** The agent hangs up while Ostrom is still answering: the call is cancelled, never reported as a failure. */
    @Test
    fun invokeWithCancellationPropagatesException(): Unit = runBlocking {
        withTimeout(30.seconds) {
            val reports = mutableListOf<String>()
            val exchange = UpstreamExchange(FailureNotices { reports.add(it) })
            val reached = CompletableDeferred<Unit>()
            val client = HttpClient(
                MockEngine {
                    reached.complete(Unit)
                    awaitCancellation()
                },
            )
            var outcome: String? = UNSET
            val call = launch {
                outcome = exchange.attempt(client, 60.seconds, { url("$SERVER/api/v1/gates") }) { }
            }
            reached.await()
            call.cancelAndJoin()
            client.close()
            assertTrue(call.isCancelled, "The cancellation went through")
            assertEquals(UNSET, outcome, "attempt neither returned a failure nor a success")
            assertEquals(emptyList(), reports, "A cancellation is not an outage")
        }
    }

    @Test
    fun theUnreachableBodyIsTheErrorShapeOfTheContract() {
        assertEquals(
            """{"error":{"type":"unreachable","message":"Ostrom is unreachable (timed out)"}}""",
            unreachableBody("timed out"),
        )
    }

    private companion object {
        const val UNSET = "unset"
    }
}
