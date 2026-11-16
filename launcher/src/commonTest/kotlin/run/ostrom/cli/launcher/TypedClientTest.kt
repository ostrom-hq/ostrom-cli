// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okio.ByteString.Companion.toByteString
import run.ostrom.core.api.models.CreateCloudSessionRequest
import run.ostrom.core.client.CloudFileSource
import run.ostrom.core.client.OstromException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The typed client of ostrom-core over the CLI's own transport: what it sends, and what a failure becomes. */
class TypedClientTest {
    private val fake = FakeServer()

    @Test
    fun aJsonCallCarriesItsTokenOrganisationKeyAndMember() = runTest {
        fake["POST /api/v1/cloud/sessions"] = 201 to cloudJson("cs1")
        fake["POST /api/v1/cloud/sessions/cs1/pause"] = 202 to cloudJson("cs1", "status" to "pausing")
        val client = typedClient(fake, "tok", "o1")
        val created = client.cloudSessions.create(CreateCloudSessionRequest("claude"), "key-1")
        assertEquals("cs1", created.id)
        client.cloudSessions.pause("cs1", onBehalfOf = "ada")
        val (create, pause) = fake.requests
        assertEquals("""{"agent":"claude"}""", create.body)
        assertEquals("tok", create.token)
        assertEquals("o1", create.org)
        assertEquals("key-1", create.idempotencyKey)
        assertNull(create.bytes)
        assertEquals("ada", pause.onBehalfOf)
        assertNull(pause.idempotencyKey)
    }

    @Test
    fun aReadHasNoBodyAndKeepsItsQuery() = runTest {
        fake["GET /api/v1/cloud/sessions?status=paused"] = 200 to """{"items":[${cloudJson("cs1")}]}"""
        val page = typedClient(fake, null).cloudSessions.list(status = "paused")
        assertEquals(listOf("cs1"), page.items.map { it.id })
        val read = fake.requests.single()
        assertNull(read.body)
        assertNull(read.token)
        assertNull(read.org)
        assertEquals("GET", read.method)
    }

