// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class HttpServerApiVariablesTest {
    @Test
    fun theOwnersVariablesAreAskedForThisExecution() = runTest {
        val id = Uuid.random()
        var status = HttpStatusCode.OK
        var answer = """{"variables":{"A":"1"},"unavailable":["B"]}"""
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/cloud/runner/variables", request.url.encodedPath)
            assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
            bodies += request.body.toByteArray().decodeToString()
            respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false }
        val api = HttpServerApi(client, RunnerConfig("http://s", id, 2, "claude", null, null, "t", "/workspace"), this)

        val values = api.variables()
        assertEquals(mapOf("A" to "1"), values?.variables)
        assertEquals(listOf("B"), values?.unavailable)
        assertEquals("""{"sessionId":"$id","epoch":2}""", bodies.single())
        status = HttpStatusCode.NotFound
        assertNull(api.variables())
        for (refused in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            status = refused
            assertFailsWith<VariablesRefusedException> { api.variables() }
        }
        status = HttpStatusCode.Conflict
        answer = """{"error":{"type":"execution_fenced","message":"no"}}"""
        assertFailsWith<VariablesRefusedException> { api.variables() }
        // Not served right now (the session is not running): asked again later.
        answer = """{"error":{"type":"variables_unavailable","message":"no"}}"""
        assertFailsWith<IllegalStateException> { api.variables() }
        answer = "not json"
        assertFailsWith<IllegalStateException> { api.variables() }
        status = HttpStatusCode.ServiceUnavailable
        assertFailsWith<IllegalStateException> { api.variables() }
    }
}
