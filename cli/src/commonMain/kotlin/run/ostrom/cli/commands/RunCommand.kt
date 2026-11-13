// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

/** The group that starts the coding agents, as TeamCode's `teamcode run`. */
internal const val RUN_COMMAND = "run"

/**
 * `ostrom run <agent> [options] [-- <agent-args>]`: start a coding agent under the team's harness. Without an agent,
 * its help, which lists them.
 */
internal class RunCommand :
    GroupCommand(
        RUN_COMMAND,
        "Start a coding agent under your team's harness; its own arguments go after --",
        comingSoon = false,
    )

/**
 * The one line a former path says on stderr before it runs: [old] (below `ostrom`) goes away in the next release,
 * [replacement] is the path to use.
 */
internal fun deprecation(old: String, replacement: String) =
    "ostrom: `ostrom $old` is deprecated and goes away in the next release: use `$replacement`\n"
