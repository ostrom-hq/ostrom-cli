// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launch

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.launcher.AgentLaunchUseCase
import run.ostrom.cli.launcher.LaunchDependencies
import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.LaunchRequest
import run.ostrom.cli.launcher.LaunchSite
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.adapters.AgentAdapter
import run.ostrom.cli.launcher.adapters.ClaudeAdapter
import run.ostrom.cli.launcher.adapters.CodexAdapter
import run.ostrom.cli.launcher.adapters.GeminiAdapter
import run.ostrom.cli.launcher.adapters.GrokAdapter
import run.ostrom.cli.launcher.adapters.HermesAdapter
import run.ostrom.cli.launcher.adapters.OpenCodeAdapter
import run.ostrom.cli.launcher.adapters.PiAdapter
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.ProcessLauncher
import run.ostrom.cli.relay.LocalRelayStarter
import kotlinx.coroutines.CoroutineDispatcher

/** `ostrom run <agent>`, once the server and the developer are known; returns the exit code. */
fun interface AgentRunner {
    /** Launches [request] at [site]. */
    suspend fun run(request: LaunchRequest, site: LaunchSite): Int
}

/**
 * The launch of the launcher module over the CLI's ports.
 *
 * @param relays The relay module, its notices printed on stderr.
 * @param blocking Where the agent is waited for (the IO dispatcher).
 */
@Suppress("LongParameterList") // The ports of a launch, each a real one built in main.
class AgentRunnerImpl(
    private val console: Console,
    private val finder: ExecutableFinder,
    private val api: (String) -> OstromApi,
    private val files: LaunchFiles,
    private val processes: ProcessLauncher,
    private val capture: ProcessCapture,
    private val relays: LocalRelayStarter,
    private val blocking: CoroutineDispatcher,
    private val userAgent: String,
) : AgentRunner {
    override suspend fun run(request: LaunchRequest, site: LaunchSite): Int {
        val server = ServerJson(ApiServerTransport(api(site.serverUrl), site.org))
        val dependencies = LaunchDependencies(
            server = server,
            files = files,
            finder = finder,
            processes = processes,
            capture = capture,
            relays = RelayStarter(relays),
            clock = SystemLaunchClock,
            say = { console.err("$it\n") },
            blocking = blocking,
            userAgent = userAgent,
            variables = MemberVariablesImpl(server, files),
        )
        return AgentLaunchUseCase(dependencies, ADAPTERS).launch(request, site)
    }

    /** The agents this build can launch. */
    companion object {
        /** The agents `ostrom run <agent>` launches in this version. */
        private val ADAPTERS: List<AgentAdapter> = listOf(
            ClaudeAdapter(),
            CodexAdapter(),
            OpenCodeAdapter(),
            GeminiAdapter(),
            GrokAdapter(),
            HermesAdapter(),
            PiAdapter(),
        )
    }
}
