// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/**
 * One verified file of a delivered version.
 *
 * @property path Its path in the version.
 * @property mode `file` or `executable`.
 * @property content Its content.
 */
class DeliveredFile(val path: String, val mode: String, val content: ByteString)

/**
 * A delivered version the runner verified, ready to stream to the installer.
 *
 * @property name The item.
 * @property version Its version.
 * @property digest The digest of its files alone (no definition line), which the installer checks again.
 * @property files Its files, sorted by path.
 */
class DeliveredItem(val name: String, val version: String, val digest: String, val files: List<DeliveredFile>)

/**
 * When and what a team hook runs (`HookDefinition` of the contract).
 *
 * @property events The Claude Code events, sorted.
 * @property matcher Its matcher, if any.
 * @property command The file of its version it runs.
 * @property timeoutSeconds Its time limit.
 */
class TeamHookDefinition(val events: List<String>, val matcher: String?, val command: String, val timeoutSeconds: Int) {
    /** The line the version's digest begins with, before the files' lines. */
    val digestLine: String
        get() = "hook\u0000${events.joinToString(",")}\u0000${matcher.orEmpty()}\u0000$command\u0000$timeoutSeconds\n"
}

/**
 * The rules a delivered artifact (`SkillArtifact`, `HookArtifact`) is checked by: the same as the launcher's
 * `Delivered` and `HookDefinition` (`ostrom run`), which the runner, a module of its own, cannot depend on. The
 * digest is `sha256:` over one line per file sorted by path, `<path>\0<mode>\0<size>\0<sha256 hex>\n`, after a hook's
 * definition line. TeamCode's `SkillDigest` and `HookDigest`.
 */
object DeliveredItems {
    /** The mode of a file run by nobody. */
    const val FILE = "file"

    /** The mode of a file the agent runs. */
    const val EXECUTABLE = "executable"

    /** At most this many files in one version. */
    const val MAX_FILES = 200

    /** At most this many bytes in one skill version. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** At most this many files in one hook version. */
    const val MAX_HOOK_FILES = 50

    /** At most this many bytes in one hook version. */
    const val MAX_HOOK_BYTES = 1024L * 1024

    /** The agent events a team hook may run on. */
    val EVENTS = setOf(
        "PreToolUse", "PostToolUse", "UserPromptSubmit", "Stop", "SubagentStop", "SessionStart", "SessionEnd",
        "PreCompact", "Notification",
    )

