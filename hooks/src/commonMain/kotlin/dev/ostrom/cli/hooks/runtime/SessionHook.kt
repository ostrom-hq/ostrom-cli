package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `ostrom hook session`: records the session with Ostrom at its start (and hands the agent the session's context),
 * signs its receipt at its end (prototype `hook_session`).
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
    private val writes = SessionWrites(calls, ids)

    /** Answers one event (normalised to the Claude Code shape). */
    fun handle(event: JsonObject): HookReply {
        val agentSession = event.string("session_id").orEmpty()
        return when (event.string("hook_event_name")) {
            "SessionStart" -> start(event, agentSession).fold(
                onSuccess = { text ->
                    // Grok and Hermes take the rules elsewhere (--rules, pre_llm_call): nothing on stdout for them
                    val quiet = context.dialect == "grok" || context.dialect == "hermes"
                    if (quiet) HookReply() else HookReply(stdout = startOutput(text))
                },
                onFailure = { HookReply(stderr = "Ostrom unreachable (${it.message}): session not recorded.\n") },
            )
            // Hermes: its session events cannot add context, so the rules go in on the first model call
            "PreLLMCall" -> firstModelCall(event, agentSession)
            "SessionEnd" -> end(event)
            else -> HookReply()
        }
    }

    private fun firstModelCall(event: JsonObject, agentSession: String): HookReply {
        val mark = context.sessionFile?.let { "$it.context-$agentSession" } ?: return HookReply()
        if (files.read(mark) != null) return HookReply()
        return start(event, agentSession).fold(
            onSuccess = { text ->
                files.write(mark, "1")
                HookReply(stdout = JsonObject(mapOf("context" to JsonPrimitive(text))).toString() + "\n")
            },
            onFailure = { HookReply(stderr = "Ostrom unreachable (${it.message}): session not recorded.\n") },
        )
    }

    private fun start(event: JsonObject, agentSession: String): Result<String> {
        ids.adopt(agentSession, agentSession)
        val body = jsonOf(
            "id" to agentSession,
            "repo" to context.repository,
            "cwd" to (event.string("cwd") ?: workingDirectory),
            "agent" to context.agent,
            "dev" to context.developer,
            "harnessVersion" to context.harnessVersion,
            "model" to (event.string("model") ?: context.model),
            "source" to event["source"],
            "launcher" to "kotlin",
            "launchId" to context.launchId,
            "packageDigest" to checks.digest,
        )
        val answer = calls.call("/api/bridge/sessions", body, START_MILLIS)
        if (answer is CallResult.Failed) return Result.failure(IllegalStateException(answer.message))
        val reply = (answer as CallResult.Ok).body
        // a resumed session is a new segment on Ostrom's side: its id is the one every hook of this launch sends
        val segment = (reply["session"] as? JsonObject)?.string("id")
        if (!segment.isNullOrEmpty()) writes.adopt(agentSession, segment)
        val text = listOfNotNull(reply.string("context").orEmpty(), checks.context).joinToString("\n")
        return Result.success(text)
    }

    private fun end(event: JsonObject): HookReply {
        val session = ids.of(event.string("session_id"))
        val body = jsonOf("source" to (event.string("reason") ?: "SessionEnd"))
        val path = "/api/bridge/sessions/${Urls.encode(session)}/end"
        return when (val answer = calls.call(path, body, context.sessionEndMillis)) {
            is CallResult.Failed ->
                HookReply(stderr = "Ostrom unreachable (${answer.message}): session receipt not signed.\n")
            is CallResult.Ok -> {
                val receipt = (answer.body["session"] as? JsonObject)?.string("receiptId") ?: "?"
                val verification = answer.body["verification"] as? JsonObject
                val valid = (verification?.get("ok") as? JsonPrimitive)?.content == "true"
                val state = if (valid) "signature valid" else "not verified"
                HookReply(stderr = "Ostrom: session receipt $receipt ($state)\n")
            }
        }
    }

    private fun startOutput(text: String): String {
        val specific = jsonOf("hookEventName" to "SessionStart", "additionalContext" to text)
        return jsonOf("hookSpecificOutput" to specific).toString() + "\n"
    }

    private companion object {
        const val START_MILLIS = 8_000
    }
}
