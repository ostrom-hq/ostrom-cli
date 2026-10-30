package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.CommandLocation
import dev.ostrom.cli.hooks.gate.GateAction
import dev.ostrom.cli.hooks.gate.GateCatalogue
import dev.ostrom.cli.hooks.gate.GitInvocation
import dev.ostrom.cli.hooks.gate.GitRunner
import dev.ostrom.cli.hooks.gate.PatternCache
import dev.ostrom.cli.hooks.gate.Push
import dev.ostrom.cli.hooks.gate.PushReader
import dev.ostrom.cli.hooks.gate.RealRepository
import dev.ostrom.cli.hooks.gate.ShellWords
import dev.ostrom.cli.hooks.gate.ToolKind
import dev.ostrom.cli.hooks.gate.ToolSubject
import dev.ostrom.cli.hooks.gate.UnreadablePushException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `ostrom hook gate`, before and after every tool call (prototype `hook_gate`, with the audit fixes of 30/10).
 *
 * The gate never holds the agent: it answers at once (allow, or "waiting for a yes") and the server keeps the
 * decision; the same command passes once the owner said yes. A guarded action run in another repository than the
 * session's is refused; a push is read for the commits it sends, the ref it lands on, the remote it goes to and its
 * force, and a push that redirects git is refused before anyone is asked.
 */
