// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.launcher.adapters.AgentAdapter
import dev.ostrom.cli.launcher.adapters.AgentLaunch
import dev.ostrom.cli.launcher.adapters.MinimumVersion
import dev.ostrom.cli.platform.CaptureResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a repository cannot change in `ostrom <agent>`: the runner of the checks, the agent's PATH, the hooks' config
 * folder, an agent older than its adapter's minimum (security audit of the CLI n°2, H3), Claude's session variables
 * (H4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLaunchGuardsTest : AgentLaunchKit() {
    @Test
    fun claudesSessionSettingsCarryTheLaunchsOwnVariables() = runTest {
        // a repository's `env` would otherwise rewrite them, the hooks' included (H4)
        serveALaunch()
        start()
        val written = filesWhileRunning[join(temporary, "claude-settings.json")].orEmpty()
        assertTrue(""""OSTROM_LAUNCH_ID":"L1"""" in written, written)
        assertTrue(""""OSTROM_TOKEN":""""" in written, "the device token, removed, is pinned empty")
        assertTrue(""""PATH":"/opt/bin:/usr/bin"""" in written, "the launcher's PATH, not a repository's")
    }

    @Test
    fun aRepositoryCannotNameTheRunnerThatRunsTheChecks() = runTest {
        // Gemini CLI loads a repository's .env without replacing a variable already set: the launcher always sets it,
        // empty on a workstation, so a repository cannot point the checks at a socket of its own
        serveALaunch()
        start()
        assertEquals("", environments.single()["OSTROM_RUNNER_CHECKS"])
    }

    @Test
    fun theRunnerOfACloudSessionKeepsItsChecks() = runTest {
        serveALaunch()
        variables["OSTROM_RUNNER_CHECKS"] = "/run/ostrom/checks.sock"
        start()
        assertEquals("/run/ostrom/checks.sock", environments.single()["OSTROM_RUNNER_CHECKS"])
    }

    @Test
    fun anAgentOlderThanItsAdaptersMinimumIsNotStartedNorLaunched() = runTest {
        serveALaunch()
        installed["gemini"] = "/usr/bin/gemini"
        val gemini = object : AgentAdapter {
            override val agent = Agent.GEMINI
            override val minimum = MinimumVersion("0.66.0", "npm install -g @google/gemini-cli@latest")

            override fun prepare(layout: dev.ostrom.cli.launcher.adapters.SessionLayout, files: LaunchFiles) =
                AgentLaunch(emptyList(), emptyMap())
        }
        adapters = listOf(gemini)
        assertEquals(70, start(Agent.GEMINI))
        assertEquals(
            "ostrom: Gemini CLI 0.65.0 is older than 0.66.0: a repository could turn Ostrom's hooks off in it, so it " +
                "is not started. Update it: npm install -g @google/gemini-cli@latest",
            said.last(),
        )
        assertEquals(listOf(listOf("/usr/bin/gemini", "--version")), versionAsked)
        assertTrue(commands.isEmpty(), "the agent never starts")
        assertTrue(relaySettings.isEmpty(), "no launch is asked of Ostrom")
        server["GET ${Routes.POLICY}?agent=gemini&dev=alice"] = 200 to """{"allowed":true}"""
        versionAnswer = CaptureResult.Exited(0, "0.66.0\n")
        assertEquals(3, start(Agent.GEMINI), "a recent enough agent starts")
    }

    @Test
    fun anAdapterSeesTheWorkingFolderAndTheRepositorysTop() = runTest {
        serveALaunch()
        val seen = mutableListOf<List<String>>()
        adapters = listOf(
            object : AgentAdapter {
                override val agent = Agent.CLAUDE

                override fun prepare(layout: dev.ostrom.cli.launcher.adapters.SessionLayout, files: LaunchFiles) =
                    AgentLaunch(emptyList(), emptyMap()).also { seen += layout.workspace }
            },
        )
        start()
        assertEquals(listOf(listOf("/work/acme-api/src", "/work/acme-api")), seen)
        gitAnswer = CaptureResult.Exited(0, "/work/acme-api/src\n")
        start()
        assertEquals(listOf("/work/acme-api/src"), seen.last(), "once when the session starts at the top")
    }

    @Test
    fun theAgentsPathHoldsOnlyAbsoluteFoldersOutsideTheRepository() = runTest {
        // a relative entry, an empty one, or one inside the repository lets the repository supply the hooks' bash
        serveALaunch()
        files.texts[join("/work/acme-api/node_modules/.bin", "bash")] = ""
        files.texts[join("/usr/bin", "bash")] = ""
        files.links["/home/dev/acme"] = "/work/acme-api"
        variables["PATH"] = "node_modules/.bin::.:/work/acme-api/node_modules/.bin:/home/dev/acme/bin:/usr/bin:" +
            "/work/acme-api/src/tools:/opt/tools:bin"
        start()
        val path = checkNotNull(environments.last()["PATH"])
        assertEquals("/opt/bin:/usr/bin:/opt/tools", path)
        val bash = path.split(':').map { join(it, "bash") }.first { it in files.texts }
        assertEquals(join("/usr/bin", "bash"), bash, "the bash found is outside the repository")
    }

    @Test
    fun theHooksConfigFolderIsTheLaunchersOwn() = runTest {
        // set, so that neither a repository's .env nor XDG_CONFIG_HOME can choose another
        serveALaunch()
        start()
        assertEquals("/config", environments.last()["OSTROM_HOME"])
    }
}
