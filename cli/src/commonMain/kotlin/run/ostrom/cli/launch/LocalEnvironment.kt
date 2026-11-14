// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launch

import run.ostrom.cli.runner.AgentEnvironment

/**
 * What a local `ostrom run <agent>` adds to the runner's [AgentEnvironment] (the one definition of the names an agent
 * may receive, shared with cloud sessions), as TeamCode's `AgentEnvironment.localScript` and `refused`.
 */
internal object LocalEnvironment {
    /**
     * Names [AgentEnvironment.accepted] withholds, said by name so an administrator can rename them.
     *
     * @param variables The member's variables.
     *
     * @return The withheld names, sorted.
     */
    fun refused(variables: Map<String, String>): List<String> =
        (variables.keys - AgentEnvironment.accepted(variables).keys).sorted()

    /**
     * The local `CLAUDE_ENV_FILE`: sources [values] (rewritten by [EnvironmentRefresh]) before every Bash command.
     * [chained] is a script the developer had in `CLAUDE_ENV_FILE` before Ostrom's: it still runs, first, so the
     * member's values win.
     *
     * @param values The values file.
     * @param chained The developer's own script, or null.
     *
     * @return The script.
     */
    fun script(values: String, chained: String? = null): String = buildString {
        appendLine("# Written by Ostrom: reads your current assigned environment variables before each command.")
        chained?.let { appendLine("[ -f ${AgentEnvironment.quote(it)} ] && . ${AgentEnvironment.quote(it)}") }
        appendLine("[ -f ${AgentEnvironment.quote(values)} ] && . ${AgentEnvironment.quote(values)}")
    }
}
