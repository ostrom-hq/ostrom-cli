package dev.ostrom.cli.hooks.gate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The gate of a catalogue action: who must say yes before the action runs.
 *
 * @property enforce True when the gate refuses until a yes; false while it is only proposed (shadow: logged).
 * @property label The gate's name, shown to the agent and the approver.
 * @property approvers Who may say yes.
 */
@Serializable
data class Gate(val enforce: Boolean = false, val label: String? = null, val approvers: List<String> = emptyList()) {
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
@Serializable
data class GateAction(
    @SerialName("class") val actionClass: String = "",
    val label: String? = null,
    val tools: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val gate: Gate? = null,
)

/**
 * The gate catalogue of a session (`GET /api/bridge/gates/catalog`).
 *
 * @property actions The classes of actions.
 * @property failMode `open` to let a gated action through when Ostrom is unreachable; anything else refuses.
 */
@Serializable
data class GateCatalogue(val actions: List<GateAction> = emptyList(), val failMode: String? = null) {
    /**
     * The action [subject] falls under, the most guarded one first: an enforced gate, then a proposed gate, then an
     * observed class; among equals, the first in the catalogue (prototype `match_action`).
     */
    fun match(subject: ToolSubject, patterns: PatternCache): GateAction? {
        if (subject.text.isEmpty()) return null
        val haystack = if (subject.kind == ToolKind.MCP) subject.text.substringBefore(' ').lowercase() else subject.text
        var best: GateAction? = null
        var bestRank = -1
        for (action in actions) {
            if (!appliesTo(action, subject)) continue
            if (action.patterns.none { patterns.matches(it, haystack) }) continue
            val rank = when {
                action.gate?.enforce == true -> 2
                action.gate != null -> 1
                else -> 0
            }
            if (rank > bestRank) {
                best = action
                bestRank = rank
            }
        }
        return best
    }

    private fun appliesTo(action: GateAction, subject: ToolSubject): Boolean =
        subject.name in action.tools || (subject.kind == ToolKind.WRITE && action.tools.any { it in EDIT_TOOLS })

    /** Reads catalogues. */
    companion object {
        private val EDIT_TOOLS = setOf("Write", "Edit", "MultiEdit")
        private val json = Json { ignoreUnknownKeys = true }

        /** The catalogue in [text], or null when it is not one (a broken copy is treated as no copy). */
        fun parse(text: String): GateCatalogue? = try {
            json.decodeFromString(serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /** The catalogue as JSON, to keep a copy. */
        fun write(catalogue: GateCatalogue): String = json.encodeToString(serializer(), catalogue)
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
