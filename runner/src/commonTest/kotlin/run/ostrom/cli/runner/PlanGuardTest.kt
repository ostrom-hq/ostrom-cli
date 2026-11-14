// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** A plan as the server computes it (DockerExecutionPlan), for the runner's tests. */
internal fun plan(session: Uuid, epoch: Long, token: String = "ostrom_runner_xyz"): ExecutionPlan {
    val hex = session.toHexString()
    val volumes = listOf("ostrom-ws-$hex", "ostrom-ws-$hex-socket", "ostrom-ws-$hex-checks").map { name ->
        PlannedObject(
            name,
            buildJsonObject {
                put("Name", name)
                putJsonObject("Labels") { put("ostrom.run/session", hex) }
            },
        )
    }
    val execution = "ostrom-s-$hex-e$epoch"
    val users = listOf("$execution-agent" to "1001:1000", "$execution-checks" to "1001:1000", execution to "1000:1000")
    val containers = users.map { (name, user) ->
        PlannedObject(name, container(hex, epoch, user, token.takeIf { name == execution }))
    }
    return ExecutionPlan(volumes, containers)
}

/**
 * A plan with the delivery of the `ostrom` binary, as the server computes it once Docker delivers it like the pod's
 * init container: the execution's binary volume, filled by the delivery container, read-only in the other three.
 */
internal fun deliveryPlan(session: Uuid, epoch: Long, runnerImage: String = "ghcr.io/ostrom-hq/ostrom-runner:1"):
    ExecutionPlan {
    val hex = session.toHexString()
    val execution = "ostrom-s-$hex-e$epoch"
    val binary = "ostrom-ws-$hex-e$epoch-bin"
    val sent = plan(session, epoch)
    val volume = PlannedObject(
        binary,
        buildJsonObject {
            put("Name", binary)
            putJsonObject("Labels") { put("ostrom.run/session", hex) }
        },
    )
    val sessionContainers = sent.containers.map { planned ->
        planned.withBinds(planned.binds() + "$binary:/opt/ostrom:ro")
    }
    val delivery = PlannedObject(
        "$execution-bin",
        JsonObject(
            sent.containers.last().body + ("Image" to JsonPrimitive(runnerImage)) + ("Env" to JsonArray(emptyList())),
        ),
    ).withBinds(listOf("$binary:/usr/local/bin"))
    return ExecutionPlan(sent.volumes + volume, listOf(delivery) + sessionContainers)
}

internal fun PlannedObject.binds(): List<String> =
    body["HostConfig"]!!.jsonObject["Binds"]!!.jsonArray.map { it.jsonPrimitive.content }

internal fun PlannedObject.withBinds(binds: List<String>): PlannedObject {
    val host = JsonObject(body["HostConfig"]!!.jsonObject + ("Binds" to JsonArray(binds.map(::JsonPrimitive))))
    return PlannedObject(name, JsonObject(body + ("HostConfig" to host)))
}

private fun container(hex: String, epoch: Long, user: String, token: String?) = buildJsonObject {
    put("Image", "ghcr.io/ostrom-hq/ostrom-runner:1")
    put("User", user)
    putJsonArray("Entrypoint") { add(JsonPrimitive("/usr/local/bin/ostrom-runner")) }
    put("WorkingDir", "/workspace")
    putJsonArray("Env") { token?.let { add(JsonPrimitive("OSTROM_RUNNER_TOKEN=$it")) } }
    putJsonObject("Labels") {
        put("ostrom.run/session", hex)
        put("ostrom.run/epoch", epoch.toString())
    }
    putJsonObject("HostConfig") {
        putJsonArray("Binds") { add(JsonPrimitive("ostrom-ws-$hex:/workspace")) }
        putJsonArray("ExtraHosts") { add(JsonPrimitive("host.docker.internal:host-gateway")) }
        putJsonObject("RestartPolicy") { put("Name", "no") }
        putJsonArray("CapDrop") { add(JsonPrimitive("ALL")) }
        putJsonArray("SecurityOpt") { add(JsonPrimitive("no-new-privileges")) }
        put("ReadonlyRootfs", true)
        putJsonObject("Tmpfs") { put("/tmp", "rw,nosuid,size=1g") }
        put("PidsLimit", 4096)
        put("Memory", 1024)
        put("NanoCpus", 2)
    }
}

