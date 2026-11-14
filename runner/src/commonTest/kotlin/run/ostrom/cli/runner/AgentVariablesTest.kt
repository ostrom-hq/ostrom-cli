// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AgentVariablesTest {
    private class FakeClock : Clock {
        var now = Instant.parse("2026-11-14T10:00:00Z")
        override fun now(): Instant = now
        fun advance(by: Duration) {
            now += by
        }
    }

    @Test
    fun anAgentStartAsksTheServerAndACommandAsksAgainOnceTheAnswerIsOld() = runTest {
        val clock = FakeClock()
        val answers = ArrayDeque(
            listOf(
                EnvironmentValues(mapOf("API_KEY" to "first-value", "OLD" to "x")),
                EnvironmentValues(mapOf("API_KEY" to "second-value")),
                EnvironmentValues(),
            ),
        )
        var asked = 0
        val logs = mutableListOf<String>()
        val variables = RunnerVariables({
            asked++
            answers.removeFirst()
        }, clock, { logs += it })

        assertEquals(mapOf("API_KEY" to "first-value", "OLD" to "x"), variables.forStart())
        clock.advance(10.seconds)
        val fresh = variables.forCommand()
        assertEquals(1, asked)
        assertEquals(mapOf("API_KEY" to "first-value", "OLD" to "x"), fresh.variables)
        clock.advance(25.seconds)
        val renewed = variables.forCommand()
        assertEquals(2, asked)
        assertEquals(mapOf("API_KEY" to "second-value"), renewed.variables)
        assertTrue(logs.single().startsWith("variables changed: API_KEY"))
        assertEquals(listOf("OLD"), renewed.withdrawn)
        clock.advance(31.seconds)
        assertEquals(emptyMap(), variables.forCommand().variables)
        assertEquals("variables changed: none", logs.last())
        assertFalse(logs.any { "first-value" in it || "second-value" in it })
        assertFalse("first-value" in fresh.toString())
        assertFalse("first-value" in EnvironmentValues(mapOf("A" to "first-value")).toString())
    }

    @Test
    fun aFailedReadKeepsTheLastValuesAndIsRetriedLater() = runTest {
        val clock = FakeClock()
        var mode = 0
        val logs = mutableListOf<String>()
        val variables = RunnerVariables({
            when (mode) {
                0 -> EnvironmentValues(mapOf("A" to "1"))
                1 -> error("down")
                else -> awaitCancellation()
            }
        }, clock, { logs += it })
        variables.forStart()
        mode = 1
        clock.advance(31.seconds)
        assertEquals(mapOf("A" to "1"), variables.forCommand().variables)
        assertTrue("the variables could not be read: IllegalStateException" in logs)
        // Not asked again before the retry delay.
        mode = 2
        clock.advance(10.seconds)
        assertEquals(mapOf("A" to "1"), variables.forCommand().variables)
        // A server that does not answer in time: the command runs with the last values.
        clock.advance(10.seconds)
        assertEquals(mapOf("A" to "1"), variables.forCommand().variables)
        mode = 0
        clock.advance(20.seconds)
        variables.forCommand()
        assertTrue("the variables are read again" in logs)
    }

    @Test
    fun aServerWithoutTheRouteOrARefusalServesNothingMore() = runTest {
        val clock = FakeClock()
        var asked = 0
        val logs = mutableListOf<String>()
        val missing = RunnerVariables({
            asked++
            null
        }, clock, { logs += it })
        assertEquals(emptyMap(), missing.forStart())
        clock.advance(60.seconds)
        missing.forCommand()
        assertEquals(1, asked)
        assertTrue(logs.single().startsWith("the server does not serve variables"))

        var refuse = false
        val refused = RunnerVariables({
            if (refuse) throw VariablesRefusedException()
            EnvironmentValues(mapOf("A" to "1"))
        }, clock, {})
        refused.forStart()
        refuse = true
        clock.advance(31.seconds)
        val answer = refused.forCommand()
        assertEquals(emptyMap(), answer.variables)
        assertEquals(listOf("A"), answer.withdrawn)
    }

    @Test
    fun aCancelledReadStaysCancelled() = runTest {
        val variables = RunnerVariables({ throw CancellationException("stop") }, FakeClock(), {})
        assertFailsWith<CancellationException> { variables.forStart() }
    }

    @Test
    fun reservedNamesNeverReachTheAgent() = runTest {
        val logs = mutableListOf<String>()
        val served = mapOf(
            "API_KEY" to "v", "PATH" to "/evil", "LD_PRELOAD" to "x", "OSTROM_RUNNER_TOKEN" to "x", "lower" to "x",
            "CLAUDE_CODE_X" to "x", "NUL" to "a\u0000b", "GIT_CONFIG_COUNT" to "1",
        )
        val variables = RunnerVariables({ EnvironmentValues(served) }, FakeClock(), { logs += it })
        assertEquals(mapOf("API_KEY" to "v"), variables.forStart())
        assertEquals(
            "variables not given to the agent (reserved names): CLAUDE_CODE_X, GIT_CONFIG_COUNT, LD_PRELOAD, NUL, " +
                "OSTROM_RUNNER_TOKEN, PATH, lower",
            logs.single(),
        )
    }

    @Test
    fun theScriptExportsQuotedValuesAndUnsetsWithdrawnNames() {
        val script = AgentEnvironment.script(
            mapOf("B" to "it's \$HOME `x` \"q\" \\", "A" to "plain", "PATH" to "/evil"),
            listOf("GONE", "A", "PATH"),
        )
        assertEquals(
            listOf(
                "# Written by Ostrom for this session: your assigned environment variables. Do not edit.",
                "unset GONE",
                "export A='plain'",
                "export B=\"it's \\\$HOME \\`x\\` \\\"q\\\" \\\\\"",
            ),
            script.lines().filter { it.isNotEmpty() },
        )
        val file = AgentEnvironment.cloudScript("/opt/ostrom/ostrom", "/run/ostrom/environment.sock")
        val call = "eval \"\$('/opt/ostrom/ostrom' cloud environment " +
            "--socket '/run/ostrom/environment.sock' 2>/dev/null)\""
        assertTrue(call in file, file)
    }
}
