// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0): cli/src/commonMain/kotlin/me/nathanfallet/teamcode/cli/cloud/AttachFiles.kt (the
// local checks and the words of `/attach`), for `ostrom cloud send --file`.
package run.ostrom.cli.commands

import okio.Buffer
import okio.FileMetadata
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.core.api.models.CloudFilePolicy

/** A file read and checked, ready to upload: [shown] is the path as typed, made printable. */
internal class LocalFile(val shown: String, val name: String, val bytes: ByteArray)

/** Reads [typed] without following a link, and checks it against [policy] as `/attach` does before any upload. */
internal fun CliDependencies.readLocalFile(typed: String, policy: CloudFilePolicy): LocalFile {
    val shown = typed.printable()
    if ('\u0000' in typed) refuseFile("$shown is not a valid path.")
    val path = typed.toPath(normalize = false)
    val metadata = metadataOf(path) ?: halt(ExitCode.NOT_FOUND, "No file at $shown.")
    kindRefusal(shown, metadata)?.let(::refuseFile)
    val guessed = CloudFileTypes.forName(path.name)
    if (guessed != null && guessed !in policy.types) refuseFile(notTaken(shown, policy) + ".")
    val bytes = readAtMost(path, policy.maxFileBytes) ?: refuseFile("$shown cannot be read.")
    if (bytes.isEmpty()) refuseFile("$shown is empty.")
    if (bytes.size > policy.maxFileBytes) refuseFile(tooLarge(shown, policy))
    val untyped = guessed == null && !(CloudFileTypes.isText(bytes) && "text/plain" in policy.types)
    if (untyped) refuseFile(notTaken(shown, policy) + ".")
    return LocalFile(shown, path.name, bytes)
}

/** Stops `cloud send` on a file it cannot send (64). */
internal fun refuseFile(text: String): Nothing = halt(ExitCode.USAGE, text)

/** Stops the command with [code] and [text]. */
internal fun halt(code: ExitCode, text: String): Nothing = throw stopWith(code, text)

/** What `cloud send` says of a file above the organization's limit. */
internal fun tooLarge(shown: String, policy: CloudFilePolicy) =
    "$shown is larger than the ${sizeLabel(policy.maxFileBytes)} the organization allows per file."

/** What `cloud send` says of a file of a type the organization does not take. */
internal fun notTaken(shown: String, policy: CloudFilePolicy) =
    "$shown is not a type the organization takes (${CloudFileTypes.labels(policy)})"

/** What [path] is, without following a link, or null when nothing is there (or it cannot be read). */
// Excluded: the fake file system of the tests never fails a metadata read.
@ExcludeFromCoverage
private fun CliDependencies.metadataOf(path: Path): FileMetadata? = try {
    fileSystem.metadataOrNull(path)
} catch (_: IOException) {
    null
}

/** Why [metadata] is not a file to send (a link, a directory, a device…), or null for a regular file. */
// Excluded: the fake file system of the tests has no file that is neither regular, a directory nor a link.
@ExcludeFromCoverage
private fun kindRefusal(shown: String, metadata: FileMetadata): String? = when {
    // Never followed: the file sent is the one typed, not another place a link points to.
    metadata.symlinkTarget != null -> "$shown is a symbolic link; send the file it points to by its own path."
    metadata.isDirectory -> "$shown is a directory; send its files one by one."
    !metadata.isRegularFile -> "$shown is not a regular file."
    else -> null
}

/** At most [max] + 1 bytes of [path] (one more tells a file above [max]), or null when it cannot be read. */
// Excluded: the fake file system of the tests never fails a read.
@ExcludeFromCoverage
private fun CliDependencies.readAtMost(path: Path, max: Long): ByteArray? = try {
    fileSystem.source(path).buffer().use { source ->
        val read = Buffer()
        while (read.size <= max && source.read(read, CHUNK) != -1L) Unit
        read.readByteArray(minOf(read.size, max + 1))
    }
} catch (_: IOException) {
    null
}

private const val CHUNK = 64L * 1024
