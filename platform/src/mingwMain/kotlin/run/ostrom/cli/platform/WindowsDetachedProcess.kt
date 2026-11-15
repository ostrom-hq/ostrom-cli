// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import okio.IOException
import platform.windows.CloseHandle
import platform.windows.GetLastError
import run.ostrom.cli.platform.interop.ostrom_detached_poll_w
import run.ostrom.cli.platform.interop.ostrom_spawn_logged_w

/** A process of [startDetachedProcess] on Windows, its end read from its handle, closed once the end is known. */
private class WindowsDetachedProcess(private val handle: COpaquePointer) : DetachedProcess {
    private var exit: Int? = null

    override fun exitCode(): Int? {
        exit?.let { return it }
        memScoped {
            val code = alloc<UIntVar>()
            when (ostrom_detached_poll_w(handle, code.ptr)) {
                1 -> exit = code.value.toInt()
                -1 -> exit = UNKNOWN
            }
        }
        // The daemon starts a host per session for as long as it runs: an ended one keeps no handle.
        if (exit != null) CloseHandle(handle)
        return exit
    }

    private companion object {
        const val UNKNOWN = -1
    }
}

/**
 * `CreateProcessW` without a console, in a process group of its own and out of this process's job where it may be,
 * stdin on NUL and its output appended to [log], which the caller created (TeamCode's `startDetachedProcess`). The
 * executable is [command]'s first element as it is: a full path, never looked up on the `PATH`.
 */
actual fun startDetachedProcess(command: List<String>, log: String): DetachedProcess {
    require(command.isNotEmpty()) { "A command needs at least its executable." }
    val line = command.joinToString(" ", transform = ::quoteWindowsArgument)
    if (line.length >= MAX_COMMAND_LINE) throw IOException("${command.first()}: the command line is too long.")
    val handle = ostrom_spawn_logged_w(command.first().wcstr, line.wcstr, log.wcstr)
        ?: throw IOException("${command.first()} could not be started (error ${GetLastError()}).")
    return WindowsDetachedProcess(handle)
}

/** `CreateProcessW`'s limit, in UTF-16 units with the final NUL. */
private const val MAX_COMMAND_LINE = 32_767
