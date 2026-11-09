// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.BuildInfo
import run.ostrom.cli.hooks.runtime.SessionHooks
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.platform.FileWriteException
import run.ostrom.cli.platform.ProcessCapture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Path.Companion.toPath

/**
 * The update notice (CLI.md § 2.2, `ostrom update`): `! ostrom 0.4.1 is available. Run ostrom update.` on stderr after
 * another command, once a day at most. No command ever waits for the network for it: the latest version is read from
 * the file at [path], which a detached `ostrom update --record-latest` ([refresh]) rewrites, once a day at most too.
 *
 * @param current The version of this binary.
 * @param now The time, in milliseconds since the epoch.
 * @param advice What the notice tells to do ([noticeAdvice]); null while the notice must wait (it is said later).
 * @param refresh Starts the detached refresh; false when it could not start.
 */
class UpdateNotice(
    private val files: FileStore,
    private val path: String,
    private val current: String,
    private val now: () -> Long,
    private val advice: () -> String?,
    private val refresh: () -> Boolean,
) {
    /**
     * After a command: says the notice when it is due, and starts a refresh when the last one is a day old. Both are
     * stamped in the file first, under its lock: a folder that cannot be written says nothing and starts nothing, or
     * it would say the notice after every command and start a refresh each time.
     */
    fun after(say: (String) -> Unit) {
        val due = stamped() ?: return
        due.notice?.let(say)
        if (due.check) refresh()
    }

    /** What is due now, stamped in the file under its lock; null when nothing is or the file cannot be written. */
    private fun stamped(): Due? = try {
        files.exclusive(path) { stamp(now()) }
    } catch (_: FileWriteException) {
        null
    }

    /** What is due at [time], stamped in the file; null when nothing is. @throws FileWriteException */
    private fun stamp(time: Long): Due? {
        val state = parseObject(files.read(path)) ?: JsonObject(emptyMap())
        val latest = state.text(LATEST)
        val fresh = latest != null && newer(latest, current) && due(state.millis(NOTIFIED), time)
        // asked only when there is something to say: it may look for a running session
        val notice = if (fresh) advice()?.let { "! ostrom $latest is available. $it\n" } else null
        val check = due(state.millis(CHECKED), time)
        if (notice == null && !check) return null
        val stamps = listOfNotNull(NOTIFIED.takeIf { notice != null }, CHECKED.takeIf { check })
        files.write(path, JsonObject(state + stamps.associateWith { JsonPrimitive(time) }).toString())
        return Due(notice, check)
    }

    /**
     * `ostrom update --record-latest`: keeps [latest] (null: unknown, the last one known is kept). A lookup that failed
     * (offline, a captive portal) is tried again an hour later, not the next day.
     */
    fun record(latest: String?) {
        try {
            files.exclusive(path) {
                val state = parseObject(files.read(path)) ?: JsonObject(emptyMap())
                val time = now()
                val checked = if (latest == null) time - DAY_MILLIS + RETRY_MILLIS else time
                files.write(path, JsonObject(state + objectOf(LATEST to latest, CHECKED to checked)).toString())
            }
        } catch (_: FileWriteException) {
            // a folder that cannot be written: nothing is said after a command either
        }
    }

    private fun JsonObject.millis(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun due(last: Long?, time: Long) = last == null || time - last !in 0 until DAY_MILLIS

    /** The keys of the notice's file. */
    companion object {
        /** The latest signed release seen. */
        const val LATEST = "latest"

        /** When the latest release was last looked up. */
        const val CHECKED = "checkedAt"

        /** When the notice was last said. */
        const val NOTIFIED = "notifiedAt"

        /** A day. */
        const val DAY_MILLIS = 86_400_000L

        /** How soon a lookup that failed is tried again. */
        const val RETRY_MILLIS = 3_600_000L
    }
}

/** The update notice's file, in the CLI folder. */
const val UPDATE_NOTICE_FILE = "update-check.json"

/** The update notice kept in the CLI folder [directory], on the real clock; none without a folder. */
internal fun updateNoticeOf(
    directory: String?,
    files: FileStore,
    advice: () -> String?,
    refresh: () -> Boolean,
): UpdateNotice? = directory?.let {
    UpdateNotice(files, join(it, UPDATE_NOTICE_FILE), BuildInfo.VERSION, SessionHooks::epochMillis, advice, refresh)
}

/**
 * What the notice tells to do: the package manager's command for an install it manages (`ostrom update` refuses
 * there); nothing while an agent's session runs (`ostrom update` refuses then too: the notice waits for the sessions to
 * end); else `ostrom update`.
 *
 * @param executable This binary, or null when the platform does not say.
 */
internal fun noticeAdvice(executable: String?, sessions: AgentSessions): () -> String? = {
    val manager = executable?.let { managerOf(it.toPath()) }
    when {
        manager != null -> "$manager."
        sessions.running() != null -> null
        else -> "Run ostrom update."
    }
}

/** The refresh of the notice: this binary, [self], started detached as `ostrom update --record-latest`. */
internal fun detachedRefresh(self: String?, capture: ProcessCapture): () -> Boolean =
    { self != null && capture.detach(listOf(self, "update", "--record-latest")) }

/** What [UpdateNotice.after] does: the [notice] to say, if any, and whether to [check] for a newer release. */
private class Due(val notice: String?, val check: Boolean)
