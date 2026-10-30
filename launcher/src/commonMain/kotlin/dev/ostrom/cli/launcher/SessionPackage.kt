// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A verified session package (`ostrom.package/1`): the harness compiled by Ostrom for one repository.
 *
 * @property body The decoded payload (`SessionPackage` of the contract).
 * @property digest SHA-256 of the payload bytes, the package's identity in receipts.
 */
class SessionPackage(val body: JsonObject, val digest: String) {
    /** The version, e.g. `p202610300845-a216a112`. */
    val version: String get() = body.text("version") ?: "?"

    /** The context line given to the model (workflows compiled into the session). */
    val context: String get() = body.text("context").orEmpty()

    /** The files of the package: `path`, `mode` (`file` or `executable`), `content`. */
    val files: List<JsonObject> get() = body.list("files").filterIsInstance<JsonObject>()

    /** The MCP servers the package uses, by name. */
    val mcpServers: List<String> get() =
        body.child("mcp")?.list("servers").orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }

    /** The gate catalogue the package was signed with (`ostrom/gates.json`), or null (security audit S2-20). */
    val gateCatalog: String? get() = files.firstOrNull { it.text("path") == GATES_FILE }?.text("content")

    /**
     * The time the checks of one action may take, for the hook timeout the agent is given: every enforced check that
     * guards the action runs in the one hook, one after the other, so their limits add up. Checks in shadow never run.
     */
    val enforcedCheckSeconds: Long get() = body.list("scripts").filterIsInstance<JsonObject>()
        .filter { it.text("mode") == "enforce" }
        .sumOf { (it["timeoutSec"] as? JsonPrimitive)?.longOrNull ?: 0L }

    /** One line for the developer: what was laid down. */
    fun summary(): String {
        fun count(key: String) = body.list(key).size
        val enforced = body.list("scripts").count { (it as? JsonObject)?.text("mode") == "enforce" }
        return "package $version (${count("workflows")} workflow(s), ${count("nodes")} node(s): " +
            "${count("hooks")} hook(s), ${count("scripts")} script(s) of which $enforced enforced, " +
            "${count("notifications")} notification(s), ${count("files")} file(s)), signature valid, " +
            "digest ${digest.take(SHORT_DIGEST)}"
    }

    private companion object {
        const val SHORT_DIGEST = 12
        const val GATES_FILE = "ostrom/gates.json"
    }
}