class PlanGuardTest {
    private val runner = Uuid.random()
    private val session = Uuid.random()
    private val guard = PlanGuard(runner, null, null)

    private fun JsonObject.with(key: String, value: JsonElement?) =
        JsonObject(if (value == null) this - key else this + (key to value))

    private fun hostWith(key: String, value: JsonElement?): ExecutionPlan {
        val sent = plan(session, 1)
        val runnerContainer = sent.containers.last()
        val host = runnerContainer.body["HostConfig"]!!.jsonObject.with(key, value)
        val changed = PlannedObject(runnerContainer.name, runnerContainer.body.with("HostConfig", host))
        return sent.copy(containers = listOf(changed))
    }

    private fun PlannedObject.label(key: String) = body["Labels"]!!.jsonObject[key]!!.jsonPrimitive.content

    private fun PlannedObject.network() = body["HostConfig"]!!.jsonObject["NetworkMode"]?.jsonPrimitive?.content

    @Test
    fun aSessionsPlanIsAcceptedAndMarkedAsThisRunners() {
        val sent = plan(session, 1)
        val checked = guard.check(sent)
        assertEquals(sent.volumes.map { it.name }, checked.volumes.map { it.name })
        for (planned in checked.volumes + checked.containers) {
            assertEquals(runner.toString(), planned.label(PlanGuard.RUNNER_LABEL))
        }
        assertEquals(session.toHexString(), checked.volumes[0].label("ostrom.run/session"))
        assertNull(checked.containers.last().network(), "The engine's default network")

        val placed = PlanGuard(runner, "sessions", "ghcr.io/ostrom-hq/ostrom-runner:1").check(sent)
        assertEquals("sessions", placed.containers.last().network())

        // A volume without labels gets this runner's.
        val name = "ostrom-ws-${session.toHexString()}"
        val bare = PlannedObject(name, buildJsonObject { put("Name", name) })
        val labelled = guard.check(ExecutionPlan(listOf(bare), emptyList())).volumes.single()
        assertEquals(runner.toString(), labelled.label(PlanGuard.RUNNER_LABEL))
    }

    @Test
    fun anythingButASessionIsRefused() {
        val hex = session.toHexString()
        val sent = plan(session, 1)
        val volume = sent.volumes.first()
        val runnerContainer = sent.containers.last()
        fun containerWith(key: String, value: JsonElement?) =
            sent.copy(containers = listOf(PlannedObject(runnerContainer.name, runnerContainer.body.with(key, value))))
        fun volumeWith(key: String, value: JsonElement) =
            sent.copy(volumes = listOf(PlannedObject(volume.name, volume.body.with(key, value))))
        val refused = mapOf(
            "a volume of another name" to sent.copy(volumes = listOf(PlannedObject("data", volume.body))),
            "a volume with a driver" to volumeWith("Driver", JsonPrimitive("local")),
            "a volume whose body names another" to volumeWith("Name", JsonPrimitive("other")),
            "a container of another name" to
                sent.copy(containers = listOf(PlannedObject("miner", runnerContainer.body))),
            "a command" to containerWith("Cmd", JsonPrimitive("sh")),
            "no image" to containerWith("Image", null),
            "an image that is not text" to containerWith("Image", JsonPrimitive(1)),
            "root" to containerWith("User", JsonPrimitive("0:0")),
            "no user" to containerWith("User", null),
            "no limits" to containerWith("HostConfig", null),
            "privileged" to hostWith("Privileged", JsonPrimitive(true)),
            "the host's network" to hostWith("NetworkMode", JsonPrimitive("host")),
            "a host path" to hostWith("Binds", JsonArray(listOf(JsonPrimitive("/:/host")))),
            "a mount that is not text" to hostWith("Binds", JsonArray(listOf(JsonPrimitive(1)))),
            "capabilities kept" to hostWith("CapDrop", null),
            "new privileges" to hostWith("SecurityOpt", JsonArray(emptyList())),
        )
        for ((why, bad) in refused) assertFailsWith<IllegalArgumentException>(why) { guard.check(bad) }
        assertFailsWith<IllegalArgumentException>("another image than the machine's") {
            PlanGuard(runner, null, "ghcr.io/ostrom-hq/ostrom-runner:2").check(sent)
        }
        assertEquals("ostrom-ws-$hex", guard.check(sent).volumes.first().name)
    }

