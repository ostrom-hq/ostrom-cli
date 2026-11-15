// SPDX-License-Identifier: Apache-2.0
// Dense assertions and fakes, in the style of the ported TeamCode tests.
@file:Suppress(
    "MaxLineLength",
    "LongParameterList",
    "ReturnCount",
)

package run.ostrom.cli.daemon

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.cli.launcher.ServerCall
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.ServerReply
import run.ostrom.cli.launcher.ServerTransport
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.platform.DetachedProcess
import run.ostrom.cli.platform.FileStore
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.Capabilities
import run.ostrom.core.api.models.DaemonListing
import run.ostrom.core.api.models.DaemonListingAnswer
import run.ostrom.core.api.models.DaemonOrder
import run.ostrom.core.api.models.DaemonPoll
import run.ostrom.core.api.models.DaemonPollRequest
import run.ostrom.core.api.models.DeviceFolder
import run.ostrom.core.api.models.PublishDeviceFoldersRequest
import run.ostrom.core.api.models.RefuseDaemonOrderRequest

/**
 * The server side of the daemon channel, in memory (TeamCode's `FakeDaemonServer`): sessions with their status,
 * desired status, epoch and host presence, the device's published folders, and the daemon's presence. A
 * [ServerTransport], so the daemon's real [DaemonServer] and [ServerJson] read it.
 */
internal class FakeDaemonServer(private val daemonCapability: Boolean = true) : ServerTransport {
    class Session(
        val id: String,
        val folderId: String?,
        val directoryName: String,
        val agent: String = "claude",
        val permissionMode: String = "ask",
        var status: String = "creating",
        var waiting: Boolean = true,
        var desiredStatus: String = "running",
        var epoch: Long = 0,
        var online: Boolean = false,
        var failure: Pair<String, String>? = null,
        val resume: String? = null,
    )

    val sessions = linkedMapOf<String, Session>()
    val events = mutableListOf<String>()
    var folders: List<DeviceFolder> = emptyList()
    var daemonOnline = false
    val versions = mutableListOf<String?>()
    val created = mutableListOf<JsonObject>()
    val features = mutableListOf<List<String>>()

    /** What the device list says the daemon announced, instead of its last poll's (an older daemon: none). */
    var deviceFeatures: List<String>? = null

    /** The device list shows an offline daemon instead of none (one that stopped answering without saying so). */
    var lostDaemon = false

    /** Whether the device list marks this device `current` (false: it names none, as for another device's token). */
    var currentDevice = true

    /** Members waiting for a folder's conversations, handed to the next poll; [answers] holds the daemon's answers. */
    val listings = mutableListOf<DaemonListing>()
    val answers = linkedMapOf<String, DaemonListingAnswer>()

    /** Routes that answer this status instead (`METHOD path`), once each, with the type of [types] or `refused`. */
    val failing = mutableMapOf<String, Int>()
    val types = mutableMapOf<String, String>()

    /** Routes that do not answer at all, once each (`METHOD path`). */
    val unreachable = mutableSetOf<String>()

    /** Every call, as `METHOD path`. */
    val calls = mutableListOf<String>()

    fun queue(
        id: String,
        folderId: String?,
        directoryName: String,
        agent: String = "claude",
        permissionMode: String = "ask",
        resume: String? = null,
        status: String = "creating",
    ) {
        sessions[id] = Session(id, folderId, directoryName, agent, permissionMode, status = status, resume = resume)
    }

    private fun queued() = sessions.values.filter {
        it.folderId != null && it.waiting && it.desiredStatus == "running" && it.status != "terminated"
    }

    fun view(session: Session): JsonObject = objectOf(
        "id" to session.id,
        "name" to session.directoryName,
        "owner" to "m1",
        "agent" to session.agent,
        "status" to session.status,
        "desiredStatus" to session.desiredStatus,
        "epoch" to session.epoch,
        "kind" to "remote",
        "createdAt" to "2026-11-15T00:00:00Z",
        "failure" to session.failure?.let { mapOf("code" to it.first, "message" to it.second) },
        "host" to mapOf(
            "deviceId" to "d1",
            "directoryName" to session.directoryName,
            "online" to session.online,
            "permissionMode" to session.permissionMode,
            "folderId" to session.folderId,
        ).filterValues { it != null },
    )

