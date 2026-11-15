// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.daemon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.commands.cloudSessionPath
import run.ostrom.cli.commands.directory
import run.ostrom.cli.commands.launchOrg
import run.ostrom.cli.commands.segment
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.serverOf
import run.ostrom.cli.launch.ApiServerTransport
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import run.ostrom.cli.remote.epochOf
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.DaemonListingAnswer
import run.ostrom.core.api.models.DaemonPoll
import run.ostrom.core.api.models.DaemonPollRequest
import run.ostrom.core.api.models.Device
import run.ostrom.core.api.models.DevicePage
import run.ostrom.core.api.models.DeviceFolder
import run.ostrom.core.api.models.PublishDeviceFoldersRequest
import run.ostrom.core.api.models.RefuseDaemonOrderRequest

/**
 * A call of the daemon's channel that did not succeed: the server's refusal, or no answer at all ([failure]'s status
 * null). TeamCode's `TeamCodeException`, for the daemon's code, which says it and goes on.
 */
internal class DaemonCallException(val failure: ServerAnswer.Failed) : Exception(failure.message) {
    /** The HTTP status, null when the server was not reached. */
    val status: Int? get() = failure.status

    /** The contract's error type, such as `daemon_requires_device`. */
    val type: String? get() = failure.type

    /** The refusal as the daemon says it: its type and the server's message, never a body (TeamCode's `describe`). */
    fun describe(): String {
        val code = when {
            type != null -> "$type: "
            status != null -> "HTTP $status: "
            else -> ""
        }
        return code + failure.message
    }
}

/** The server's refusal [error] carries; null when the server was not reached, or for any other failure. */
internal fun refusalOf(error: Exception): DaemonCallException? =
    (error as? DaemonCallException)?.takeIf { it.status != null }

/** Whether [error] is the server's refusal with [status]. */
internal fun refusedWith(error: Exception, status: Int): Boolean = (error as? DaemonCallException)?.status == status

/**
 * What a session says to the daemon (`GET /api/v1/cloud/sessions/{id}`, the contract's `CloudSession`).
 *
 * @property status `creating`, `running`, `pausing`, `paused`, `resuming`, `terminated`, `failed`...
 * @property desiredStatus `running`, `paused` or `terminated`.
 * @property epoch The current execution, 0 before the first.
 * @property online Whether its host heartbeats.
 * @property failureCode Why it paused or ended on its own (`host_offline`, `host_refused`).
 * @property failureMessage The server's sentence for it.
 * @property folderId The daemon's folder it was started in; null for a session hosted by a terminal.
 */
internal data class SessionState(
    val status: String?,
    val desiredStatus: String?,
    val epoch: Long,
    val online: Boolean,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val folderId: String? = null,
) {
    /** It never runs again. */
    val ended: Boolean get() = status in ENDED || desiredStatus == TERMINATED

    /** Paused and meant to stay so: by its owner, or by the server for a lost host ([failureCode]). */
    val paused: Boolean get() = status == PAUSED && desiredStatus == PAUSED

    /** The session as the server sent it. */
    companion object {
        /** A status. */
        const val PAUSED = "paused"

        /** A desired status. */
        const val RUNNING = "running"
        private const val TERMINATED = "terminated"
        private val ENDED = setOf(TERMINATED, "failed")

        /** Read from the session's JSON. */
        fun of(session: JsonObject): SessionState {
            val host = session.child("host")
            val failure = session.child("failure")
            return SessionState(
                status = session.text("status"),
                desiredStatus = session.text("desiredStatus"),
                epoch = epochOf(session) ?: 0,
                online = host?.flag("online") == true,
                failureCode = failure?.text("code"),
                failureMessage = failure?.text("message"),
                folderId = host?.text("folderId"),
            )
        }
    }
}

/**
 * The calls of the device's daemon (`runners.cloud.remote.daemon.v1`) and of `ostrom remote` through it, on the
 * organisation's JSON API with the device's own sign-in: TeamCode's typed client methods, in Ostrom's JSON calls.
 * Each throws [DaemonCallException] when it does not succeed.
 *
 * @param server The organisation's API (`Ostrom-Org` on every call).
 * @param token The device's token, read again at each call (a new `ostrom login` while the service runs).
 */
@Suppress("TooManyFunctions") // one function per route of the daemon's channel
internal class DaemonServer(private val server: ServerJson, private val token: () -> String?) {
    /** The server's features (`GET /api/v1/capabilities`), read at each call: the service outlives an upgrade. */
    suspend fun capabilities(): List<String> =
        ok(call("GET", CAPABILITIES)).list("features").mapNotNull { (it as? JsonPrimitive)?.content }

    /** The long poll: waits up to [waitMillis] for orders and listings. */
    suspend fun poll(waitMillis: Long, version: String, features: List<String>): DaemonPoll {
        val request = DaemonPollRequest(waitMillis, version, features)
        val answer = call("POST", POLL, body(DaemonPollRequest.serializer(), request), waitMillis + POLL_MARGIN_MILLIS)
        return OstromJson.decodeFromJsonElement(DaemonPoll.serializer(), ok(answer))
    }

