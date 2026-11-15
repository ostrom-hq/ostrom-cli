// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

// TeamCode's cli/platform/UploadFiles.kt: what a conversation host needs to write the files of a member's message
// for its agent (`runners.cloud.files.v1`) without ever following a link or widening a mode.

/**
 * A path as the host sees it before writing a message file under it, read without following a link.
 *
 * @property directory Whether it is a directory.
 * @property symlink Whether it is a link.
 * @property ownedByCurrentUser Whether the current user owns it.
 * @property mode Its permission bits (0 where the platform has none).
 */
data class PathStatus(val directory: Boolean, val symlink: Boolean, val ownedByCurrentUser: Boolean, val mode: Int)

/**
 * Reads [path] without following a link.
 *
 * @param path The path.
 *
 * @return What it is, or null when nothing is there.
 */
expect fun pathStatus(path: String): PathStatus?

/**
 * Creates the directory [path] with [mode], left as it is when it exists.
 *
 * @param path The directory.
 * @param mode Its permission bits.
 *
 * @return Whether it was created.
 *
 * @throws IllegalStateException When it cannot be created for another reason than existing.
 */
expect fun createModeDirectory(path: String, mode: Int): Boolean

/**
 * Sets the permission bits of [path], which the caller checked is no link; nothing on a platform without them.
 *
 * @param path The path.
 * @param mode The bits.
 *
 * @throws IllegalStateException When they cannot be set.
 */
expect fun setPathMode(path: String, mode: Int)

/** A file being written by the host; [close] once, after the last [write]. */
interface ExclusiveFile {
    /**
     * Appends bytes.
     *
     * @param bytes The buffer.
     * @param count How many of its first bytes to write.
     */
    fun write(bytes: ByteArray, count: Int)

    /** Flushes the file to disk and closes it. */
    fun close()
}

/**
 * Creates [path] for writing only when nothing exists there (`O_CREAT | O_EXCL | O_NOFOLLOW`), with exactly [mode].
 *
 * @param path The file.
 * @param mode Its permission bits, set whatever the umask.
 *
 * @return The open file, or null when the path exists, a link included.
 *
 * @throws IllegalStateException When it cannot be created for another reason.
 */
expect fun createExclusiveFile(path: String, mode: Int): ExclusiveFile?
