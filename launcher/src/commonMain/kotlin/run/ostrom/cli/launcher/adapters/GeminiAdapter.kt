// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.FileAccess
import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.LaunchVariables
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.jsonOf
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.pretty
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.random.nextULong

/**
 * Gemini CLI: a `GEMINI_CLI_HOME` made for the session, its `.gemini` linking the developer's, `settings.json` theirs
 * with Ostrom's hooks added after their own and the relayed MCP servers, `GEMINI.md` theirs plus the rules.
 *
 * A trusted workspace's `.gemini/settings.json` wins over the user's: `hooksConfig.enabled: false` would turn Ostrom's
 * hooks off, and its `hooksConfig.disabled` adds hook names to skip (Gemini CLI 0.66, security audit of the CLI n°2,
 * H3). Only the system settings win over the workspace, and Gemini ignores system settings not owned by root, so a
 * session cannot write its own: Gemini is not started where the workspace's settings touch `hooks` or `hooksConfig`.
 * The system settings stay the administrator's, named by Ostrom so that a repository's `.env` cannot name others,
 * Ostrom's hooks get names no repository can know in advance, and the variables that decide the folder's trust or the
 * shell of the hooks are Ostrom's too ([GeminiEnvironment]).
 *
 * By default the repository does not configure the agent, as for `ostrom run claude` (reviews REVUES-CLI-6 and -7,
 * probed on Gemini CLI 0.66), since what it configures ran its programs outside the gate, faster than a list of names
 * can
 * follow. The session's settings say `advanced.ignoreLocalEnv`: Gemini loads no `.env` of the repository's into its
 * environment (a lower-case `google_application_credentials` naming an executable, a `RIPGREP_CONFIG_PATH` with a
 * `--pre` script), and it is not started where it would still load a repository's `.gemini/.env`. They say
 * `experimental.extensionConfig: false` too: Gemini otherwise reads the working folder's `.env` as the developer's
 * extensions' settings, whose values replace `${extensionPath}` in those extensions' hooks; the developer's extensions
 * then run without the settings they keep in Gemini (said at launch). And Gemini is not started where a command of the
 * repository would run its programs, nor where the repository has an agent ([GeminiProjectFiles]). A team that trusts
 * its repository asks for all of it with `ostrom run gemini --trust-project-settings` ([SessionLayout.trustProject]),
 * said before the agent starts: the guarantee is then the variables' pins only.
 *
 * @param nonce A value of the session no repository can guess, for the names of Ostrom's hooks.
 */
