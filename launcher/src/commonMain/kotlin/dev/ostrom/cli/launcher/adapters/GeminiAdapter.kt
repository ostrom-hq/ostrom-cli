// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.jsonOf
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.pretty
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
        val root = join(layout.temporary, "gemini-home")
        files.createPrivateDirectory(root)
        val own = setOf("settings.json", "GEMINI.md", "skills")
        val directory = overlayHome(files, real, join(root, ".gemini"), own)
        skillsInto(files, layout.plugin, join(directory, "skills"), join(real, "skills"))
        val text = files.read(join(real, "settings.json"))?.removePrefix(BOM)
        val parsed = if (text.isNullOrBlank()) null else parseObject(text, comments = true)
        val theirs = parsed ?: JsonObject(emptyMap())
        developersRules(theirs, join(real, "settings.json"))
        extensionsRules(files, real)
        // Gemini reads the session's settings, not these: settings Ostrom cannot read are left out, and said
        val notes = if (parsed == null && !text.isNullOrBlank()) listOf(UNREAD) else emptyList()
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
            if (!own) refuseLink(files, folder, directory)
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

    /** A repository's `.gemini` must be a folder: through a link, what Gemini reads may not be what Ostrom read. */
    private fun refuseLink(files: LaunchFiles, folder: String, directory: String) {
        if (".gemini" !in files.list(folder)) return
        val target = files.canonical(directory)
        if (target != join(files.canonical(folder), ".gemini")) {
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
     * @throws AdapterException When one adds a `BeforeTool` hook.
     */
    private fun extensionsRules(files: LaunchFiles, real: String) {
        val extensions = join(real, "extensions")
        for (name in files.list(extensions)) {
            val folder = join(extensions, name)
            val install = files.read(join(folder, ".gemini-extension-install.json"))?.let { parseObject(it) }
            val source = (install?.get("source") as? JsonPrimitive)?.content
            val effective = if (install?.get("type") == JsonPrimitive("link") && source != null) source else folder
            val path = join(effective, "hooks", "hooks.json")
            val hooks = files.read(path)?.let { parseObject(it) } ?: continue
            developersRules(hooks, path)
        }
    }

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
        return JsonObject(theirs + mapOf("hooks" to JsonObject(merged), "mcpServers" to JsonObject(servers)))
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
