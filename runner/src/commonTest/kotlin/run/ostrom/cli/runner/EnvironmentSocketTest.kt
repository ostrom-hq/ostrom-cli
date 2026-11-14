// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.time.Duration.Companion.seconds

class EnvironmentSocketTest {
    private fun variables(fetch: suspend () -> EnvironmentValues?) = RunnerVariables(fetch, Clock.System, {})

    private suspend fun ask(
        input: String,
        logs: MutableList<String> = mutableListOf(),
        variables: RunnerVariables,
    ): String? =
        ByteChannel().let { output ->
            EnvironmentSocket.answer(ByteReadChannel(input), output, variables, { logs += it })
            output.flushAndClose()
            output.readUTF8Line()
        }

    @Test
    fun theEnvironmentRequestIsAnsweredWithTheVariables() = runTest {
        val answer = ask("environment\n", variables = variables { EnvironmentValues(mapOf("A" to "1")) })
        assertEquals("""{"variables":{"A":"1"}}""", answer)
    }

    @Test
    fun anythingElseIsAnsweredWithNothing() = runTest {
        val never = variables { error("never") }
        assertNull(ask("wakeups 1\n", variables = never))
        assertNull(ask("", variables = never))
        assertNull(ask("x".repeat(EnvironmentSocket.MAX_LINE + 1), variables = never))
        val slow = ByteChannel()
        EnvironmentSocket.answer(ByteChannel(), slow, never, {}, 1.seconds)
        slow.flushAndClose()
        assertNull(slow.readUTF8Line())
    }

    @Test
    fun aBrokenReplyIsLogged() = runTest {
        val logs = mutableListOf<String>()
        val gone = ByteChannel()
        gone.flushAndClose()
        EnvironmentSocket.answer(ByteReadChannel("environment\n"), gone, variables { null }, { logs += it })
        assertTrue(logs.single().startsWith("environment reply failed"))
    }

    private class Pipe(override val input: ByteReadChannel, override val output: ByteWriteChannel) : AgentConnection {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun theAgentsCommandAsksTheRunner() = runTest {
        val toRunner = ByteChannel()
        val fromRunner = ByteChannel()
        val pipe = Pipe(fromRunner, toRunner)
        val values = variables { EnvironmentValues(mapOf("A" to "1")) }
        launch { EnvironmentSocket.answer(toRunner, fromRunner, values, {}) }
        val answer = EnvironmentSocket.ask({ pipe })
        assertEquals(mapOf("A" to "1"), answer?.variables)
        assertTrue(pipe.closed)
    }

    @Test
    fun aRunnerThatCannotBeReachedOrAnswersNothingGivesNothing() = runTest {
        assertNull(EnvironmentSocket.ask({ error("no socket") }))
        assertNull(EnvironmentSocket.ask({ Pipe(ByteReadChannel("not json\n"), ByteChannel()) }))
        assertNull(EnvironmentSocket.ask({ Pipe(ByteReadChannel(""), ByteChannel()) }))
        assertNull(EnvironmentSocket.ask({ Pipe(ByteChannel(), ByteChannel()) }, 1.seconds))
        assertFailsWith<CancellationException> { EnvironmentSocket.ask({ throw CancellationException("stop") }) }
    }

    @Test
    fun theSocketAndTheScriptAreNextToTheCredentialSocket() {
        assertEquals("/run/ostrom/environment.sock", EnvironmentSocket.socketFor("/run/ostrom/credential.sock"))
        assertEquals("/run/ostrom/agent-env.sh", EnvironmentSocket.scriptFor("/run/ostrom/credential.sock"))
        assertEquals("/environment.sock", EnvironmentSocket.socketFor("/credential.sock"))
        assertEquals("./environment.sock", EnvironmentSocket.socketFor("credential.sock"))
        assertEquals("/environment.sock", EnvironmentSocket.socketFor("/credential.sock"))
    }

    @Test
    fun theScriptIsWrittenWithoutAnyValue() {
        val directory = Path(SystemTemporaryDirectory, "ostrom-env-${Uuid.random()}")
        SystemFileSystem.createDirectories(directory)
        val path = Path(directory, "agent-env.sh").toString()
        EnvironmentSocket.writeScript(path, "/opt/ostrom/ostrom", "/run/ostrom/environment.sock")
        EnvironmentSocket.writeScript(path, "/opt/ostrom/ostrom", "/run/ostrom/environment.sock")
        val script = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        assertEquals(AgentEnvironment.cloudScript("/opt/ostrom/ostrom", "/run/ostrom/environment.sock"), script)
        assertFalse(SystemFileSystem.exists(Path("$path.tmp")))
        SystemFileSystem.delete(Path(path))
        SystemFileSystem.delete(directory)
    }
}
