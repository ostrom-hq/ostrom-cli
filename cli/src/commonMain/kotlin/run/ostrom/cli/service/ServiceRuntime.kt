// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.holdExclusiveLock
import run.ostrom.cli.platform.installServiceSignalHandlers
import run.ostrom.cli.platform.serviceStopRequested
import kotlin.time.Clock

internal fun stopRequested(settings: ServiceSettings, signalled: () -> Boolean = ::serviceStopRequested): Boolean =
    signalled() || (settings.os == "windows" && stopMarkerNames(settings))

/** Whether Task Scheduler's cooperative stop marker (`service.stop`) holds this service's id. */
private fun stopMarkerNames(settings: ServiceSettings): Boolean {
    val path = serviceStopPath(settings.config).toPath()
    val metadata = FileSystem.SYSTEM.metadataOrNull(path) ?: return false
    require(isPlainFile(metadata, STOP_MARKER_BYTES))
    return FileSystem.SYSTEM.read(path) { readUtf8() } == settings.id
}

private const val STOP_MARKER_BYTES = 64L
private const val LOG_LINES = 128
private const val LOG_LINE_CHARACTERS = 240
private const val LOG_QUEUE = 64

/**
 * The local service. One per configuration: `daemon.lock` is held for the whole run, and a second instance exits before
 * writing anything (not even the log, which it would replace). That is not a failure: the OS manager restarts a service
 * that exits with an error, so the second instance calls [onBusy] and returns (the command exits 0) instead.
 */
internal suspend fun serve(
    settings: ServiceSettings,
    onBusy: () -> Unit = {},
    daemon: ServiceDaemon,
    signalled: () -> Boolean = ::serviceStopRequested,
) = coroutineScope {
    installServiceSignalHandlers()
    val instance = holdExclusiveLock(daemonLockPath(settings.config)) ?: return@coroutineScope onBusy()
    try {
        serveLocked(settings, daemon) { stopRequested(settings, signalled) }
    } finally {
        instance.close()
    }
}

private suspend fun serveLocked(
    settings: ServiceSettings,
    daemon: ServiceDaemon,
    stop: () -> Boolean,
) = coroutineScope {
    val logs = Channel<String>(LOG_QUEUE)
    val writer = launch {
        val lines = ArrayDeque<String>()
        for (message in logs) {
            lines.addLast("${Clock.System.now()} ${message.take(LOG_LINE_CHARACTERS)}")
            while (lines.size > LOG_LINES) lines.removeFirst()
            writePrivateFile(serviceLogPath(settings.config), lines.joinToString("\n", postfix = "\n"))
        }
    }
    fun report(message: String) {
        logs.trySend(message)
    }
    try {
        report("Service started.")
        daemon(settings, ::report, stop)
        report("Service stopped.")
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        report("Service failed. Check the saved configuration and file permissions.")
        throw error
    } finally {
        logs.close()
        withContext(NonCancellable) { writer.join() }
    }
}
