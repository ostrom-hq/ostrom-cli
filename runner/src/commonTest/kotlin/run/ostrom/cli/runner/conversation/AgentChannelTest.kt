// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
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
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    private val requests = mutableListOf<HttpRequestData>()

    private fun files(status: HttpStatusCode = HttpStatusCode.OK, body: ByteArray, type: String? = null) =
        HttpAgentChannel(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val headers = if (type == null) {
                        headersOf(HttpHeaders.ContentType, "application/octet-stream")
                    } else {
                        json
                    }
                    respond(body, status, headers)
                },
            ) { install(HttpTimeout) },
            "https://ostrom.test",
            { "runner-token" },
            "s1",
            2,
        )

    @Test
    fun aFileIsStreamedFromTheRunnerRouteWithTheExecution() = runTest {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val received = Buffer()
        files(body = bytes).file("f-1", bytes.size.toLong()) { chunk, count -> received.write(chunk, 0, count) }
        assertContentEquals(bytes, received.readByteArray())
        val request = requests.single()
        assertEquals("/api/v1/cloud/runner/files/f-1", request.url.encodedPath)
        assertEquals("s1", request.url.parameters["sessionId"])
        assertEquals("2", request.url.parameters["epoch"])
        assertEquals("Bearer runner-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aFileLongerThanAnnouncedOrRefusedFails() = runTest {
        val long = assertFailsWith<RunnerChannelException> { files(body = ByteArray(10)).file("f", 9) { _, _ -> } }
        assertEquals(502, long.statusCode)
        assertEquals("file_too_large", long.code)
        val gone = files(HttpStatusCode.NotFound, """{"error":{"type":"not_found"}}""".encodeToByteArray(), "json")
        val refused = assertFailsWith<RunnerChannelException> { gone.file("f", 9) { _, _ -> } }
        assertEquals(404, refused.statusCode)
        assertEquals("not_found", refused.code)
        assertFailsWith<IllegalArgumentException> { files(body = ByteArray(0)).file("../f", 0) { _, _ -> } }
        var calls = 0
        files(body = ByteArray(0)).file("empty", 0) { _, _ -> calls++ }
        assertEquals(0, calls, "An empty file hands no chunk")
    }

    @Test
    fun aChannelWithoutFilesDownloadsNone() = runTest {
        val bare = object : AgentChannel {
            override suspend fun agent() = RunnerAgentView("claude")
            override suspend fun events(events: List<RunnerEventView>) = RunnerEventsAckView(0, 0)
            override suspend fun poll(waitMillis: Long, desiredState: String, accepts: RunnerAccepts) =
                RunnerInputsView(emptyList())
            override suspend fun acknowledge(acks: List<RunnerInputAckView>) = Unit
        }
        assertFailsWith<UnsupportedOperationException> { bare.file("f", 1) { _, _ -> } }
        bare.agent()
        bare.events(emptyList())
        bare.poll(0, "running", RunnerAccepts())
        bare.acknowledge(emptyList())
    }

    @Test
    fun anInputCarriesItsFiles() {
        val input = Json.decodeFromString(
            RunnerInputView.serializer(),
            """
            {"id":"i1","kind":"message","text":"","files":[{"id":"f1","name":"a.png","size":8,
            "contentType":"image/png","sha256":"${"0".repeat(64)}"}]}
            """,
        )
        assertEquals(listOf(RunnerFileView("f1", "a.png", 8, "image/png", "0".repeat(64))), input.files)
    }
}
