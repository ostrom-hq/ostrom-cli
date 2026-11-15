// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner.conversation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import run.ostrom.cli.runner.ProcessStarter
import run.ostrom.cli.runner.PtyTerminal
import run.ostrom.cli.runner.pty.ostrom_environment

/** The pod's exec starter, on this device: pipes, and a session of its own (`setsid`) the terminal's Ctrl-C misses. */
actual fun localAgentProcesses(): ProcessStarter = PtyTerminal.Exec

/** `environ`, read once per call. */
actual fun processEnvironment(): Map<String, String> {
    val block = ostrom_environment() ?: return emptyMap()
    val variables = mutableMapOf<String, String>()
    var index = 0
    while (true) {
        val entry = block[index++]?.toKString() ?: break
        val equals = entry.indexOf('=')
        if (equals > 0) variables[entry.substring(0, equals)] = entry.substring(equals + 1)
    }
    return variables
}
