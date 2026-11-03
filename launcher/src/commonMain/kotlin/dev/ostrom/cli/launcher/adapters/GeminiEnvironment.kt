// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath

/**
 * The variables Gemini gets from Ostrom, and the cases where it is not started (Gemini CLI 0.66, security audit of the
 * CLI n°2, H3). Gemini loads the folder's `.env`, `.gemini/.env` included, without replacing a variable already set,
 * then runs each hook by `bash -c` (PowerShell on Windows) with that environment: a `.env` naming `BASH_ENV`, or
 * `SHELLOPTS` with `noexec`, stops Ostrom's gate before it runs, and an untrusted folder runs none of the settings'
 * hooks. So Ostrom sets each variable that decides the folder's trust or changes the shell, the loader under it or
 * Node, and every `OSTROM_` one: the launcher's value, else the developer's, else empty.
 */
internal object GeminiEnvironment {
    /**
     * @param real The developer's `.gemini` folder, where Gemini finds their trusted folders.
     * @throws AdapterException When Ostrom cannot vouch for its gate: a variable of the developer's that takes the
     * trust away or runs before the hooks, no `PATH`, this binary's path unknown, or a folder the developer does not
     * trust.
     */
    fun of(layout: SessionLayout, files: LaunchFiles, real: String): Map<String, String> {
        val untrusting = UNTRUSTING.firstOrNull { (name, value) -> layout.variable(name) == value }
        if (untrusting != null) {
            refuse(
                "${untrusting.first}=${untrusting.second} takes the trust away from this folder, and Gemini then " +
                    "runs none of the settings' hooks, Ostrom's included, so it is not started. Unset it to work " +
                    "with Ostrom",
            )
        }
        val before = REFUSED.firstOrNull { layout.variable(it) != null }
        if (before != null) {
            refuse(
                "$before is set in your environment, and it changes what runs before Ostrom's hooks: Ostrom cannot " +
                    "vouch for its gate, so Gemini is not started. Unset it to work with Ostrom",
            )
        }
        if (!layout.executable.toPath().isAbsolute) {
            refuse(
                "Ostrom does not know the full path of its own binary (${layout.executable}), and Gemini would look " +
                    "it up in PATH to run Ostrom's hooks, so it is not started. Run Ostrom by its full path to work " +
                    "with Ostrom",
            )
        }
        refuseDistrust(layout, files, real)
        val pinned = (TRUST + SHELL + LaunchVariables.ALL).associateWith { name ->
            if (name in layout.environment) layout.environment[name].orEmpty() else layout.variable(name).orEmpty()
        }
        if (pinned.getValue(PATH).isEmpty()) {
            refuse(
                "PATH is not set, and Gemini looks bash up in it to run Ostrom's hooks, so a repository's .env could " +
                    "name another: Gemini is not started. Set PATH to work with Ostrom",
            )
        }
        return pinned
    }

    /**
     * Stops the launch in a folder the developer's trusted folders do not trust (`DO_NOT_TRUST`), matched as Gemini
     * matches them: the folder and each rule with their links followed (a path that does not resolve is kept as
     * written), the longest rule holding the folder wins, `TRUST_PARENT` names the rule's parent, and case is not told
     * apart (it is not on macOS and Windows).
     */
    private fun refuseDistrust(layout: SessionLayout, files: LaunchFiles, real: String) {
        val path = layout.variable(TRUSTED_FOLDERS) ?: join(real, "trustedFolders.json")
        val text = files.read(path)
        if (text.isNullOrBlank()) return
        val parsed = parseObject(text, comments = true) ?: refuse(
            "$path cannot be read as JSON: Ostrom cannot tell whether it takes the trust away from this folder, so " +
                "Gemini is not started",
        )
        // Gemini takes a TRUST_PARENT rule's parent as written, then follows its links
        val rules = parsed.entries.map { (rule, level) ->
            Triple(rule, level, files.canonical(if (level == PARENT) parent(rule) else rule))
        }
        for (folder in layout.workspace) {
            val followed = files.canonical(folder)
            val level = rules.filter { (_, _, resolved) -> within(followed, resolved) }
                .maxByOrNull { (rule, _, _) -> rule.length }?.second
            if (level == JsonPrimitive("DO_NOT_TRUST")) {
                refuse(
                    "$path does not trust $folder (DO_NOT_TRUST), and Gemini then runs none of the settings' hooks, " +
                        "Ostrom's included, so it is not started. Remove that entry to work with Ostrom",
                )
            }
        }
    }

    private fun within(folder: String, rule: String): Boolean {
        val parent = rule.trimEnd('/', '\\').lowercase()
        val child = folder.trimEnd('/', '\\').lowercase()
        return child == parent || child.startsWith("$parent/") || child.startsWith("$parent\\")
    }

    private fun parent(rule: String): String {
        val trimmed = rule.trimEnd('/', '\\')
        return trimmed.substring(0, maxOf(trimmed.lastIndexOfAny(charArrayOf('/', '\\')), 0))
    }

    private fun refuse(reason: String): Nothing = throw AdapterException("gemini: $reason")

    private const val PATH = "PATH"
    private const val TRUSTED_FOLDERS = "GEMINI_CLI_TRUSTED_FOLDERS_PATH"
    private val PARENT = JsonPrimitive("TRUST_PARENT")

    // what takes the trust away from a folder: restricted mode, or the workspace's trust turned off
    private val UNTRUSTING = listOf("GEMINI_RESTRICTED_MODE" to "true", "GEMINI_CLI_TRUST_WORKSPACE" to "false")

    // what decides the folder's trust: the two above, the trusted folders' file, and an IDE's connection, which tells
    // Gemini whether the IDE trusts the folder
    private val TRUST = listOf(
        "GEMINI_RESTRICTED_MODE",
        "GEMINI_CLI_TRUST_WORKSPACE",
        TRUSTED_FOLDERS,
        "GEMINI_CLI_IDE_SERVER_PORT",
        "GEMINI_CLI_IDE_SERVER_STDIO_COMMAND",
        "GEMINI_CLI_IDE_SERVER_STDIO_ARGS",
        "GEMINI_CLI_IDE_WORKSPACE_PATH",
        "GEMINI_CLI_IDE_AUTH_TOKEN",
        "GEMINI_CLI_IDE_PID",
    )

    // what runs before the hooks whatever their command: the files and options of the shell, the loader's libraries
    private val REFUSED = listOf(
        "BASH_ENV",
        "ENV",
        "SHELLOPTS",
        "BASHOPTS",
        "LD_PRELOAD",
        "LD_AUDIT",
        "DYLD_INSERT_LIBRARIES",
    )

    // what changes the shell that runs the hooks, where it is found, the loader under it, Node, or Gemini's sandbox
    private val SHELL = listOf(
        PATH,
        "BASH_ENV",
        "ENV",
        "SHELLOPTS",
        "BASHOPTS",
        "CDPATH",
        "GLOBIGNORE",
        "IFS",
        "PS4",
        "LD_PRELOAD",
        "LD_AUDIT",
        "LD_LIBRARY_PATH",
        "DYLD_INSERT_LIBRARIES",
        "DYLD_LIBRARY_PATH",
        "DYLD_FRAMEWORK_PATH",
        "NODE_OPTIONS",
        "NODE_PATH",
        "GEMINI_SANDBOX",
        "ComSpec",
    )
}
