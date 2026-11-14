// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.Uuid

/**
 * What a registered runner accepts to create on its machine. The server computes every plan, but the machine is the
 * organization's: a plan only creates the session's own named volumes and hardened containers, never a host path, a
 * privilege, a capability or a host namespace. Every key outside the known ones is refused.
 *
 * The `ostrom` binary reaches the session's containers as in the pod's init container: the execution's delivery
 * container (`<execution>-bin`, from the runner image) mounts the execution's binary volume
 * (`ostrom-ws-<session>-e<epoch>-bin`) over the image's binary directory, so the engine copies the binary into it, and
 * every other container mounts that volume read-only. The delivery container mounts nothing else.
 *
 * An accepted plan comes back marked as this runner's ([RUNNER_LABEL]), on the machine's network if it has one.
 *
 * @param runnerId This runner.
 * @param network The machine's network for the sessions, or null for the engine's default.
 * @param image The only image a session may run, or null for the one the server chose.
 * @param runnerImage The only image the binary may be delivered from, or null for the one the server chose.
 */
class PlanGuard(
    private val runnerId: Uuid,
    private val network: String?,
    private val image: String?,
    private val runnerImage: String? = null,
) {
    /**
     * Checks a plan.
     *
     * @param plan The plan.
     *
     * @return The plan to create: labelled as this runner's, on the machine's network.
     *
     * @throws IllegalArgumentException When the plan asks for anything else than a session.
     */
    fun check(plan: ExecutionPlan): ExecutionPlan = ExecutionPlan(
        plan.volumes.map { volume(it) },
        plan.containers.map { container(it) },
    )

    private fun volume(planned: PlannedObject): PlannedObject {
        require(VOLUME.matches(planned.name)) { "A volume must be a session's, not ${planned.name}." }
        require(planned.body.keys.all { it in VOLUME_KEYS }) { "A volume asks for more than a name and labels." }
        require(text(planned.body["Name"]) == planned.name) { "A volume's body must name it." }
        return PlannedObject(planned.name, labelled(planned.body))
    }

    private fun container(planned: PlannedObject): PlannedObject {
        val body = planned.body
        require(CONTAINER.matches(planned.name)) { "A container must be an execution's, not ${planned.name}." }
        require(body.keys.all { it in CONTAINER_KEYS }) { "A container asks for more than a session's container." }
        val delivery = planned.name.endsWith(DELIVERY_SUFFIX)
        val allowed = if (delivery) runnerImage else image
        val requested = text(body["Image"])
        require(requested != null && (allowed == null || requested == allowed)) { "A container's image is refused." }
        require(USER.matches(text(body["User"]).orEmpty())) { "A container must run as a non-root uid and gid." }
        val host = body["HostConfig"] as? JsonObject ?: throw IllegalArgumentException("A container needs its limits.")
        require(host.keys.all { it in HOST_KEYS }) { "A container asks for a host setting a session never needs." }
        val binds = texts(host["Binds"])
        require(binds.all { it != null && BIND.matches(it) }) { "A container may mount the session's volumes only." }
        val binaries = binds.filterNotNull().filter { BINARY_BIND.matches(it) }
        if (delivery) {
            val own = "${VOLUME_PREFIX}${planned.name.removePrefix(CONTAINER_PREFIX).removeSuffix(DELIVERY_SUFFIX)}"
            require(binds.size == 1 && binaries.size == 1 && binaries[0].startsWith("$own$DELIVERY_SUFFIX:")) {
                "A delivery container mounts its execution's binary volume only."
            }
            require(!binaries[0].endsWith(READ_ONLY)) { "A delivery container fills its binary volume." }
        } else {
            require(binaries.all { it.endsWith(READ_ONLY) }) { "A session's container mounts the binary read-only." }
        }
        require("ALL" in texts(host["CapDrop"])) { "A container must drop every capability." }
        require("no-new-privileges" in texts(host["SecurityOpt"])) { "A container must forbid new privileges." }
        val placed = if (network == null) host else JsonObject(host + ("NetworkMode" to JsonPrimitive(network)))
        return PlannedObject(planned.name, JsonObject(labelled(body) + ("HostConfig" to placed)))
    }

    private fun labelled(body: JsonObject): JsonObject {
        val labels = body["Labels"] as? JsonObject ?: JsonObject(emptyMap())
        val mine = JsonObject(labels + (RUNNER_LABEL to JsonPrimitive(runnerId.toString())))
        return JsonObject(body + ("Labels" to mine))
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun texts(element: JsonElement?): List<String?> = (element as? JsonArray)?.map(::text).orEmpty()

    /** Constants of the enclosing type. */
    companion object {
        /** The label of every object a runner creates, with its id: the objects it may delete. */
        const val RUNNER_LABEL = "ostrom.run/runner"

        /** The suffix of an execution's delivery container and binary volume. */
        const val DELIVERY_SUFFIX = "-bin"

        private const val VOLUME_PREFIX = "ostrom-ws-"
        private const val CONTAINER_PREFIX = "ostrom-s-"
        private const val READ_ONLY = ":ro"
        private val VOLUME = Regex("ostrom-ws-[0-9a-f]{32}(-socket|-checks|-e[0-9]+-bin)?")
        private val CONTAINER = Regex("ostrom-s-[0-9a-f]{32}-e[0-9]+(-agent|-checks|-bin)?")
        private val BIND = Regex("ostrom-ws-[0-9a-f]{32}(-socket|-checks|-e[0-9]+-bin)?:/[A-Za-z0-9._/-]+(:ro)?")
        private val BINARY_BIND = Regex("ostrom-ws-[0-9a-f]{32}-e[0-9]+-bin:.*")
        private val USER = Regex("[1-9][0-9]*:[1-9][0-9]*")
        private val VOLUME_KEYS = setOf("Name", "Labels")
        private val CONTAINER_KEYS = setOf("Image", "User", "Entrypoint", "WorkingDir", "Env", "Labels", "HostConfig")
        private val HOST_KEYS = setOf(
            "Binds", "GroupAdd", "ExtraHosts", "RestartPolicy", "CapDrop", "SecurityOpt", "ReadonlyRootfs", "Tmpfs",
            "PidsLimit", "Memory", "NanoCpus",
        )
    }
}
