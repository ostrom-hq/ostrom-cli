// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply
import run.ostrom.cli.hooks.gate.CommandChecks
import run.ostrom.cli.hooks.gate.CommandLocation
import run.ostrom.cli.hooks.gate.GateCatalogue
import run.ostrom.cli.hooks.gate.GitRunner
import run.ostrom.cli.hooks.gate.PatternCache
import run.ostrom.cli.hooks.gate.PushReader
import run.ostrom.cli.hooks.gate.ScriptTexts
import run.ostrom.cli.hooks.gate.ShellPaths
import run.ostrom.cli.hooks.gate.ToolKind
import run.ostrom.cli.hooks.gate.ToolSubject
import run.ostrom.cli.hooks.gate.UnreadablePushException
import kotlinx.serialization.json.JsonObject

/**
 * `ostrom hook gate`, before and after every tool call (prototype `hook_gate`, with the audit fixes of 30/10).
 *
 * The gate never holds the agent: it answers at once (allow, or "waiting for a yes") and the server keeps the
 * decision; the same command passes once the owner said yes. A guarded action run in another repository than the
 * session's is refused; a push is read for the commits it sends, the ref it lands on, the remote it goes to and its
 * force, and a push that redirects git is refused before anyone is asked. A command the gate cannot read while a gate
 * is enforced (a program named by a variable, a change to git's remotes or aliases) is refused, and the scripts a
 * command runs are read with it (security audit S2-26, S2-27).
 */
@Suppress("LongParameterList") // the hook's collaborators, each a port tested on its own
class GateHook(
    private val context: HookContext,
    private val calls: OstromCalls,
    private val ids: SessionIds,
    private val files: HookFiles,
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
    private val refusals = LocalRefusals(context, writes, ids, replies)

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

    private fun beforeTool(event: JsonObject, written: ToolSubject, cwd: String): HookReply =
        when (val read = catalogues.current()) {
            // no policy to trust: every action is held, not only a few classes of commands (S2-20, S2-38)
            is CatalogueRead.Held ->
                refusals.deny(event, null, LocalRefusals.POLICY_HELD, written.text, GateTexts.held(read.reason))
            is CatalogueRead.Verified -> beforeTool(event, written, cwd, read.catalogue)
        }

    private fun beforeTool(event: JsonObject, written: ToolSubject, cwd: String, catalogue: GateCatalogue): HookReply {
        val raw = withWhatItReaches(written, cwd)
        val (subject, landing) = withPushTargets(raw, cwd)
        val action = catalogue.match(subject, patterns)
        val gate = action?.gate
        // a gate only proposed (shadow) never stops the agent, not even from another clone
        val foreign = if (checks.guards(subject) || gate?.enforce == true) {
            location.foreign(cwd, subject.text, context.launchTop, canonical)
        } else {
            null
        }
        val unread = if (raw.kind == ToolKind.BASH) unreadable(event, catalogue, raw.text) else null
        return when {
            unread != null -> unread
            foreign != null -> refusals.deny(
                event,
                action?.actionClass,
                LocalRefusals.FOREIGN_REPOSITORY,
                subject.text,
                GateTexts.foreign(context, location.repositoryAt(context.launchTop), foreign),
            )
            else -> checks.beforeTool(event, subject, cwd) ?: when {
                action != null && gate != null ->
                    requests.ask(GateAsk(event, raw.text, subject, cwd, action, gate, catalogue.failMode))
                landing != null -> landingReply(event, catalogue, subject, landing)
                else -> replies.allow()
            }
        }
    }

    /** A push whose landing Ostrom cannot read may land on main: refused while a gate on main is enforced (A9). */
    private fun landingReply(
        event: JsonObject,
        catalogue: GateCatalogue,
        subject: ToolSubject,
        landing: String,
    ): HookReply {
        val main = subject.copy(text = subject.text + GateRequests.targetsNote(listOf("main")))
        val guard = catalogue.enforcedFor(main, patterns)
        return if (guard == null) {
            replies.allow()
        } else {
            val text = GateTexts.unknownLanding(guard.first.name, guard.second, landing)
            refusals.deny(event, guard.first.actionClass, LocalRefusals.UNKNOWN_LANDING, subject.text, text)
        }
    }

    /** A program named by a variable, or a change to where pushes go, while a gate holds them (S2-26, S2-27). */
    private fun unreadable(event: JsonObject, catalogue: GateCatalogue, text: String): HookReply? {
        val program = CommandChecks.dynamicProgram(text)
        val guard = if (program == null) null else catalogue.enforcedOn(ToolKind.BASH.wire)
        val setting = CommandChecks.pushSetting(text)
        val push = ToolSubject(ToolKind.BASH, ToolKind.BASH.wire, "git push" + GateRequests.targetsNote(listOf("main")))
        val pushGuard = if (setting == null) null else catalogue.enforcedFor(push, patterns)
        return when {
            program != null && guard != null -> refusals.deny(
                event,
                guard.first.actionClass,
                LocalRefusals.DYNAMIC_COMMAND,
                text,
                GateTexts.dynamicCommand(guard.first.name, guard.second, program),
            )
            setting != null && pushGuard != null -> refusals.deny(
                event,
                pushGuard.first.actionClass,
                LocalRefusals.GIT_SETTING,
                text,
                GateTexts.gitSetting(pushGuard.first.name, pushGuard.second, setting),
            )
            else -> null
        }
    }

    /** The command with the scripts it runs appended, so that the patterns and the push reader read them too. */
    private fun withScripts(subject: ToolSubject, cwd: String): ToolSubject {
        val scripts = ScriptTexts.of(subject.text, location.directory(cwd, subject.text), context.home) {
            files.readStart(it, ScriptTexts.MAX_CHARS)
        }
        return if (scripts.isEmpty()) subject else subject.copy(text = subject.text + scripts)
    }

    /**
     * [written] with what a shell command runs, or with where a file write really lands ([ToolSubject.reaches]) when
     * that is not the path the agent gave: a relative path, `a/./b`, `a/../b` or a folder linked elsewhere (security
     * audit of the CLI n°2, M2). The patterns then see it. A file tool expands no `~`: `~/x` is under the working
     * folder.
     */
    private fun withWhatItReaches(written: ToolSubject, cwd: String): ToolSubject {
        val path = written.path
        return when {
            written.kind == ToolKind.BASH -> withScripts(written, cwd)
            path == null -> written
            else -> {
                val absolute = if (ShellPaths.isAbsolute(path)) path else "${cwd.trimEnd('/', '\\')}/$path"
                val real = ShellPaths.real(absolute, canonical)
                if (real == path) written else written.copy(reaches = real)
            }
        }
    }

    // the action ran: Ostrom, not the hook, uses its yes up when it sees the push land (S2-29)
    private fun afterTool(event: JsonObject, raw: ToolSubject, cwd: String): HookReply {
        checks.afterTool(event, withPushTargets(raw, cwd).first)
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
                subject to e.reason
            }
        }

    private fun reportSeen(event: JsonObject, subject: ToolSubject) {
        val (kind, name) = Seen.of(event, subject.text) ?: return
        // `via` is how Ostrom learnt it (`hook` or `mcp`, ostrom-server `SeenRequest`), not the agent's tool
        val body = jsonOf("kind" to kind, "name" to name, "via" to "hook")
        val agentSession = event.string("session_id")
        writes.send(ids.of(agentSession), agentSession, SEEN_MILLIS) { session -> Routes.seen(session) to body }
    }

    private companion object {
        const val DENIED = 2
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
