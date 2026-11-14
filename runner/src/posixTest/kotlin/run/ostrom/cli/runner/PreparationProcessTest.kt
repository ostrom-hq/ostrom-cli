// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The preparation's commands on real processes (pipes, as the agent host runs them) and a real git. */
class PreparationProcessTest {
    private val files = FileSystem.SYSTEM
    private val root = "/tmp/ostrom-prepare-${Random.nextLong().toULong().toString(36)}"
    private val workspace = "$root/workspace"
    private val environment = mapOf("PATH" to "/usr/local/bin:/usr/bin:/bin", "HOME" to "$workspace/.home")

    @AfterTest
    fun cleanUp() {
        files.deleteRecursively(root.toPath(), mustExist = false)
    }

    private suspend fun shell(script: String, directory: String = root): Int? {
        val terminal = PtyTerminal.Pipes.start(script, environment, directory)
        while (terminal.read() != null) Unit
        return terminal.exitCode.also { terminal.close() }
    }

    private val output = StringBuilder()

    /** The agent host's side, in memory: each command gets a connection the host serves, as over its socket. */
    private fun CoroutineScope.commands(): AgentHostCommands {
        val host = AgentHost(PtyTerminal, commands = PtyTerminal.Pipes)
        val starter = RemoteTerminalStarter({
            val toHost = ByteChannel()
            val fromHost = ByteChannel()
            launch { host.serve(toHost, fromHost) }
            object : AgentConnection {
                override val input: ByteReadChannel = fromHost
                override val output: ByteWriteChannel = toHost

                override fun close() {
                    launch { toHost.flushAndClose() }
                }
            }
        })
        return AgentHostCommands(starter::command, "$workspace/.ostrom/logs/prepare-e1.log") {
            output.append(it.decodeToString())
        }
    }

    private class Server(private val environment: RunnerEnvironment) : ServerApi {
        val reports = mutableListOf<Pair<String, PreparationFailure?>>()

        override suspend fun environment(): RunnerEnvironment = environment

        override suspend fun reportPreparation(phase: String, failure: PreparationFailure?): Instruction {
            reports += phase to failure
            return Instruction.RUN
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

    @Test
    fun argumentsReachTheProgramExactlyAndTheirVariablesAreGone() = runBlocking {
        withTimeout(20.seconds) {
            files.createDirectories(root.toPath())
            val arguments = listOf("/bin/sh", "-c", "printf '[%s]' \"\$@\"; env | grep -c OSTROM_PREPARE", "sh") +
                listOf("it's", "a  b", "\$HOME", "-n", "")
            val code = commands().run(arguments, root, environment, emptySet())
            assertEquals(1, code)
            assertEquals("[it's][a  b][\$HOME][-n][]0\r\n", output.toString())
        }
    }

    @Test
    fun aRepositoryIsClonedOnceThenFetchedAndTheSetupRunsAsTheRecipeSays() = runBlocking {
        withTimeout(60.seconds) {
            files.createDirectories(workspace.toPath())
            val origin = "$root/origin"
            val made = shell(
                "git init -q -b main origin && cd origin && " +
                    "git -c user.name=t -c user.email=t@t commit -q --allow-empty -m init",
            )
            assertEquals(0, made)
            val recipe = CloudEnvironment(
                repositories = listOf(CloudRepository("file://$origin", "repo", ref = "main", branch = "task")),
                workingDirectory = "repo",
                setup = CloudSetup(listOf("echo created >> created.txt"), listOf("echo \"key=\$API_KEY\"")),
            )
            val identity = GitIdentity("octo", "octo@example.com")
            val prepared = RunnerEnvironment(recipe, identity, mapOf("API_KEY" to "s3cret"))
            val commands = commands()
            fun preparation(server: Server) = EnvironmentPreparation(
                server,
                commands,
                environment,
                workspace,
                "/bin/false",
                token = { null },
                protocols = setOf("https", "file"),
            )

            val first = Server(prepared)
            assertEquals(PreparationOutcome.Prepared("$workspace/repo"), preparation(first).run())
            assertTrue(files.exists("$workspace/repo/.git".toPath()))
            assertFalse(files.exists("$workspace/.ostrom/clone-0".toPath()))
            assertEquals(0, shell("test \"\$(git rev-parse --abbrev-ref HEAD)\" = task", "$workspace/repo"))
            assertEquals(0, shell("test \"\$(git config --global user.email)\" = octo@example.com"))
            assertEquals("created\n", files.read("$workspace/repo/created.txt".toPath()) { readUtf8() })
            val log = files.read("$workspace/.ostrom/logs/prepare-e1.log".toPath()) { readUtf8() }
            assertTrue("key=[REDACTED]" in log, log)
            assertFalse("s3cret" in log || "s3cret" in output)

            val second = Server(prepared)
            assertEquals(PreparationOutcome.Prepared("$workspace/repo"), preparation(second).run())
            val phases = listOf("configuring_git", "fetching_repositories", "running_setup")
            assertEquals(phases, second.reports.map { it.first })
            // The create command ran once.
            assertEquals("created\n", files.read("$workspace/repo/created.txt".toPath()) { readUtf8() })
        }
    }
}
