// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.IOException
import run.ostrom.cli.platform.interop.ostrom_detached_exit
import run.ostrom.cli.platform.interop.ostrom_spawn_logged

/** `posix_spawnp` in a new session, its output appended to [log]; its end read with `waitpid(WNOHANG)`. */
actual fun startDetachedProcess(command: List<String>, log: String): DetachedProcess = memScoped {
    require(command.isNotEmpty()) { "A command needs at least its executable." }
    val argv = allocArrayOf(command.map { it.cstr.ptr } + listOf(null))
    val pid = ostrom_spawn_logged(command.first(), argv, log)
    if (pid <= 0) throw IOException("${command.first()} could not be started.")
    var ended: Int? = null
    DetachedProcess { ended ?: ostrom_detached_exit(pid).takeIf { it >= 0 }?.also { ended = it } }
}
