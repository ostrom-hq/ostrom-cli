package dev.ostrom.cli.platform

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

// The JVM target only runs the common tests and measures their coverage (CONVENTIONS.md section 7.2); it is never
// shipped. These actuals let commonMain run there; the shipped ones are in posixMain, mingwMain and nativeMain.

// coverage: test-only JVM target; the shipped native actual is tested in posixTest and mingwTest.
@ExcludeFromCoverage
internal actual fun spawnAndWait(command: List<String>, environment: Map<String, String?>): ProcessResult = try {
    val builder = ProcessBuilder(command).inheritIO()
    environment.forEach { (name, value) ->
        if (value == null) builder.environment().remove(name) else builder.environment()[name] = value
    }
    ProcessResult.Exited(builder.start().waitFor())
} catch (_: IOException) {
    ProcessResult.NotFound
}

// coverage: test-only JVM target; the shipped native actual is exercised by every native test binary.
@ExcludeFromCoverage
internal actual fun writeBytes(descriptor: Int, bytes: ByteArray) {
    val stream = if (descriptor == 2) System.err else System.out
    stream.write(bytes)
    stream.flush()
}

// coverage: test-only JVM target; the shipped native actual is tested in posixTest and mingwTest.
@ExcludeFromCoverage
internal actual fun isTerminal(descriptor: Int): Boolean = System.console() != null

// coverage: test-only JVM target; the shipped native actual is measured by the start-up test of the real binary.
@ExcludeFromCoverage
internal actual fun readStdin(buffer: ByteArray, timeoutMillis: Int): Int {
    // System.in has no deadline: read on a daemon thread and give up at the deadline, as the native shim does.
    var result = READ_TIMEOUT
    val reader = Thread { result = readAllOfStdin(buffer) }
    reader.isDaemon = true
    reader.start()
    reader.join(timeoutMillis.toLong())
    return if (reader.isAlive) READ_TIMEOUT else result
}

// coverage: test-only JVM target, see readStdin.
@ExcludeFromCoverage
private fun readAllOfStdin(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val count = System.`in`.read(buffer, total, buffer.size - total)
        if (count < 0) return total
        total += count
    }
    return READ_TOO_LARGE
}

// coverage: test-only JVM target; the shipped native actual is tested by EnvironmentTest on each target.
@ExcludeFromCoverage
internal actual fun environmentVariable(name: String): String? = System.getenv(name)

// coverage: test-only JVM target; the shipped native actual is tested by EnvironmentTest on each target.
@ExcludeFromCoverage
internal actual fun currentOperatingSystem(): OperatingSystem {
    val name = System.getProperty("os.name").lowercase()
    return when {
        name.startsWith("mac") -> OperatingSystem.MACOS
        name.startsWith("windows") -> OperatingSystem.WINDOWS
        else -> OperatingSystem.LINUX
    }
}

// coverage: test-only JVM target; the shipped native actual is tested by EnvironmentTest on each target.
@ExcludeFromCoverage
internal actual fun currentArchitecture(): String = when (val arch = System.getProperty("os.arch")) {
    "aarch64" -> "arm64"
    "amd64", "x86_64" -> "x64"
    else -> arch
}

// coverage: test-only JVM target; the shipped native actual is tested by OwnerOnlyTest on macOS and Linux.
@ExcludeFromCoverage
internal actual fun restrictToOwnerExecutable(path: String) {
    val file = Paths.get(path)
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    }
}

// coverage: test-only JVM target; the shipped native actual is tested by FileStoreTest on macOS and Linux.
@ExcludeFromCoverage
internal actual fun restrictToOwner(path: String) {
    val file = Paths.get(path)
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }
}

// coverage: test-only JVM target; the shipped native actual is tested in posixTest and mingwTest.
@ExcludeFromCoverage
internal actual fun captureOutput(
    command: List<String>,
    capacity: Int,
    timeoutMillis: Int,
): CaptureResult = try {
    val process = ProcessBuilder(command).redirectErrorStream(false).start()
    val output = process.inputStream.readBytes()
    if (!process.waitFor(timeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        CaptureResult.TimedOut
    } else {
        CaptureResult.Exited(process.exitValue(), output.decodeToString(0, minOf(output.size, capacity)))
    }
} catch (_: IOException) {
    CaptureResult.NotFound
}

// coverage: test-only JVM target; the shipped native actual is tested in posixTest and mingwTest.
@ExcludeFromCoverage
internal actual fun spawnDetached(command: List<String>): Boolean = try {
    ProcessBuilder(command).start()
    true
} catch (_: IOException) {
    false
}

// coverage: test-only JVM target; the shipped native actual is tested in posixTest and by the end-to-end bench.
@ExcludeFromCoverage
internal actual fun tcpExchange(
    port: Int,
    request: ByteArray,
    capacity: Int,
    timeoutMillis: Int,
): Pair<Int, ByteArray> = try {
    java.net.Socket().use { socket ->
        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        socket.getOutputStream().write(request)
        val bytes = socket.getInputStream().readBytes()
        (if (bytes.size >= capacity) TCP_TOO_LARGE else bytes.size) to bytes
    }
} catch (_: java.net.SocketTimeoutException) {
    TCP_TIMED_OUT to ByteArray(0)
} catch (_: IOException) {
    TCP_UNREACHABLE to ByteArray(0)
}

/** The running JVM's command. */
// coverage: test-only JVM target; the shipped native actuals are tested by ExecutableTest on each target.
@ExcludeFromCoverage
actual fun currentExecutable(): String? = ProcessHandle.current().info().command().orElse(null)
