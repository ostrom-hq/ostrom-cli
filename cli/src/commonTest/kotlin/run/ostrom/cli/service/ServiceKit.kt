// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import run.ostrom.cli.platform.secureRandomBytes

/** One OS tool call a [FakeRunner] saw. */
internal data class ToolCall(
    val command: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val quiet: Boolean,
    val timeoutMillis: Long,
) {
    /** The call without its environment, as one line. */
    val line: String get() = (listOf(command) + arguments).joinToString(" ")
}

/** The OS tools, recorded; [answer] gives the exit code of each call (0 by default). */
internal class FakeRunner(private val answer: (ToolCall) -> Int = { 0 }) : ServiceRunner {
    val calls = mutableListOf<ToolCall>()

    override suspend fun run(
        command: String,
        arguments: List<String>,
        environment: Map<String, String>,
        quiet: Boolean,
        timeoutMillis: Long,
    ): Int {
        val call = ToolCall(command, arguments, environment, quiet, timeoutMillis)
        calls += call
        return answer(call)
    }
}

/** A scratch home folder, removed afterwards: the service code works on the real file system. */
internal class ServiceScratch(val root: Path) {
    val home = root / "home"
    val config = (home / ".config" / "ostrom" / "config.toml").toString()
    val launcher = (root / "install" / "bin" / "ostrom").toString()

    fun settings(os: String = "linux", atBoot: Boolean = false, executable: String = launcher): ServiceSettings {
        val platform = ServicePlatform(os, executable, if (os == "windows") "S-1-5-21-123-456-789-1001" else "501", "fixture", home.toString())
        return newServiceSettings(config, executable, atBoot, platform, home.toString())
    }

    /** Writes service.json and the registration, as `service install` does. */
    fun install(settings: ServiceSettings): ServiceSettings {
        writePrivateFile(servicePath(settings.config), serviceJson.encodeToString(ServiceSettings.serializer(), settings))
        writeRegistration(settings)
        return settings
    }
}

internal fun serviceScratch(block: suspend TestScope.(ServiceScratch) -> Unit) = runTest {
    val fs = FileSystem.SYSTEM
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("ostrom-service-" + secureRandomBytes(12).toByteString().hex())
    fs.createDirectories(root / "home")
    try {
        block(ServiceScratch(fs.canonicalize(root)))
    } finally {
        if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root)
    }
}

/** [serviceScratch], for tests that block (a command runs its own event loop). */
internal fun blockingScratch(block: (ServiceScratch) -> Unit) {
    val fs = FileSystem.SYSTEM
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("ostrom-service-" + secureRandomBytes(12).toByteString().hex())
    fs.createDirectories(root / "home")
    try {
        block(ServiceScratch(fs.canonicalize(root)))
    } finally {
        if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root)
    }
}

/** A manager over [runner]: Windows' folder is [systemRoot], and no wait takes time unless [pause] says so. */
internal fun serviceManager(runner: ServiceRunner, systemRoot: String? = "C:\\Windows", pause: suspend (Long) -> Unit = {}) =
    ServiceManager(runner, { systemRoot }, pause)
