// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The gate of a catalogue action: who must say yes before the action runs.
 *
 * @property enforce True when the gate refuses until a yes; false while it is only proposed (shadow: logged).
 * @property label The gate's name, shown to the agent and the approver.
 * @property approvers Who may say yes.
 */
data class Gate(val enforce: Boolean, val label: String?, val approvers: List<String>) {
    /** The approvers as one phrase, `a or b`, or `the gate owner` when none is declared. */
    val approversPhrase: String get() = if (approvers.isEmpty()) "the gate owner" else approvers.joinToString(" or ")
}

/**
 * One class of actions the server watches (`push-main`, `deploy`…), with the patterns that recognise it.
 *
 * @property actionClass The class name, sent back with a gate request.
 * @property label What the action is, for people.
 * @property tools The tool families it applies to (`Bash`, `Write`, `mcp`…).
 * @property patterns Regular expressions, matched case-insensitively against the tool's text.
 * @property gate The gate that guards it, or null when the class is only observed.
 */
data class GateAction(
    val actionClass: String,
    val label: String?,
    val tools: List<String>,
    val patterns: List<String>,
    val gate: Gate?,
) {
    /** What the agent and the approver read: the label, else the class. */
    val name: String get() = label ?: actionClass
}

/**
 * The gate catalogue of a session (`GET /api/v1/gates/catalog`).
 *
 * @property actions The classes of actions.
 * @property failMode `open` to let a gated action through when Ostrom is unreachable; anything else, and no value,
 * refuses.
 */
data class GateCatalogue(val actions: List<GateAction>, val failMode: String?) {
    /**
     * The action [subject] falls under, the most guarded one first: an enforced gate, then a proposed gate, then an
     * observed class; among equals, the first in the catalogue (prototype `match_action`).
     */
    fun match(subject: ToolSubject, patterns: PatternCache): GateAction? {
        if (subject.text.isEmpty()) return null
        val haystacks = when (subject.kind) {
            ToolKind.MCP -> listOf(subject.text.substringBefore(' ').lowercase())
            // the words as the shell reads them too: `kube''ctl apply` and `g\it push` are `kubectl apply` (S2-26)
            ToolKind.BASH -> listOf(subject.text, words(subject.text))
            else -> listOf(subject.text)
        }
        // maxByOrNull keeps the first of equals
        return actions.filter { action ->
            appliesTo(action, subject) && action.patterns.any { pattern ->
                haystacks.any { patterns.matches(pattern, it) }
            }
        }.maxByOrNull(::rank)
    }

    private fun words(text: String): String = ShellWords.segments(text).joinToString("\n") { it.joinToString(" ") }

    /** The first action with an enforced gate for the tool family [tool], and its gate; null when there is none. */
    fun enforcedOn(tool: String): Pair<GateAction, Gate>? = actions.firstNotNullOfOrNull { action ->
        action.gate?.takeIf { it.enforce && tool in action.tools }?.let { action to it }
    }

    /** The action [subject] falls under when its gate is enforced, and the gate; null otherwise. */
    fun enforcedFor(subject: ToolSubject, patterns: PatternCache): Pair<GateAction, Gate>? {
        val action = match(subject, patterns)
        val gate = action?.gate?.takeIf { it.enforce }
        return if (action != null && gate != null) action to gate else null
    }

    /**
     * This catalogue with the actions of [added] after its own: a catalogue that is not Ostrom's (the copy in the
     * session's package) may add gated actions and close the fail mode, never remove or open anything (S2-20).
     */
    fun with(added: GateCatalogue): GateCatalogue =
        GateCatalogue(actions + added.actions, if (added.failMode == CLOSED_MODE) CLOSED_MODE else failMode)

    private fun rank(action: GateAction): Int = when {
        action.gate?.enforce == true -> ENFORCED
        action.gate != null -> PROPOSED
        else -> OBSERVED
    }

    private fun appliesTo(action: GateAction, subject: ToolSubject): Boolean =
        subject.name in action.tools || (subject.kind == ToolKind.WRITE && action.tools.any { it in EDIT_TOOLS })

