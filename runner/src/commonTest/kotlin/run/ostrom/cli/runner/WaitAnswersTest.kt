// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The answers that arrived while the session was paused, taken once with the launch's agent token. */
class WaitAnswersTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    // The engine on the test's scheduler: the time limit and a slow answer are both in virtual time.
    private fun TestScope.answers(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpWaitAnswers(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            requests += request
                            bodies += request.body.toByteArray().decodeToString()
                            answer(request)
                        }
                    },
                ),
            ) {
                expectSuccess = false
                install(HttpTimeout)
            },
            "http://s",
        )

    @Test
    fun theWakeIsAskedWithoutHoldingWithTheAgentTokenAndItsMessagesReturned() = runTest {
        val body = """{"open":false,"messages":["[Ostrom wait answered] A","[Ostrom wait answered] B"]}"""
        val taken = answers { respond(body, HttpStatusCode.OK, json) }.take("agent-token")
        assertEquals(listOf("[Ostrom wait answered] A", "[Ostrom wait answered] B"), taken)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/waits/wake", request.url.encodedPath)
        assertTrue(request.url.parameters.isEmpty(), "The bound token names no session")
        assertEquals("Bearer agent-token", request.headers[HttpHeaders.Authorization])
        assertEquals("""{"waitMillis":0}""", bodies.single())
    }

    @Test
    fun anythingButAListOfMessagesGivesNone() = runTest {
        val refused = listOf(
            """{"open":false,"messages":[]}""" to HttpStatusCode.OK,
            """{"open":true}""" to HttpStatusCode.OK,
            """{"messages":"one"}""" to HttpStatusCode.OK,
            """[]""" to HttpStatusCode.OK,
            """not json""" to HttpStatusCode.OK,
            """{"messages":["a"]}""" to HttpStatusCode.Forbidden,
        )
        refused.forEach { (body, status) ->
            assertTrue(answers { respond(body, status, json) }.take("t").isEmpty(), body)
        }
        val mixed = answers { respond("""{"messages":["a",1,{},null,"b"]}""", HttpStatusCode.OK, json) }.take("t")
        assertEquals(listOf("a", "b"), mixed)
    }

    @Test
    fun anUnreachableOrSilentServerGivesNone() = runTest {
        assertTrue(answers { error("down") }.take("t").isEmpty())
        val slow = answers {
            delay(30.seconds)
            respond("""{"messages":["late"]}""", HttpStatusCode.OK, json)
        }
        assertTrue(slow.take("t").isEmpty())
    }

    @Test
    fun aCancellationIsNotSwallowed() = runTest {
        assertFailsWith<CancellationException> { answers { throw CancellationException("stop") }.take("t") }
    }
}
