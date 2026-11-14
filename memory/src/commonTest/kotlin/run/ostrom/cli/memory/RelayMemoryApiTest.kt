// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.memory

import run.ostrom.cli.platform.LoopbackException
import run.ostrom.cli.platform.LoopbackFailure
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.cli.platform.LoopbackResponse
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.UpdateMemoryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayMemoryApiTest {
    /** The relay: answers every request with [answer], and keeps what it was sent. */
    private class FakeRelay(private val answer: (LoopbackRequest) -> Result<LoopbackResponse>) : LoopbackHttp {
        val sent = mutableListOf<Triple<Int, LoopbackRequest, Int>>()

        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
            sent += Triple(port, request, timeoutMillis)
            return answer(request)
        }
    }

    private val entry = OstromJson.encodeToString(MemoryEntry.serializer(), memoryEntry())

    private fun ok(body: String, status: Int = 200) = Result.success(LoopbackResponse(status, body))

    @Test
    fun everyCallGoesToItsRouteWithTheRelaysCapability() {
        val relay = FakeRelay { request ->
            when {
                request.path.startsWith("/api/v1/memory/index") -> ok("""{"entries":[],"next":"n"}""")
                request.path.startsWith("/api/v1/memory/search") -> ok("""{"entries":[]}""")
                request.method == "DELETE" -> ok("", 204)
                request.method == "POST" -> ok(entry, 201)
                else -> ok(entry)
            }
        }
        val api = RelayMemoryApi(relay, 4100, "cap")
        assertEquals("n", api.index(null, emptyList(), null, 50).next)
        api.index("team", listOf("a", "b.c"), "x y/é", 2)
        api.search("deploy & roll=back", listOf("ci"), 20)
        api.search("x", emptyList(), 5)
        assertEquals(1, api.read("4f1c-uuid_like").version)
        api.create(CreateMemoryRequest("T", "S", "C"))
        api.update("m-1", UpdateMemoryRequest(2, "T", "S", "C"))
        api.forget("m-1")
        assertEquals(
            listOf(
                "GET /api/v1/memory/index?limit=50",
                "GET /api/v1/memory/index?scope=team&tags=a%2Cb.c&after=x%20y%2F%C3%A9&limit=2",
                "GET /api/v1/memory/search?q=deploy%20%26%20roll%3Dback&tags=ci&limit=20",
                "GET /api/v1/memory/search?q=x&limit=5",
                "GET /api/v1/memory/4f1c-uuid_like",
                "POST /api/v1/memory",
                "PATCH /api/v1/memory/m-1",
                "DELETE /api/v1/memory/m-1",
            ),
            relay.sent.map { "${it.second.method} ${it.second.path}" },
        )
        assertEquals(setOf(mapOf("Authorization" to "Bearer cap")), relay.sent.map { it.second.headers }.toSet())
        assertEquals(setOf(4100 to RelayMemoryApi.CALL_MILLIS), relay.sent.map { it.first to it.third }.toSet())
        assertEquals("""{"title":"T","summary":"S","content":"C"}""", relay.sent[5].second.body)
        assertEquals("""{"expectedVersion":2,"title":"T","summary":"S","content":"C"}""", relay.sent[6].second.body)
    }

    @Test
    fun anIdThatIsNoPlainIdNeverLeavesThisProcess() {
        val relay = FakeRelay { ok(entry) }
        val api = RelayMemoryApi(relay, 1, "cap")
        for (id in listOf("../index", "a?b", "", "x".repeat(129), "a b", "index", "search", "proposals")) {
            val error = assertFailsWith<MemoryApiException> { api.read(id) }
            assertEquals(MEMORY_NOT_FOUND, error.code)
        }
        assertFailsWith<MemoryApiException> { api.forget("a/b") }
        assertFailsWith<MemoryApiException> { api.update("a/b", UpdateMemoryRequest(1, "T", "S", "C")) }
        assertEquals(0, relay.sent.size)
    }

    @Test
    fun ostromsRefusalsTheRelaysAndAnUnreadableAnswerAreSaid() {
        val answers = listOf(
            ok("""{"error":{"type":"memory_secret","message":"Looks like a token."}}""", 422) to
                Triple("memory_secret", 422, "Looks like a token."),
            ok("A local Ostrom capability is required.", 401) to Triple("http_401", 401, "Ostrom answered HTTP 401"),
            ok("""{"other":1}""", 409) to Triple("http_409", 409, "Ostrom answered HTTP 409"),
            ok("", 101) to Triple("http_101", 101, "Ostrom answered HTTP 101"),
            ok("""{"entries":"no"}""") to Triple(RelayMemoryApi.UNREADABLE, null, "Ostrom's answer could not be read"),
            Result.failure<LoopbackResponse>(LoopbackException(LoopbackFailure.UNREACHABLE)) to Triple(
                RelayMemoryApi.UNREACHABLE,
                null,
                "the local relay did not answer (the local relay is unreachable)",
            ),
        )
        for ((answer, expected) in answers) {
            val error = assertFailsWith<MemoryApiException> {
                RelayMemoryApi(FakeRelay { answer }, 1, "cap").search("x", emptyList(), 1)
            }
            assertEquals(expected, Triple(error.code, error.status, error.message))
        }
    }

    @Test
    fun aQueryEscapesEveryByteButTheUnreservedOnes() {
        assertEquals("", QueryString.of(listOf("a" to null)))
        assertEquals("aZ09-._~%20%2B%E2%82%AC", QueryString.escape("aZ09-._~ +€"))
    }
}
