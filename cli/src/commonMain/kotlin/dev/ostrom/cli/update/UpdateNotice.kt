// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.platform.FileStore
import dev.ostrom.cli.platform.ProcessCapture

/** The update notice (CLI.md § 2.2): not built yet, the red run of its tests. */
@Suppress("UnusedPrivateProperty", "UnusedParameter")
class UpdateNotice(
    private val files: FileStore,
    private val path: String,
    private val current: String,
    private val now: () -> Long,
    private val advice: () -> String?,
    private val refresh: () -> Boolean,
) {
    /** Not built yet. */
    fun after(say: (String) -> Unit) = Unit

    /** Not built yet. */
    fun record(latest: String?) = Unit

    /** The keys of the notice's file. */
    companion object {
        /** When the latest release was last looked up. */
        const val CHECKED = "checkedAt"

        /** A day. */
        const val DAY_MILLIS = 86_400_000L

        /** How soon a lookup that failed is tried again. */
        const val RETRY_MILLIS = 3_600_000L
    }
}

/** The update notice's file, in the CLI folder. */
const val UPDATE_NOTICE_FILE = "update-check.json"

/** Not built yet. */
@Suppress("UnusedParameter")
internal fun updateNoticeOf(
    directory: String?,
    files: FileStore,
    advice: () -> String?,
    refresh: () -> Boolean,
): UpdateNotice? = null

/** Not built yet. */
@Suppress("UnusedParameter")
internal fun noticeAdvice(executable: String?, sessions: AgentSessions): () -> String? = { null }

/** Not built yet. */
@Suppress("UnusedParameter")
internal fun detachedRefresh(self: String?, capture: ProcessCapture): () -> Boolean = { false }
