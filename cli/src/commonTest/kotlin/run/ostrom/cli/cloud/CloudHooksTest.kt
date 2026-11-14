// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import run.ostrom.cli.CliKit
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.StandardInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudHooksTest : CliKit() {
    private val sent = mutableListOf<HttpRequestData>()
    private val agent = mapOf("OSTROM_URL" to "https://ostrom.example/", "OSTROM_AGENT_TOKEN" to "agent-token")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val capture = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult = error("no check here")
        override fun detach(command: List<String>): Boolean = error("nothing detached")
    }

    private fun run(
        command: String,
        variables: Map<String, String> = agent,
        event: String = """{"hook_event_name":"Stop","stop_hook_active":false}""",
        status: HttpStatusCode = HttpStatusCode.OK,
        answer: (String) -> String,
    ): Int {
        console.standardInput = StandardInput.Read(event.encodeToByteArray())
        val client = HttpClient(MockEngine { request ->
            sent += request
            respond(answer(request.url.encodedPath), status, json)
        }) { expectSuccess = false }
        return CloudHooks(FakeEnvironment(variables), console, client, fileSystem, capture, Dispatchers.Unconfined)
            .run(command)
    }

    @Test
    fun theTodosStopHookAsksTheServerWithTheAgentTokenAndNoSession() {
        val code = run("todo-stop") { """{"action":"block","reason":"Finish fix."}""" }
        assertEquals(0, code)
        assertEquals("{\"decision\":\"block\",\"reason\":\"Finish fix.\"}\n", console.out.toString())
        val request = sent.first()
        assertEquals("https://ostrom.example/api/v1/todos/session/stop", request.url.toString())
        assertEquals("Bearer agent-token", request.headers[HttpHeaders.Authorization])
        assertEquals("", console.err.toString())
    }

    @Test
    fun theTodosStopIsNeverHeldWhenTheServerLetsItPass() {
        assertEquals(0, run("todo-stop") { """{"action":"allow"}""" })
        assertEquals("", console.out.toString())
    }

    @Test
    fun theTodosReminderIsPrintedAfterACompactionOrAResume() {
        val code = run("todo-context", event = """{"source":"resume"}""") { """{"text":"The list"}""" }
        assertEquals(0, code)
        assertTrue("\"additionalContext\":\"The list\"" in console.out, console.out.toString())
        assertEquals("https://ostrom.example/api/v1/todos/session/context", sent.single().url.toString())
        assertEquals("Bearer agent-token", sent.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun theWorkflowsStopHookSendsTheAgentBackToDeliver() {
        val pending = """{"needsAction":true,"nodeTitle":"Analyze","deliverable":{"type":"text"}}"""
        assertEquals(0, run("workflow-stop") { pending })
        assertTrue("\"decision\":\"block\"" in console.out, console.out.toString())
        assertTrue("Analyze" in console.out)
        val request = sent.single()
        assertEquals("GET", request.method.value)
        assertEquals("https://ostrom.example/api/v1/workflows/session/pending", request.url.toString())
        assertEquals("Bearer agent-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun everyHookFailsOpenWithoutTheVariablesOrWhenTheServerRefuses() {
        CloudHooks.COMMANDS.forEach { command ->
            sent.clear()
            val without = agent - "OSTROM_URL"
            assertEquals(0, run(command, without) { error("never asked") }, command)
            assertEquals(0, run(command, agent - "OSTROM_AGENT_TOKEN") { error("never asked") }, command)
            assertTrue(sent.isEmpty(), "$command asked the server without its variables")
            val error = """{"error":{"type":"x","message":"no"}}"""
            assertEquals(0, run(command, status = HttpStatusCode.Forbidden) { error }, command)
        }
        assertEquals("", console.out.toString())
        assertEquals("", console.err.toString())
    }

    @Test
    fun anotherCommandDoesNothing() {
        assertEquals(0, run("something-else") { error("never asked") })
        assertTrue(sent.isEmpty())
        assertEquals("", console.out.toString())
    }

    @Test
    fun theCommandsAreTheThreeHooks() {
        assertEquals(setOf("todo-stop", "todo-context", "workflow-stop"), CloudHooks.COMMANDS)
    }
}
