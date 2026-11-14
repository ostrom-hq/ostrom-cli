// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.cloud

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import okio.FileSystem
import run.ostrom.cli.hooks.runtime.WorkflowStopHook
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.runner.CloudProcess
import run.ostrom.cli.runner.McpForwarder
import run.ostrom.cli.runner.ServerHttp
import run.ostrom.cli.todo.TodoHookRunner

/**
 * `ostrom cloud todo-stop|todo-context|workflow-stop` (hidden): the Stop and SessionStart hooks of Claude Code in a
 * cloud session's agent container (TeamCode's `teamcode cloud todo-stop|todo-context|workflow-stop`), which the runner
 * installs next to its own ([run.ostrom.cli.runner.CloudHookSettings]). They are the hooks of `ostrom hook`, with the
 * same decisions ([TodoHookRunner], [WorkflowStopHook]), reaching the server straight ([ServerHttp]) with the launch's
 * agent token (`OSTROM_URL`, `OSTROM_AGENT_TOKEN`) where no local relay runs; the server binds that token to its cloud
 * session, so no session id is sent. They fail open as the local hooks do: they exit 0 and print nothing but their
 * answer, whatever fails (no variable, no answer from the server). The todo's Stop is never held for a wait's answer
 * (`--wake`): in a pod an answer arrives as a queued message.
 *
 * @param environment The process environment.
 * @param console The hook's streams.
 * @param client Makes the HTTP calls; closed when the hook ends.
 * @param files Reads the transcript the Stop hook checks.
 * @param capture Runs the todo's checks.
 * @param io Where the Stop hook's blocking calls run.
 */
class CloudHooks(
    private val environment: Environment,
    private val console: Console,
    private val client: HttpClient,
    private val files: FileSystem,
    private val capture: ProcessCapture,
    private val io: CoroutineDispatcher,
) {
    /**
     * Runs the hook [command] on the event on stdin.
     *
     * @param command One of [COMMANDS].
     *
     * @return The exit code: 0, always.
     */
    fun run(command: String): Int = client.use {
        val url = environment[McpForwarder.URL_VARIABLE]?.trimEnd('/')
        if (url == null) return 0
        val http = ServerHttp(client, url)
        val token = { environment[McpForwarder.TOKEN_VARIABLE] }
        when (command) {
            CloudProcess.WORKFLOW_STOP ->
                WorkflowStopHook(console, environment, http, { null }, direct = token).run()
            else -> TodoHookRunner(console, environment, files, http, capture, io, token)
                .run(if (command == CloudProcess.TODO_CONTEXT) TodoHookRunner.CONTEXT else TodoHookRunner.STOP, null)
        }
    }

    /** The commands. */
    companion object {
        /** The commands the CLI serves here, after `cloud`. */
        val COMMANDS: Set<String> = setOf(CloudProcess.TODO_STOP, CloudProcess.TODO_CONTEXT, CloudProcess.WORKFLOW_STOP)
    }
}
