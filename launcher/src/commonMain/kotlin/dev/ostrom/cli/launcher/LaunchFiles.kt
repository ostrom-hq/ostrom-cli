// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.platform.restrictToOwner
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/** Who may use a file the launcher writes. */
enum class FileAccess {
    /** The default mode of the user (the agent's own files read by the agent). */
    SHARED,

    /** Read and written by its owner only (0600): tokens, settings, envelopes. */
    PRIVATE,

    /** A script of the package, run by its owner only (0700). */
    EXECUTABLE,
}

/** The files a launch lays down for its session only, and the developer's own files it reads. */
interface LaunchFiles {
    /** The text of [path], or null when it cannot be read. */
    fun read(path: String): String?

    /** Writes [text] to [path], its folders created. */
    fun write(path: String, text: String, access: FileAccess = FileAccess.SHARED)

    /** Creates the folder [path] (and its parents), usable by its owner only (0700). */
    fun createPrivateDirectory(path: String)

    /** The names of the entries of the folder [path], sorted; empty when it does not exist. */
    fun list(path: String): List<String>

    /** True when [path] exists. */
    fun exists(path: String): Boolean

    /** Makes [link] a symbolic link to [target]; false where links cannot be made (Windows without developer mode). */
    fun link(target: String, link: String): Boolean

    /** Deletes [path] and everything under it. */
    fun deleteRecursively(path: String)
}

/**
 * [LaunchFiles] over okio.
 *
 * @param restrict Sets the owner-only mode of a path (`executable`: 0700, else 0600); the platform's by default.
 */
class LaunchFilesImpl(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val restrict: (String, Boolean) -> Unit = ::restrictToOwner,
) : LaunchFiles {
    override fun read(path: String): String? = try {
        fileSystem.read(path.toPath()) { readUtf8() }
    } catch (_: IOException) {
        null
    }

    override fun write(path: String, text: String, access: FileAccess) {
        val file = path.toPath()
        fileSystem.createDirectories(requireNotNull(file.parent) { "$path is a root, not a file." })
        fileSystem.write(file) { writeUtf8(text) }
        when (access) {
            FileAccess.SHARED -> Unit
            FileAccess.PRIVATE -> restrict(path, false)
            FileAccess.EXECUTABLE -> restrict(path, true)
        }
    }

    override fun createPrivateDirectory(path: String) {
        fileSystem.createDirectories(path.toPath())
        restrict(path, true)
    }

    override fun list(path: String): List<String> = try {
        fileSystem.list(path.toPath()).map { it.name }.sorted()
    } catch (_: IOException) {
        emptyList()
    }

    override fun exists(path: String): Boolean = fileSystem.exists(path.toPath())

    override fun link(target: String, link: String): Boolean = try {
        fileSystem.createSymlink(link.toPath(), target.toPath())
        true
    } catch (_: IOException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    override fun deleteRecursively(path: String) = fileSystem.deleteRecursively(path.toPath(), mustExist = false)
}