class GeminiAdapter(private val nonce: () -> String = { Random.nextULong().toString(HEX).padStart(NONCE, '0') }) :
    AgentAdapter {
    override val agent = Agent.GEMINI

    // the version whose settings were read for H3 (security audit n°2): an older one is not trusted to keep the
    // hooks on
    override val minimum = MinimumVersion("0.66.0", "npm install -g @google/gemini-cli@latest")

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val real = join(layout.variable("GEMINI_CLI_HOME") ?: layout.home, ".gemini")
        val pinned = GeminiEnvironment.of(layout, files, real)
        refuseWorkspaceHooks(layout, files, real)
        if (!layout.trustProject) GeminiProjectFiles.refuse(layout, files, real)
        val root = join(layout.temporary, "gemini-home")
        files.createPrivateDirectory(root)
        val own = setOf("settings.json", "GEMINI.md", "skills")
        val directory = overlayHome(files, real, join(root, ".gemini"), own)
        skillsInto(files, layout.plugin, join(directory, "skills"), join(real, "skills"))
        val text = files.read(join(real, "settings.json"))?.removePrefix(BOM)
        val parsed = if (text.isNullOrBlank()) null else parseObject(text, comments = true)
        val theirs = parsed ?: JsonObject(emptyMap())
        developersRules(theirs, join(real, "settings.json"))
        val configured = extensionsRules(files, real)
        // Gemini reads the session's settings, not these: settings Ostrom cannot read are left out, and said
        val unread = if (parsed == null && !text.isNullOrBlank()) listOf(UNREAD) else emptyList()
        val trust = when {
            layout.trustProject -> listOf(TRUSTED)
            configured.isEmpty() -> emptyList()
            else -> listOf(unconfigured(configured))
        }
        val notes = unread + trust
        val suffix = nonce()
        files.write(join(directory, "settings.json"), pretty(settings(layout, theirs, suffix)), FileAccess.PRIVATE)
        val rules = files.read(join(real, "GEMINI.md")).orEmpty() + "\n" + layout.rules
        files.write(join(directory, "GEMINI.md"), rules)
        val system = systemSettings(layout, files)
        val environment = pinned + mapOf(
            "GEMINI_CLI_HOME" to root,
            LaunchVariables.HOOK_DIALECT to "gemini",
            LaunchVariables.HOOK_BUDGET_MS to BUDGET_MILLIS,
        ) + system
        return AgentLaunch(layout.arguments, environment, notes)
    }

    /**
     * Stops the launch when a workspace's settings touch `hooks` or `hooksConfig`, or cannot be read: read as Gemini
     * reads them (comments, trailing commas), in each folder of [SessionLayout.workspace]. In their home, the
     * developer's own settings are the workspace's, and their own rules hold.
     *
     * @throws AdapterException When one does: a repository could turn Ostrom's hooks off.
     */
    private fun refuseWorkspaceHooks(layout: SessionLayout, files: LaunchFiles, real: String) {
        for (folder in layout.workspace) {
            val directory = join(folder, ".gemini")
            // in their home, Gemini reads the developer's own settings as the workspace's (its home is the session's)
            val own = directory == real
            if (!own) refuseLink(files, folder, layout.foldsCase)
            val path = join(directory, "settings.json")
            val text = files.read(path)?.removePrefix(BOM)
            if (text.isNullOrBlank()) continue
            val settings = parseObject(text, comments = true) ?: throw AdapterException(
                "gemini: $path cannot be read as JSON: " + (if (own) "Gemini reads it in this folder, and " else "") +
                    "Ostrom cannot tell whether it turns its hooks off, so Gemini is not started",
            )
            val outside = if (own) null else WORKSPACE.firstOutside(settings)
            if (own) developersRules(settings, path)
            if (outside != null) {
                throw AdapterException(
                    "gemini: $path sets `$outside`, which Ostrom does not know to be harmless: a repository's " +
                        "settings could run its own programs or turn Ostrom's hooks off with it, so Gemini is not " +
                        "started. Remove it from that file to work with Ostrom",
                )
            }
        }
    }

    /**
     * A repository's `.gemini` must be a folder: through a link, what Gemini reads may not be what Ostrom read. Its
     * name is matched as this machine matches paths: `.GEMINI` is Gemini's folder on macOS and Windows.
     */
    private fun refuseLink(files: LaunchFiles, folder: String, foldsCase: Boolean) {
        val name = files.list(folder).firstOrNull { it.equals(".gemini", ignoreCase = foldsCase) } ?: return
        val directory = join(folder, name)
        val target = files.canonical(directory)
        if (!target.equals(join(files.canonical(folder), name), ignoreCase = foldsCase)) {
            throw AdapterException(
                "gemini: $directory is a link (to $target), so what Gemini reads there may not be what Ostrom " +
                    "checked: Gemini is not started. Make it a folder to work with Ostrom",
            )
        }
    }

    /**
     * The developer's extensions follow their rules: Gemini registers an extension's hooks (`hooks/hooks.json`, in its
     * folder or, for a linked one, in its source) with the settings' ones. A file Gemini cannot read loads no hook.
     *
     * @return The names of those that declare settings (`settings` in `gemini-extension.json`), which a session
     * without `--trust-project-settings` does not give them.
     * @throws AdapterException When one adds a `BeforeTool` hook.
     */
    private fun extensionsRules(files: LaunchFiles, real: String): List<String> {
        val extensions = join(real, "extensions")
        val configured = mutableListOf<String>()
        for (name in files.list(extensions)) {
            val folder = join(extensions, name)
            val install = files.read(join(folder, ".gemini-extension-install.json"))?.let { parseObject(it) }
            val source = (install?.get("source") as? JsonPrimitive)?.content
            val effective = if (install?.get("type") == JsonPrimitive("link") && source != null) source else folder
            val manifest = files.read(join(effective, "gemini-extension.json"))?.let { parseObject(it) }
            if ((manifest?.get("settings") as? JsonArray).orEmpty().isNotEmpty()) configured += name
            val path = join(effective, "hooks", "hooks.json")
            val hooks = files.read(path)?.let { parseObject(it) } ?: continue
            developersRules(hooks, path)
        }
        return configured
    }

    private fun unconfigured(names: List<String>) = "gemini: your extensions ${names.joinToString(", ")} run " +
        "without the settings you gave them in Gemini: Gemini would read them from this repository's .env too, " +
        "where a repository can rewrite what those extensions' hooks run. Set them in your environment, or run " +
        "`ostrom run gemini --trust-project-settings` if your team trusts this repository"

    /**
     * The developer's own rules: the gate is the organization's, so their settings do not turn the hooks off
     * (`hooksConfig.enabled: false`) nor add a `BeforeTool` hook, which Gemini runs beside the gate in any order and
     * which could change a tool's input after the gate has checked it. Their hooks of other events run beside
     * Ostrom's, and their `hooksConfig.disabled` cannot name Ostrom's, named anew in each session.
     *
     * @throws AdapterException When they do.
     */
    private fun developersRules(settings: JsonObject, path: String) {
        if (settings.child("hooksConfig")?.get("enabled") == JsonPrimitive(false)) {
            throw AdapterException(
                "gemini: $path sets `hooksConfig.enabled: false`, which turns Ostrom's hooks off: Gemini is not " +
                    "started. Remove it from that file to work with Ostrom",
            )
        }
        if ((settings.child("hooks")?.get("BeforeTool") as? JsonArray).orEmpty().isNotEmpty()) {
            throw AdapterException(
                "gemini: $path adds a `BeforeTool` hook: Gemini runs it beside Ostrom's gate, in any order, and it " +
                    "could change a tool's input after the gate has checked it, so Gemini is not started. Move it to " +
                    "another event, or remove it, to work with Ostrom",
            )
        }
    }

    /**
     * The variables that name the administrator's system settings and defaults, where Gemini would read them on this
     * machine (`GEMINI_CLI_SYSTEM_SETTINGS_PATH`, or the place of this machine's platform): set by Ostrom, a
     * repository's `.env` cannot name others.
     */
    private fun systemSettings(layout: SessionLayout, files: LaunchFiles): Map<String, String> {
        val real = layout.variable(SYSTEM_SETTINGS) ?: systemPlace(layout, files)
        // Gemini looks for the defaults beside the system settings
        val cut = real.lastIndexOfAny(charArrayOf('/', '\\'))
        val beside = if (cut < 0) "." else real.substring(0, maxOf(cut, 1))
        val defaults = layout.variable(SYSTEM_DEFAULTS) ?: join(beside, "system-defaults.json")
        return mapOf(SYSTEM_SETTINGS to real, SYSTEM_DEFAULTS to defaults)
    }

    /**
     * Where Gemini CLI reads the system settings without the variable: `C:\ProgramData` on Windows (known by its
     * `ProgramData` variable), `/Library/Application Support` on macOS, `/etc` elsewhere. Always an absolute path: a
     * relative one would be a repository's file.
     */
    private fun systemPlace(layout: SessionLayout, files: LaunchFiles): String {
        val windows = layout.variable("ProgramData")
        return when {
            windows != null -> join(windows, "gemini-cli", "settings.json")
            files.exists(MACOS_ROOT) -> join(MACOS_ROOT, "GeminiCli", "settings.json")
            else -> join("/etc", "gemini-cli", "settings.json")
        }
    }

    private fun settings(layout: SessionLayout, theirs: JsonObject, suffix: String): JsonObject {
        fun hook(event: String, kind: String, seconds: Long, matcher: String?) =
            hook(layout, "ostrom-$event-$suffix", kind, seconds, matcher)
        val ours = mapOf(
            "BeforeTool" to hook("BeforeTool", "gate", BEFORE_SECONDS, ".*"),
            "AfterTool" to hook("AfterTool", "gate", AFTER_SECONDS, ".*"),
            "SessionStart" to hook("SessionStart", "session", START_SECONDS, null),
            "SessionEnd" to hook("SessionEnd", "session", END_SECONDS, null),
        )
        val hooks = theirs.child("hooks").orEmpty()
        val merged = hooks + ours.mapValues { (event, entry) ->
            JsonArray((hooks[event] as? JsonArray).orEmpty() + entry)
        }
        val servers = theirs.child("mcpServers").orEmpty() + layout.stdioServers.mapValues { (_, arguments) ->
            jsonOf(mapOf("command" to layout.executable, "args" to arguments))
        }
        val added = mapOf("hooks" to JsonObject(merged), "mcpServers" to JsonObject(servers))
        // the folders' .env files are skipped, the developer's other advanced settings kept
        val advanced = theirs.child("advanced").orEmpty() + (IGNORE_LOCAL_ENV to JsonPrimitive(true))
        // nor does it read the working folder's .env as the extensions' settings (review REVUES-CLI-7)
        val experimental = theirs.child("experimental").orEmpty() + (EXTENSION_CONFIG to JsonPrimitive(false))
        val local = if (layout.trustProject) {
            emptyMap()
        } else {
            mapOf("advanced" to JsonObject(advanced), "experimental" to JsonObject(experimental))
        }
        return JsonObject(theirs + added + local)
    }

    private fun hook(layout: SessionLayout, name: String, kind: String, seconds: Long, matcher: String?): JsonElement {
        val command = mapOf(
            "type" to "command",
            "command" to layout.hookCommand(kind),
            "timeout" to seconds * MILLIS,
            "name" to name,
        )
        return jsonOf(mapOf("hooks" to listOf(command)) + listOfNotNull(matcher?.let { "matcher" to it }))
    }

    private companion object {
        const val BEFORE_SECONDS = 600L
        const val AFTER_SECONDS = 15L
        const val START_SECONDS = 20L
        const val END_SECONDS = 30L
        const val MILLIS = 1_000L
        const val BUDGET_MILLIS = "590000"
        const val UNREAD = "gemini: your settings.json cannot be read as JSON: this session starts without your " +
            "settings"
        const val BOM = "\uFEFF"
        const val IGNORE_LOCAL_ENV = "ignoreLocalEnv"
        const val EXTENSION_CONFIG = "extensionConfig"
        const val TRUSTED = "gemini: --trust-project-settings: Gemini loads this repository's .env and " +
            ".gemini/.env, whose variables can run the repository's programs, reads its .env as your extensions' " +
            "settings, runs the shell commands of its commands, and loads its agents, with their MCP servers and " +
            "their remote agents' `auth`: Ostrom pins the variables it knows of, and does not vouch for its gate in " +
            "this session"
        const val SYSTEM_SETTINGS = "GEMINI_CLI_SYSTEM_SETTINGS_PATH"
        const val SYSTEM_DEFAULTS = "GEMINI_CLI_SYSTEM_DEFAULTS_PATH"
        const val HEX = 16
        const val NONCE = 16

        const val MACOS_ROOT = "/Library/Application Support"
    }
}

private val BOOLEAN = { value: JsonElement -> value == JsonPrimitive(true) || value == JsonPrimitive(false) }

// The twelve keys of Gemini CLI 0.66 that only change how its terminal looks, with the values they take (inventory of
// the CLI's security audit n°2, annex A § 5). Left out though they look harmless: `ui.theme` (may name a file),
// `ui.customWittyPhrases` (free text), the keys that hide what is running (`ui.footer.hideCWD`, …).
private val WORKSPACE = InertKeys(
    listOf(
        "ui.hideBanner",
        "ui.hideTips",
        "ui.showShortcutsHint",
        "ui.showLineNumbers",
        "ui.showCitations",
        "ui.showSpinner",
        "ui.showMemoryUsage",
        "ui.footer.showLabels",
        "ui.accessibility.enableLoadingPhrases",
        "ui.accessibility.screenReader",
        "general.vimMode",
    ).associateWith { BOOLEAN } +
        mapOf("ui.loadingPhrases" to { value -> value in listOf("tips", "witty", "all", "off").map(::JsonPrimitive) }),
)
