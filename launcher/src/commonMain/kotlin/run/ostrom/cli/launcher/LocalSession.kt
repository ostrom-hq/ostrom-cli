// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Local sessions (TeamCode's `cloud.sessions.local.v1`): the session of an `ostrom <agent>` launch. */
const val RUNNERS_CLOUD_LOCAL = "runners.cloud.local.v1"

/**
 * The server-side session of one `ostrom <agent>` launch (kind `local`, TeamCode's `LocalSession`,
 * docs/cloud-sessions.md "Local sessions"): what lists the launch beside the cloud and remote sessions in the apps,
 * with its launch id. The launch hosts it like a remote host, under the member's own sign-in: it takes the session's
 * one execution (`POST /cloud/sessions/{id}/host`, from any of the member's devices) and heartbeats on the runner
 * channel every 15 s.
 * It lives as long as the launch: [close] stops it. After 90 s without a heartbeat (the computer slept or lost its
 * network) the server pauses it; [keepHosted] then takes the next epoch by itself, whenever the launch is back. A
 * launch that is killed leaves it paused until the organization's paused retention ends it.
 *
 * Departure from TeamCode: Ostrom's waits and todo of a launch already live on its governance session (the launch's
 * agent token), so no credential confined to this session is asked for, and the agent's tools do not depend on it.
 *
 * @property sessionId The cloud session's id.
 */
