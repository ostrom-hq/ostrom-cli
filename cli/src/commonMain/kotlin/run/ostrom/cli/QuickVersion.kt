// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.commands.versionLine
import run.ostrom.cli.platform.ConfigStoreImpl
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.telemetry.UsageState
import run.ostrom.cli.telemetry.UsageStatistics

/**
 * `ostrom --version` alone, answered before the dependencies and the command tree are built (hundreds of commands and
 * their collaborators, for a line that needs the environment only): the start-up budget of `--version` is held by
 * this path, as `ostrom hook` is by its own.
 *
 * It answers only when nothing would follow the command in the full path: no usage statistic (turned on, with a
 * destination in the build) and no update notice (said on a terminal only). Otherwise it leaves the line to the tree,
 * which prints the same line and does both.
 */
internal object QuickVersion {
    private const val OPTION = "--version"

    /** Whether usage statistics are on here: what a command would send one event for ([send] is never called). */
    fun usageState(
        environment: Environment,
        files: FileStore,
        postHogKey: String,
        send: suspend (url: String, body: String) -> Unit,
    ): UsageState {
        val config = environment.configDirectory?.let { ConfigStoreImpl(files, configFileIn(it)) }
        return UsageStatistics(environment, config, postHogKey, BuildInfo.VERSION, send).state()
    }

    /** Prints the version line to [out] and returns true when [args] is `--version` alone and nothing follows it. */
    fun answer(
        args: List<String>,
        environment: Environment,
        usage: () -> UsageState,
        stderrIsTerminal: () -> Boolean,
        out: (String) -> Unit,
    ): Boolean {
        val quiet = args == listOf(OPTION) && !stderrIsTerminal() && usage() != UsageState.ON
        if (quiet) out(versionLine(environment))
        return quiet
    }
}
