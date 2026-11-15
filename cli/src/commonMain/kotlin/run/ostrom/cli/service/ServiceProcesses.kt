// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.ProcessCaptureImpl
import run.ostrom.cli.platform.ProcessLauncherImpl
import run.ostrom.cli.platform.ProcessResult
import run.ostrom.cli.platform.setEnvironmentVariable

/** An OS tool the service commands run (`launchctl`, `systemctl --user`, `schtasks`, `sudo`, PowerShell). */
internal fun interface ServiceRunner {
    /**
     * Runs [command] and returns its exit code.
     *
     * @param environment Variables set for the tool (the PowerShell scripts read their values from them).
     * @param quiet True for a query or an automatic step: no terminal, nothing printed. Otherwise the tool shares the
     * terminal, so that `sudo` and a masked password prompt can ask.
     * @param timeoutMillis The longest a quiet tool runs; a tool sharing the terminal waits for the person.
     */
    suspend fun run(
        command: String,
        arguments: List<String>,
        environment: Map<String, String>,
        quiet: Boolean,
        timeoutMillis: Long,
    ): Int
}

/** The real tools, run off the main thread. */
// coverage: runs real OS processes; ProcessCapture and ProcessLauncher are tested in platform, and
// SystemServiceRunnerTest runs it on a shell.
@ExcludeFromCoverage
internal object SystemServiceRunner : ServiceRunner {
    override suspend fun run(
        command: String,
        arguments: List<String>,
        environment: Map<String, String>,
        quiet: Boolean,
        timeoutMillis: Long,
    ): Int = withContext(Dispatchers.IO) {
        val line = listOf(command) + arguments
        if (quiet) {
            environment.forEach { (name, value) -> setEnvironmentVariable(name, value) }
            val timeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            exitCodeOf(command, ProcessCaptureImpl().capture(line, timeout))
        } else {
            exitCodeOf(command, ProcessLauncherImpl().run(line, environment))
        }
    }

    private fun exitCodeOf(command: String, result: CaptureResult): Int = when (result) {
        is CaptureResult.Exited -> result.code
        CaptureResult.NotFound -> throw CliktError("The OS service manager $command was not found.")
        CaptureResult.TimedOut -> throw CliktError("The OS service manager did not answer in time.")
        CaptureResult.Failed -> throw CliktError("The OS service manager could not be run.")
    }

    private fun exitCodeOf(command: String, result: ProcessResult): Int = when (result) {
        is ProcessResult.Exited -> result.code
        ProcessResult.NotFound -> throw CliktError("The OS service manager $command was not found.")
        is ProcessResult.Failed -> throw CliktError("The OS service manager could not be run.")
    }
}
