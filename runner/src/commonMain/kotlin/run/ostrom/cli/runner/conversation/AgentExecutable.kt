// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

/**
 * A way to start an npm-installed agent on Windows without evaluating its `.cmd` wrapper (TeamCode's
 * `NpmLaunchCandidate`).
 *
 * @property relativePath The entry point, relative to the directory holding `<command>.cmd`.
 * @property needsNode Whether it is a script `node.exe` runs.
 */
data class NpmLaunchCandidate(val relativePath: String, val needsNode: Boolean)

/**
 * Where a global npm install (the directory holding `<command>.cmd`) keeps the real entry point, best first. Current
 * claude-code releases ship a native `bin/claude.exe` (linked from a per-platform optional package); older ones shipped
 * `cli.js`, and codex still ships a `bin/codex.js` launcher.
 *
 * @param command The agent's command.
 * @param architecture `arm64` or `x64`.
 *
 * @return The candidates, best first; none for another command.
 */
fun npmLaunchCandidates(command: String, architecture: String): List<NpmLaunchCandidate> = when (command) {
    "claude" -> {
        val claudeCode = "node_modules/@anthropic-ai/claude-code"
        // Both packages are tried: the only Windows build is x64 even when it runs emulated on an ARM machine.
        val platforms = if (architecture == "arm64") {
            listOf("win32-arm64", "win32-x64")
        } else {
            listOf("win32-x64", "win32-arm64")
        }
        val nested = "$claudeCode/node_modules/@anthropic-ai/claude-code"
        platforms.map { NpmLaunchCandidate("$nested-$it/claude.exe", false) } +
            platforms.map { NpmLaunchCandidate("node_modules/@anthropic-ai/claude-code-$it/claude.exe", false) } +
            NpmLaunchCandidate("$claudeCode/bin/claude.exe", false) +
            NpmLaunchCandidate("$claudeCode/cli.js", true)
    }
    "codex" -> listOf(NpmLaunchCandidate("node_modules/@openai/codex/bin/codex.js", true))
    else -> emptyList()
}

/**
 * Why an agent could not be started on Windows (TeamCode's `ProcessStartException`); its message says what to do.
 *
 * @property reason Why.
 * @property command The agent's command.
 */
class AgentStartException(val reason: Reason, val command: String) : IllegalStateException(messageOf(reason, command)) {
    /** Why an agent could not be started. */
    enum class Reason {
        /** No executable, nor npm wrapper, on the agent's `PATH`. */
        NOT_FOUND,

        /** The npm installation is a script and no `node.exe` runs it. */
        NODE_MISSING,

        /** `CreateProcessW` refused it. */
        CANNOT_LAUNCH,

        /** The command line exceeds what Windows takes. */
        TOO_LONG,
    }

    private companion object {
        fun messageOf(reason: Reason, command: String): String = when (reason) {
            Reason.NOT_FOUND -> agentNotFoundMessage(command)
            Reason.NODE_MISSING -> "Node.js is required to run the npm installation of $command, but node.exe was " +
                "not found next to it or on PATH. Install Node.js, or install $command with its native installer."
            Reason.CANNOT_LAUNCH -> "Cannot start $command. Check that the executable exists, can be run by this " +
                "user, and is not blocked by antivirus or file permissions."
            Reason.TOO_LONG -> "The command line for $command is too long for Windows (32767 characters maximum)."
        }

        fun agentNotFoundMessage(command: String): String = when (command) {
            "claude" -> "Cannot find the claude executable on PATH. Install Claude Code (PowerShell: irm " +
                "https://claude.ai/install.ps1 | iex; or npm install -g @anthropic-ai/claude-code) and make sure its " +
                "directory is on PATH. The native installer puts claude.exe in %USERPROFILE%\\.local\\bin."
            "codex" -> "Cannot find the codex executable on PATH. Install Codex (npm install -g @openai/codex) and " +
                "make sure its directory is on PATH."
            else -> "Cannot find the \"$command\" executable on PATH. Install it and make it available on PATH."
        }
    }
}

/**
 * The environment block of a Windows child (`NAME=value` entries, sorted by upper-cased name, double-NUL terminated),
 * from [environment]: Windows names are case-insensitive, so a later entry replaces an earlier one of another case
 * (TeamCode's `windowsEnvironmentBlock`, given the whole environment as the POSIX starters are).
 *
 * @param environment The child's variables.
 *
 * @return The block.
 *
 * @throws IllegalArgumentException When a name is empty or holds `=` (after its first character) or a NUL, or a
 *   value holds a NUL.
 */
fun windowsEnvironmentBlock(environment: Map<String, String>): String {
    val values = linkedMapOf<String, Pair<String, String>>()
    environment.forEach { (name, value) ->
        // `=C:` and the like (each drive's current directory) start with `=`.
        require(name.isNotEmpty() && '=' !in name.drop(1) && '\u0000' !in name && '\u0000' !in value) {
            "Not a Windows environment variable: $name"
        }
        values[name.uppercase()] = name to value
    }
    return values.entries.sortedBy { it.key }
        .joinToString("\u0000", postfix = "\u0000\u0000") { "${it.value.first}=${it.value.second}" }
}

/**
 * A variable of [environment] whatever the case of its name (Windows names are case-insensitive: `Path` is `PATH`).
 *
 * @param environment The variables.
 * @param name The name.
 *
 * @return Its value, the last one when several cases are set; null when it is not set.
 */
fun windowsVariable(environment: Map<String, String>, name: String): String? =
    environment.entries.lastOrNull { it.key.equals(name, ignoreCase = true) }?.value
