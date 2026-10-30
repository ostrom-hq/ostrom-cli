// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject

/**
 * A package laid down for one session.
 *
 * @property view The hook view (`package.json`, `OSTROM_PACKAGE`).
 * @property manifest The manifest of every file laid down, `<sha256>  <path>` per line (`OSTROM_PACKAGE_MANIFEST`).
 * @property seal The manifest's SHA-256 (`OSTROM_PACKAGE_SEAL`): the hooks check the package against it at every run,
 * since the agent can rewrite the files and the manifest but not the environment its hooks start with (S2-33).
 */
data class LaidPackage(val view: String, val manifest: String, val seal: String)

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
     * @return The hook view and the package's seal.
     */
    fun layDown(pack: SessionPackage, plugin: String, temporary: String): LaidPackage {
        val written = mutableListOf<Pair<String, String>>()
        for (file in pack.files) {
            val path = file.text("path").orEmpty()
            val content = file.text("content").orEmpty()
            when {
                !isSafe(path) -> Unit
                path.startsWith(SCRIPTS) || file.text("mode") == "executable" -> {
                    files.write(join(temporary, path), content, FileAccess.EXECUTABLE)
                    written += join(temporary, path) to content
                }
                else -> {
                    files.write(join(plugin, path), content)
                    written += join(plugin, path) to content
                }
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
        val text = pretty(view)
        files.write(path, text, FileAccess.PRIVATE)
        written += path to text
        val manifest = written.joinToString("") { (file, content) ->
            "${sha256Hex(content.encodeToByteArray())}  $file\n"
        }
        val manifestPath = join(temporary, "package.sha256")
        files.write(manifestPath, manifest, FileAccess.PRIVATE)
        return LaidPackage(path, manifestPath, sha256Hex(manifest.encodeToByteArray()))
    }

    private companion object {
        const val SCRIPTS = "scripts/"

        /** A relative path that stays under its folder: no `..` segment, no root, no drive, no backslash. */
        fun isSafe(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') && '\\' !in path &&
            ':' !in path && path.split('/').none { it == ".." }
    }
}
