// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package run.ostrom.cli.runner.conversation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.wcstr
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import run.ostrom.cli.platform.quoteWindowsArgument
import run.ostrom.cli.runner.ProcessStarter
import run.ostrom.cli.runner.Terminal
import run.ostrom.cli.runner.windows.ostrom_agent_spawn
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.Platform

/**
 * The agent of a remote session on this device (TeamCode's Windows `startAgentProcess`): Windows has no `sh`, so the
 * starter does what [ExecProcesses.EXEC_SCRIPT] says itself (the agent's `HOME` created, then its command run with its
 * arguments), the program found on the agent's `PATH` as TeamCode finds it (an `.exe`, or the entry point behind an
 * npm `.cmd` wrapper, never the wrapper through `cmd.exe`). The child has pipes for stdin and stdout, stderr on NUL, no
 * console window, and runs in a job object of its own ([WindowsAgentTerminal]).
 */
actual fun localAgentProcesses(): ProcessStarter = WindowsAgentProcesses

/** `GetEnvironmentStringsW`, read once per call (UTF-16, so a non-ASCII profile path stays intact). */
actual fun processEnvironment(): Map<String, String> {
    val variables = mutableMapOf<String, String>()
    val block = GetEnvironmentStringsW() ?: return variables
    try {
        var offset = 0
        while (block[offset] != 0.toUShort()) {
            val entry = checkNotNull(block + offset).toKStringFromUtf16()
            offset += entry.length + 1
            // `=C:=C:\dir` (a drive's current directory) has its name's `=` first.
            val separator = entry.indexOf('=', startIndex = 1)
            if (separator > 0) variables[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
    } finally {
        FreeEnvironmentStringsW(block)
    }
    return variables
}

/** Starts [ExecProcesses]' agents on Windows. */
internal object WindowsAgentProcesses : ProcessStarter {
    override suspend fun start(
        script: String,
        arguments: List<String>,
        environment: Map<String, String>,
        directory: String,
    ): Terminal {
        require(script == ExecProcesses.EXEC_SCRIPT && arguments.isNotEmpty()) {
            "Windows starts the agent's command only, without a shell script."
        }
        // `mkdir -p "$HOME" 2>/dev/null`: a home that cannot be made is the agent's to report.
        windowsVariable(environment, "HOME")?.takeIf { it.isNotBlank() }?.let { home ->
            try {
                FileSystem.SYSTEM.createDirectories(home.toPath())
            } catch (_: IOException) {
                // As the shell's `2>/dev/null`.
            }
        }
        val command = arguments.first()
        val path = windowsVariable(environment, "PATH")
        val (application, actualArguments) = resolveAgent(command, arguments.drop(1), path)
        val line = (listOf(application) + actualArguments).joinToString(" ", transform = ::quoteWindowsArgument)
        if (line.length >= MAX_COMMAND_LINE) throw AgentStartException(AgentStartException.Reason.TOO_LONG, command)
        val block = windowsEnvironmentBlock(environment)
        val handle = ostrom_agent_spawn(application.wcstr, line.wcstr, block.wcstr, directory.wcstr)
            ?: throw AgentStartException(AgentStartException.Reason.CANNOT_LAUNCH, command)
        return WindowsAgentTerminal(handle)
    }

    /** `CreateProcessW`'s limit, in UTF-16 units with the final NUL. */
    private const val MAX_COMMAND_LINE = 32_767
}

/** [command] as an `.exe`: as given when it has a folder, else the first of the [path] folders that holds it. */
private fun executable(command: String, path: String?): String? {
    val names = if (command.endsWith(".exe", ignoreCase = true)) listOf(command) else listOf("$command.exe")
    val roots = if (command.toPath().isAbsolute || '/' in command || '\\' in command) {
        listOf("")
    } else {
        folders(path).map { "$it\\" }
    }
    return roots.flatMap { root -> names.map { root + it } }.firstOrNull(::isFile)
}

private fun folders(path: String?): List<String> =
    path.orEmpty().split(';').filter { it.isNotBlank() }.map { it.trim('"') }

private fun isFile(file: String): Boolean = FileSystem.SYSTEM.metadataOrNull(file.toPath())?.isRegularFile == true

/**
 * The program and arguments that start [command] (TeamCode's `resolve`): its `.exe` on [path], or the entry point of
 * the npm installation whose `<command>.cmd` is on [path] ([npmLaunchCandidates]), run by the `node.exe` beside it or
 * on [path] when it is a script. The `.cmd` wrapper itself is never evaluated nor run through a shell.
 *
 * @throws AgentStartException When nothing starts it.
 */
internal fun resolveAgent(command: String, arguments: List<String>, path: String?): Pair<String, List<String>> {
    val architecture = if (Platform.cpuArchitecture == CpuArchitecture.ARM64) "arm64" else "x64"
    val candidates = npmLaunchCandidates(command, architecture)
    var nodeMissing = false
    // The candidates in PATH order, each wrapper's folder first: a script is run by the node.exe beside it, or on PATH.
    val launches = folders(path).asSequence()
        .map { it.toPath() }
        .filter { isFile(it.resolve("$command.cmd").toString()) }
        .flatMap { root -> candidates.asSequence().map { root to it } }
        .filter { (root, candidate) -> isFile(root.resolve(candidate.relativePath).toString()) }
        .mapNotNull { (root, candidate) ->
            val file = root.resolve(candidate.relativePath).toString()
            if (!candidate.needsNode) return@mapNotNull file to arguments
            val node = executable(root.resolve("node.exe").toString(), path) ?: executable("node", path)
            if (node == null) nodeMissing = true
            node?.let { it to (listOf(file) + arguments) }
        }
    return executable(command, path)?.let { it to arguments } ?: launches.firstOrNull() ?: throw AgentStartException(
        if (nodeMissing) AgentStartException.Reason.NODE_MISSING else AgentStartException.Reason.NOT_FOUND,
        command,
    )
}
