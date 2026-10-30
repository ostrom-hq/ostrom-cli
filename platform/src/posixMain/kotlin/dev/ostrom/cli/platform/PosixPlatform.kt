@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.chmod
import platform.posix.getenv
import dev.ostrom.cli.platform.interop.ostrom_capture
import dev.ostrom.cli.platform.interop.ostrom_is_terminal
import dev.ostrom.cli.platform.interop.ostrom_read_stdin
import dev.ostrom.cli.platform.interop.ostrom_spawn_detached
import dev.ostrom.cli.platform.interop.ostrom_spawn_wait
import dev.ostrom.cli.platform.interop.ostrom_tcp_exchange
import dev.ostrom.cli.platform.interop.ostrom_write

internal actual fun spawnAndWait(command: List<String>): ProcessResult = memScoped {
    val argv = allocArrayOf(command.map { it.cstr.ptr } + listOf(null))
    val exitCode = alloc<IntVar>()
    when (ostrom_spawn_wait(command.first(), argv, exitCode.ptr)) {
        0 -> ProcessResult.Exited(exitCode.value)
        1 -> ProcessResult.NotFound
        else -> ProcessResult.Failed("could not start ${command.first()}")
    }
}

internal actual fun writeBytes(descriptor: Int, bytes: ByteArray) {
    bytes.usePinned { ostrom_write(descriptor, it.addressOf(0).reinterpret(), bytes.size) }
}

internal actual fun isTerminal(descriptor: Int): Boolean = ostrom_is_terminal(descriptor) == 1

internal actual fun readStdin(buffer: ByteArray, timeoutMillis: Int): Int =
    buffer.usePinned { ostrom_read_stdin(it.addressOf(0).reinterpret(), buffer.size, timeoutMillis) }

internal actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()

internal actual fun restrictToOwner(path: String) {
    chmod(path, OWNER_READ_WRITE.convert())
}

/** Mode 0600: read and write for the owner only. */
private const val OWNER_READ_WRITE = 0x180

internal actual fun captureOutput(
    command: List<String>,
    capacity: Int,
    timeoutMillis: Int,
): CaptureResult = memScoped {
    val argv = allocArrayOf(command.map { it.cstr.ptr } + listOf(null))
    val exitCode = alloc<IntVar>()
    val output = ByteArray(capacity)
    val count = output.usePinned {
        ostrom_capture(command.first(), argv, it.addressOf(0).reinterpret(), capacity, timeoutMillis, exitCode.ptr)
    }
    captureResultOf(count, exitCode.value, output)
}

internal actual fun spawnDetached(command: List<String>): Boolean = memScoped {
    val argv = allocArrayOf(command.map { it.cstr.ptr } + listOf(null))
    ostrom_spawn_detached(command.first(), argv) == 0
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
