// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.Folders
import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.LaunchVariables
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath

/**
 * The variables Gemini gets from Ostrom, and the cases where it is not started (Gemini CLI 0.66, security audit of the
 * CLI n°2, H3). Gemini loads the folder's `.env`, `.gemini/.env` included, without replacing a variable already set,
 * then runs each hook by `bash -c` (PowerShell on Windows) with that environment: a `.env` naming `BASH_ENV`, or
 * `SHELLOPTS` with `noexec`, stops Ostrom's gate before it runs, and an untrusted folder runs none of the settings'
 * hooks. By default the session loads no `.env` of the repository's (review REVUES-CLI-6): its settings skip the
 * `.env` files ([GeminiAdapter]), and Gemini is not started where it would still load a `.gemini/.env` that is not the
 * developer's. With `--trust-project-settings` it loads the repository's, as before. In both cases Ostrom sets each
 * variable that decides the folder's trust, the ones that change a program the agent or its hooks start, shared with
 * Claude Code ([ProgramVariables]), Gemini's own, and every `OSTROM_` one: the launcher's value, else the developer's,
 * else empty. The variables that choose Gemini's account are the developer's too ([GeminiAuth]).
 */
internal object GeminiEnvironment {
    /**
     * @param real The developer's `.gemini` folder, where Gemini finds their trusted folders.
     * @throws AdapterException When Ostrom cannot vouch for its gate: a variable of the developer's that takes the
     * trust away or runs before the hooks, no `PATH`, this binary's path unknown, a folder the developer does not
     * trust, a repository's `.gemini/.env` Gemini would load, or, when the developer trusts the repository, a `.env`
     * of it naming a variable Ostrom cannot set empty.
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
        val names = TRUST + ProgramVariables.PINNED + SHELL + LaunchVariables.ALL
        val pinned = ProgramVariables.pinned(names, layout.environment, KEPT, layout.variable)
        if (layout.trustProject) {
            refuseUnpinned(layout, files, pinned.keys)
        } else {
            refuseRepositoryEnv(layout, files, real)
        }
        GeminiShell.check(layout, files, pinned.getValue(PATH), pinned.getValue(COMSPEC))
        val all = pinned + GeminiAuth.of(layout, files)
        return if (layout.pathSeparator == ';') all + GeminiShell.WINDOWS else all
    }

    /**
     * Stops the launch in a folder the developer's trusted folders do not trust (`DO_NOT_TRUST`), matched as Gemini
     * matches them: the folder and each rule with their links followed (a path that does not resolve is kept as
     * written), the longest rule holding the folder wins, `TRUST_PARENT` names the rule's parent, and case is told
     * apart except on macOS and Windows.
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
            val level = rules.filter { (_, _, resolved) -> Folders.within(followed, resolved, layout.foldsCase) }
                .maxByOrNull { (rule, _, _) -> rule.length }?.second
            if (level == JsonPrimitive("DO_NOT_TRUST")) {
                refuse(
                    "$path does not trust $folder (DO_NOT_TRUST), and Gemini then runs none of the settings' hooks, " +
                        "Ostrom's included, so it is not started. Remove that entry to work with Ostrom",
                )
            }
        }
    }

    /**
     * Stops the launch where Gemini would load a `.gemini/.env` that is not the developer's (review REVUES-CLI-6): the
     * session's `advanced.ignoreLocalEnv` skips the `.env` files only, and in a trusted folder Gemini 0.66's
     * `findEnvFile` still takes the first `.gemini/.env` from the working folder up to the root. Theirs are
     * `<home>/.gemini/.env` and the one of their `GEMINI_CLI_HOME`, compared as this machine compares paths.
     */
    private fun refuseRepositoryEnv(layout: SessionLayout, files: LaunchFiles, real: String) {
        val own = listOf(join(layout.home, ".gemini", ".env"), join(real, ".env"))
        val path = generateSequence(layout.workspace.firstOrNull()) { folder -> up(folder) }
            .map { join(it, ".gemini", ".env") }
            .firstOrNull { files.exists(it) } ?: return
        if (own.any { it.equals(path, ignoreCase = layout.foldsCase) }) return
        refuse(
            "$path is not yours: Gemini loads its variables into the session, and a repository's variables can run " +
                "its programs (its Google credentials, ripgrep's configuration), so Gemini is not started. Remove " +
                "it, or run `ostrom gemini --trust-project-settings` if your team trusts this repository, to work " +
                "with Ostrom",
        )
    }