class LocalSession private constructor(
    val sessionId: String,
    private val server: ServerJson,
    private val token: String,
    private val say: (String) -> Unit,
    epoch: Long,
) {
    // The epoch changes when the session is resumed after the computer was offline: every heartbeat reads it.
    private var epoch: Long = epoch

    /**
     * Keeps the session heartbeating every [everyMillis], and resumes it when the server ended its execution (a pause
     * for silence, a fence): the launch, being alive, takes the next epoch. Returns only when the session cannot be
     * resumed (deleted, or ended by its retention); cancelled with the agent's end.
     */
    suspend fun keepHosted(everyMillis: Long = HEARTBEAT_MILLIS) {
        while (true) {
            while (beat()) delay(everyMillis)
            say("ostrom: the local session went offline on the server; resuming it.")
            epoch = resume() ?: return
            say("ostrom: the local session is back.")
        }
    }

    /** One heartbeat; false when the server ended this execution (so the next epoch must be taken). */
    internal suspend fun beat(): Boolean {
        val body = objectOf(
            "sessionId" to sessionId,
            "epoch" to epoch,
            "ready" to true,
            "stopped" to false,
            "background" to false,
        )
        return when (val answer = server.post(HEARTBEAT, body, token, CALL_MILLIS)) {
            is ServerAnswer.Ok -> answer.body.text("instruction") != STOP
            // 401 or 409: another epoch holds the session, or the server ended this one; anything else is the network
            is ServerAnswer.Failed -> answer.status != UNAUTHORIZED && answer.status != CONFLICT
        }
    }

    /**
     * Takes the next execution of the session and returns its epoch, retrying while the server or the network is out
     * of reach, however long that lasts. Null when the session is gone for good.
     */
    private suspend fun resume(): Long? {
        var wait = RESUME_RETRY_MILLIS
        while (true) {
            when (val answer = host(server, sessionId, token)) {
                is ServerAnswer.Ok -> answer.body.epoch()?.let { return it }
                is ServerAnswer.Failed -> if (answer.status == NOT_FOUND || answer.type == NOT_RUNNING) {
                    say("ostrom: the local session ended on the server and cannot be resumed; the agent goes on.")
                    return null
                }
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(RESUME_RETRY_MAX_MILLIS)
        }
    }

    /** Stops the session (the agent exited); a stop that is lost is a pause by the server once heartbeats stop. */
    suspend fun close() {
        withContext(NonCancellable) {
            if (server.call("DELETE", path(sessionId), null, token, CALL_MILLIS) is ServerAnswer.Failed) {
                say(
                    "ostrom: the local session could not be closed; the server pauses it after 90 seconds and " +
                        "ends it with the organization's paused retention.",
                )
            }
        }
    }

    /** Opening a launch's session. */
    companion object {
        private const val SESSIONS = "/api/v1/cloud/sessions"
        private const val HEARTBEAT = "/api/v1/cloud/runner/heartbeat"
        private const val LOCAL = "local"
        private const val STOP = "stop"
        private const val NOT_RUNNING = "session_not_running"
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val CALL_MILLIS = 5_000L
        private const val NAME_LENGTH = 80

        /** Between two heartbeats, as a remote host's. */
        const val HEARTBEAT_MILLIS = 15_000L
        private const val RESUME_RETRY_MILLIS = 5_000L
        private const val RESUME_RETRY_MAX_MILLIS = 60_000L

        /** The agents a local session takes, as TeamCode's. */
        private val AGENTS = setOf(Agent.CLAUDE, Agent.CODEX)

        /** Whether a launch of [agent] opens a local session on a server that announces [features]. */
        fun wanted(agent: Agent, features: Set<String>): Boolean = agent in AGENTS && RUNNERS_CLOUD_LOCAL in features

        /** Opens the session of [launch] at [site], with the member's sign-in there. */
        internal suspend fun open(dependencies: LaunchDependencies, site: LaunchSite, launch: Launch, agent: Agent) =
            open(dependencies.server, site.credentials.token, launch.launchId, agent, site.cwd, dependencies.say)

        /**
         * Opens the session of launch [launchId] (create, take the one execution, report ready) with the member's
         * [token]. Null, with a notice, for any failure (a launch the server does not know, a service key, the
         * network): the launch then runs without a session, as before.
         *
         * @param directory The launch's working directory, whose last segment names the session.
         */
        @Suppress("LongParameterList") // the launch's facts, one each
        suspend fun open(
            server: ServerJson,
            token: String,
            launchId: String,
            agent: Agent,
            directory: String,
            say: (String) -> Unit,
        ): LocalSession? {
            val body = objectOf(
                "agent" to agent.command,
                "name" to nameOf(agent, directory),
                "kind" to LOCAL,
                "launchId" to launchId,
            )
            val created = server.call("POST", SESSIONS, body, token, CALL_MILLIS, idempotencyKey = launchId)
            val id = if (created is ServerAnswer.Ok) created.body.text("id") else null
            val hosted = id?.let { host(server, it, token) } as? ServerAnswer.Ok
            val epoch = hosted?.let { it.body.epoch() }
            val session = if (id != null && epoch != null) LocalSession(id, server, token, say, epoch) else null
            // Ready before the agent starts, as a remote host reports it.
            if (session != null && session.beat()) return session
            val why = when {
                created !is ServerAnswer.Failed -> "the server did not host it"
                created.status == null -> "network error"
                created.type == null -> "HTTP ${created.status}"
                else -> "HTTP ${created.status} ${created.type}"
            }
            say("ostrom: the local session of this launch could not be opened ($why); the agent starts without it.")
            if (id != null) withContext(NonCancellable) { server.call("DELETE", path(id), null, token, CALL_MILLIS) }
            return null
        }

        private fun nameOf(agent: Agent, directory: String): String {
            val folder = directory.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').take(NAME_LENGTH)
            return (if (folder.isBlank()) "${agent.command} run" else "${agent.command} in $folder")
                .filterNot { it.isISOControl() }
        }

        private fun path(id: String) = "$SESSIONS/${Routes.encode(id)}"

        private suspend fun host(server: ServerJson, id: String, token: String): ServerAnswer =
            server.post("${path(id)}/host", objectOf(), token, CALL_MILLIS)

        private fun kotlinx.serialization.json.JsonObject.epoch(): Long? = (this["epoch"] as? JsonPrimitive)?.longOrNull
    }
}

/** Stops [this] session when the launch opened one, however the agent ended. */
internal suspend fun LocalSession?.closeIfOpen() {
    this?.close()
}
