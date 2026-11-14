// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentPreparationTest {
    private class Command(
        val arguments: List<String>,
        val directory: String,
        val environment: Map<String, String>,
        val secrets: Set<String>,
    ) {
        val line: String get() = arguments.joinToString(" ")
    }

    /** Records each command and answers [exit] (0 by default; `test` fails unless [existing] holds its path). */
    private class FakeCommands(
        val existing: MutableSet<String> = mutableSetOf(),
        val exit: (Command) -> Int? = { 0 },
    ) : PreparationCommands {
        val runs = mutableListOf<Command>()

        override suspend fun run(
            arguments: List<String>,
            directory: String,
            environment: Map<String, String>,
            secrets: Set<String>,
        ): Int? {
            val command = Command(arguments, directory, environment, secrets)
            runs += command
            if (arguments.first() == "test") return if (arguments.last() in existing) 0 else 1
            return exit(command)
        }
    }

    private class FakeServer(
        val answers: MutableList<() -> RunnerEnvironment?> = mutableListOf(),
        val reportAnswer: (String, PreparationFailure?) -> Instruction = { _, _ -> Instruction.RUN },
    ) : ServerApi {
        val reports = mutableListOf<Pair<String, PreparationFailure?>>()
        var asked = 0

        override suspend fun environment(): RunnerEnvironment? {
            asked++
            return answers.removeAt(0)()
        }

        override suspend fun reportPreparation(phase: String, failure: PreparationFailure?): Instruction {
            reports += phase to failure
            return reportAnswer(phase, failure)
        }

        override suspend fun heartbeat(
            ready: Boolean,
            stopped: Boolean,
            lastModelRequestAt: String?,
            background: Boolean,
        ): Instruction = error("no heartbeat")

        override suspend fun openTerminal(): TerminalLink = error("no terminal")
        override suspend fun openLaunch(): RunnerLaunch = error("no launch")
        override suspend fun reportCheck(check: RunnerCheck): Boolean = error("no check")
    }

    private val recipe = CloudEnvironment(
        repositories = listOf(
            CloudRepository("https://github.com/acme/api.git", "api", ref = "v1", branch = "task-1"),
            CloudRepository("https://github.com/acme/web"),
        ),
        workingDirectory = "api",
        setup = CloudSetup(create = listOf("npm ci"), start = listOf("make dev")),
    )

    private fun environment(
        spec: CloudEnvironment = recipe,
        variables: Map<String, String> = mapOf("API_KEY" to "s3cret-value"),
    ) = RunnerEnvironment(spec, GitIdentity("octo", "1+octo@users.noreply.github.com"), variables)

    private fun preparation(
        server: ServerApi,
        commands: PreparationCommands,
        logs: MutableList<String> = mutableListOf(),
        token: suspend () -> String? = { "ghu_token" },
    ) = EnvironmentPreparation(
        server,
        commands,
        mapOf("HOME" to "/workspace/.home", "PATH" to "/opt/ostrom:/usr/bin", "OSTROM_WORKSPACE" to "/workspace"),
        "/workspace",
        "/opt/ostrom/ostrom cloud credential",
        token = token,
        log = { logs += it },
    )

    private val git = GitHardening.git("/opt/ostrom/ostrom cloud credential").joinToString(" ")

    @Test
    fun aFirstExecutionWritesTheIdentityClonesChecksOutAndRunsTheSetup() = runTest {
        val server = FakeServer(mutableListOf({ environment() }))
        val commands = FakeCommands(mutableSetOf("/workspace/api"))
        val outcome = preparation(server, commands).run()

        assertEquals(PreparationOutcome.Prepared("/workspace/api"), outcome)
        val marker = "/workspace/.ostrom/setup/create-0-${"npm ci".encodeUtf8().sha256().hex().take(16)}.done"
        assertEquals(
            listOf(
                "/bin/sh -c mkdir -p -- \"\$HOME\" \"\$1\" sh /workspace/.ostrom/setup",
                "git config --global user.name octo",
                "git config --global user.email 1+octo@users.noreply.github.com",
                "test -e /workspace/api/.git",
                "test -L /workspace/api/.git",
                "rm -rf -- /workspace/.ostrom/clone-0",
                "$git clone -- https://github.com/acme/api.git /workspace/.ostrom/clone-0",
                "$git checkout --quiet v1 --",
                "$git switch --quiet -c task-1",
                "/bin/sh -c " + MOVE + " sh /workspace/.ostrom/clone-0 /workspace/api",
                "test -e /workspace/web/.git",
                "test -L /workspace/web/.git",
                "rm -rf -- /workspace/.ostrom/clone-1",
                "$git clone -- https://github.com/acme/web /workspace/.ostrom/clone-1",
                "/bin/sh -c " + MOVE + " sh /workspace/.ostrom/clone-1 /workspace/web",
                "test -d /workspace/api",
                "test -e $marker",
                "/bin/sh -c npm ci",
                "touch -- $marker",
                "/bin/sh -c make dev",
            ),
            commands.runs.map { it.line },
        )
        assertEquals("/workspace/.ostrom/clone-0", commands.runs[7].directory)
        assertEquals("/workspace/api", commands.runs.single { it.line == "/bin/sh -c npm ci" }.directory)
        assertEquals(
            listOf("configuring_git", "cloning_repositories", "running_setup"),
            server.reports.map { it.first },
        )
        val first = commands.runs.first()
        assertEquals("s3cret-value", first.environment["API_KEY"])
        assertEquals(setOf("s3cret-value", "ghu_token"), first.secrets)
    }

    @Test
    fun aResumeFetchesAndSkipsTheCreateCommandsAlreadyDone() = runTest {
        val marker = "/workspace/.ostrom/setup/create-0-${"npm ci".encodeUtf8().sha256().hex().take(16)}.done"
        val server = FakeServer(mutableListOf({ environment() }))
        val existing = mutableSetOf("/workspace/api/.git", "/workspace/web/.git", "/workspace/api", marker)
        val commands = FakeCommands(existing)
        val outcome = preparation(server, commands).run()

        assertEquals(PreparationOutcome.Prepared("/workspace/api"), outcome)
        val lines = commands.runs.map { it.line }
        assertEquals(2, lines.count { it == "$git fetch --prune origin" })
        assertEquals("/workspace/web", commands.runs.last { it.line.endsWith("fetch --prune origin") }.directory)
        assertFalse(lines.any { "clone" in it || it == "/bin/sh -c npm ci" })
        assertEquals("/bin/sh -c make dev", lines.last())
        assertEquals(
            listOf("configuring_git", "fetching_repositories", "running_setup"),
            server.reports.map { it.first },
        )
    }

    @Test
    fun aRepositoryWhoseGitIsALinkIsFetchedAndNeverClonedOver() = runTest {
        val spec = CloudEnvironment(listOf(CloudRepository("https://github.com/acme/api", "api/")))
        val commands = FakeCommands(mutableSetOf("/workspace/api/.git", "/workspace"))
        val outcome = preparation(FakeServer(mutableListOf({ environment(spec) })), object : PreparationCommands {
            override suspend fun run(
                arguments: List<String>,
                directory: String,
                environment: Map<String, String>,
                secrets: Set<String>,
            ): Int? = if (arguments.take(2) == listOf("test", "-e")) {
                1
            } else {
                commands.run(arguments, directory, environment, secrets)
            }
        }).run()

        // Without a working directory: the workspace.
        assertEquals(PreparationOutcome.Prepared("/workspace"), outcome)
        assertTrue(commands.runs.any { it.line == "$git fetch --prune origin" })
    }

    @Test
    fun ownerVariablesThatWouldSteerTheCommandsAreLeftOut() = runTest {
        val variables = mapOf(
            "PATH" to "/evil", "HOME" to "/evil", "SHELL" to "x", "IFS" to "x", "ENV" to "x", "BASH_ENV" to "x",
            "XDG_CONFIG_HOME" to "x", "LD_PRELOAD" to "x", "DYLD_X" to "x", "GIT_DIR" to "x", "GH_TOKEN" to "x",
            "OSTROM_RUNNER_TOKEN" to "x", "TEAMCODE_X" to "x", "SSH_AUTH_SOCK" to "x", "1BAD" to "x", "A-B" to "x",
            "OK_NAME" to "value", "_lower1" to "abc",
        )
        val logs = mutableListOf<String>()
        val commands = FakeCommands(mutableSetOf("/workspace"))
        preparation(FakeServer(mutableListOf({ environment(CloudEnvironment(), variables) })), commands, logs).run()

        val seen = commands.runs.first().environment
        assertEquals("/opt/ostrom:/usr/bin", seen["PATH"])
        assertEquals("/workspace/.home", seen["HOME"])
        assertEquals("value", seen["OK_NAME"])
        assertEquals("abc", seen["_lower1"])
        val kept = setOf("PATH", "HOME", "OK_NAME", "_lower1")
        assertTrue(variables.keys.filter { it !in kept }.none { it in seen })
        // Too short to mask: "abc".
        assertEquals(setOf("value", "ghu_token"), commands.runs.first().secrets)
        assertTrue(logs.any { it.startsWith("ignored variables") && "LD_PRELOAD" in it && "value" !in it })
        assertTrue(EnvironmentPreparation.steers("A".repeat(129)))
        assertFalse(EnvironmentPreparation.steers("A".repeat(128)))
    }

    @Test
    fun aFailedCommandIsReportedAsItsStepIndexAndClampedExitCode() = runTest {
        val server = FakeServer(mutableListOf({ environment() }))
        val commands = FakeCommands(mutableSetOf("/workspace/api")) { if ("clone" in it.arguments) 300 else 0 }
        val logs = mutableListOf<String>()
        assertEquals(PreparationOutcome.Failed, preparation(server, commands, logs).run())
        assertEquals("cloning_repositories" to PreparationFailure("clone", 0, 255), server.reports.last())
        assertTrue(logs.any { it.startsWith("preparation failed at clone") })
    }

    @Test
    fun eachStepReportsItsOwnFailure() = runTest {
        suspend fun failure(
            spec: CloudEnvironment,
            existing: Set<String>,
            fails: (List<String>) -> Boolean,
        ): PreparationFailure? {
            val server = FakeServer(mutableListOf({ environment(spec) }))
            val commands = FakeCommands(existing.toMutableSet()) { if (fails(it.arguments)) 2 else 0 }
            preparation(server, commands).run()
            return server.reports.last().second
        }
        val one = CloudEnvironment(
            listOf(CloudRepository("https://github.com/acme/api", "api", ref = "v1", branch = "task")),
            workingDirectory = "api",
            setup = CloudSetup(listOf("a", "b"), listOf("c", "d")),
        )
        val there = setOf("/workspace/api")
        assertEquals(PreparationFailure("git_identity", null, 2), failure(one, there) { "user.email" in it })
        assertEquals(PreparationFailure("checkout", 0, 2), failure(one, there) { "checkout" in it })
        assertEquals(PreparationFailure("checkout", 0, 2), failure(one, there) { "switch" in it })
        assertEquals(PreparationFailure("fetch", 0, 2), failure(one, there + "/workspace/api/.git") { "fetch" in it })
        assertEquals(PreparationFailure("working_directory"), failure(one, emptySet()) { false })
        assertEquals(PreparationFailure("setup_create", 1, 2), failure(one, there) { it.last() == "b" })
        assertEquals(PreparationFailure("setup_start", 1, 2), failure(one, there) { it.last() == "d" })
        // A code the host could not read: a failure without one.
        val server = FakeServer(mutableListOf({ environment(one) }))
        preparation(server, FakeCommands(there.toMutableSet()) { if (it.arguments.last() == "c") null else 0 }).run()
        assertEquals(PreparationFailure("setup_start", 0, null), server.reports.last().second)
        // A check whose answer is unknown counts as failed.
        val unknown = FakeServer(mutableListOf({ environment(one) }))
        val unanswered = PreparationCommands { arguments, _, _, _ -> if (arguments[0] == "test") null else 0 }
        preparation(unknown, unanswered).run()
        assertEquals(PreparationFailure("working_directory"), unknown.reports.last().second)
    }

    @Test
    fun aServerWithoutTheRecipeLeavesTheCloneToTheSessionScript() = runTest {
        val commands = FakeCommands()
        assertEquals(PreparationOutcome.Legacy, preparation(FakeServer(mutableListOf({ null })), commands).run())
        assertTrue(commands.runs.isEmpty())
    }

    @Test
    fun theEnvironmentIsAskedAgainWhileTheServerCannotAnswer() = runTest {
        val logs = mutableListOf<String>()
        val server = FakeServer(
            mutableListOf(
                { error("down") },
                { error("down") },
                { error("down") },
                { error("down") },
                { error("down") },
                { environment(CloudEnvironment()) },
            ),
        )
        val outcome = preparation(server, FakeCommands(mutableSetOf("/workspace")), logs).run()
        assertEquals(PreparationOutcome.Prepared("/workspace"), outcome)
        assertEquals(6, server.asked)
        // 1 + 2 + 4 + 8 + 15 (capped) seconds.
        assertEquals(30_000, testScheduler.currentTime)
        assertTrue(logs.count { it.endsWith("retrying") } == 5)
    }

    @Test
    fun aRefusalOfTheEnvironmentStopsThePreparation() = runTest {
        val notPreparing = FakeServer(mutableListOf({ throw PreparationRefusedException("not_preparing", 409) }))
        assertEquals(PreparationOutcome.NotPreparing, preparation(notPreparing, FakeCommands()).run())
        val failed = FakeServer(mutableListOf({ throw PreparationRefusedException("preparation_failed", 409) }))
        assertEquals(PreparationOutcome.Failed, preparation(failed, FakeCommands()).run())
        val bad = FakeServer(mutableListOf({ throw PreparationRefusedException(null, 400) }))
        val logs = mutableListOf<String>()
        assertEquals(PreparationOutcome.Failed, preparation(bad, FakeCommands(), logs).run())
        assertTrue(logs.single().endsWith("(400)"))
        assertTrue(notPreparing.reports.isEmpty() && failed.reports.isEmpty())
    }

    @Test
    fun anEndedExecutionEndsThePreparation() = runTest {
        val ended = FakeServer(mutableListOf({ throw ExecutionEndedException("gone") }))
        assertFailsWith<ExecutionEndedException> { preparation(ended, FakeCommands()).run() }
        val endedOnReport = FakeServer(mutableListOf({ environment() })) { _, _ ->
            throw ExecutionEndedException("gone")
        }
        assertFailsWith<ExecutionEndedException> { preparation(endedOnReport, FakeCommands()).run() }
    }

    @Test
    fun aPhaseReportThatFailsNeverStopsThePreparationButARefusalOrAStopDoes() = runTest {
        val logs = mutableListOf<String>()
        val flaky = FakeServer(mutableListOf({ environment() })) { _, _ -> error("down") }
        val outcome = preparation(flaky, FakeCommands(mutableSetOf("/workspace/api")), logs).run()
        assertEquals(PreparationOutcome.Prepared("/workspace/api"), outcome)
        assertTrue(logs.any { it.startsWith("could not report") })

        val fenced = FakeServer(mutableListOf({ environment() })) { _, _ ->
            throw PreparationRefusedException("execution_fenced", 409)
        }
        val commands = FakeCommands(mutableSetOf("/workspace/api"))
        assertEquals(PreparationOutcome.Failed, preparation(fenced, commands).run())
        assertTrue(commands.runs.isEmpty())
        // Stopped: no failure is reported on top.
        assertEquals(listOf<Pair<String, PreparationFailure?>>("configuring_git" to null), fenced.reports)

        // Any other refusal of a report is only logged.
        val forbidden = FakeServer(mutableListOf({ environment() })) { _, _ ->
            throw PreparationRefusedException(null, 403)
        }
        val going = preparation(forbidden, FakeCommands(mutableSetOf("/workspace/api")), logs).run()
        assertEquals(PreparationOutcome.Prepared("/workspace/api"), going)
        assertTrue(logs.any { it == "the server refused the preparation's progress (403)" })

        val stopped = FakeServer(mutableListOf({ environment() })) { _, _ -> Instruction.STOP }
        assertEquals(PreparationOutcome.Failed, preparation(stopped, FakeCommands()).run())
    }

    @Test
    fun aFailureReportIsRetriedAndARefusedOneIsOnlyLogged() = runTest {
        var attempts = 0
        val server = FakeServer(mutableListOf({ environment() })) { _, failure ->
            if (failure != null && ++attempts < 3) error("down")
            if (failure == null) Instruction.RUN else Instruction.STOP
        }
        preparation(server, FakeCommands { if ("user.name" in it.arguments) 1 else 0 }).run()
        assertEquals(3, server.reports.count { it.second != null })

        val refusals = listOf(PreparationRefusedException(null, 422), PreparationRefusedException("not_preparing", 409))
        for (refusal in refusals) {
            val logs = mutableListOf<String>()
            val refusing = FakeServer(mutableListOf({ environment() })) { _, failure ->
                if (failure != null) throw refusal
                Instruction.RUN
            }
            preparation(refusing, FakeCommands { if ("user.name" in it.arguments) 1 else 0 }, logs).run()
            assertTrue(logs.any { it.startsWith("the server refused the failure's report") })
        }
    }

    @Test
    fun aRunnerErrorIsReportedAsTheRunnersStep() = runTest {
        val logs = mutableListOf<String>()
        val server = FakeServer(mutableListOf({ environment() }))
        val broken = PreparationCommands { _, _, _, _ -> error("the agent host is gone") }
        assertEquals(PreparationOutcome.Failed, preparation(server, broken, logs, token = { error("no") }).run())
        assertEquals("configuring_git" to PreparationFailure("runner"), server.reports.last())
        assertTrue(logs.any { it.startsWith("preparation stopped on a runner error") })
    }

    @Test
    fun aFailureIsReportedUnderItsPhaseEvenWhenThatPhaseWasNotReported() = runTest {
        val server = FakeServer(mutableListOf({ environment() })) { phase, failure ->
            if (failure == null) error("down $phase")
            Instruction.STOP
        }
        preparation(server, FakeCommands { if ("user.name" in it.arguments) 1 else 0 }).run()
        assertEquals("configuring_git" to PreparationFailure("git_identity", null, 1), server.reports.last())
    }

    private companion object {
        const val MOVE = "mkdir -p -- \"\$(dirname -- \"\$2\")\" && " +
            "{ [ ! -e \"\$2\" ] || rmdir -- \"\$2\"; } && mv -- \"\$1\" \"\$2\""
    }
}