    private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9._+-]*")
    private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")
    private val DIGEST = Regex("sha256:[0-9a-f]{64}")
    private const val MAX_NAME = 64
    private const val MAX_PATH = 255
    private const val MAX_DEPTH = 8
    private const val DEFAULT_SECONDS = 60
    private const val MAX_SECONDS = 600
    private const val MAX_MATCHER = 256

    /** `sha256:<hex>` of [text]. */
    fun digestOf(text: String): String = "sha256:" + text.encodeUtf8().sha256().hex()

    /** The digest line of one file. */
    fun line(path: String, mode: String, size: Long, sha256: String) = "$path\u0000$mode\u0000$size\u0000$sha256\n"

    /** The digest of [files] alone. */
    fun digestOf(files: List<DeliveredFile>): String = digestOf(lines(files))

    private fun lines(files: List<DeliveredFile>) = files.joinToString("") {
        line(it.path, it.mode, it.content.size.toLong(), it.content.sha256().hex())
    }

    /**
     * A path a version may hold: at most 8 names of letters, digits, `_`, `.`, `+` and `-`, none starting with a dot
     * nor ending with one, so never `..`, a hidden file or a marker.
     */
    fun validPath(path: String): Boolean {
        val names = path.split('/')
        return path.length <= MAX_PATH && names.size <= MAX_DEPTH &&
            names.all { SEGMENT.matches(it) && !it.endsWith(".") }
    }

    /**
     * The name, version and digest of a manifest entry, when this runner can install it.
     *
     * @param entry An `EffectiveSkill` or `EffectiveHook`.
     *
     * @return The three, or null.
     */
    fun identity(entry: JsonObject): Triple<String, String, String>? {
        val name = entry.text("name")?.takeIf { it.length <= MAX_NAME && NAME.matches(it) }
        val version = entry.text("version")?.takeIf(VERSION::matches)
        val digest = entry.text("digest")?.takeIf(DIGEST::matches)
        return if (name != null && version != null && digest != null) Triple(name, version, digest) else null
    }

    /**
     * [json] as a definition, or null when it is not one this runner installs: known events, each once; a matcher of
     * 1 to 256 characters without control characters; a command that is a path a version may hold; 1 to 600 seconds.
     */
    fun definition(json: JsonObject?): TeamHookDefinition? {
        val given = json?.get("events") as? JsonArray ?: return null
        val events = given.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        val known = events.isNotEmpty() && events.all { it in EVENTS } && events.distinct().size == events.size
        val matcherElement = json["matcher"]
        val matcher = (matcherElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        val matches = if (matcher == null) {
            matcherElement == null || matcherElement is JsonNull
        } else {
            matcher.length in 1..MAX_MATCHER && matcher.none(Char::isISOControl)
        }
        val command = json.text("command")?.takeIf(::validPath)
        val timeout = json["timeoutSeconds"]
        val seconds = if (timeout == null) {
            DEFAULT_SECONDS
        } else {
            (timeout as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..MAX_SECONDS }
        }
        val valid = known && matches
        return if (valid && command != null && seconds != null) {
            TeamHookDefinition(events.filterNotNull().sorted(), matcher, command, seconds)
        } else {
            null
        }
    }

    /**
     * The files of [artifact], each checked against its `size` and `sha256`, then [digest] recomputed from them after
     * [prefix]. Refused: no file, too many or too big, a file not described as the contract says, a path that is not
     * [validPath] or given twice (case ignored), another name, version or digest than [identity]'s.
     *
     * @param artifact The artifact.
     * @param identity The name, version and digest the manifest gave.
     * @param prefix A hook's definition line.
     * @param maxBytes At most this many bytes.
     * @param maxFiles At most this many files.
     * @param executable A path that must be an executable file of it (a hook's command), or null.
     *
     * @return The files sorted by path, or null when refused.
     */
    @Suppress("LongParameterList") // The artifact, what it is checked against, and the bounds of its kind.
    fun verify(
        artifact: JsonObject,
        identity: Triple<String, String, String>,
        prefix: String = "",
        maxBytes: Long = MAX_BYTES,
        maxFiles: Int = MAX_FILES,
        executable: String? = null,
    ): List<DeliveredFile>? {
        val (name, version, digest) = identity
        val same = artifact.text("name") == name && artifact.text("version") == version &&
            artifact.text("digest") == digest
        val entries = (artifact["files"] as? JsonArray).orEmpty()
        val files = entries.mapNotNull { (it as? JsonObject)?.let(::fileOf) }.sortedBy { it.path }
        val folded = files.map { it.path.lowercase() }
        val executables = files.map { "${it.mode}:${it.path}" }
        val runs = executable == null || "$EXECUTABLE:$executable" in executables
        val sound = same && runs && entries.size in 1..maxFiles && files.size == entries.size &&
            folded.distinct().size == folded.size && files.sumOf { it.content.size.toLong() } <= maxBytes
        return files.takeIf { sound && digestOf(prefix + lines(files)) == digest }
    }

    /** One `ArtifactFileContent`, its path [validPath], its content matching its size and sha256, its mode known. */
    private fun fileOf(entry: JsonObject): DeliveredFile? {
        val path = entry.text("path")?.takeIf(::validPath)
        val mode = entry.text("mode")?.takeIf { it == FILE || it == EXECUTABLE }
        val size = (entry["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val content = entry.text("contentBase64")?.decodeBase64()
            ?.takeIf { it.size.toLong() == size && it.sha256().hex() == entry.text("sha256") }
        return if (path != null && mode != null && content != null) DeliveredFile(path, mode, content) else null
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
}
