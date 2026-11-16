// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/** A process started in the background by [startDetachedProcess], never signalled by this one. */
fun interface DetachedProcess {
    /** Its exit code once it ended (128 + the signal when one ended it), without waiting; null while it runs. */
    fun exitCode(): Int?
}

/**
 * Starts [command] (the executable, then its arguments) in the background, in a session of its own so that neither the
 * terminal's Ctrl-C nor its closing reaches it, stdin from the null device and its output appended to [log] (TeamCode's
 * `startDetachedProcess`, for `ostrom remote start --detach` and the daemon's hosts). On Windows it has no console, and
 * the executable is a full path; [log] exists already there.
 *
 * @throws okio.IOException When it could not be started.
 */
expect fun startDetachedProcess(command: List<String>, log: String): DetachedProcess
