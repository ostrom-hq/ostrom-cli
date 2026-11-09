// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.gate.GateAction
import run.ostrom.cli.hooks.gate.GateCatalogue
import run.ostrom.cli.hooks.gate.PatternCache
import run.ostrom.cli.hooks.gate.ToolSubject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One hook of the package (the contract's `CompiledHook`): when its event fires on a matching tool call, it runs a
 * check, asks a gate or sends a notification.
 *
 * @property kind `check`, `notify` or `gate`.
 * @property event `PreToolUse` or `PostToolUse`.
 * @property mode `shadow` while the workflow is only proposed (reported, never run as a blocker); anything else,
 *   `enforce`, a mode this CLI does not know or none at all, blocks: a view the agent garbled never weakens a check.
 * @property scriptId The script a check runs, or null.
 * @property notifyId The notification Ostrom sends, or null.
 * @property order Its place in its workflow: the checks of one action run in this order.
 */
data class CompiledHook(
    val id: String,
    val nodeId: String,
    val kind: String,
    val event: String,
    val tools: List<String>,
    val patterns: List<String>,
    val mode: String,
    val scriptId: String?,
    val notifyId: String?,
    val order: Int,
) {
    /** Whether the hook blocks: every mode but exactly `shadow`, which is only reported. */
    val enforced: Boolean get() = mode != SHADOW

    /** Whether the tool call [subject] falls under the hook, read as the gate reads its catalogue's patterns. */
    fun matches(subject: ToolSubject, patterns: PatternCache): Boolean =
        GateCatalogue(listOf(GateAction(id, null, tools, this.patterns, null)), null).match(subject, patterns) != null

    /** The values of [kind] and [event]. */
    companion object {
        /** A hook that runs a script before the action. */
        const val CHECK = "check"

        /** A hook whose notification Ostrom sends after the action. */
        const val NOTIFY = "notify"

        /** Before the tool runs. */
        const val BEFORE = "PreToolUse"

        /** After the tool ran. */
        const val AFTER = "PostToolUse"

        /** The mode of a hook that blocks. */
        const val ENFORCE = "enforce"

        /** The only mode of a hook that does not block: it is reported, never run as a blocker. */
        const val SHADOW = "shadow"
    }
}

/**
 * One script a check runs (the contract's `CompiledScript`), laid at `<tmp>/<path>` by the launcher.
 *
 * @property path Its path in the package, under `scripts/`.
 * @property sha256 The SHA-256 of its text, signed with the package: a script that no longer matches is not run.
 * @property command What it runs, quoted in a refusal.
 * @property timeoutSec Past this it is stopped and counts as failed.
 * @property network Whether the sandbox lets it reach the network.
 */
data class CompiledScript(
    val id: String,
    val label: String,
    val path: String,
    val sha256: String,
    val command: String,
    val timeoutSec: Int,
    val network: Boolean,
)

/**
 * The hook view of the session's package (`package.json`, `OSTROM_PACKAGE`), written by the launcher's
 * `PackageLayout` and sealed with the rest of the package (S2-33).
 *
 * @property digest The package's digest, recorded with the session and sent with every check run and notification.
 * @property context The package's line of session context, or null.
 * @property tmp The launch's private folder: the scripts, the sandbox's folder and the cache of passed checks.
 */
data class PackageView(
    val digest: String,
    val context: String?,
    val tmp: String,
    val hooks: List<CompiledHook>,
    val scripts: List<CompiledScript>,
) {
    /** The script [id], or null. */
    fun script(id: String?): CompiledScript? = scripts.firstOrNull { it.id == id }

    /** Reads the view. */
    companion object {
        private const val DEFAULT_TIMEOUT_SEC = 120

        /**
         * The view in [text], or null when it is not one: a field of the wrong type makes the whole view unreadable,
         * never a smaller one.
         */
        fun parse(text: String): PackageView? = try {
            val root = Json.parseToJsonElement(text).jsonObject
            PackageView(
                digest = root.text("digest").orEmpty(),
                context = root.text("context")?.takeIf { it.isNotEmpty() },
                tmp = root.text("tmp").orEmpty(),
                hooks = root.list("hooks") { hookOf(it.jsonObject) },
                scripts = root.list("scripts") { scriptOf(it.jsonObject) },
            )
        } catch (_: IllegalArgumentException) {
            null // not JSON, or a field of the wrong type (kotlinx's `jsonObject`, `jsonArray`, `jsonPrimitive`)
        }

        private fun hookOf(fields: JsonObject) = CompiledHook(
            id = fields.text("id").orEmpty(),
            nodeId = fields.text("nodeId").orEmpty(),
            kind = fields.text("kind").orEmpty(),
            event = fields.text("event").orEmpty(),
            tools = fields.list("tools") { it.jsonPrimitive.content },
            patterns = fields.list("patterns") { it.jsonPrimitive.content },
            mode = fields.text("mode").orEmpty(),
            scriptId = fields.text("scriptId"),
            notifyId = fields.text("notifyId"),
            order = fields.primitive("order")?.intOrNull ?: 0,
        )

        private fun scriptOf(fields: JsonObject) = CompiledScript(
            id = fields.text("id").orEmpty(),
            label = fields.text("label") ?: "check",
            path = fields.text("path").orEmpty(),
            sha256 = fields.text("sha256").orEmpty(),
            command = fields.text("command").orEmpty(),
            timeoutSec = fields.primitive("timeoutSec")?.intOrNull ?: DEFAULT_TIMEOUT_SEC,
            network = fields.primitive("network")?.booleanOrNull == true,
        )

        private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = value(key)?.let { it.jsonPrimitive }

        private fun JsonObject.text(key: String): String? = primitive(key)?.content

        private fun <T> JsonObject.list(key: String, item: (JsonElement) -> T): List<T> =
            value(key)?.let { it.jsonArray.map(item) }.orEmpty()
    }
}
