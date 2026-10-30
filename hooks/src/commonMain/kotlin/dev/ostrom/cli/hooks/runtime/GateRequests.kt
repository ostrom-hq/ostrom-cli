// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.CommandLocation
import dev.ostrom.cli.hooks.gate.Gate
import dev.ostrom.cli.hooks.gate.GateAction
import dev.ostrom.cli.hooks.gate.GitInvocation
import dev.ostrom.cli.hooks.gate.GitRunner
import dev.ostrom.cli.hooks.gate.Push
import dev.ostrom.cli.hooks.gate.PushReader
import dev.ostrom.cli.hooks.gate.ShellWords
import dev.ostrom.cli.hooks.gate.ToolKind
import dev.ostrom.cli.hooks.gate.ToolSubject
import dev.ostrom.cli.hooks.gate.UnreadablePushException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A gated action the before-tool hook asks a yes for.
 *
 * @property written The command as the agent wrote it, with the scripts it runs: never the note the hook appends.
 * @property subject The command with where its pushes land appended, which the gate patterns read.
 * @property gate The action's gate.
 * @property failMode The organisation's fail mode (`open` lets the action through when Ostrom is unreachable).
 */
data class GateAsk(
    val event: JsonObject,
    val written: String,
    val subject: ToolSubject,
    val cwd: String,
    val action: GateAction,
    val gate: Gate,
    val failMode: String?,
)

