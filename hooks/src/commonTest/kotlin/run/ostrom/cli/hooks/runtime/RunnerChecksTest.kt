// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.platform.UnixSocketException
import run.ostrom.cli.platform.UnixSocketFailure
import run.ostrom.cli.platform.UnixSockets
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerChecksTest {
    /** The runner's socket: answers [answer], and records each exchange. */
    private class Socket(private val answer: Result<String>) : UnixSockets {
        val sent = mutableListOf<Triple<String, String, Int>>()

        override fun exchange(path: String, request: String, timeoutMillis: Int): Result<String> {
            sent += Triple(path, request, timeoutMillis)
            return answer
        }
    }

    private val check = RunnerCheck("S1", "sha256:p", "n-test", "s-test", "npm test\n", "/w", "M", 30_000)

    private fun ask(answer: String) =
        RunnerSocketChecks("/run/ostrom/checks.sock", Socket(Result.success(answer))).run(check)

    @Test
    fun theCheckIsOneJsonLineAndItsRunTheRunnersAnswer() {
        val socket = Socket(Result.success("""{"exit":0,"started":true,"outputTail":"ok\n"}"""))
        val run = RunnerSocketChecks("/run/ostrom/checks.sock", socket).run(check)
        assertEquals(ScriptRun(0, "ok\n", RUNNER), run)
        val request = """{"sessionId":"S1","digest":"sha256:p","nodeId":"n-test","scriptId":"s-test",""" +
            """"script":"npm test\n","directory":"/w","head":"M","timeoutMs":30000}""" + "\n"
        assertEquals(listOf(Triple("/run/ostrom/checks.sock", request, 35_000)), socket.sent)
    }

    @Test
    fun aFailureATimeoutAndARunThatCouldNotStartAreAnswered() {
        assertEquals(ScriptRun(1, "boom", RUNNER), ask("""{"exit":1,"started":true,"outputTail":"boom"}"""))
        assertEquals(ScriptRun(null, "", RUNNER), ask("""{"exit":null,"started":true}"""), "a timeout")
        assertEquals(
            ScriptRun(null, "no check host", RUNNER, started = false),
            ask("""{"started":false,"outputTail":"no check host"}"""),
        )
        assertEquals(
            ScriptRun(null, "the session's runner could not run it", RUNNER, started = false),
            ask("""{"started":false}"""),
        )
    }

    @Test
    fun anAnswerThatCannotBeReadRunsNothing() {
        val unreadable = ScriptRun(null, "the session's runner gave an answer that could not be read", RUNNER, false)
        val answers = listOf(
            "", "not json", "[1]", """{"exit":0}""", """{"exit":0,"started":"true"}""", """{"exit":0,"started":1}""",
            """{"started":true}""", """{"exit":"0","started":true}""", """{"exit":1.5,"started":true}""",
            """{"exit":{},"started":true}""",
        )
        for (answer in answers) {
            assertEquals(unreadable, ask(answer), answer)
        }
    }

    @Test
    fun aRunnerThatDoesNotAnswerOrNoTimeLeftRefuses() {
        val gone = Socket(Result.failure(UnixSocketException(UnixSocketFailure.UNREACHABLE)))
        assertEquals(
            ScriptRun(null, "the session's runner did not answer (nothing answers on the socket)", RUNNER, false),
            RunnerSocketChecks("/run/ostrom/checks.sock", gone).run(check),
        )
        var now = 0L
        val budget = HookBudget(10_000) { now }
        val socket = Socket(Result.success("""{"exit":0,"started":true}"""))
        RunnerSocketChecks("/s", socket, budget).run(check)
        assertEquals(9_000, socket.sent.single().third, "the exchange keeps to the agent's limit on the hook")
        now = 10_000
        val spent = RunnerSocketChecks("/s", socket, budget).run(check)
        assertEquals(ScriptRun(null, SandboxedScripts.SPENT, RUNNER, false), spent)
        assertEquals(1, socket.sent.size, "no time left: the runner is not asked")
    }

    private companion object {
        const val RUNNER = "isolated runner"
    }
}
