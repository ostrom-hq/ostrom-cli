// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/**
 * What the managed installation of `ostrom install` needs from the operating system (TeamCode's
 * `InstallationPlatform`): locks that never wait, executables flushed to disk, folders whose renames are flushed, and
 * the check that a file of the installation is this user's alone.
 */
interface InstallationFiles {
    /**
     * Takes the shared or [exclusive] lock of the file at [path] (created, owner-only, never deleted) without waiting:
     * null when another process holds it. The system releases it when the process ends.
     *
     * @throws okio.IOException When the file cannot be opened or locked.
     */
    fun tryLock(path: String, exclusive: Boolean): AutoCloseable?

    /**
     * Flushes the verified regular executable at [path] to disk and makes it the owner's alone (0700).
     *
     * @throws okio.IOException When it cannot, or [path] is not a regular file of this user with a single name.
     */
    fun seal(path: String)

    /**
     * Flushes the folder [path] (the renames made in it) to disk; nothing on Windows, whose moves write through.
     *
     * @throws okio.IOException When it cannot.
     */
    fun flushDirectory(path: String)

    /**
     * True when [path] is not a link, is a folder ([directory]) or a regular file with a single name, and (macOS and
     * Linux) belongs to this user and gives nothing to its group or others.
     */
    fun isPrivate(path: String, directory: Boolean): Boolean

    /**
     * The [FileIdentity] of the regular file at [path], never followed when it is a link: null when it cannot be read
     * or is not a regular file.
     */
    fun identity(path: String): FileIdentity?
}

/**
 * What tells, without reading it, that a file is still the one that was checked: its [device] and [inode] (the volume
 * serial number and the file index on Windows), its [size], and its [modified] and [changed] times (nanoseconds since
 * the epoch on macOS and Linux, 100 ns units on Windows). Any write, `chmod` or replacement of the file changes
 * [changed], which a process cannot set back on macOS and Linux (only a change of the system clock can).
 */
data class FileIdentity(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)

/** [InstallationFiles] of this system. */
// coverage: the system's own locks and files; the managed installation's tests use a fake InstallationFiles.
@ExcludeFromCoverage
object SystemInstallationFiles : InstallationFiles {
    override fun tryLock(path: String, exclusive: Boolean): AutoCloseable? = tryLockInstallationFile(path, exclusive)
    override fun seal(path: String) = sealInstallationFile(path)
    override fun flushDirectory(path: String) = flushInstallationDirectory(path)
    override fun isPrivate(path: String, directory: Boolean): Boolean = isPrivateInstallationPath(path, directory)
    override fun identity(path: String): FileIdentity? = installationFileIdentity(path)
}

internal expect fun tryLockInstallationFile(path: String, exclusive: Boolean): AutoCloseable?

internal expect fun sealInstallationFile(path: String)

internal expect fun flushInstallationDirectory(path: String)

internal expect fun isPrivateInstallationPath(path: String, directory: Boolean): Boolean

internal expect fun installationFileIdentity(path: String): FileIdentity?

/**
 * Runs the payload [path] with [arguments] in place of this process: `execv` on macOS and Linux (it returns only when
 * it could not), a child sharing the terminal on Windows, whose exit code is returned.
 *
 * @throws okio.IOException When the payload cannot be started.
 */
expect fun replaceProcess(path: String, arguments: List<String>): Int

/** The current user's PATH in the Windows registry; [expandable] for REG_EXPAND_SZ, otherwise REG_SZ. */
data class UserPath(val value: String, val expandable: Boolean)

/**
 * The current user's PATH (`HKCU\Environment`), or null when it is not set. Windows only.
 *
 * @throws okio.IOException When it cannot be read, or on another system.
 */
expect fun readUserPath(): UserPath?

/**
 * Replaces the current user's PATH with [value] and tells running applications. Windows only.
 *
 * @throws okio.IOException When it cannot be written, or on another system.
 */
expect fun writeUserPath(value: UserPath)
