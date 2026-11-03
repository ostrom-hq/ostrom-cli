// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `ostrom hook session`: records the session with Ostrom at its start and hands the agent the session's context
 * (prototype `hook_session`). Its end is the launcher's: the agent cannot end the session it is watched in (S2-25).
 */
@Suppress("LongParameterList") // the hook's collaborators, each a port tested on its own
class SessionHook(
    private val context: HookContext,
    private val calls: OstromCalls,
    private val ids: SessionIds,
    private val files: HookFiles,
    private val checks: WorkflowChecks,
    private val workingDirectory: String,
) {
    private val writes = SessionWrites(calls, ids, context.launchId)

    /** Answers one event (normalised to the Claude Code shape). */
    fun handle(event: JsonObject): HookReply {
        val agentSession = event.string("session_id").orEmpty()
        return when (event.string("hook_event_name")) {
            "SessionStart" -> when (val started = start(event, agentSession)) {
                is Started.Recorded -> {
                    // Grok and Hermes take the rules elsewhere (--rules, pre_llm_call): nothing on stdout for them
                    val quiet = context.dialect == "grok" || context.dialect == "hermes"
                    if (quiet) HookReply() else HookReply(stdout = startOutput(started.text))
                }
                is Started.NotRecorded -> started.reply()
            }
            // Hermes: its session events cannot add context, so the rules go in on the first model call
            "PreLLMCall" -> firstModelCall(event, agentSession)
            else -> HookReply()
        }
    }

    private fun firstModelCall(event: JsonObject, agentSession: String): HookReply {
        val session = context.sessionFile
        val mark = if (session == null) null else "$session.context-$agentSession".takeIf { files.read(it) == null }
        return if (mark == null) {
            HookReply()
        } else {
            when (val started = start(event, agentSession)) {
                is Started.Recorded -> {
                    files.write(mark, "1")
                    HookReply(stdout = JsonObject(mapOf("context" to JsonPrimitive(started.text))).toString() + "\n")
                }
                is Started.NotRecorded -> started.reply()
            }
        }
    }

    /** What the session's start came to. */
    private sealed interface Started {
        /** Recorded: [text] is the context the agent is handed. */
        class Recorded(val text: String) : Started

        /** Not recorded: Ostrom refused it (a 4xx with its reason), or could not be reached, or failed. */
        class NotRecorded(val answer: CallResult.Failed) : Started {
            /** Said to the developer; the agent goes on, handed no context, and every gate still asks Ostrom. */
            fun reply(): HookReply {
                val why = if (answer.refused) "Ostrom refused" else "Ostrom unreachable"
                return HookReply(stderr = "$why (${answer.message}): session not recorded.\n")
            }
        }
    }

    private fun start(event: JsonObject, agentSession: String): Started {
        ids.adopt(agentSession, agentSession)
        val body = jsonOf(
            "id" to agentSession,
            "repo" to context.repository,
            "cwd" to (event.string("cwd") ?: workingDirectory),
            "agent" to context.agent,
            "dev" to context.developer,
            "harnessVersion" to context.harnessVersion,
            "model" to (event.string("model") ?: context.model),
            "launchId" to context.launchId,
            "packageDigest" to checks.digest,
        )
        val answer = calls.call(Routes.SESSIONS, body, START_MILLIS)
        if (answer is CallResult.Failed) return Started.NotRecorded(answer)
        val reply = (answer as CallResult.Ok).body
        // a resumed session is a new segment on Ostrom's side: its id is the one every hook of this launch sends
        val segment = (reply["session"] as? JsonObject)?.string("id")
        if (!segment.isNullOrEmpty()) writes.adopt(agentSession, segment)
        val text = listOfNotNull(reply.string("context").orEmpty(), checks.context).joinToString("\n")
        return Started.Recorded(text)
    }

    private fun startOutput(text: String): String {
        val specific = jsonOf("hookEventName" to "SessionStart", "additionalContext" to text)
        return jsonOf("hookSpecificOutput" to specific).toString() + "\n"
    }

    private companion object {
        const val START_MILLIS = 8_000
    }
}
