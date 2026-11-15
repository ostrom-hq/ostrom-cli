// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/**
 * The operating system account the local service runs as.
 *
 * @property userId The numeric user id on macOS and Linux, the account's SID (`S-1-5-...`) on Windows.
 * @property userName The account name; macOS and Linux only.
 * @property userHome The home folder the system lists for the account; macOS and Linux only.
 */
data class ServiceAccount(val userId: String, val userName: String? = null, val userHome: String? = null)

/** The account this process runs as. */
expect fun currentServiceAccount(): ServiceAccount

/**
 * Takes the exclusive advisory lock on [path] (an empty file, created when missing, owner-only) for as long as the
 * returned handle stays open, without waiting: null when another holder has it. The system releases it when the holder
 * exits, and child processes never inherit it. Unlike [FileStore.exclusive] (held around a short read-modify-write),
 * this lock is held for a process's whole life: a BSD `flock` on macOS and Linux, `LockFileEx` on Windows. A second
 * acquisition in the same process gets null too.
 *
 * @throws okio.IOException When the file cannot be opened or locked (a symbolic link in its place, say).
 */
expect fun holdExclusiveLock(path: String): AutoCloseable?

/**
 * Makes the service's stop request readable by [serviceStopRequested]: SIGTERM and SIGINT on macOS and Linux, the
 * console control events (Ctrl+C, Ctrl+Break, close, logoff, shutdown) on Windows. Called once, by `service serve`.
 */
expect fun installServiceSignalHandlers()

/** Whether a stop was requested since [installServiceSignalHandlers]. */
expect fun serviceStopRequested(): Boolean

/**
 * Sets (or, with a null [value], removes) a variable of this process's environment, which the processes it starts
 * afterwards inherit: how the service manager's PowerShell scripts receive their values. The JVM, which only runs the
 * tests, cannot change its own environment and ignores it.
 */
expect fun setEnvironmentVariable(name: String, value: String?)
