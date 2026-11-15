// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentChannelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private val tokens = mutableListOf<String?>()

    private fun channel(status: HttpStatusCode = HttpStatusCode.OK, body: (String) -> String) = HttpAgentChannel(
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath.removePrefix("/api/v1/cloud/runner/")
                calls += path to Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                tokens += request.headers[HttpHeaders.Authorization]
                respond(body(path), status, json)
            },
        ),
        "https://ostrom.test",
        { "runner-token" },
        "s1",
        2,
    )

    @Test
    fun theChannelSpeaksTheServersRunnerRoutes() = runTest {
        val channel = channel { path ->
            when (path) {
                "agent" ->
                    """
                    {"kind":"claude","model":"opus","conversation":"resumable","permissionRelay":true,
                    "permissionDeadlineMinutes":5,"liveText":true,"backgroundTasks":true,
                    "owner":{"username":"nathan","githubLogin":null},"future":1}
                    """
                "events" -> """{"acceptedThrough":3,"lastSeq":3}"""
                "inputs" ->
                    """
                    {"inputs":[{"id":"i1","kind":"message","text":"Hi","attempt":2}],"desiredStatus":"paused"}
                    """
                else -> ""
            }
        }
        val agent = channel.agent()
        assertEquals(
            RunnerAgentView(
                "claude", "opus", null, "resumable", true, 5, liveText = true, backgroundTasks = true,
                owner = RunnerOwnerView("nathan"),
            ),
            agent,
        )
        val event = RunnerEventView(3, CloudEventTypes.TURN, JsonObject(mapOf("phase" to JsonPrimitive("started"))))
        assertEquals(RunnerEventsAckView(3, 3), channel.events(listOf(event)))
        val inputs = channel.poll(20_000, "running", RunnerAccepts(false, true, true, false))
        assertEquals(RunnerInputsView(listOf(RunnerInputView("i1", "message", "Hi", 2)), "paused"), inputs)
        channel.acknowledge(listOf(RunnerInputAckView("i1", CloudInputStatuses.CONSUMED)))

        assertEquals(listOf("agent", "events", "inputs", "inputs/ack"), calls.map { it.first })
        assertEquals(List<String?>(4) { "Bearer runner-token" }, tokens)
        for ((_, body) in calls) {
            assertEquals("s1", body["sessionId"]!!.jsonPrimitive.content)
            assertEquals(2L, body["epoch"]!!.jsonPrimitive.long)
        }
        assertEquals(3L, calls[1].second["events"]!!.jsonArray.single().jsonObject["sequence"]!!.jsonPrimitive.long)
        val poll = calls[2].second
        assertEquals(20_000L, poll["waitMillis"]!!.jsonPrimitive.long)
        assertEquals("running", poll["desiredStatus"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(false, true, true, false),
            listOf("acceptsFiles", "acceptsTaskStops", "acceptsModelChanges", "acceptsEffortChanges")
                .map { poll[it]!!.jsonPrimitive.boolean },
        )
        val ack = calls[3].second["acks"]!!.jsonArray.single().jsonObject
        assertNull(ack["reason"], "No null is written")
    }

    @Test
    fun aRefusalCarriesItsStatusAndTheServersErrorType() = runTest {
        val fenced = channel(HttpStatusCode.Conflict) { """{"error":{"type":"execution_fenced","message":"x"}}""" }
        val refused = assertFailsWith<RunnerChannelException> { fenced.agent() }
        assertEquals(409, refused.statusCode)
        assertEquals("execution_fenced", refused.code)
        val plain = channel(HttpStatusCode.BadGateway) { "<html>" }
        assertNull(assertFailsWith<RunnerChannelException> { plain.acknowledge(emptyList()) }.code)
        val untyped = channel(HttpStatusCode.BadRequest) { """{"error":{}}""" }
        assertNull(assertFailsWith<RunnerChannelException> { untyped.events(emptyList()) }.code)
    }
}
