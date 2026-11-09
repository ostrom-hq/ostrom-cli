// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.LaunchVariables

/**
 * The variables Claude Code's session settings hold in their `env` (security audit of the CLI n°2, H4; review
 * REVUES-CLI-4). The `env` of a repository's `.claude/settings.json` rewrites the session's environment, the shell that
 * runs every hook included: `SHELLOPTS` with `noexec` makes Ostrom's gate a no-op that exits 0,
 * `CLAUDE_CODE_SHELL_PREFIX` runs each hook command through the repository's script, which can drop the gate and let
 * the tool through, `GIT_CONFIG_COUNT` naming `core.fsmonitor` runs the repository's script at the agent's next
 * `git status`, and `OSTROM_RELAY_URL` or `ANTHROPIC_BASE_URL` would point the gate or the inference elsewhere (probed
 * on Claude Code 2.1.317 and 2.1.318). Ostrom starts Claude Code without the repository's settings ([ClaudeAdapter]);
 * these pins hold where a team trusts them, and against the developer's own. The settings Claude Code is started with
 * (`--settings`) come before the project's, so Ostrom writes there every variable it sets or removes, every name of
 * [LaunchVariables.ALL], [ProgramVariables] (as for Gemini) and Claude Code's own shell: the launcher's value, else the
 * developer's, else empty (which Ostrom's own commands and Claude Code read as unset).
 */
internal object ClaudeEnvironment {
    /**
     * @param own The variables the adapter sets (a value) or removes (null): the provider's, the gate's budget.
     * @return Each pinned variable and its value, `""` for one Ostrom removes or the developer does not set.
     */
    fun pinned(layout: SessionLayout, own: Map<String, String?>): Map<String, String> {
        val set = layout.environment + own
        val names = set.keys + LaunchVariables.ALL + ProgramVariables.PINNED + SHELL
        return ProgramVariables.pinned(names, set, variable = layout.variable)
    }

    // Claude Code's own: the prefix of every command it runs, hooks included, and the shell it runs them with (Git
    // Bash on Windows); each is read as unset when empty (Claude Code 2.1.317)
    private val SHELL = listOf("CLAUDE_CODE_SHELL_PREFIX", "CLAUDE_CODE_GIT_BASH_PATH", "CLAUDE_CODE_SHELL")
}
