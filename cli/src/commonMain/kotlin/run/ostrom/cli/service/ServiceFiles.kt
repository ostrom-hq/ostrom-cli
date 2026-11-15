// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.restrictToOwner

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

/** Publishes [content] as a private file only when [path] does not exist; false when it does (nothing is replaced). */
internal fun createPrivateFile(path: String, content: String): Boolean {
    val fs = FileSystem.SYSTEM
    val parent = requireNotNull(path.toPath().parent) { "$path is a root, not a file." }
    fs.createDirectories(parent)
    try {
        // O_EXCL: of two concurrent setups, one creates the file and the other gives way.
        fs.write(path.toPath(), mustCreate = true) { }
    } catch (_: IOException) {
        return false
    }
    restrictToOwner(path, executable = false)
    writePrivateFile(path, content)
    return true
}

/** Whether [meta] is a plain file (no link, no folder) of at most [limit] bytes; a size not reported counts as 0. */
// coverage: no file system we read reports no size; the other branches are tested through the callers.
@ExcludeFromCoverage
internal fun isPlainFile(meta: FileMetadata, limit: Long): Boolean =
    meta.isRegularFile && meta.symlinkTarget == null && (meta.size ?: 0) <= limit
