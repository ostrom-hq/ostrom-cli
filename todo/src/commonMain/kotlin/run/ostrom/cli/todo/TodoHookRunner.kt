// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.StandardInput

/**
 * `ostrom hook todo-stop|todo-context`, the todo's hooks, reached from `main`'s fast path like the gate. They reach
 * Ostrom only through the launch's local relay (`OSTROM_RELAY_URL`) with the hooks' capability, read from its file
 * (`OSTROM_RELAY_TOKEN_FILE`, never from the agent's environment). They print their answer, if any, and always exit 0:
 * a failure of Ostrom never keeps the agent from stopping or starting.
 *
 * @param io Where the blocking calls of the Stop hook run.
 */
@Suppress("LongParameterList") // the hooks' ports, each faked in its tests
class TodoHookRunner(
    private val console: Console,
    private val environment: Environment,
    private val files: FileSystem,
    private val http: LoopbackHttp,
    private val capture: ProcessCapture,
    private val io: CoroutineDispatcher,
) {
    /**
     * Runs the hook [kind] ([STOP] or [CONTEXT]) on the event on stdin; its exit code, always 0. Whatever fails on the
     * way (the read, the relay, a hook-error that fails too) prints nothing: the agent stops or starts as usual.
     * [Throwable], as the gate's runner: this is the hook process's blocking entry, no cancellation can reach it.
     */
    @Suppress("TooGenericExceptionCaught")
    fun run(kind: String): Int {
        val answer = try {
            answer(kind)
        } catch (ignored: Throwable) { // not coroutine-reachable: the hook process's blocking entry, its own guard
            null
        }
        answer?.let { console.data("$it\n") }
        return 0
    }

    private fun answer(kind: String): String? {
        val read = console.readStandardInput(MAX_INPUT, READ_MILLIS) as? StandardInput.Read
        val port = environment["OSTROM_RELAY_URL"]?.let { RELAY_URL.matchEntire(it) }?.let { it.groupValues[1].toInt() }
        val token = environment["OSTROM_RELAY_TOKEN_FILE"]?.let(::token)
        if (read == null || port == null || token == null) return null
        val input = read.bytes.decodeToString()
        val hooks = RelayTodoHooks(http, port, token)
        return if (kind == CONTEXT) todoContext(input, hooks::context) else stopped(input, hooks, port, token)
    }

    private fun stopped(input: String, hooks: TodoHookApi, port: Int, token: String): String? {
        val api = RelayTodoApi(http, port, token)
        val checks = TodoChecks(GitHub(capture), CommandCheck(capture, environment.operatingSystem))
        val stop = TodoStop(api, hooks, checks, Transcripts(files)::pullRequestsOf, io)
        return runBlocking { stop.decide(input) }
    }

    private fun token(file: String): String? = try {
        files.read(file.toPath()) { readUtf8() }.trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    }

    /** The hooks' names after `ostrom hook`. */
    companion object {
        /** The Stop hook. */
        const val STOP = "todo-stop"

        /** The SessionStart (compact, resume) hook. */
        const val CONTEXT = "todo-context"

        /** Both. */
        val KINDS = setOf(STOP, CONTEXT)

        private const val MAX_INPUT = 8 shl 20
        private const val READ_MILLIS = 5_000
        private val RELAY_URL = Regex("http://127\\.0\\.0\\.1:(\\d{1,5})/?")
    }
}
