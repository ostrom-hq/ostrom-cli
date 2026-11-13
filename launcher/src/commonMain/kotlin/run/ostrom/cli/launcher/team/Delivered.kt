// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/**
 * One file of a delivered version (a team skill or a team hook), its content checked against what the artifact says.
 *
 * @property path Its path in the version, `/`-separated, checked by [Delivered.validPath].
 * @property executable Its mode is `executable`.
 */
class DeliveredFile(val path: String, val executable: Boolean, val content: ByteString)

/** A delivered version's files once verified, or why they are refused. */
sealed interface Verified {
    /** Every file matches its size and sha256, and the digest matches the files. */
    class Files(val files: List<DeliveredFile>) : Verified

    /** Why the version is refused, for the member. */
    class Refused(val reason: String) : Verified
}

/**
 * What a delivered artifact (`SkillArtifact`, `HookArtifact` of the contract) is checked against: the digest the
 * catalogue gives, `sha256:<64 hex>` over one line per file sorted by path, `<path>\0<mode>\0<size>\0<sha256 hex>\n`,
 * after a [prefix] (a hook's definition line). The rules of TeamCode's `SkillDigest` (cli/…/cloud/agent/Skills.kt).
 */
object Delivered {
    /** The mode of a file run by nobody. */
    const val FILE = "file"

    /** The mode of a file the agent runs (a hook's command, a skill's script). */
    const val EXECUTABLE = "executable"

    /** At most this many files in one version. */
    const val MAX_FILES = 200

    /** At most this many bytes in one version. */
    const val MAX_BYTES = 64L * 1024 * 1024

    private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9._+-]*")
    private const val MAX_PATH = 255
    private const val MAX_DEPTH = 8

    /** `sha256:<hex>` of [text]. */
    fun digestOf(text: String): String = "sha256:" + text.encodeUtf8().sha256().hex()

    /** The digest line of one file. */
    fun line(path: String, mode: String, size: Long, sha256: String) = "$path\u0000$mode\u0000$size\u0000$sha256\n"

    /**
     * A path a version may hold: at most 8 names of letters, digits, `_`, `.`, `+` and `-`, none starting with a dot
     * nor ending with one, so never `..`, a hidden file or Ostrom's marker.
     */
    fun validPath(path: String): Boolean {
        val names = path.split('/')
        return path.length <= MAX_PATH && names.size <= MAX_DEPTH &&
            names.all { SEGMENT.matches(it) && !it.endsWith(".") }
    }

    /**
     * The files of [artifact], each checked against its `size` and `sha256`, then the [digest] recomputed from them
     * after [prefix]. Refused: no file, too many or too big, a file not described as the contract says (an unknown
     * mode, content that is not base64 or does not match), a path that is not [validPath] or given twice (case
     * ignored, as macOS and Windows do), a digest that differs.
     *
     * @param maxBytes At most this many bytes in all ([MAX_BYTES]).
     * @param maxFiles At most this many files ([MAX_FILES]).
     */
    fun verify(
        artifact: JsonObject,
        digest: String,
        prefix: String = "",
        maxBytes: Long = MAX_BYTES,
        maxFiles: Int = MAX_FILES,
    ): Verified {
        val entries = artifact.list("files")
        val read = entries.map { (it as? JsonObject)?.let(::fileOf) }
        val files = read.filterNotNull().sortedBy { it.path }
        val problem = when {
            entries.isEmpty() -> "it has no file"
            entries.size > maxFiles -> "it has more than $maxFiles files"
            files.size < read.size -> "a file is not described as the contract says"
            else -> problemOf(files, maxBytes)
        }
        if (problem != null) return Verified.Refused(problem)
        val text = prefix + files.joinToString("") { file ->
            val mode = if (file.executable) EXECUTABLE else FILE
            line(file.path, mode, file.content.size.toLong(), file.content.sha256().hex())
        }
        return if (digestOf(text) == digest) Verified.Files(files) else Verified.Refused("digest")
    }

    /** Why [files] cannot be installed together: a path not allowed, one given twice, too many bytes; else null. */
    private fun problemOf(files: List<DeliveredFile>, maxBytes: Long): String? {
        val folded = files.map { it.path.lowercase() }
        val refused = files.firstOrNull { !validPath(it.path) }
        val twice = files.filterIndexed { i, file -> folded.indexOf(file.path.lowercase()) != i }.firstOrNull()
        return when {
            refused != null -> "the path ${refused.path} is not allowed"
            twice != null -> "${twice.path} is twice"
            files.sumOf { it.content.size.toLong() } > maxBytes -> "it is bigger than ${maxBytes / MEBIBYTE} MiB"
            else -> null
        }
    }

    /** One file of the contract's `ArtifactFileContent`, its content matching its size and sha256, its mode known. */
    private fun fileOf(entry: JsonObject): DeliveredFile? {
        val path = entry.text("path")
        val mode = entry.text("mode")?.takeIf { it == FILE || it == EXECUTABLE }
        val size = (entry["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val content = entry.text("contentBase64")?.decodeBase64()
            ?.takeIf { it.size.toLong() == size && it.sha256().hex() == entry.text("sha256") }
        return if (path == null || mode == null || content == null) {
            null
        } else {
            DeliveredFile(path, mode == EXECUTABLE, content)
        }
    }

    private const val MEBIBYTE = 1024 * 1024
}
