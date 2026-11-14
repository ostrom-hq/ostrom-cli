// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class HttpServerApiPreparationTest {
    private val id = Uuid.random()
    private val config = RunnerConfig("http://s", id, 3, "claude", null, null, "t", "/workspace")

    private class Exchange {
        var status = HttpStatusCode.OK
        var answer = ""
        val bodies = mutableListOf<String>()
        val paths = mutableListOf<String>()
    }

    private fun TestScope.api(exchange: Exchange) = HttpServerApi(
        HttpClient(MockEngine { request ->
            assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
            exchange.paths += request.url.encodedPath
            exchange.bodies += request.body.toByteArray().decodeToString()
            respond(exchange.answer, exchange.status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false },
        config,
        this,
    )

    private fun error(type: String) = """{"error":{"type":"$type","message":"no"}}"""

    @Test
    fun theEnvironmentIsReadForThisExecution() = runTest {
        val exchange = Exchange()
        exchange.answer = """{"environment":{""" +
            """"repositories":[{"url":"https://github.com/a/b","path":"b","ref":"v1"}],""" +
            """"workingDirectory":"b","setup":{"create":["make"]},"requiredVariables":["K"],"requireGitHub":true},""" +
            """"git":{"name":"octo","email":"o@x"},"variables":{"K":"value"},"unavailable":[],"later":1}"""
        val prepared = assertNotNull(api(exchange).environment())

        assertEquals("/api/v1/cloud/runner/environment", exchange.paths.single())
        assertEquals("""{"sessionId":"$id","epoch":3}""", exchange.bodies.single())
        assertEquals(CloudRepository("https://github.com/a/b", "b", "v1"), prepared.environment.repositories.single())
        assertEquals(CloudSetup(create = listOf("make")), prepared.environment.setup)
        assertEquals(GitIdentity("octo", "o@x"), prepared.git)
        assertEquals(mapOf("K" to "value"), prepared.variables)
        assertFalse("value" in prepared.toString())
    }

    @Test
    fun anAnswerOutsideTheContractFailsThePreparation() = runTest {
        val exchange = Exchange().apply { answer = """{"environment":{}}""" }
        val refused = assertFailsWith<PreparationRefusedException> { api(exchange).environment() }
        assertEquals(PreparationRefusedException.UNREADABLE, refused.code)
        assertFalse(refused.ends)
    }

    @Test
    fun aServerWithoutTheRouteServesNoEnvironment() = runTest {
        val exchange = Exchange().apply { status = HttpStatusCode.NotFound }
        assertNull(api(exchange).environment())
    }

    @Test
    fun refusalsStopAndEverythingElseIsRetried() = runTest {
        val exchange = Exchange()
        val api = api(exchange)
        for (type in listOf("execution_fenced", "not_preparing", "preparation_failed")) {
            exchange.status = HttpStatusCode.Conflict
            exchange.answer = error(type)
            assertEquals(type, assertFailsWith<PreparationRefusedException> { api.environment() }.code)
        }
        for (status in listOf(400, 403, 413, 422)) {
            exchange.status = HttpStatusCode.fromValue(status)
            exchange.answer = "not json"
            val refused = assertFailsWith<PreparationRefusedException> { api.reportPreparation("running_setup") }
            assertNull(refused.code)
            assertEquals(status, refused.status)
        }
        exchange.status = HttpStatusCode.Unauthorized
        assertFailsWith<ExecutionEndedException> { api.environment() }
        for (status in listOf(409, 429, 503)) {
            exchange.status = HttpStatusCode.fromValue(status)
            exchange.answer = error("conflict")
            assertFailsWith<IllegalStateException> { api.environment() }
        }
    }

    @Test
    fun aPhaseOrAFailureIsReportedAndTheAnswerSaysWhatToDo() = runTest {
        val exchange = Exchange()
        val api = api(exchange)
        exchange.answer = """{"instruction":"run","state":"creating"}"""
        assertEquals(Instruction.RUN, api.reportPreparation("cloning_repositories"))
        exchange.answer = """{"instruction":"stop","state":"creating"}"""
        val failure = PreparationFailure("clone", 1, 128)
        assertEquals(Instruction.STOP, api.reportPreparation("cloning_repositories", failure))

        assertEquals("/api/v1/cloud/runner/preparation", exchange.paths.first())
        assertEquals("""{"sessionId":"$id","epoch":3,"phase":"cloning_repositories"}""", exchange.bodies[0])
        assertEquals(
            """{"sessionId":"$id","epoch":3,"phase":"cloning_repositories",""" +
                """"failure":{"step":"clone","index":1,"exitCode":128}}""",
            exchange.bodies[1],
        )
        api.reportPreparation("running_setup", PreparationFailure("working_directory"))
        assertEquals(
            """{"sessionId":"$id","epoch":3,"phase":"running_setup","failure":{"step":"working_directory"}}""",
            exchange.bodies[2],
        )
    }

    @Test
    fun theModelsNameTheirDefaults() {
        assertEquals("b", CloudRepository("https://github.com/a/b.git").directory)
        assertEquals("b", CloudRepository("https://github.com/a/b/").directory)
        assertEquals("x/y", CloudRepository("https://github.com/a/b", "x/y/").directory)
        val refused = PreparationRefusedException(null, 400)
        assertEquals("The server refused the preparation (HTTP 400).", refused.message)
        assertEquals(
            "The server refused the preparation (not_preparing).",
            PreparationRefusedException("not_preparing", 409).message,
        )
    }

    @Test
    fun everyGitOfThePreparationIsHardened() {
        val git = GitHardening.git("/opt/ostrom/ostrom cloud credential", setOf("https", "file"))
        assertEquals("git", git.first())
        val options = git.drop(1).chunked(2).map { (flag, value) ->
            assertEquals("-c", flag)
            value
        }
        assertEquals(
            listOf(
                "core.hooksPath=/dev/null",
                "core.fsmonitor=false",
                "protocol.allow=never",
                "protocol.file.allow=always",
                "protocol.https.allow=always",
                "fetch.recurseSubmodules=false",
                "submodule.recurse=false",
                "credential.helper=",
                "credential.https://github.com.helper=/opt/ostrom/ostrom cloud credential",
            ),
            options,
        )
        assertEquals(SessionScript.GIT_HARDENING, GitHardening.SCRIPT_OPTIONS)
    }
}