    /**
     * With `--trust-project-settings`, stops the launch where the `.env` Gemini loads names one of [KEPT] the
     * developer does not set: Ostrom cannot pin it empty for them (an empty `GIT_DIR`
     * stops git), and Gemini would take the file's.
     *
     * The file is found as Gemini 0.66's `findEnvFile` finds it (review REVUES-CLI-5): in each folder from the working
     * folder up to the root, `.gemini/.env` then `.env`, the first that exists, past the repository's top (a submodule,
     * an archive without `.git`). The developer's own `<home>/.env` and `<home>/.gemini/.env` are theirs. A name counts
     * wherever it stands in the file as a word, whatever a parser makes of the line: a byte order mark or a no-break
     * space read as white space by Gemini's parser and not by another set a variable Ostrom had not seen. On Windows,
     * whose variables do not tell case apart, a name counts in any case.
     */
    private fun refuseUnpinned(layout: SessionLayout, files: LaunchFiles, pinned: Set<String>) {
        val own = setOf(join(layout.home, ".env"), join(layout.home, ".gemini", ".env"))
        val (path, text) = generateSequence(layout.workspace.firstOrNull()) { folder -> up(folder) }
            .flatMap { sequenceOf(join(it, ".gemini", ".env"), join(it, ".env")) }
            .firstNotNullOfOrNull { path -> files.read(path)?.let { path to it } }
            ?.takeIf { (path, _) -> path !in own } ?: return
        val windows = layout.pathSeparator == ';'
        val named = KEPT.firstOrNull { name ->
            pinned.none { it.equals(name, ignoreCase = windows) } && word(name, windows).containsMatchIn(text)
        } ?: return
        refuse(
            "$path names $named, which changes the programs Gemini and Ostrom's hooks start, and Ostrom cannot " +
                "set it for this session without breaking them, so Gemini is not started. Remove it from that file, " +
                "or set it in your environment, to work with Ostrom",
        )
    }

    /** The folder above [folder], the root's included (`/`, `C:\`), or null above the root. */
    private fun up(folder: String): String? = folder.toPath().parent?.toString()

    private fun parent(rule: String): String {
        val trimmed = rule.trimEnd('/', '\\')
        return trimmed.substring(0, maxOf(trimmed.lastIndexOfAny(charArrayOf('/', '\\')), 0))
    }

    private fun refuse(reason: String): Nothing = throw AdapterException("gemini: $reason")

    // a name as a word: not inside a longer name
    private fun word(name: String, anyCase: Boolean) = Regex(
        "(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])",
        if (anyCase) setOf(RegexOption.IGNORE_CASE) else emptySet(),
    )

    private const val PATH = "PATH"
    private const val COMSPEC = "ComSpec"
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

    // not unset when empty: git's places and editors ([ProgramVariables.KEPT]), and Gemini's telemetry target and OTLP
    // protocol, which Gemini CLI 0.66 refuses empty ("Invalid telemetry target: ."), so that no session started
    private val KEPT = ProgramVariables.KEPT + listOf("GEMINI_TELEMETRY_TARGET", "GEMINI_TELEMETRY_OTLP_PROTOCOL")

    // Gemini's own (the shared ones are [ProgramVariables]): its sandbox, the shell of its hooks on Windows, and what a
    // repository's .env could still set, from the inventory of Gemini CLI 0.66 (security audit of the CLI n°2, annex
    // A § 4): endpoints, telemetry, system prompt, sandbox, IDE, its debug mode and npm's registry
    private val SHELL = listOf(
        "GEMINI_SANDBOX",
        COMSPEC,
        "CODE_ASSIST_ENDPOINT",
        "CODE_ASSIST_API_VERSION",
        "GOOGLE_GEMINI_BASE_URL",
        "GOOGLE_VERTEX_BASE_URL",
        "GEMINI_CLI_CUSTOM_HEADERS",
        "GEMINI_TELEMETRY_ENABLED",
        "GEMINI_TELEMETRY_OTLP_ENDPOINT",
        "GEMINI_TELEMETRY_OTLP_HEADERS",
        "GEMINI_TELEMETRY_LOG_PROMPTS",
        "GEMINI_TELEMETRY_OUTFILE",
        "GEMINI_TELEMETRY_USE_COLLECTOR",
        "GEMINI_TELEMETRY_USE_CLI_AUTH",
        "GEMINI_TELEMETRY_TRACES_ENABLED",
        "OTEL_EXPORTER_OTLP_ENDPOINT",
        "OTEL_EXPORTER_OTLP_HEADERS",
        "GEMINI_CLI_ACTIVITY_LOG_TARGET",
        "GEMINI_SYSTEM_MD",
        "GEMINI_WRITE_SYSTEM_MD",
        "GEMINI_CONTEXT_TRACE_DIR",
        "GEMINI_EXP",
        "GEMINI_SANDBOX_IMAGE",
        "GEMINI_SANDBOX_PROXY_COMMAND",
        "SANDBOX",
        "SANDBOX_FLAGS",
        "SANDBOX_MOUNTS",
        "SANDBOX_ENV",
        "SANDBOX_PORTS",
        "SANDBOX_SET_UID_GID",
        "BUILD_SANDBOX",
        "SEATBELT_PROFILE",
        "DEBUG",
        "DEBUG_MODE",
        "DEBUG_PORT",
        "GEMINI_CLI_IDE_SERVER_STDIO_COMMAND",
        "GEMINI_CLI_IDE_SERVER_STDIO_ARGS",
        "GEMINI_CLI_IDE_SERVER_PORT",
        "GEMINI_CLI_IDE_AUTH_TOKEN",
        "GEMINI_CLI_IDE_PID",
        "GEMINI_CLI_EXTENSION_REGISTRY_URI",
        "GEMINI_CLI_EXP_AGENT",
        "GEMINI_CLI_INTEGRATION_TEST",
        "VITEST",
        "OAUTH_CALLBACK_HOST",
        "OAUTH_CALLBACK_PORT",
        "npm_config_registry",
        "NPM_CONFIG_REGISTRY",
    )
}
