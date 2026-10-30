// SPDX-License-Identifier: Apache-2.0
// One actual per expect of the module, as in posixMain and jvmMain: their number follows the platform API.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("TooManyFunctions")

package dev.ostrom.cli.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.getenv
import platform.windows.GetModuleFileNameW
import platform.windows.SetEnvironmentVariableW
import platform.windows.WCHARVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.toKStringFromUtf16
import dev.ostrom.cli.platform.interop.ostrom_capture_w
import dev.ostrom.cli.platform.interop.ostrom_is_terminal
import dev.ostrom.cli.platform.interop.ostrom_raw_enter
import dev.ostrom.cli.platform.interop.ostrom_raw_restore
import dev.ostrom.cli.platform.interop.ostrom_read_stdin
import dev.ostrom.cli.platform.interop.ostrom_spawn_detached_w
import dev.ostrom.cli.platform.interop.ostrom_spawn_wait_w
import dev.ostrom.cli.platform.interop.ostrom_tcp_exchange
import dev.ostrom.cli.platform.interop.ostrom_terminal_read
import dev.ostrom.cli.platform.interop.ostrom_terminal_size
import dev.ostrom.cli.platform.interop.ostrom_write

internal actual fun spawnAndWait(command: List<String>, environment: Map<String, String?>): ProcessResult = memScoped {
    // CreateProcessW with no environment block gives the child this process's block, which SetEnvironmentVariableW sets
    environment.forEach { (name, value) -> SetEnvironmentVariableW(name, value) }
    val launch = windowsLaunch(command, environmentVariable("SystemRoot"))
        ?: return ProcessResult.Failed(
            "a .cmd or .bat script cannot receive a line break or a NUL, nor run from a path that holds a quote, a " +
                "line break or ends with a backslash",
        )
    val exitCode = alloc<IntVar>()
    when (ostrom_spawn_wait_w(launch.application?.wcstr, launch.commandLine.wcstr, exitCode.ptr)) {
        0 -> ProcessResult.Exited(exitCode.value)
        1 -> ProcessResult.NotFound
        else -> ProcessResult.Failed("could not start ${command.first()}")
    }
}

internal actual fun writeBytes(descriptor: Int, bytes: ByteArray) {
    bytes.usePinned { ostrom_write(descriptor, it.addressOf(0).reinterpret(), bytes.size) }
}

internal actual fun isTerminal(descriptor: Int): Boolean = ostrom_is_terminal(descriptor) == 1

internal actual fun readStdin(buffer: ByteArray, offset: Int, timeoutMillis: Int): Int =
    buffer.usePinned { ostrom_read_stdin(it.addressOf(0).reinterpret(), buffer.size, offset, timeoutMillis) }

// getenv returns the ANSI code page on Windows; the values the CLI reads (paths, URLs, flags) are ASCII in practice.
// A UTF-16 read (_wgetenv) is the follow-up if a non-ASCII profile path shows up.
internal actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()

internal actual fun restrictToOwner(path: String) = Unit

internal actual fun restrictToOwnerExecutable(path: String) = Unit

/**
 * [command] with its program resolved on the PATH alone. CreateProcessW given a bare name looks in the application's
 * folder and the current one first: a `git.exe` checked into the repository an agent works in would run instead of
 * git. A program with a folder in its name is kept as given; null when the PATH has none.
 */
private fun onThePath(command: List<String>): List<String>? {
    val program = command.first()
    val found = if ('\\' in program || '/' in program) {
        program
    } else {
        ExecutableFinderImpl(EnvironmentImpl()).find(program)
    }
    return found?.let { listOf(it) + command.drop(1) }
}

internal actual fun captureOutput(
    command: List<String>,
    capacity: Int,
    timeoutMillis: Int,
): CaptureResult = memScoped {
    val resolved = onThePath(command) ?: return CaptureResult.NotFound
    val exitCode = alloc<IntVar>()
    val output = ByteArray(capacity)
    val line = windowsCommandLine(resolved).wcstr
    val count = output.usePinned {
        ostrom_capture_w(line, it.addressOf(0).reinterpret(), capacity, timeoutMillis, exitCode.ptr)
    }
    captureResultOf(count, exitCode.value, output)
}

internal actual fun spawnDetached(command: List<String>): Boolean = memScoped {
    val resolved = onThePath(command)
    resolved != null && ostrom_spawn_detached_w(windowsCommandLine(resolved).wcstr) == 0
}

internal actual fun tcpExchange(
    port: Int,
    request: ByteArray,
    capacity: Int,
    timeoutMillis: Int,
): Pair<Int, ByteArray> {
    val response = ByteArray(capacity)
    val count = request.usePinned { sent ->
        response.usePinned { received ->
            ostrom_tcp_exchange(
                port,
                sent.addressOf(0).reinterpret(),
                request.size,
                received.addressOf(0).reinterpret(),
                capacity,
                timeoutMillis,
            )
        }
    }
    return count to response
}

/** The running binary, as this platform says it. */
actual fun currentExecutable(): String? = memScoped {
    val buffer = allocArray<WCHARVar>(MODULE_PATH_CAPACITY)
    val length = GetModuleFileNameW(null, buffer, MODULE_PATH_CAPACITY.toUInt()).toInt()
    if (length in 1 until MODULE_PATH_CAPACITY) buffer.toKStringFromUtf16() else null
}

/** Long enough for any path Windows hands out without the `\\?\` prefix. */
private const val MODULE_PATH_CAPACITY = 32_768

internal actual fun terminalEnterRaw(): Boolean = ostrom_raw_enter() == 1

internal actual fun terminalRestore() = ostrom_raw_restore()

internal actual fun terminalSize(): Long = ostrom_terminal_size()

internal actual fun terminalRead(buffer: ByteArray, timeoutMillis: Int): Int =
    buffer.usePinned { ostrom_terminal_read(it.addressOf(0).reinterpret(), buffer.size, timeoutMillis) }
