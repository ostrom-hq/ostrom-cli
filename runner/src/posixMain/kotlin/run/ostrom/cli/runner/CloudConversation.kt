// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import run.ostrom.cli.runner.conversation.ContainerAgentStateStore
import run.ostrom.cli.runner.conversation.ConversationHost
import run.ostrom.cli.runner.conversation.ConversationLaunch
import run.ostrom.cli.runner.conversation.ExecProcesses
import run.ostrom.cli.runner.conversation.HttpAgentChannel

/**
 * The headless agent of a `conversation` session: started in the agent's container without a terminal, relayed to the
 * server's runner channel with the runner's credential ([RunnerConfig.bearer]), its conversation kept in the agent's
 * home.
 */
internal fun conversation(client: HttpClient, config: RunnerConfig, terminals: RemoteTerminalStarter) =
    ConversationStarter { agentEnvironment, directory, first, activity ->
        val processes = ExecProcesses(terminals, agentEnvironment)
        val channel = HttpAgentChannel(client, config.serverUrl, config::bearer, config.sessionId.toString(),
            config.epoch)
        ConversationHost(
            channel = channel,
            start = processes::start,
            state = ContainerAgentStateStore(processes::start, directory),
            workingDirectory = directory,
            clientVersion = CONVERSATION_CLIENT_VERSION,
            log = ::log,
            version = { kind -> ConversationLaunch.version(processes::start, kind, directory) },
            launchCommand = { kind, command -> ConversationLaunch.command(kind, command, agentEnvironment) },
            initialMessage = first,
            activity = activity,
        )
    }

/** The client version the conversation host gives the agent (Codex's `clientInfo`). */
private const val CONVERSATION_CLIENT_VERSION = "1"
