// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.usleep
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native Unix socket against a real server on macOS and Linux: a Python child listens, reads one line, answers it
 * and closes, as a session's runner does for a check.
 */
class UnixSocketsPosixTest {
    // short: a socket path holds about a hundred bytes, and the temporary folder of macOS is long
    private val folder = "/tmp/ostrom-us-${Random.nextInt(0, Int.MAX_VALUE)}"
    private val socket = "$folder/s.sock"

    init {
        FileSystem.SYSTEM.createDirectories(folder.toPath())
    }

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder.toPath(), mustExist = false)

    @Test
    fun aRequestIsAnsweredOverARealSocket() {
        check(spawnDetached(listOf("python3", "-c", SERVER, socket)))
        val sockets = UnixSocketsImpl()
        // the child binds the socket in a moment: try again until it does, for at most five seconds
        var answer = sockets.exchange(socket, "{\"scriptId\":\"s\"}\n", TIMEOUT_MILLIS)
        var attempts = 1
        while (answer.isFailure && attempts < WAITS) {
            usleep(PAUSE_MICROS)
            answer = sockets.exchange(socket, "{\"scriptId\":\"s\"}\n", TIMEOUT_MILLIS)
            attempts++
        }
        assertEquals("answered {\"scriptId\":\"s\"}\n", answer.getOrThrow())
    }

    @Test
    fun aPathLongerThanASocketAddressReachesNothing() {
        val result = UnixSocketsImpl().exchange("/tmp/" + "x".repeat(LONG), "{}\n", TIMEOUT_MILLIS)
        assertEquals(UnixSocketFailure.UNREACHABLE, (result.exceptionOrNull() as UnixSocketException).failure)
    }

    private companion object {
        const val WAITS = 50
        const val PAUSE_MICROS = 100_000u
        const val TIMEOUT_MILLIS = 2_000
        const val LONG = 200
        val SERVER = """
            import socket, sys
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            server.bind(sys.argv[1])
            server.listen(1)
            server.settimeout(10)
            connection, _ = server.accept()
            line = b""
            while not line.endswith(b"\n"):
                chunk = connection.recv(4096)
                if not chunk:
                    break
                line += chunk
            connection.sendall(b"answered " + line)
            connection.close()
            server.close()
        """.trimIndent()
    }
}
