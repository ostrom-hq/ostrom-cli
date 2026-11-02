// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launch

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.launcher.AgentLaunchUseCase
import dev.ostrom.cli.launcher.LaunchDependencies
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchRequest
import dev.ostrom.cli.launcher.LaunchSite
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.adapters.AgentAdapter
import dev.ostrom.cli.launcher.adapters.ClaudeAdapter
import dev.ostrom.cli.launcher.adapters.CodexAdapter
import dev.ostrom.cli.launcher.adapters.GeminiAdapter
import dev.ostrom.cli.launcher.adapters.GrokAdapter
import dev.ostrom.cli.launcher.adapters.HermesAdapter
import dev.ostrom.cli.launcher.adapters.OpenCodeAdapter
import dev.ostrom.cli.launcher.adapters.PiAdapter
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.relay.LocalRelayStarter
import kotlinx.coroutines.CoroutineDispatcher

/** `ostrom <agent>`, once the server and the developer are known; returns the exit code. */
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
        val dependencies = LaunchDependencies(
            server = ServerJson(ApiServerTransport(api(site.serverUrl), site.org)),
            files = files,
            finder = finder,
            processes = processes,
            capture = capture,
            relays = RelayStarter(relays),
            clock = SystemLaunchClock,
            say = { console.err("$it\n") },
            blocking = blocking,
            userAgent = userAgent,
        )
        return AgentLaunchUseCase(dependencies, ADAPTERS).launch(request, site)
    }

    /** The agents this build can launch. */
    companion object {
        /** The agents `ostrom <agent>` launches in this version. */
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