    /** What a host does once its session should pause: it checkpoints and exits. */
    fun checkpoint(id: String) {
        val session = sessions.getValue(id)
        session.status = "paused"
        session.online = false
        events += "checkpoint $id"
    }

    /** What the server does once a ready session's host went silent. */
    fun loseHost(id: String) {
        val session = sessions.getValue(id)
        session.status = "paused"
        session.desiredStatus = "paused"
        session.online = false
        session.failure = "host_offline" to "The host stopped answering."
    }

    /** The daemon's channel on this server, with a token. */
    fun server(token: () -> String? = { "ost_1" }) = DaemonServer(ServerJson(this), token)

    private fun ok(body: JsonObject) = ServerReply(200, body.toString())
    private fun error(status: Int, type: String) = ServerReply(status, """{"error":{"type":"$type","message":"$type"}}""")

    override suspend fun send(call: ServerCall): ServerReply {
        val route = "${call.method} ${call.path}"
        calls += route
        failing.remove(route)?.let { return error(it, types.remove(route) ?: "refused") }
        if (unreachable.remove(route)) return ServerReply(null, "connection refused")
        return handle(call.method, call.path, call.body.orEmpty())
    }

    private fun capabilities(): JsonObject {
        val served = listOf("runners.cloud.remote.v1", "runners.cloud.remote.resume.v1") +
            if (daemonCapability) listOf(DAEMON_CAPABILITY) else emptyList()
        return OstromJson.encodeToJsonElement(Capabilities.serializer(), Capabilities(1, "1.4.0", served)).jsonObject
    }

    private fun devices(): JsonObject {
        val daemon = when {
            daemonOnline -> mapOf(
                "online" to true,
                "lastSeenAt" to "2026-11-15T00:00:00Z",
                "version" to versions.lastOrNull(),
                "features" to (deviceFeatures ?: features.lastOrNull().orEmpty()),
            )
            lostDaemon -> mapOf("online" to false, "lastSeenAt" to "2026-11-15T00:00:00Z")
            else -> null
        }
        val device = mapOf("id" to "d1", "name" to "Laptop", "current" to currentDevice, "daemon" to daemon).filterValues { it != null }
        return objectOf("items" to listOf(device, mapOf("id" to "d2", "name" to "Desktop", "current" to false)))
    }

