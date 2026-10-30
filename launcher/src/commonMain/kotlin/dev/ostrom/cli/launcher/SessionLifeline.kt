// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The launcher's side of a session while the agent runs and after it ends: a sign of life every 20 s, a new segment
 * when Ostrom closed the session for silence (the machine slept), the end with the agent's exit code, and the
 * workflow receipt with its bundle, kept to verify offline.
 *
 * The session id is the one the SessionStart hook wrote in [sessionFile]; until it is there, nothing is sent.
 */
class SessionLifeline(
    private val server: ServerJson,
    private val files: LaunchFiles,
    private val agentToken: String,
    private val launchId: String,
    private val sessionFile: String,
    private val say: (String) -> Unit,
) {
    /** The session the hooks recorded, or an empty string. */
    fun current(): String = files.read(sessionFile)?.trim().orEmpty()

    /** Sends a sign of life every [everyMillis] until cancelled. */
    suspend fun keepAlive(everyMillis: Long) {
        while (true) {
            delay(everyMillis)
            try {
                beat()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // a segment that could not be written down: the next sign of life tries again, and the agent runs on
            }
        }
    }

    /** One sign of life; back from a sleep longer than Ostrom waits, the session continues as a new segment. */
    suspend fun beat() {
        val session = current()
        if (session.isEmpty()) return
        val answer = server.post(Routes.alive(session), objectOf("launchId" to launchId), agentToken, ALIVE_MILLIS)
        val closed = when (answer) {
            is ServerAnswer.Ok -> answer.body.text("endedAt") != null
            is ServerAnswer.Failed -> answer.type == "session_closed"
        }
        if (closed) reopen(session)
    }

    private suspend fun reopen(closed: String) {
        val answer = server.post(Routes.reopen(closed), objectOf("launchId" to launchId), agentToken, REOPEN_MILLIS)
        val body = when (answer) {
            is ServerAnswer.Ok -> answer.body
            is ServerAnswer.Failed -> return
        }
        val session = body.child("session") ?: body
        val id = session.text("id")
        if (id.isNullOrEmpty()) return
        adopt(session.text("agentSessionId") ?: closed.substringBefore('~'), id)
    }

    /** From now on the hooks of this launch send [ostromId]; events of [agentSessionId] map to it. */
    private fun adopt(agentSessionId: String, ostromId: String) {
        files.write(sessionFile, ostromId, FileAccess.PRIVATE)
        val mapFile = "$sessionFile.map.json"
        val map = parseObject(files.read(mapFile)) ?: JsonObject(emptyMap())
        val updated = JsonObject(map + (agentSessionId to JsonPrimitive(ostromId)))
        files.write(mapFile, updated.toString(), FileAccess.PRIVATE)
    }

    /**
     * Ends the session (the safety net of the SessionEnd hook: idempotent on the server) and signs the workflow
     * receipt of [pack].
     *
     * @param exitCode The agent's exit code.
     * @param model The model asked, or null.
     * @param agent The agent's name in Ostrom (`claude-code`, `codex`…).
     * @param receipts Where receipts are kept (`<config>/receipts`).
     */
    suspend fun end(exitCode: Int, model: String?, agent: String, pack: SessionPackage?, receipts: String) {
        val session = current()
        if (session.isEmpty()) {
            say("no session was recorded (the session hook did not run).")
            return
        }
        val body = objectOf("source" to SOURCE, "exitCode" to exitCode, "model" to model)
        when (val answer = server.post(Routes.end(session), body, agentToken, END_MILLIS)) {
            is ServerAnswer.Ok -> say(endLine(session, answer.body))
            is ServerAnswer.Failed -> say("session receipt not signed: ${answer.message}")
        }
        if (pack != null) workflowReceipt(session, pack, agent, model, receipts)
    }

    private fun endLine(session: String, body: JsonObject): String {
        val segment = body.child("session") ?: body
        val receipt = body.child("receipt")
        val id = segment.text("receiptId") ?: receipt?.text("id") ?: "?"
        val state = when {
            body.child("verification")?.flag("ok") == true -> "signature valid"
            receipt?.text("signature") != null -> "signed"
            else -> "NOT verified"
        }
        return "session $session · receipt $id ($state) · ${segment.list("gates").size} gate request(s)"
    }

    private suspend fun workflowReceipt(
        session: String,
        pack: SessionPackage,
        agent: String,
        model: String?,
        kept: String,
    ) {
        val body = objectOf("digest" to pack.digest, "agent" to agent, "model" to model)
        val answer = server.post(Routes.workflowReceipt(session), body, agentToken, END_MILLIS)
        if (answer is ServerAnswer.Failed) {
            say("workflow receipt not signed: ${answer.message}")
            return
        }
        val receipt = (answer as ServerAnswer.Ok).body.child("receipt")
        val receiptId = receipt?.text("id")
        val id = receiptId ?: "?"
        val valid = if (answer.body.child("verification")?.flag("ok") == true) "signature valid" else "NOT verified"
        say("workflow receipt $id ($valid) · package ${pack.version}")
        if (receiptId == null) return
        when (val bundle = server.get(Routes.bundle(id), agentToken, BUNDLE_MILLIS)) {
            is ServerAnswer.Ok -> {
                val path = join(kept, "$id.json")
                files.write(path, pretty(bundle.body), FileAccess.PRIVATE)
                say("receipt kept in $path · check it offline: ostrom verify $id")
            }
            is ServerAnswer.Failed -> say("receipt bundle not fetched (${bundle.message})")
        }
    }

    private companion object {
        const val SOURCE = "ostrom launcher"
        const val ALIVE_MILLIS = 3_000L
        const val REOPEN_MILLIS = 5_000L
        const val END_MILLIS = 15_000L
        const val BUNDLE_MILLIS = 10_000L
    }
}
