// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import run.ostrom.cli.runner.pty.ostrom_check_host_harden
import run.ostrom.cli.runner.pty.ostrom_check_host_sweep
import kotlinx.cinterop.staticCFunction
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.IO
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.free
import platform.posix.mkdtemp
import platform.posix.realpath
import platform.posix.system
import platform.posix.signal
import kotlin.system.exitProcess

/**
 * The check host (R4): the pid 1 of its container, under the scripts' uid. It stops being dumpable, links to the
 * runner, then runs the checks the runner asks for one after the other, each in a new home, killing what each left
 * behind. When the link ends it exits: the runner accepts no second link, so later checks fail rather than reach
 * another process.
 */
internal fun checkHost(socket: String, workspace: String): Nothing {
    signal(SIGTERM, staticCFunction<Int, Unit> { _exit(0) })
    if (ostrom_check_host_harden() != 0) log("the check host could not stop being dumpable")
    runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val connect = suspend { AgentSocket.connect(selector, socket) }
        val link = linkToRunner(connect)
        log("check host linked to the runner")
        val terminals = CheckTerminals(PtyTerminal.Pipes, NativeCheckHomes, workspace, ::realPath)
        CheckHost(AgentHost(terminals), connect, ::log).serve(link)
    }
    log("the runner closed the check link")
    exitProcess(EXIT_LINK_CLOSED)
}

/** The first connection to the runner, retried while it starts. */
private suspend fun linkToRunner(connect: suspend () -> AgentConnection): AgentConnection {
    repeat(LINK_ATTEMPTS - 1) {
        try {
            return connect()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            delay(LINK_RETRY_MILLIS)
        }
    }
    return connect()
}

/** The real path of [path], links resolved, or null when it does not exist. */
internal fun realPath(path: String): String? {
    val resolved = realpath(path, null) ?: return null
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

/** The checks' homes, under /tmp; a release kills every other process of the uid first (pid 1 only). */
private object NativeCheckHomes : CheckHomes {
    override fun create(): String = memScoped {
        val template = "/tmp/check-XXXXXX".cstr.getPointer(this)
        mkdtemp(template)?.toKString() ?: error("no home for the check")
    }

    override fun release(home: String) {
        if (ostrom_check_host_sweep() != 0) log("the check host could not kill what the check left behind")
        // The path is mkdtemp's: /tmp/check- and six letters or digits, nothing to quote.
        if (system("rm -rf -- $home") != 0) log("the check host could not remove a check's home")
    }
}

/** The check host links to the runner as the session starts: it tries for a minute. */
private const val LINK_ATTEMPTS = 120
private const val LINK_RETRY_MILLIS = 500L

/** The check host's exit once the runner closed the link. */
private const val EXIT_LINK_CLOSED = 3