@Suppress("LongParameterList") // the hook's collaborators, each a port tested on its own
class GateHook(
    private val context: HookContext,
    private val calls: OstromCalls,
    private val ids: SessionIds,
    private val catalogues: CatalogueStore,
    private val checks: WorkflowChecks,
    private val git: GitRunner,
    private val canonical: (String) -> String,
    private val workingDirectory: String,
) {
    private val replies = HookReplies(context.dialect)
    private val writes = SessionWrites(calls, ids)
    private val location = CommandLocation(git, context.home)
    private val pushes = PushReader(git, context.home)
    private val patterns = PatternCache()

    /** Answers one event (normalised to the Claude Code shape). */
    fun handle(event: JsonObject): HookReply {
        val after = event.string("hook_event_name") == "PostToolUse"
        val cwd = event.string("cwd") ?: workingDirectory
        val subject = ToolSubject.of(event.string("tool_name").orEmpty(), event["tool_input"])
        if (after) reportSeen(event, subject)
        if (subject.text.isEmpty()) return replies.allow()
        if (after) {
            // the before-tool hook refused this very command: it did not run, nothing follows it
            if (ids.wasRefused(subject.text)) {
                ids.markRefused(subject.text, refused = false)
                return replies.allow()
            }
            return afterTool(event, subject, cwd)
        }
        val reply = beforeTool(event, subject, cwd)
        ids.markRefused(subject.text, reply.exitCode == DENIED)
        return reply
    }

    private fun beforeTool(event: JsonObject, raw: ToolSubject, cwd: String): HookReply {
        val catalogue = catalogues.current()
        val (subject, landing) = withPushTargets(raw, cwd)
        val action = catalogue.match(subject, patterns)
        if (checks.guards(subject) || action?.gate != null) {
            val foreign = location.foreign(cwd, subject.text, context.launchTop, canonical)
            if (foreign != null) return replies.deny(foreignDenial(foreign))
        }
        checks.beforeTool(event, subject, cwd)?.let { return it }
        val gated = action?.takeIf { it.gate != null }
        if (gated != null) return ask(event, subject, cwd, gated, catalogue)
        // a push whose landing Ostrom cannot read may land on main: refused while a gate on main is enforced (A9)
        if (landing == null) return replies.allow()
        val worst = catalogue.match(subject.copy(text = subject.text + targetsNote(listOf("main"))), patterns)
        return if (worst?.gate?.enforce == true) replies.deny(unknownLanding(worst, landing)) else replies.allow()
    }

    private fun afterTool(event: JsonObject, raw: ToolSubject, cwd: String): HookReply {
        val catalogue = catalogues.current()
        val subject = withPushTargets(raw, cwd).first
        checks.afterTool(event, subject)
        val action = catalogue.match(subject, patterns)?.takeIf { it.gate != null } ?: return replies.allow()
        // the action ran: the yes is used up (one yes, one action)
        val directory = location.directory(cwd, subject.text)
        val push = if (subject.kind == ToolKind.BASH) readPush(directory, subject.text).getOrNull() else null
        val body = jsonOf(
            "sessionId" to ids.of(event.string("session_id")),
            "repo" to context.repository,
            "class" to action.actionClass,
            "command" to subject.text,
            "push" to push?.let(::pushJson),
            "realRepo" to push?.let { location.repository(cwd, subject.text)?.label },
        )
        calls.call("/api/bridge/gates/consume", body, CONSUME_MILLIS)
        return replies.allow()
    }

    private fun ask(
        event: JsonObject,
        subject: ToolSubject,
        cwd: String,
        action: GateAction,
        catalogue: GateCatalogue,
    ): HookReply {
        val gate = checkNotNull(action.gate)
        val label = action.label ?: action.actionClass
        val gateLabel = gate.label ?: "gate"
        val directory = location.directory(cwd, subject.text)
        redirection(subject)?.let {
            return replies.deny(
                "Ostrom: denied. \"$label\" needs a yes from ${gate.approversPhrase} (gate \"$gateLabel\"), and this " +
                    "command sends git elsewhere ($it). Run a plain `git push <remote> <branch>` as its own command.",
            )
        }
        val read = if (subject.kind == ToolKind.BASH) readPush(directory, subject.text) else Result.success(null)
        val push = read.getOrElse { problem ->
            if (gate.enforce) {
                return replies.deny(
                    "Ostrom: denied. \"$label\" needs a yes from ${gate.approversPhrase} (gate \"$gateLabel\"), " +
                        "and ${problem.message}",
                )
            }
            null
        }
        val diff = when {
            push != null -> Diffs(git).pushed(directory, push)
            action.actionClass.startsWith("push") -> Diffs(git).outgoing(directory)
            else -> ""
        }
        val body = jsonOf(
            "sessionId" to ids.of(event.string("session_id")),
            "repo" to context.repository,
            "class" to action.actionClass,
            "tool" to event["tool_name"],
            "command" to subject.text,
            "dev" to context.developer,
            "harnessVersion" to context.harnessVersion,
            "realRepo" to if (subject.kind == ToolKind.BASH) location.repository(cwd, subject.text)?.label else null,
            "push" to push?.let(::pushJson),
            "diff" to diff.ifEmpty { null },
        )
        val answer = writes.send("/api/bridge/gates/request", body, event.string("session_id"), REQUEST_MILLIS)
        return answerOf(answer, label, action, catalogue)
    }

    private fun answerOf(answer: CallResult, label: String, action: GateAction, catalogue: GateCatalogue): HookReply {
        val gate = checkNotNull(action.gate)
        if (answer is CallResult.Failed) {
            return when {
                answer.message.startsWith("session closed:") -> replies.deny(
                    "Ostrom: denied. \"$label\" needs a yes, and this session is closed on Ostrom's side and could " +
                        "not be reopened (${answer.message}). The action was not run. Tell the user; a new session " +
                        "(`ostrom <agent>`) asks again.",
                )
                catalogue.failMode == "open" ->
                    replies.say(
                        "Ostrom unreachable (${answer.message}): \"$label\" let through (the organisation's fail " +
                            "mode is open).",
                    )
                else -> replies.deny(
                    "Ostrom: denied. \"$label\" needs a yes from ${gate.approversPhrase} (${gate.label ?: "gate"}), " +
                        "and Ostrom is unreachable (${answer.message}). The action was not run. Do not work around " +
                        "this gate; tell the user.",
                )
            }
        }
        val request = (answer as CallResult.Ok).body
        val served = request["gate"] as? JsonObject
        val gateLabel = served?.string("label") ?: gate.label ?: "gate"
        val who = served?.let { approversOf(it) } ?: gate.approversPhrase
        val id = request.string("id") ?: "?"
        val by = request.string("decidedBy") ?: who
        return when (request.string("status")) {
            "approved" -> replies.say(
                "Ostrom: $by said yes to \"$label\" (gate \"$gateLabel\", receipt " +
                    "${request.string("receiptId") ?: "pending"}).",
            )
            "shadow" -> replies.say(
                "Ostrom: \"$label\" falls under the proposed gate \"$gateLabel\" ($who). Not decided yet, so it is " +
                    "only logged.",
            )
            "ungated", "consumed" -> replies.allow()
            "rejected" -> replies.deny(
                "Ostrom: $by said no to \"$label\" (gate \"$gateLabel\", receipt " +
                    "${request.string("receiptId") ?: "-"}). " +
                    "The action was not run. Do not retry it or work around it; report the refusal to the user.",
            )
            // the instruction first: Grok shows the model only the first ~200 characters of a refusal (measured 28/10)
            else -> replies.deny(
                "Ostrom: waiting for a yes. Run `ostrom wait $id` as its own command, then the exact same command " +
                    "again once it says approved. Not run yet: \"$label\" needs a yes from $who " +
                    "(gate \"$gateLabel\"); " +
                    "the request is in the Ostrom console. Do not work around it.",
            )
        }
    }

    /**
     * The subject with where its pushes land appended (`# ostrom: git push to: main`), so that a pattern on
     * `git … push … main` sees `git push origin HEAD` from main (audit A9). The second value is why the landing
     * could not be read, or null.
     */
    private fun withPushTargets(subject: ToolSubject, cwd: String): Pair<ToolSubject, String?> {
        if (subject.kind != ToolKind.BASH || !PushReader.mentions(subject.text)) return subject to null
        return try {
            val targets = pushes.destinations(location.directory(cwd, subject.text), subject.text)
            subject.copy(text = subject.text + targetsNote(targets)) to null
        } catch (e: UnreadablePushException) {
            subject to e.message.orEmpty()
        }
    }

    private fun readPush(directory: String, text: String): Result<Push?> = try {
        Result.success(pushes.read(directory, text.substringBefore(NOTE)))
    } catch (e: UnreadablePushException) {
        Result.failure(e)
    }

    private fun redirection(subject: ToolSubject): String? = if (subject.kind != ToolKind.BASH) {
        null
    } else {
        ShellWords.segments(subject.text).firstNotNullOfOrNull { words ->
            GitInvocation.parse(words)?.takeIf { it.verb == "push" }?.redirection()
        }
    }

    private fun reportSeen(event: JsonObject, subject: ToolSubject) {
        val (kind, name) = Seen.of(event, subject.text) ?: return
        val body = jsonOf(
            "sessionId" to ids.of(event.string("session_id")),
            "kind" to kind,
            "name" to name,
            "via" to event["tool_name"],
        )
        writes.send("/api/compiler/seen", body, event.string("session_id"), SEEN_MILLIS)
    }

    private fun foreignDenial(real: RealRepository): String {
        val mine = location.repositoryAt(context.launchTop)
        val launchTop = context.launchTop
        val session = context.repository
        val mineLabel = if (mine.origin.isNotEmpty() && mine.origin != session) {
            "$session (${mine.origin}, $launchTop)"
        } else {
            "$session ($launchTop)"
        }
        return "Ostrom: denied. This session was opened for the repository $mineLabel, but this command runs in the " +
            "repository ${real.label} (${real.top}). The session's checks and gates do not cover ${real.label}, so " +
            "the action was not run. Work only in $launchTop; do not run it elsewhere."
    }

    private fun unknownLanding(action: GateAction, reason: String): String {
        val gate = checkNotNull(action.gate)
        return "Ostrom: denied. Ostrom cannot tell which branches this push lands on ($reason), and " +
            "\"${action.label ?: action.actionClass}\" needs a yes from ${gate.approversPhrase} (gate " +
            "\"${gate.label ?: "gate"}\"). The action was not run. Run `git push <remote> <branch>` as its own command."
    }

    private fun approversOf(gate: JsonObject): String? {
        val list = (gate["approvers"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        return list?.let { if (it.isEmpty()) "the gate owner" else it.joinToString(" or ") }
    }

    private companion object {
        const val DENIED = 2
        const val REQUEST_MILLIS = 3_000
        const val CONSUME_MILLIS = 2_000
        const val SEEN_MILLIS = 2_000
        const val NOTE = "\n# ostrom: git push to:"

        fun targetsNote(targets: List<String>): String =
            if (targets.isEmpty()) "" else "$NOTE ${targets.joinToString(", ")}"

        fun pushJson(push: Push): JsonObject = jsonOf(
            "remote" to push.remoteRepository,
            "refs" to JsonArray(push.refs.map { jsonOf("ref" to it.destination, "sha" to it.sha) }),
            "force" to push.force,
        )
    }
}

/** What the after-tool hook saw of the package's skills and sub-agents (prototype `seen_of`, CORRECTIFS-2). */
object Seen {
    private val SKILL_FILE = Regex("skills/(ostrom-[A-Za-z0-9_-]+)/SKILL\\.md")

    /**
     * @return `skill` or `subagent` and its name, or null when the call opened nothing of the package's (names
     * without `ostrom`).
     */
    fun of(event: JsonObject, text: String): Pair<String, String>? {
        val input = event["tool_input"] as? JsonObject
        val field = { key: String -> input?.string(key) }
        val seen = when (event.string("tool_name")) {
            "Skill" -> (field("skill") ?: field("command") ?: field("name"))?.let { "skill" to it }
            "Task", "Agent", "spawn_agent" -> (field("subagent_type") ?: field("agent_type"))?.let { "subagent" to it }
            else -> SKILL_FILE.find(field("file_path") ?: text)?.let { "skill" to "/${it.groupValues[1]}/" }
        }
        return seen?.takeIf { "ostrom" in it.second }
    }
}
