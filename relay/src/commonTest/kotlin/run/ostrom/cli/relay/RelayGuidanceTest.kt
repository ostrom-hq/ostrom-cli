// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RelayGuidanceTest {
    private fun claim(expiresAt: String = "2099-01-01T00:00:00Z", claimId: String? = "claim-1") =
        """{"directive":{"id":"adv-1","pace":"slow_down","maxConcurrent":0,"minIntervalMillis":5000,""" +
            """"message":"Space your calls by 5 s.","createdAt":"2026-11-15T00:00:00Z","expiresAt":"$expiresAt",""" +
            """"later":true}""" + (claimId?.let { ""","claimId":"$it"""" } ?: "") + "}"

    private fun ostrom(claim: String, status: HttpStatusCode = HttpStatusCode.OK) = FakeOstrom { request ->
        when (request.url.encodedPath) {
            "/api/v1/directives/claim" -> respond(claim, status, JSON)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private suspend fun ApplicationTestBuilder.hook(body: String) = client.post("/guidance") {
        bearerAuth(TOKEN)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun FakeOstrom.receipt() = withTimeout(5.seconds) {
        while (requests.none { it.url.encodedPath == "/api/v1/directives/receipt" }) delay(10)
        requests.single { it.url.encodedPath == "/api/v1/directives/receipt" }
    }

    @Test
    fun theLaunchsNewAdviceIsTheAgentsContextAndItsEmissionIsConfirmedAfter() {
        val ostrom = ostrom(claim())
        relayTest(ostrom) {
            val answer = hook("""{"hook_event_name":"PreToolUse","session_id":"s","tool_input":{"command":"secret"}}""")
            assertEquals(HttpStatusCode.OK, answer.status)
            assertEquals("no-store", answer.headers["Cache-Control"])
            assertEquals(
                """{"hookSpecificOutput":{"hookEventName":"PreToolUse",""" +
                    """"additionalContext":"Ostrom capacity guidance: Space your calls by 5 s."}}""",
                answer.bodyAsText(),
            )
            val claim = ostrom.requests.first()
            assertEquals("Bearer $AGENT_TOKEN", claim.headers["Authorization"])
            val asked = claim.body.toByteArray().decodeToString()
            assertEquals("""{"sessionId":"$LAUNCH","stage":"emitted_to_hook"}""", asked)
            val receipt = ostrom.receipt()
            assertEquals("Bearer $AGENT_TOKEN", receipt.headers["Authorization"])
            assertEquals(
                """{"id":"adv-1","stage":"emitted_to_hook","sessionId":"$LAUNCH","claimId":"claim-1"}""",
                receipt.body.toByteArray().decodeToString(),
            )
            assertTrue(ostrom.requests.none { "secret" in it.body.toByteArray().decodeToString() })
        }
    }

    @Test
    fun aPromptGetsItsOwnEventName() {
        relayTest(ostrom(claim())) {
            val answer = hook("""{"hook_event_name":"UserPromptSubmit","prompt":"the plan"}""").bodyAsText()
            assertTrue(""""hookEventName":"UserPromptSubmit"""" in answer, answer)
        }
    }

    @Test
    fun nothingNewOrUsableToSayAnswersAnEmptyObjectAndConfirmsNothing() {
        val cases = listOf(
            ostrom("{}") to """{"hook_event_name":"PreToolUse"}""",
            ostrom(claim(claimId = null)) to """{"hook_event_name":"PreToolUse"}""",
            ostrom(claim(expiresAt = "2020-01-01T00:00:00Z")) to """{"hook_event_name":"PreToolUse"}""",
            ostrom(claim(), HttpStatusCode.Forbidden) to """{"hook_event_name":"PreToolUse"}""",
            ostrom("not json") to """{"hook_event_name":"PreToolUse"}""",
            ostrom(claim()) to """{"hook_event_name":"Stop"}""",
            ostrom(claim()) to """{"hook_event_name":7}""",
            ostrom(claim()) to """{"hook_event_name":{}}""",
            ostrom(claim()) to "[1]",
            ostrom(claim()) to "not json",
            ostrom(claim()) to """{"hook_event_name":"PreToolUse","tool_response":"${"x".repeat(70_000)}"}""",
            ostrom(claim()) to "x".repeat(HOOK_BODY_LIMIT + 1),
        )
        for ((ostrom, payload) in cases) {
            relayTest(ostrom) {
                val answer = hook(payload)
                assertEquals(HttpStatusCode.OK to "{}", answer.status to answer.bodyAsText(), payload.take(60))
            }
            assertTrue(ostrom.requests.none { it.url.encodedPath.endsWith("/receipt") }, payload.take(60))
        }
    }

    @Test
    fun anOstromThatIsDownOrLateLeavesTheAgentWithoutAdvice() {
        val late = FakeOstrom {
            // past the lookup's 2.5 s
            delay(4.seconds)
            respond(claim(), HttpStatusCode.OK, JSON)
        }
        val down = FakeOstrom { error("down") }
        for (ostrom in listOf(late, down)) {
            relayTest(ostrom) {
                assertEquals("{}", hook("""{"hook_event_name":"PreToolUse"}""").bodyAsText())
            }
        }
    }

    @Test
    fun aReceiptOstromRefusesOrNeverAnswersChangesNothingForTheAgent() {
        val refused = FakeOstrom { request ->
            if (request.url.encodedPath.endsWith("/claim")) respond(claim(), HttpStatusCode.OK, JSON) else error("down")
        }
        relayTest(refused) {
            assertTrue("hookSpecificOutput" in hook("""{"hook_event_name":"PreToolUse"}""").bodyAsText())
            refused.receipt()
        }
        val late = FakeOstrom { request ->
            if (!request.url.encodedPath.endsWith("/claim")) delay(2.seconds)
            respond(claim(), HttpStatusCode.OK, JSON)
        }
        relayTest(late) {
            assertTrue("hookSpecificOutput" in hook("""{"hook_event_name":"PreToolUse"}""").bodyAsText())
            late.receipt()
        }
    }

    @Test
    fun theGuidanceRouteTakesEitherCapabilityOnPostOnly() {
        val config = relayConfig()
        assertEquals(RelayTarget.Guidance, resolveTarget(HttpMethod.Post, "/guidance", config))
        assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Get, "/guidance", config))
        assertTrue(opens(RelayTarget.Guidance, hooks = true, client = false))
        assertTrue(opens(RelayTarget.Guidance, hooks = false, client = true))
    }

    private companion object {
        val JSON = headersOf("Content-Type", "application/json")
    }
}