    /** Reads catalogues. */
    companion object {
        private val EDIT_TOOLS = setOf("Write", "Edit", "MultiEdit")
        private const val ENFORCED = 2
        private const val PROPOSED = 1
        private const val OBSERVED = 0
        private const val CLOSED_MODE = "closed"
        private const val LINE = "[^\\n;&|]*"
        private val OFFLINE = Gate(enforce = true, label = "Ostrom unreachable", approvers = emptyList())

        private fun bash(actionClass: String, label: String, vararg patterns: String) =
            GateAction(actionClass, label, listOf("Bash"), patterns.toList(), OFFLINE)

        /**
         * The catalogue when Ostrom's cannot be read (S2-20): the boundary actions every organisation guards, failing
         * closed. A gated action then asks Ostrom, which it cannot reach, and is refused.
         */
        val CLOSED = GateCatalogue(
            listOf(
                bash("push", "git push", "\\bgit\\b${LINE}\\bpush\\b"),
                bash(
                    "deploy",
                    "deployment",
                    "\\bkubectl\\b$LINE\\b(apply|create|delete|replace|patch|rollout|scale|set|edit|drain)\\b",
                    "\\bhelm\\b$LINE\\b(install|upgrade|uninstall|rollback|delete)\\b",
                    "\\b(terraform|tofu)\\b$LINE\\b(apply|destroy|import)\\b",
                    "\\bpulumi\\b$LINE\\b(up|destroy)\\b",
                ),
                bash("merge", "merge or release", "\\bgh\\b$LINE\\b(pr\\s+merge|release\\s+create)\\b"),
                bash(
                    "publish",
                    "package publication",
                    "\\b(npm|pnpm|yarn|cargo)\\b$LINE\\bpublish\\b",
                    "\\bdocker\\b$LINE\\bpush\\b",
                    "\\btwine\\b$LINE\\bupload\\b",
                ),
            ),
            CLOSED_MODE,
        )

        /**
         * The catalogue in [text] (the contract's `GateCatalog`, unknown fields ignored), or null when it is not one: a
         * field of the wrong type makes the whole catalogue unreadable, never a smaller one.
         */
        fun parse(text: String): GateCatalogue? = try {
            val root = Json.parseToJsonElement(text).jsonObject
            GateCatalogue(root.list("actions", ::actionOf), root.text("failMode"))
        } catch (_: IllegalArgumentException) {
            null // not JSON, or a field of the wrong type (kotlinx's `jsonObject`, `jsonArray`, `jsonPrimitive`)
        }

        private fun actionOf(element: JsonElement): GateAction {
            val fields = element.jsonObject
            val gate = fields["gate"]
            return GateAction(
                actionClass = fields.text("class").orEmpty(),
                label = fields.text("label"),
                tools = fields.list("tools") { it.jsonPrimitive.content },
                patterns = fields.list("patterns") { it.jsonPrimitive.content },
                gate = if (gate == null || gate is JsonNull) null else gateOf(gate.jsonObject),
            )
        }

        private fun gateOf(fields: JsonObject): Gate {
            val enforce = fields["enforce"]
            return Gate(
                enforce = enforce != null && enforce.jsonPrimitive.booleanOrNull == true,
                label = fields.text("label"),
                approvers = fields.list("approvers") { it.jsonPrimitive.content },
            )
        }

        private fun JsonObject.text(key: String): String? {
            val value = this[key]
            return if (value == null || value is JsonNull) null else value.jsonPrimitive.content
        }

        private fun <T> JsonObject.list(key: String, item: (JsonElement) -> T): List<T> {
            val value = this[key]
            return if (value == null || value is JsonNull) emptyList() else value.jsonArray.map(item)
        }
    }
}

/**
 * Compiled action patterns, each compiled once per hook process: the hooks of a package share the patterns of their
 * action class, and a tool call is matched twice (prototype `pattern_hit`). A pattern that does not compile matches
 * nothing.
 */
class PatternCache {
    private val compiled = mutableMapOf<String, Regex?>()

    /** Whether [pattern] (case-insensitive) matches somewhere in [text]. */
    fun matches(pattern: String, text: String): Boolean =
        compiled.getOrPut(pattern) { compile(pattern) }?.containsMatchIn(text) ?: false

    private fun compile(pattern: String): Regex? = try {
        Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (_: IllegalArgumentException) {
        null
    }
}
