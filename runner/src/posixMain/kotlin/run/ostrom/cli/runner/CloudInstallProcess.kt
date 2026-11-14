// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import okio.FileSystem
import platform.posix.chmod
import run.ostrom.cli.platform.RawTerminalImpl
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stdout
import kotlin.system.exitProcess

/**
 * `ostrom cloud install-skills|install-hooks --agent <agent>`, in the agent's container under its uid: started by the
 * runner on a terminal of the agent host, it makes that terminal raw (no echo, no line editing, no newline
 * translation), so the lines of [CloudInstall.serve] pass as they are, then installs what the runner streams. It ends
 * with 0 once the runner ended the exchange, 1 when the runner left before (nothing was moved in), 2 for an agent that
 * gets none of [command].
 */
internal fun install(command: String, args: List<String>, environment: Map<String, String>): Nothing {
    val agent = args.indexOf("--agent").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val installer = CloudInstall.installer(command, agent, environment, FileSystem.SYSTEM) { paths ->
        for (path in paths) chmod(path.toString(), EXECUTABLE_MODE.convert())
    } ?: exitProcess(EXIT_BAD_CONFIGURATION)
    // The platform's raw mode (no echo, no line editing, no newline translation); nothing when stdin is no terminal.
    RawTerminalImpl().enterRaw()
    val ended = CloudInstall.serve(installer, ::readlnOrNull) { line ->
        fputs("$line\n", stdout)
        fflush(stdout)
    }
    exitProcess(if (ended) 0 else 1)
}

/** rwx for the agent's uid, rx for the session's group and others (0755), as TeamCode's installer. */
private const val EXECUTABLE_MODE = 0x1ED
