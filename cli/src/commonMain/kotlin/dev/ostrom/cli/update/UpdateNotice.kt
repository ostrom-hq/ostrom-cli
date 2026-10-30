// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.BuildInfo
import dev.ostrom.cli.hooks.runtime.SessionHooks
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.FileStore
import dev.ostrom.cli.platform.FileWriteException
import dev.ostrom.cli.platform.ProcessCapture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The update notice (CLI.md § 2.2, `ostrom update`): `! ostrom 0.4.1 is available. Run ostrom update.` on stderr after
 * another command, once a day at most. No command ever waits for the network for it: the latest version is read from
 * the file at [path], which a detached `ostrom update --record-latest` ([refresh]) rewrites, once a day at most too.
 *
 * @param current The version of this binary.
 * @param now The time, in milliseconds since the epoch.
 * @param refresh Starts the detached refresh; false when it could not start.
 */
class UpdateNotice(
    private val files: FileStore,
    private val path: String,
    private val current: String,
    private val now: () -> Long,
    private val refresh: () -> Boolean,
) {
    /** After a command: says the notice when it is due, and starts a refresh when the last one is a day old. */
    fun after(say: (String) -> Unit) {
        val state = parseObject(files.read(path)) ?: JsonObject(emptyMap())
        val latest = state.text(LATEST)
        val time = now()
        val show = latest != null && newer(latest, current) && due(state.millis(NOTIFIED), time)
        val check = due(state.millis(CHECKED), time)
        if (show) say("! ostrom $latest is available. Run ostrom update.\n")
        if (!show && !check) return
        val next = JsonObject(
            state + listOfNotNull(
                (NOTIFIED to JsonPrimitive(time)).takeIf { show },
                (CHECKED to JsonPrimitive(time)).takeIf { check },
            ),
        )
        // written before the refresh starts, so that two commands at the same time start one refresh, not two
        if (write(next) && check) refresh()
    }

    /** `ostrom update --record-latest`: keeps [latest] (null: unknown, the last one known is kept). */
    fun record(latest: String?) {
        val state = parseObject(files.read(path)) ?: JsonObject(emptyMap())
        write(JsonObject(state + objectOf(LATEST to latest, CHECKED to now())))
    }

    private fun write(state: JsonObject): Boolean = try {
        files.write(path, state.toString())
        true
    } catch (_: FileWriteException) {
        // a folder that cannot be written: no notice is worth an error, and no refresh starts again and again
        false
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
    }
}

/** The update notice's file, in the CLI folder. */
const val UPDATE_NOTICE_FILE = "update-check.json"

/** The update notice kept in the CLI folder [directory], on the real clock; none without a folder. */
internal fun updateNoticeOf(directory: String?, files: FileStore, refresh: () -> Boolean): UpdateNotice? =
    directory?.let {
        UpdateNotice(files, join(it, UPDATE_NOTICE_FILE), BuildInfo.VERSION, SessionHooks::epochMillis, refresh)
    }

/** The refresh of the notice: this binary, [self], started detached as `ostrom update --record-latest`. */
internal fun detachedRefresh(self: String?, capture: ProcessCapture): () -> Boolean =
    { self != null && capture.detach(listOf(self, "update", "--record-latest")) }