    @Test
    fun anUploadSendsItsBytesAsTheyAre() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val stored = """{"id":"f1","sessionId":"cs1","name":"a.png","size":3,"contentType":"image/png",""" +
            """"sha256":"${bytes.toByteString().sha256().hex()}","createdAt":"t","expiresAt":"u"}"""
        fake["POST /api/v1/cloud/sessions/cs1/files?name=a.png"] = 201 to stored
        val client = typedClient(fake, "tok")
        assertEquals("f1", client.cloudFiles.upload("cs1", "a.png", bytes).id)
        assertEquals("f1", client.cloudFiles.upload("cs1", "a.png", CloudFileSource.of(bytes), "key-2").id)
        val (whole, streamed) = fake.requests
        assertEquals(bytes.toList(), whole.bytes?.toList())
        assertEquals(bytes.toList(), streamed.bytes?.toList())
        assertNull(streamed.body)
        assertEquals("key-2", streamed.idempotencyKey)
    }

    @Test
    fun aBodyReadFromAChannelIsSentToo() = runTest {
        fake["POST /raw"] = 200 to "{}"
        val body = object : OutgoingContent.ReadChannelContent() {
            override fun readFrom() = ByteReadChannel(byteArrayOf(9))
            override val contentType = ContentType.Application.OctetStream
        }
        HttpClient(TransportEngine(fake)).use { it.post("https://ostrom.invalid/raw") { setBody(body) } }
        assertEquals(listOf<Byte>(9), fake.requests.single().bytes?.toList())
    }

    @Test
    fun aBodyOfAnotherKindIsRefused() = runTest {
        val body = object : OutgoingContent.ProtocolUpgrade() {
            override suspend fun upgrade(
                input: io.ktor.utils.io.ByteReadChannel,
                output: io.ktor.utils.io.ByteWriteChannel,
                engineContext: kotlin.coroutines.CoroutineContext,
                userContext: kotlin.coroutines.CoroutineContext,
            ) = kotlinx.coroutines.Job()
        }
        HttpClient(TransportEngine(fake)).use {
            assertFailsWith<IllegalStateException> { it.post("https://ostrom.invalid/up") { setBody(body) } }
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun theTextOfTheAnswerIsTheTransportsOwn() = runTest {
        fake["POST /echo"] = 200 to """{"a":1}"""
        val text = HttpClient(TransportEngine(fake)).use { it.post("https://ostrom.invalid/echo").bodyAsText() }
        assertEquals("""{"a":1}""", text)
    }

    @Test
    fun aRefusalAnUnreachableServerAndAnUnreadableAnswerAreFailures() = runTest {
        fake["GET /api/v1/cloud/sessions/cs1"] = 404 to """{"error":{"type":"not_found","message":"No session."}}"""
        fake["GET /api/v1/cloud/sessions/cs2"] = 200 to """{"id":"cs2"}"""
        fake["GET /api/v1/cloud/sessions/cs3"] = 200 to "<html>not json</html>"
        fake["GET /api/v1/cloud/sessions/cs4"] = 200 to cloudJson("cs4")
        fake.unreachable = setOf("GET /api/v1/cloud/sessions/cs5")
        val sessions = typedClient(fake, "tok").cloudSessions
        assertEquals(
            TypedAnswer.Failed(ServerAnswer.Failed(404, "not_found", "No session.")),
            typedAnswer { sessions.get("cs1") },
        )
        val unreadable = TypedAnswer.Failed(ServerAnswer.Failed(502, UNREADABLE, "its answer cannot be read"))
        assertEquals(unreadable, typedAnswer { sessions.get("cs2") })
        assertEquals(unreadable, typedAnswer { sessions.get("cs3") })
        val unreachable = typedAnswer { sessions.get("cs5") } as TypedAnswer.Failed
        assertNull(unreachable.failure.status)
        assertEquals("unreachable", unreachable.failure.type)
        val ok = typedAnswer { sessions.get("cs4") }
        assertEquals("cs4", ok.valueOrNull()?.id)
        assertNull(unreadable.valueOrNull())
    }

    @Test
    fun aFailureWithoutStatusAndASerializationFailureAreFailuresToo() = runTest {
        val noStatus = typedAnswer { throw OstromException(0, null, "no answer") } as TypedAnswer.Failed
        assertNull(noStatus.failure.status)
        assertNull(noStatus.failure.type)
        val unreadable = typedAnswer { throw SerializationException("bad") } as TypedAnswer.Failed
        assertEquals(502, unreadable.failure.status)
        assertEquals(UNREADABLE, unreadable.failure.type)
    }

    @Test
    fun aRequestWithATimeoutButNoRequestTimeoutGetsTheDefault() = runTest {
        fake["GET /empty"] = 200 to "{}"
        val client = HttpClient(TransportEngine(fake)) { install(HttpTimeout) { connectTimeoutMillis = 5 } }
        client.use { it.get("https://ostrom.invalid/empty") }
        assertNull(fake.requests.single().body)
    }

    @Test
    fun aTypedAnswerIsTheUntypedOneItReplaces() = runTest {
        fake["GET /api/v1/cloud/sessions/cs1"] = 200 to cloudJson("cs1")
        val sessions = typedClient(fake, "tok").cloudSessions
        val ok = typedAnswer { sessions.get("cs1") }.asServerAnswer() as ServerAnswer.Ok
        assertEquals("cs1", ok.body["id"].toString().trim('"'))
        assertEquals("owner", ok.body["githubAccess"].toString().trim('"'), "a default is written")
        val failed = typedAnswer { sessions.get("cs9") }.asServerAnswer()
        assertEquals(ServerAnswer.Failed(404, "not_found", "no GET /api/v1/cloud/sessions/cs9"), failed)
    }

    @Test
    fun aClientWithABoundGivesItToEveryCallAndTheDefaultIsThirtySeconds() = runTest {
        fake["GET /api/v1/cloud/sessions/cs1"] = 200 to cloudJson("cs1")
        typedClient(fake, "tok", timeoutMillis = 3_000L).cloudSessions.get("cs1")
        typedClient(fake, "tok").cloudSessions.get("cs1")
        assertEquals(listOf(3_000L, 30_000L), fake.requests.map { it.timeoutMillis })
    }

    @Test
    fun aStrayExceptionIsAFailureButACancellationGoesThrough() = runTest {
        val unreadable = TypedAnswer.Failed(ServerAnswer.Failed(502, UNREADABLE, "its answer cannot be read"))
        assertEquals(unreadable, typedAnswer { throw IllegalArgumentException("bad body") })
        assertEquals(unreadable, typedAnswer { throw IllegalStateException("no such body") })
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            typedAnswer { throw kotlinx.coroutines.CancellationException("stop") }
        }
    }

    @Test
    fun aValueThatIsNoObjectIsAnUnreadableAnswer() {
        val answer = TypedAnswer.Ok(listOf(1)).asServerAnswer()
        assertEquals(ServerAnswer.Failed(502, UNREADABLE, "its answer cannot be read"), answer)
    }
}
