// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
private const val STOP_POLL_MILLIS = 250L

/**
 * What the local service runs while it holds its lock. The device daemon (remote sessions hosted on this device) and
 * the persistent relay start here, in later slices; until then the service only waits for its stop request.
 *
 * @param report Writes a line to `service.log` (at most 240 characters; never a credential or a response body).
 * @param stopRequested True once the service manager (or `service stop`) asked it to stop: return then, after
 * finishing what it hosts.
 */
@Suppress("UnusedParameter") // the daemon of a later slice reads the settings
internal suspend fun runDaemon(settings: ServiceSettings, report: (String) -> Unit, stopRequested: () -> Boolean) {
    report("Nothing to host yet; waiting to be stopped.")
    while (!stopRequested()) delay(STOP_POLL_MILLIS)
}

/**
 * The local service. One per configuration: `daemon.lock` is held for the whole run, and a second instance exits before
 * writing anything (not even the log, which it would replace). That is not a failure: the OS manager restarts a service
 * that exits with an error, so the second instance calls [onBusy] and returns (the command exits 0) instead.
 */
internal suspend fun serve(
    settings: ServiceSettings,
    onBusy: () -> Unit = {},
    daemon: suspend (ServiceSettings, (String) -> Unit, () -> Boolean) -> Unit = ::runDaemon,
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
    daemon: suspend (ServiceSettings, (String) -> Unit, () -> Boolean) -> Unit,
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
