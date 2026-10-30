// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject

/**
 * Lays a verified package down for one session (prototype `package::lay_down`): rules, skills, sub-agents and the gate
 * catalogue in the plugin folder; the scripts (0700) and the hook view of the package (`package.json`, 0600) outside
 * it, where the agent's own file tools do not look.
 */
class PackageLayout(private val files: LaunchFiles) {
    /**
     * @param pack The verified package.
     * @param plugin `<temporary>/plugin`.
     * @param temporary The private folder of the launch.
     *
     * @return The path of the hook view (`OSTROM_PACKAGE`).
     */
    fun layDown(pack: SessionPackage, plugin: String, temporary: String): String {
        for (file in pack.files) {
            val path = file.text("path").orEmpty()
            val content = file.text("content").orEmpty()
            when {
                !isSafe(path) -> Unit
                path.startsWith(SCRIPTS) || file.text("mode") == "executable" ->
                    files.write(join(temporary, path), content, FileAccess.EXECUTABLE)
                else -> files.write(join(plugin, path), content)
            }
        }
        val nodes = pack.body.list("nodes").filterIsInstance<JsonObject>().map { node ->
            objectOf("id" to node["id"], "kind" to node["kind"], "label" to node["label"], "mode" to node["mode"])
        }
        val view = objectOf(
            "version" to pack.version,
            "digest" to pack.digest,
            "repo" to pack.body["repo"],
            "context" to pack.context,
            "hooks" to pack.body["hooks"],
            "scripts" to pack.body["scripts"],
            "notifications" to pack.body["notifications"],
            "nodes" to nodes,
            "scriptsDir" to join(temporary, "scripts"),
            "tmp" to temporary,
        )
        val path = join(temporary, "package.json")
        files.write(path, pretty(view), FileAccess.PRIVATE)
        return path
    }

    private companion object {
        const val SCRIPTS = "scripts/"

        /** A relative path that stays under its folder: no `..` segment, no root, no drive, no backslash. */
        fun isSafe(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') && '\\' !in path &&
            ':' !in path && path.split('/').none { it == ".." }
    }
}
