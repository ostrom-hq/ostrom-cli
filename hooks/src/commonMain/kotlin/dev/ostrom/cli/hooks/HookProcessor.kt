// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks

import dev.ostrom.cli.hooks.runtime.HookReplies

/** The hooks an agent calls, `ostrom hook <kind>` (spec CLI.md § 2.4). */
enum class HookKind(val command: String) {
    /** Before and after every tool call. */
    GATE("gate"),

    /** At the start and the end of a session. */
    SESSION("session"),
    ;

    /** Lookup by command name. */
    companion object {
        /** The kind named [command], or null. */
        fun of(command: String): HookKind? = entries.firstOrNull { it.command == command }
    }
}

/** What a gate says about one event. */
sealed interface GateDecision {
    /** The tool runs. */
    data object Allow : GateDecision

    /** The tool does not run; [reason] is told to the agent (and read by its model: keep it short and actionable). */
    data class Deny(val reason: String) : GateDecision
}

/** Decides one gate event. The hooks agent plugs the harness rules in here. */
interface GateHandler {
    /** The decision for [event]; must answer within a few milliseconds and never wait on the network. */
    fun decide(event: HookEvent): GateDecision
}

/**
 * The gate of the socle: it lets every tool run. No launcher installs `ostrom hook` yet; the hooks agent replaces it
 * with the harness gate (signed package, rules, approvals), which is fail-closed.
 */
class PassThroughGateHandler : GateHandler {
    override fun decide(event: HookEvent): GateDecision = GateDecision.Allow
}

/** What the hook process writes and how it exits. */
data class HookReply(val stdout: String = "", val stderr: String = "", val exitCode: Int = 0)

/** Turns a hook event into the reply the calling agent expects. */
interface HookProcessor {
    /**
     * Handles one hook call.
     *
     * @param kind Which hook the agent called.
     * @param input The bytes piped in by the agent, or null when none could be read.
     * @param dialect The agent family (`OSTROM_HOOK_DIALECT`): `claude` (Claude Code, Codex, OpenCode, Pi), `grok`,
     * `gemini`, `hermes`. It decides the shape of a refusal.
     *
     * @return The reply to write.
     */
    fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply
}

/** [HookProcessor] that asks [gate] about gate events and answers in the agent's dialect. */
class HookProcessorImpl(private val gate: GateHandler) : HookProcessor {
    override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply = when (kind) {
        HookKind.GATE -> when (val decision = gate.decide(HookEvent.parse(input))) {
            GateDecision.Allow -> HookReply()
            is GateDecision.Deny -> deny(decision.reason, dialect)
        }
        HookKind.SESSION -> HookReply()
    }

    /** A refusal in the agent's dialect ([HookReplies.deny]): exit 2, the reason on stderr. */
    private fun deny(reason: String, dialect: String): HookReply = HookReplies(dialect).deny(reason)
}