    @Test
    fun theBinaryIsDeliveredFromTheRunnerImageAndMountedReadOnly() {
        val sent = deliveryPlan(session, 2)
        val checked = guard.check(sent)
        assertEquals(sent.containers.map { it.name }, checked.containers.map { it.name })
        assertEquals(sent.volumes.map { it.name }, checked.volumes.map { it.name })
        // The machine may pin both images.
        val pinned = PlanGuard(runner, null, "ghcr.io/ostrom-hq/ostrom-runner:1", "ghcr.io/ostrom-hq/ostrom-runner:1")
        assertEquals(4, pinned.check(sent).containers.size)
        assertFailsWith<IllegalArgumentException>("another delivery image than the machine's") {
            PlanGuard(runner, null, null, "ghcr.io/ostrom-hq/ostrom-runner:2").check(sent)
        }
        // The team's session image and the runner image, each pinned on its own.
        val team = sent.copy(
            containers = listOf(sent.containers.first()) + sent.containers.drop(1).map {
                PlannedObject(it.name, JsonObject(it.body + ("Image" to JsonPrimitive("team:1"))))
            },
        )
        val split = PlanGuard(runner, null, "team:1", "ghcr.io/ostrom-hq/ostrom-runner:1")
        assertEquals(4, split.check(team).containers.size)
        assertFailsWith<IllegalArgumentException>("the session image pin applies to the delivery's peers only") {
            PlanGuard(runner, null, "team:1", null).check(sent)
        }
    }

    @Test
    fun aDeliveryThatCouldReachTheSessionIsRefused() {
        val hex = session.toHexString()
        val sent = deliveryPlan(session, 2)
        val delivery = sent.containers.first()
        val agent = sent.containers[1]
        fun swapped(first: PlannedObject = delivery, second: PlannedObject = agent) =
            sent.copy(containers = listOf(first, second) + sent.containers.drop(2))
        val refused = mapOf(
            "a delivery that mounts the workspace" to
                swapped(delivery.withBinds(delivery.binds() + "ostrom-ws-$hex:/workspace")),
            "a delivery that mounts another execution's binary" to
                swapped(delivery.withBinds(listOf("ostrom-ws-$hex-e3-bin:/usr/local/bin"))),
            "a delivery that mounts nothing" to swapped(delivery.withBinds(emptyList())),
            "a delivery that mounts the workspace only" to
                swapped(delivery.withBinds(listOf("ostrom-ws-$hex:/workspace"))),
            "a delivery mounting its volume read-only" to
                swapped(delivery.withBinds(listOf("ostrom-ws-$hex-e2-bin:/usr/local/bin:ro"))),
            "a session's container that may write the binary" to swapped(
                second = agent.withBinds(listOf("ostrom-ws-$hex:/workspace", "ostrom-ws-$hex-e2-bin:/opt/ostrom")),
            ),
        )
        for ((why, bad) in refused) assertFailsWith<IllegalArgumentException>(why) { guard.check(bad) }
    }

    @Test
    fun aPlansTokenIsNeverPrinted() {
        val sent = plan(session, 1, token = "secret-token")
        assertEquals("PlannedObject(name=ostrom-s-${session.toHexString()}-e1)", sent.containers.last().toString())
    }
}
