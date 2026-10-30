package run.ostrom.cli.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.OsFamily
import kotlin.native.Platform

/** The operating system families the CLI ships for. */
enum class OperatingSystem(val displayName: String) {
    MACOS("macOS"),
    LINUX("Linux"),
    WINDOWS("Windows"),
}

/** The process environment and the machine it runs on. */
interface Environment {
    /** The value of the environment variable [name], or null when it is unset or empty. */
    operator fun get(name: String): String?

    /** The operating system of this binary. */
    val operatingSystem: OperatingSystem

    /** The CPU architecture of this binary, lower case (`arm64`, `x64`). */
    val architecture: String

    /** The user's home folder, or null when the environment does not say. */
    val homeDirectory: String?
        get() = if (operatingSystem == OperatingSystem.WINDOWS) get("USERPROFILE") ?: get("HOME") else get("HOME")

    /**
     * The folder of the CLI's own files: `$XDG_CONFIG_HOME/ostrom`, else `~/.config/ostrom` (spec CLI.md § 2.2), and
     * `%APPDATA%\ostrom` on Windows. Null when no home is known.
     */
    val configDirectory: String?
        get() = when {
            operatingSystem == OperatingSystem.WINDOWS -> (get("APPDATA") ?: homeDirectory?.let { "$it\\AppData\\Roaming" })?.let { "$it\\ostrom" }
            get("XDG_CONFIG_HOME") != null -> "${get("XDG_CONFIG_HOME")}/ostrom"
            else -> homeDirectory?.let { "$it/.config/ostrom" }
        }

    /** The separator of the PATH list: `;` on Windows, `:` elsewhere. */
    val pathListSeparator: Char
        get() = if (operatingSystem == OperatingSystem.WINDOWS) ';' else ':'
}

/** [Environment] read from the real process. */
@OptIn(ExperimentalNativeApi::class)
class EnvironmentImpl : Environment {
    override fun get(name: String): String? = environmentVariable(name)?.takeIf { it.isNotEmpty() }

    override val operatingSystem: OperatingSystem = when (Platform.osFamily) {
        OsFamily.MACOSX -> OperatingSystem.MACOS
        OsFamily.WINDOWS -> OperatingSystem.WINDOWS
        else -> OperatingSystem.LINUX
    }

    override val architecture: String = when (Platform.cpuArchitecture) {
        CpuArchitecture.ARM64 -> "arm64"
        CpuArchitecture.X64 -> "x64"
        else -> Platform.cpuArchitecture.name.lowercase()
    }
}

/** Reads an environment variable of this process. */
internal expect fun environmentVariable(name: String): String?
