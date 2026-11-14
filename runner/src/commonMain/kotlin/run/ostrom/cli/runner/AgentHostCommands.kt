// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [PreparationCommands] in a session pod: each command is started by the agent host, in the agent's container and
 * under its uid, where the runner token is not, so nothing the agent wrote on the workspace (a repository's git
 * configuration, a dependency's install script) runs beside that token. The host writes the log; the masked lines it
 * sends back go to [output] (the session's terminal).
 *
 * The arguments reach the script through its environment, never through its text.
 *
 * @param start Starts a command on the agent host ([RemoteTerminalStarter.command]).
 * @param logPath The preparation log of this execution.
 * @param output Takes the masked output.
 */
class AgentHostCommands(
    private val start: suspend (String, Map<String, String>, String, PreparationLog) -> Terminal,
    private val logPath: String,
    private val output: suspend (ByteArray) -> Unit,
) : PreparationCommands {
    override suspend fun run(
        arguments: List<String>,
        directory: String,
        environment: Map<String, String>,
        secrets: Set<String>,
    ): Int? {
        val variables = arguments.withIndex().associate { (index, argument) -> "$ARGUMENT$index" to argument }
        val terminal = start(
            SCRIPT,
            environment + variables + (COUNT to arguments.size.toString()),
            directory,
            PreparationLog(logPath, secrets.sorted()),
        )
        try {
            while (true) output(terminal.read() ?: break)
            return terminal.exitCode
        } finally {
            withContext(NonCancellable) {
                if (terminal.exitCode == null) terminal.terminate()
                terminal.close()
            }
        }
    }

    /** Constants of the enclosing type. */
    companion object {
        /** The number of arguments. */
        const val COUNT = "OSTROM_PREPARE_ARGC"

        /** The prefix of each argument's variable, then its index from 0. */
        const val ARGUMENT = "OSTROM_PREPARE_ARG_"

        /**
         * Rebuilds the arguments from the environment (only an index is ever evaluated), removes their variables, then
         * runs them with stdin from `/dev/null` and stderr with stdout.
         */
        const val SCRIPT = """
n=${'$'}{OSTROM_PREPARE_ARGC:-0}
i=0
set --
while [ "${'$'}i" -lt "${'$'}n" ]; do
  eval "set -- \"\${'$'}@\" \"\${'$'}OSTROM_PREPARE_ARG_${'$'}i\""
  unset "OSTROM_PREPARE_ARG_${'$'}i"
  i=${'$'}((i + 1))
done
unset OSTROM_PREPARE_ARGC n i
exec "${'$'}@" 2>&1 </dev/null
"""
    }
}
