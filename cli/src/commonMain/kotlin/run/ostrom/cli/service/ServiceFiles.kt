// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import okio.ByteString.Companion.toByteString
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.publishFileIfAbsent
import run.ostrom.cli.platform.restrictToOwner
import run.ostrom.cli.platform.secureRandomBytes

/**
 * Replaces the file at [path] with [content], atomically, readable by its owner only (the CLI folder too). The file and
 * its folder are never a symbolic link.
 */
internal fun writePrivateFile(path: String, content: String) {
    val parent = requireNotNull(path.toPath().parent) { "$path is a root, not a file." }
    val fs = FileSystem.SYSTEM
    require(fs.metadataOrNull(parent)?.symlinkTarget == null) { "The configuration folder cannot be a symbolic link." }
    require(fs.metadataOrNull(path.toPath())?.symlinkTarget == null) { "The file cannot be a symbolic link." }
    FileStoreImpl(fs).write(path, content)
    restrictToOwner(parent.toString(), executable = true)
}

/**
 * Publishes [content] as a private file only when [path] does not exist; false when it does (nothing is replaced). The
 * content is written whole to a file of its own first and published in one atomic step, so a crash never leaves an
 * empty or half-written [path], and of two concurrent setups one wins. Any other failure is thrown.
 *
 * @param publish Publishes the finished file when the target is absent ([publishFileIfAbsent]).
 */
internal fun createPrivateFile(
    path: String,
    content: String,
    publish: (temporary: String, target: String) -> Boolean = ::publishFileIfAbsent,
): Boolean {
    val fs = FileSystem.SYSTEM
    val parent = requireNotNull(path.toPath().parent) { "$path is a root, not a file." }
    fs.createDirectories(parent)
    val temporary = "$path.${secureRandomBytes(TEMPORARY_BYTES).toByteString().hex()}.tmp"
    writePrivateFile(temporary, content)
    try {
        return publish(temporary, path)
    } catch (failure: IOException) {
        fs.delete(temporary.toPath(), mustExist = false)
        throw failure
    }
}

private const val TEMPORARY_BYTES = 12

/** Whether [meta] is a plain file (no link, no folder) of at most [limit] bytes; a size not reported counts as 0. */
// coverage: no file system we read reports no size; the other branches are tested through the callers.
@ExcludeFromCoverage
internal fun isPlainFile(meta: FileMetadata, limit: Long): Boolean =
    meta.isRegularFile && meta.symlinkTarget == null && (meta.size ?: 0) <= limit
