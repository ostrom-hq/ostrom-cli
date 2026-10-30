// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.CommandLocation
import dev.ostrom.cli.hooks.gate.GateCatalogue
import dev.ostrom.cli.hooks.gate.GitRunner
import dev.ostrom.cli.hooks.gate.PatternCache
import dev.ostrom.cli.hooks.gate.PushReader
import dev.ostrom.cli.hooks.gate.ToolKind
import dev.ostrom.cli.hooks.gate.ToolSubject
import dev.ostrom.cli.hooks.gate.UnreadablePushException
import kotlinx.serialization.json.JsonObject

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
    private val writes = SessionWrites(calls, ids, context.launchId)
    private val location = CommandLocation(git, context.home)
    private val pushes = PushReader(git, context.home)
    private val patterns = PatternCache()
    private val requests = GateRequests(context, writes, ids, replies, location, pushes, git)

    /** Answers one event (normalised to the Claude Code shape). */
    fun handle(event: JsonObject): HookReply {
        val after = event.string("hook_event_name") == "PostToolUse"
        val cwd = event.string("cwd") ?: workingDirectory
        val subject = ToolSubject.of(event.string("tool_name").orEmpty(), event["tool_input"])
        if (after) reportSeen(event, subject)
        return when {
            subject.text.isEmpty() -> replies.allow()
            // the before-tool hook refused this very command: it did not run, nothing follows it
            after && ids.wasRefused(subject.text) -> {
                ids.markRefused(subject.text, refused = false)
                replies.allow()
            }
            after -> afterTool(event, subject, cwd)
            else -> beforeTool(event, subject, cwd).also { ids.markRefused(subject.text, it.exitCode == DENIED) }
        }
    }

    private fun beforeTool(event: JsonObject, raw: ToolSubject, cwd: String): HookReply {
        val catalogue = catalogues.current()
        val (subject, landing) = withPushTargets(raw, cwd)
        val action = catalogue.match(subject, patterns)
        val gate = action?.gate
        val foreign = if (checks.guards(subject) || gate != null) {
            location.foreign(cwd, subject.text, context.launchTop, canonical)
        } else {
            null
        }
        return when {
            foreign != null ->
                replies.deny(GateTexts.foreign(context, location.repositoryAt(context.launchTop), foreign))
            else -> checks.beforeTool(event, subject, cwd) ?: when {
                action != null && gate != null ->
                    requests.ask(GateAsk(event, raw.text, subject, cwd, action, gate, catalogue.failMode))
                landing != null -> landingReply(catalogue, subject, landing)
                else -> replies.allow()
            }
        }
    }

    /** A push whose landing Ostrom cannot read may land on main: refused while a gate on main is enforced (A9). */
    private fun landingReply(catalogue: GateCatalogue, subject: ToolSubject, landing: String): HookReply {
        val main = subject.copy(text = subject.text + GateRequests.targetsNote(listOf("main")))
        val worst = catalogue.match(main, patterns)
        val gate = worst?.gate?.takeIf { it.enforce }
        return if (worst != null && gate != null) {
            replies.deny(GateTexts.unknownLanding(worst.name, gate, landing))
        } else {
            replies.allow()
        }
    }

    private fun afterTool(event: JsonObject, raw: ToolSubject, cwd: String): HookReply {
        checks.afterTool(event, withPushTargets(raw, cwd).first)
        // the action ran: the yes it ran under is used up (one yes, one action)
        val gateId = ids.takeYes(raw.text)
        if (gateId != null) calls.call(Routes.consume(gateId), JsonObject(emptyMap()), CONSUME_MILLIS)
        return replies.allow()
    }

    /**
     * The subject with where its pushes land appended (`# ostrom: git push to: main`), so that a pattern on
     * `git … push … main` sees `git push origin HEAD` from main (audit A9). The second value is why the landing
     * could not be read, or null.
     */
    private fun withPushTargets(subject: ToolSubject, cwd: String): Pair<ToolSubject, String?> =
        if (subject.kind != ToolKind.BASH || !PushReader.mentions(subject.text)) {
            subject to null
        } else {
            try {
                val targets = pushes.destinations(location.directory(cwd, subject.text), subject.text)
                subject.copy(text = subject.text + GateRequests.targetsNote(targets)) to null
            } catch (e: UnreadablePushException) {
                subject to e.message.orEmpty()
            }
        }

    private fun reportSeen(event: JsonObject, subject: ToolSubject) {
        val (kind, name) = Seen.of(event, subject.text) ?: return
        val body = jsonOf("kind" to kind, "name" to name, "via" to event["tool_name"])
        val agentSession = event.string("session_id")
        writes.send(ids.of(agentSession), agentSession, SEEN_MILLIS) { session -> Routes.seen(session) to body }
    }

    private companion object {
        const val DENIED = 2
        const val CONSUME_MILLIS = 2_000
        const val SEEN_MILLIS = 2_000
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
