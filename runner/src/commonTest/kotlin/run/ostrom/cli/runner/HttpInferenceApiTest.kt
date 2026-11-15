// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The execution's inference credential (`runners.cloud.inference.v1`) and the organization's models. */
class HttpInferenceApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun theCredentialIsAskedForThisExecution() = runTest {
        val id = Uuid.random()
        var status = HttpStatusCode.OK
        var answer = """{"token":"ostrom_agent_x","expiresAt":"2026-11-15T12:00:00Z","expiresInMillis":3600000}"""
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/cloud/runner/inference", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
            bodies += request.body.toByteArray().decodeToString()
            respond(answer, status, json)
        }) { expectSuccess = false }
        val api = HttpInferenceApi(client, RunnerConfig("http://s", id, 2, "claude", null, null, "t", "/workspace"))

        val credential = api.inference()
        assertEquals("ostrom_agent_x", credential.token)
        assertEquals(3_600_000L, credential.expiresInMillis)
        assertEquals("""{"sessionId":"$id","epoch":2}""", bodies.single())
        status = HttpStatusCode.NotFound
        answer = "not json"
        assertEquals(404, assertFailsWith<InferenceRefusedException> { api.inference() }.status)
        status = HttpStatusCode.Conflict
        answer = """{"error":{"type":"execution_fenced","message":"no"}}"""
        val fenced = assertFailsWith<InferenceRefusedException> { api.inference() }
        assertEquals(409, fenced.status)
        assertEquals("execution_fenced", fenced.code)
        status = HttpStatusCode.BadGateway
        assertFailsWith<IllegalStateException> { api.inference() }
    }

    @Test
    fun theModelsAreAskedWithTheCredential() = runTest {
        var status = HttpStatusCode.OK
        var answer = """{"data":[{"id":"claude-opus"}]}"""
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/inference/v1/models", request.url.encodedPath)
            assertEquals("Bearer credential", request.headers[HttpHeaders.Authorization])
            respond(answer, status, json)
        }) { expectSuccess = false }
        val config = RunnerConfig("http://s", Uuid.random(), 2, "claude", null, null, "t", "/w")
        val api = HttpInferenceApi(client, config)

        assertTrue(api.servesModels("credential"))
        answer = """{"data":[]}"""
        assertFalse(api.servesModels("credential"))
        answer = "not json"
        assertFalse(api.servesModels("credential"))
        status = HttpStatusCode.Unauthorized
        answer = """{"data":[{"id":"claude-opus"}]}"""
        assertFalse(api.servesModels("credential"))
    }
}
