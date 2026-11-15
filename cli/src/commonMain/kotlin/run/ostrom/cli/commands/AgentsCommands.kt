// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.guidance.GuidanceReads
import run.ostrom.cli.guidance.liveAt
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.Directive
import run.ostrom.core.api.models.DirectiveReceipt
import run.ostrom.core.api.models.DirectiveStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `ostrom agents`: the capacity advice for your agents (TeamCode's `teamcode agents`). */
internal class AgentsCommand : GroupCommand("agents", "The capacity advice for your agents", comingSoon = false)

/**
 * `ostrom agents watch [--session ID]`: the advice as JSON lines, each new one once, read every 15 s until stopped
 * (TeamCode's). With a session, its new advice is claimed as received by the CLI; without, the member-wide one is
 * read. It never launches nor stops anything; a failed read says so on stderr and is tried again.
 */
internal class AgentsWatchCommand(private val dependencies: CliDependencies) : CliktCommand(name = "watch") {
    override fun help(context: Context) =
        "Print each new capacity advice as a JSON line, until stopped; this never launches or stops an agent"

    private val session by option("--session", help = "An agent session (its launch id): only its new advice")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = dependencies.orgAccess(server, org)
        val reads = GuidanceReads(access.server, access.signed.token)
        runBlocking {
            var previous: String? = null
            while (true) {
                try {
                    previous = round(reads, previous)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    dependencies.console.err("Guidance is temporarily unavailable; retrying.\n")
                }
                dependencies.sleep(INTERVAL_MILLIS)
            }
        }
    }

    /** One read: prints and confirms the advice when it is new and in force; the id of the last one printed. */
    private suspend fun round(reads: GuidanceReads, previous: String?): String? {
        val named = session
        val claim = if (named == null) null else reads.claim(named, DirectiveStage.RECEIVED_BY_CLI)
        val advice = if (claim == null) reads.current() else claim.directive?.takeIf { claim.claimId != null }
        if (advice == null || advice.id == previous || !advice.liveAt(dependencies.clock())) return previous
        dependencies.console.data(OstromJson.encodeToString(Directive.serializer(), advice) + "\n")
        reads.acknowledge(DirectiveReceipt(advice.id, DirectiveStage.RECEIVED_BY_CLI, named, claim?.claimId))
        return advice.id
    }

    private companion object {
        const val INTERVAL_MILLIS = 15_000L
    }
}

/**
 * `ostrom agents hint --session ID [--format text|claude|json]`: the session's new advice for an agent's hook, claimed
 * as emitted to it, then confirmed (TeamCode's). `claude` prints the additional context of Claude Code's `PreToolUse`.
 * Nothing new, or any failure: nothing printed and exit 0, since an outage of the advice must never block a hook
 * (the server's admission still paces the calls), and no error reaches the model's context.
 */
internal class AgentsHintCommand(private val dependencies: CliDependencies) : CliktCommand(name = "hint") {
    override fun help(context: Context) =
        "Print the capacity advice new for an agent session, for its hook (--format claude: Claude Code's context)"

    private val session by option("--session", help = "The agent session (its launch id): only its new advice")
        .required()
    private val format by option("--format", help = "text (default), claude or json")
        .choice(TEXT, CLAUDE, JSON).default(TEXT)
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        try {
            val access = dependencies.orgAccess(server, org)
            runBlocking { hint(GuidanceReads(access.server, access.signed.token)) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // no raw error nor secret in the model's context
        }
    }

    private suspend fun hint(reads: GuidanceReads) {
        val claim = reads.claim(session, DirectiveStage.EMITTED_TO_HOOK)
        val claimId = claim.claimId ?: return
        val advice = claim.directive?.takeIf { it.liveAt(dependencies.clock()) } ?: return
        dependencies.console.data(shown(advice) + "\n")
        reads.acknowledge(DirectiveReceipt(advice.id, DirectiveStage.EMITTED_TO_HOOK, session, claimId))
    }

    private fun shown(advice: Directive): String = when (format) {
        JSON -> OstromJson.encodeToString(Directive.serializer(), advice)
        CLAUDE -> buildJsonObject {
            putJsonObject("hookSpecificOutput") {
                put("hookEventName", "PreToolUse")
                put("additionalContext", "Ostrom capacity guidance: ${advice.message}")
            }
        }.toString()
        else -> advice.message
    }

    private companion object {
        const val TEXT = "text"
        const val CLAUDE = "claude"
        const val JSON = "json"
    }
}