/** Asks Ostrom for the yes of a gated action and turns its answer into the hook's reply (`POST /gates`). */
@Suppress("LongParameterList") // the asker's collaborators, shared with the gate hook that builds it
class GateRequests(
    private val context: HookContext,
    private val writes: SessionWrites,
    private val ids: SessionIds,
    private val replies: HookReplies,
    private val location: CommandLocation,
    private val pushes: PushReader,
    private val git: GitRunner,
) {
    /**
     * Refuses a push Ostrom cannot read or that redirects git while the gate is enforced, else asks the gate. A gate
     * only proposed (shadow) never stops the agent: it is asked, and what Ostrom logs is all it does.
     */
    fun ask(ask: GateAsk): HookReply {
        val subject = ask.subject
        val directory = location.directory(ask.cwd, subject.text)
        val redirected = redirection(subject)
        val read = if (redirected == null && subject.kind == ToolKind.BASH) {
            // what the agent wrote, never cut at a note it may have written itself: the shell runs all of it
            readPush(directory, ask.written)
        } else {
            PushRead(null, null)
        }
        return when {
            redirected != null && ask.gate.enforce ->
                replies.deny(GateTexts.redirected(ask.action.name, ask.gate, redirected))
            read.problem != null && ask.gate.enforce ->
                replies.deny(GateTexts.unreadable(ask.action.name, ask.gate, read.problem))
            else -> request(ask, directory, read.push)
        }
    }

    private fun request(ask: GateAsk, directory: String, push: Push?): HookReply {
        val subject = ask.subject
        val diff = when {
            push != null -> Diffs(git).pushed(directory, push)
            ask.action.actionClass.startsWith("push") -> Diffs(git).outgoing(directory)
            else -> ""
        }
        val bash = subject.kind == ToolKind.BASH
        val body = jsonOf(
            "repo" to context.repository,
            "class" to ask.action.actionClass,
            "tool" to ask.event["tool_name"],
            "command" to subject.text,
            "dev" to context.developer,
            "harnessVersion" to context.harnessVersion,
            "realRepo" to if (bash) location.repository(ask.cwd, subject.text)?.label else null,
            "push" to push?.let(::pushJson),
            "diff" to diff.ifEmpty { null },
        )
        val agentSession = ask.event.string("session_id")
        val answer = writes.send(ids.of(agentSession), agentSession, REQUEST_MILLIS) { session ->
            Routes.GATES to JsonObject(mapOf("sessionId" to JsonPrimitive(session)) + body)
        }
        return when (answer) {
            is CallResult.Failed -> failure(answer, ask)
            is CallResult.Ok -> answered(answer.body, ask)
        }
    }

    private fun failure(answer: CallResult.Failed, ask: GateAsk): HookReply = when {
        !ask.gate.enforce -> replies.allow()
        answer.sessionClosed -> replies.deny(GateTexts.closed(ask.action.name, answer.message))
        ask.failMode == "open" -> replies.say(GateTexts.letThrough(ask.action.name, answer.message))
        else -> replies.deny(GateTexts.unreachable(ask.action.name, ask.gate, answer.message))
    }

    private fun answered(body: JsonObject, ask: GateAsk): HookReply {
        // the contract answers {"gate": <request>, "approval", "receipt"}; the prototype answered the request itself
        val request = if ("status" in body) body else body["gate"] as? JsonObject ?: body
        val answer = GateAnswer.of(request, ask.action.name, ask.gate)
        return when (request.string("status")) {
            "approved" -> replies.say(GateTexts.approved(answer))
            "shadow" -> replies.say(GateTexts.shadow(answer))
            "ungated" -> replies.allow()
            "rejected" -> replies.deny(GateTexts.rejected(answer))
            else -> replies.deny(GateTexts.waiting(answer))
        }
    }

    /** A push read from a command, or why it could not be. */
    private class PushRead(val push: Push?, val problem: String?)

    private fun readPush(directory: String, text: String): PushRead = try {
        PushRead(pushes.read(directory, text), null)
    } catch (e: UnreadablePushException) {
        PushRead(null, e.reason)
    }

    private fun redirection(subject: ToolSubject): String? = if (subject.kind != ToolKind.BASH) {
        null
    } else {
        // any git call of a gated command, not only its push: `git -c alias.x=push x` redirects too (S2-27)
        ShellWords.segments(subject.text).firstNotNullOfOrNull { words -> GitInvocation.parse(words)?.redirection() }
    }

    /** The note the hooks append to a push for the gate patterns, and how they read it. */
    companion object {
        private const val REQUEST_MILLIS = 3_000
        private const val NOTE = "\n# ostrom: git push to:"

        /** `\n# ostrom: git push to: main, dev`, or nothing without a destination. */
        fun targetsNote(targets: List<String>): String =
            if (targets.isEmpty()) "" else "$NOTE ${targets.joinToString(", ")}"

        private fun pushJson(push: Push): JsonObject = jsonOf(
            "remote" to push.remoteRepository,
            "refs" to JsonArray(push.refs.map { jsonOf("ref" to it.destination, "sha" to it.sha) }),
            "force" to push.force,
        )
    }
}

/**
 * What a gate request's answer says, for the reply.
 *
 * @property id The gate request's id, for `ostrom wait`.
 * @property label The action's label.
 * @property gateLabel The gate's label.
 * @property who Who can say yes.
 * @property by Who decided, else [who].
 * @property receiptId The receipt of the decision, once signed.
 */
data class GateAnswer(
    val id: String,
    val label: String,
    val gateLabel: String,
    val who: String,
    val by: String,
    val receiptId: String?,
) {
    /** Reads it. */
    companion object {
        /** @param request The gate request Ostrom answered. */
        fun of(request: JsonObject, name: String, gate: Gate): GateAnswer {
            val served = request["gate"] as? JsonObject
            val who = served?.let(::approversOf) ?: gate.approversPhrase
            return GateAnswer(
                id = request.string("id") ?: "?",
                label = name,
                gateLabel = served?.string("label") ?: gate.label ?: "gate",
                who = who,
                by = request.string("decidedBy") ?: who,
                receiptId = request.string("receiptId"),
            )
        }

        private fun approversOf(gate: JsonObject): String? {
            val array = gate["approvers"] as? JsonArray ?: return null
            val list = array.filter { it is JsonPrimitive && it.isString }.map { it.jsonPrimitive.content }
            return if (list.isEmpty()) "the gate owner" else list.joinToString(" or ")
        }
    }
}
