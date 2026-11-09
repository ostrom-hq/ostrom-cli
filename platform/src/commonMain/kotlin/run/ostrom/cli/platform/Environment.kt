// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/**
 * The operating system families the CLI ships for.
 *
 * @property foldsCase Whether its paths ignore case, as its default file systems do (and Gemini CLI compares them).
 */
enum class OperatingSystem(val displayName: String, val foldsCase: Boolean) {
    MACOS("macOS", true),
    LINUX("Linux", false),
    WINDOWS("Windows", true),
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
     * The folder of the CLI's own files: `$OSTROM_HOME` when set (tests, the end-to-end bench), else
     * `$XDG_CONFIG_HOME/ostrom`, else `~/.config/ostrom` (spec CLI.md § 2.2), and `%APPDATA%\ostrom` on Windows.
     * Null when no home is known.
     */
    val configDirectory: String?
        get() {
            val override = get("OSTROM_HOME")
            val appData = get("APPDATA")
            val xdg = get("XDG_CONFIG_HOME")
            val home = homeDirectory
            val windows = operatingSystem == OperatingSystem.WINDOWS
            return when {
                override != null -> override
                windows && appData != null -> "$appData\\ostrom"
                windows && home != null -> "$home\\AppData\\Roaming\\ostrom"
                windows -> null
                xdg != null -> "$xdg/ostrom"
                home != null -> "$home/.config/ostrom"
                else -> null
            }
        }

    /** The separator of the PATH list: `;` on Windows, `:` elsewhere. */
    val pathListSeparator: Char
        get() = if (operatingSystem == OperatingSystem.WINDOWS) ';' else ':'
}

/**
 * [Environment] read from the real process. An empty variable counts as unset, as in the prototype.
 *
 * @param lookup Reads one variable; the process environment by default.
 */
class EnvironmentImpl(private val lookup: (String) -> String? = ::environmentVariable) : Environment {
    override fun get(name: String): String? = lookup(name)?.takeIf { it.isNotEmpty() }

    override val operatingSystem: OperatingSystem = currentOperatingSystem()

    override val architecture: String = currentArchitecture()
}

/** Reads an environment variable of this process. */
internal expect fun environmentVariable(name: String): String?

/** The operating system this binary was built for. */
internal expect fun currentOperatingSystem(): OperatingSystem

/** The CPU architecture this binary was built for, lower case (`arm64`, `x64`). */
internal expect fun currentArchitecture(): String