    /** Answers a member waiting for a folder's conversations. */
    suspend fun answerListing(requestId: String, answer: DaemonListingAnswer) {
        ok(call("POST", "$DAEMON/listings/${segment(requestId)}", body(DaemonListingAnswer.serializer(), answer)))
    }

    /** Refuses to host [sessionId], with a [RefusalReasons] code. */
    suspend fun refuse(sessionId: String, reason: String) {
        val request = body(RefuseDaemonOrderRequest.serializer(), RefuseDaemonOrderRequest(reason))
        ok(call("POST", "$DAEMON/sessions/${segment(sessionId)}/refuse", request))
    }

    /** Opens the session's next execution on this device (`POST /host`): its organisation and epoch. */
    suspend fun host(sessionId: String): OpenedHost {
        val opened = ok(call("POST", "${cloudSessionPath(sessionId)}/host"))
        val org = opened.text("orgId")
        val epoch = epochOf(opened)
        if (org == null || epoch == null) throw DaemonCallException(ServerAnswer.Failed(null, null, NO_EXECUTION))
        return OpenedHost(org, epoch)
    }

    /** Replaces this device's published folders. */
    suspend fun publishFolders(folders: List<DeviceFolder>) {
        val request = body(PublishDeviceFoldersRequest.serializer(), PublishDeviceFoldersRequest(folders))
        ok(call("PUT", FOLDERS, request))
    }

    /** What [sessionId] says now. */
    suspend fun session(sessionId: String): SessionState = SessionState.of(ok(call("GET", cloudSessionPath(sessionId))))

    /** Puts [sessionId] back in this device's queue. */
    suspend fun resume(sessionId: String) {
        ok(call("POST", "${cloudSessionPath(sessionId)}/resume"))
    }

    /** Asks [sessionId] to pause: its agent finishes its turn, its host checkpoints and exits. */
    suspend fun pause(sessionId: String) {
        ok(call("POST", "${cloudSessionPath(sessionId)}/pause"))
    }

    /** The daemon stops: the device is offline at once. */
    suspend fun stop() {
        ok(call("DELETE", DAEMON))
    }

    /** The caller's devices, its own marked `current`. */
    suspend fun devices(): List<Device> =
        OstromJson.decodeFromJsonElement(DevicePage.serializer(), ok(call("GET", DEVICES))).items

    /** Creates a session (`POST /api/v1/cloud/sessions`), retried safely with [idempotencyKey]. */
    suspend fun create(request: JsonObject, idempotencyKey: String): JsonObject =
        ok(call("POST", SESSIONS, request, WRITE_MILLIS, idempotencyKey))

    @Suppress("LongParameterList") // one parameter per part of the call, as ServerJson.call
    private suspend fun call(
        method: String,
        path: String,
        body: JsonObject? = null,
        timeoutMillis: Long = WRITE_MILLIS,
        idempotencyKey: String? = null,
    ): ServerAnswer {
        val bearer = token() ?: return ServerAnswer.Failed(UNAUTHORIZED, "unauthorized", NOT_SIGNED_IN)
        return server.call(method, path, body, bearer, timeoutMillis, idempotencyKey)
    }

    private fun ok(answer: ServerAnswer): JsonObject = when (answer) {
        is ServerAnswer.Ok -> answer.body
        is ServerAnswer.Failed -> throw DaemonCallException(answer)
    }

    private fun <T> body(serializer: KSerializer<T>, value: T): JsonObject =
        OstromJson.encodeToJsonElement(serializer, value).jsonObject

    private companion object {
        const val CAPABILITIES = "/api/v1/capabilities"
        const val DEVICES = "/api/v1/devices"
        const val DAEMON = "/api/v1/devices/current/daemon"
        const val POLL = "$DAEMON/poll"
        const val FOLDERS = "/api/v1/devices/current/folders"
        const val SESSIONS = "/api/v1/cloud/sessions"
        const val WRITE_MILLIS = 15_000L

        /** A poll's call lasts its wait, and this much more for the network. */
        const val POLL_MARGIN_MILLIS = 15_000L
        const val UNAUTHORIZED = 401
        const val NOT_SIGNED_IN = "Not signed in: run ostrom login."
        const val NO_EXECUTION = "The server opened no execution for this host."
    }
}

/** The execution `POST /host` opened: the session's organisation and the epoch. */
internal data class OpenedHost(val orgId: String, val epoch: Long)

/**
 * The daemon's channel on the server of this CLI's configuration, signed in with the device's token, in the
 * organisation `ostrom init` kept (or `OSTROM_ORG`), else the person's only one.
 *
 * @throws com.github.ajalt.clikt.core.PrintMessage When the device is not signed in, or names an organisation
 *   the person is not a member of.
 */
internal fun daemonServerOf(dependencies: CliDependencies): DaemonServer {
    val directory = dependencies.directory()
    val serverUrl = dependencies.serverOf(null, directory)
    val credentials = dependencies.credentials(directory)
    val token = { credentials.load(serverUrl)?.token }
    val signed = token() ?: throw stopWith(
        ExitCode.SIGN_IN_REQUIRED,
        "ostrom: not signed in to $serverUrl: run `ostrom login`",
    )
    val org = dependencies.launchOrg(serverUrl, signed, null)
    return DaemonServer(ServerJson(ApiServerTransport(dependencies.api(serverUrl), org)), token)
}