    private suspend fun poll(body: String): ServerReply {
        val request = OstromJson.decodeFromString(DaemonPollRequest.serializer(), body)
        versions += request.version
        features += request.features
        daemonOnline = true
        if (queued().isEmpty() && listings.isEmpty()) delay(minOf(request.waitMillis, 50))
        val orders = queued().map {
            DaemonOrder(it.id, it.folderId!!, it.directoryName, it.agent, it.permissionMode, it.status, "2026-11-15T00:00:00Z", it.resume)
        }
        val asked = listings.toList().also { listings.clear() }
        return ServerReply(200, OstromJson.encodeToString(DaemonPoll.serializer(), DaemonPoll(orders, 60_000, 20_000, asked)))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun handle(method: String, path: String, body: String): ServerReply {
        val session = Regex("/api/v1/cloud/sessions/([A-Za-z0-9-]+)(/[a-z]+)?").matchEntire(path)
        val refusal = Regex("/api/v1/devices/current/daemon/sessions/([A-Za-z0-9-]+)/refuse").matchEntire(path)
        return when {
            path == "/api/v1/capabilities" -> ok(capabilities())
            path == "/api/v1/devices" -> ok(devices())
            path == "/api/v1/devices/current/daemon/poll" -> poll(body)
            path.startsWith("/api/v1/devices/current/daemon/listings/") -> {
                val id = path.removePrefix("/api/v1/devices/current/daemon/listings/")
                answers[id] = OstromJson.decodeFromString(DaemonListingAnswer.serializer(), body)
                ServerReply(204, "")
            }
            path == "/api/v1/devices/current/daemon" && method == "DELETE" -> {
                daemonOnline = false
                events += "offline"
                ServerReply(204, "")
            }
            path == "/api/v1/devices/current/folders" -> {
                folders = OstromJson.decodeFromString(PublishDeviceFoldersRequest.serializer(), body).folders
                events += "publish ${folders.joinToString(",") { it.id }}"
                ServerReply(200, "[]")
            }
            refusal != null -> {
                val id = refusal.groupValues[1]
                val reason = OstromJson.decodeFromString(RefuseDaemonOrderRequest.serializer(), body).reason
                val refused = sessions.getValue(id)
                refused.status = "terminated"
                refused.desiredStatus = "terminated"
                refused.waiting = false
                refused.failure = "host_refused" to "The device refused to start this session ($reason)."
                events += "refuse $id $reason"
                ok(view(refused))
            }
            path == "/api/v1/cloud/sessions" && method == "POST" -> create(body)
            session != null -> sessionRoute(session.groupValues[1], session.groupValues[2])
            else -> error(404, "not_found")
        }
    }

    private fun create(body: String): ServerReply {
        val request = OstromJson.parseToJsonElement(body).jsonObject
        created += request
        val folderId = request["folderId"]?.jsonPrimitive?.content
        val folder = folders.firstOrNull { it.id == folderId } ?: return error(422, "folder_not_published")
        val id = "5e55i0n-${created.size}"
        val agent = request["agent"]?.jsonPrimitive?.content ?: "claude"
        queue(id, folder.id, folder.name, agent, resume = request["resumeSessionId"]?.jsonPrimitive?.content)
        events += "create $id ${request["deviceId"]?.jsonPrimitive?.content} $folderId"
        return ok(view(sessions.getValue(id)))
    }

    private fun sessionRoute(id: String, action: String): ServerReply {
        val session = sessions[id] ?: return error(404, "session_not_found")
        return when (action) {
            "" -> ok(view(session))
            "/host" -> {
                session.epoch++
                session.waiting = false
                events += "host ${session.id} ${session.epoch}"
                ok(objectOf("orgId" to "o1", "epoch" to session.epoch, "session" to view(session)))
            }
            "/pause" -> {
                session.desiredStatus = "paused"
                events += "pause ${session.id}"
                ok(view(session))
            }
            "/resume" -> {
                session.desiredStatus = "running"
                session.status = "resuming"
                session.waiting = true
                session.failure = null
                events += "resume ${session.id}"
                ok(view(session))
            }
            else -> error(404, "not_found")
        }
    }

    /**
     * Hosts that behave like `ostrom remote host`: online once started, they checkpoint and exit 0 when their session
     * should pause, exit 1 when [crash] names them, and hang when [hang] does. [started] lists what the daemon started.
     */
    inner class Hosts : HostLauncher {
        val started = mutableListOf<Triple<String, Long, String>>()
        val logs = mutableListOf<String>()
        val crash = mutableSetOf<String>()

        /** Hosts that stop answering without exiting: alive until a newer epoch fences them. */
        val hang = mutableSetOf<String>()

        /** Sessions whose host cannot be started at all. */
        val broken = mutableSetOf<String>()

        /** Sessions whose start fails in a way the daemon does not expect. */
        val exploding = mutableSetOf<String>()

        override fun start(sessionId: String, epoch: Long, orgId: String, log: String): DetachedProcess {
            if (sessionId in broken) throw okio.IOException("no such program")
            check(sessionId !in exploding) { "the launcher failed" }
            started += Triple(sessionId, epoch, orgId)
            logs += log
            val session = sessions.getValue(sessionId)
            session.online = true
            session.status = "running"
            var exit: Int? = null
            return DetachedProcess {
                exit ?: when {
                    session.epoch != epoch -> 0
                    sessionId in hang -> null
                    sessionId in crash -> {
                        crash -= sessionId
                        session.online = false
                        1
                    }
                    session.desiredStatus != "running" -> {
                        checkpoint(sessionId)
                        0
                    }
                    else -> null
                }.also { exit = it }
            }
        }
    }
}

/** A [FileStore] whose files exist but cannot be read (their permissions changed meanwhile): [read] gives none. */
internal class UnreadableFiles(files: FileStore) : FileStore by files {
    override fun read(path: String): String? = null
}
