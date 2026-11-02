// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.platform.UnixSockets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One check asked of the cloud session's runner: the script's text as the hook read it, never a file the runner would
 * trust. The runner computes its SHA-256 itself, runs it on its check host, reports the run to Ostrom (attested
 * `isolated_runner`, `tampered` when the script is not the package's) and answers its exit code.
 *
 * @property sessionId The Ostrom session the check belongs to.
 * @property digest The package's digest.
 * @property nodeId The workflow node of the check.
 * @property scriptId The script's id in the package.
 * @property script The script's text.
 * @property directory Where it runs: the repository, or the detached checkout of the commit a push sends.
 * @property head The commit it tests.
 * @property timeoutMillis The script's own time limit.
 */
data class RunnerCheck(
    val sessionId: String,
    val digest: String,
    val nodeId: String,
    val scriptId: String,
    val script: String,
    val directory: String,
    val head: String,
    val timeoutMillis: Int,
)

/** Asks the cloud session's runner to run a check. */
fun interface RunnerChecks {
    /** @return What became of the check; [ScriptRun.started] is false when the runner gave no usable answer. */
    fun run(check: RunnerCheck): ScriptRun
}

/**
 * [RunnerChecks] over the runner's Unix socket (`OSTROM_RUNNER_CHECKS`, `/run/ostrom/checks.sock`): one JSON line
 * out, one JSON object back (`{"exit": 0, "started": true, "outputTail": "…"}`), then the runner closes. A runner
 * that does not answer refuses the action: a cloud session never falls back to running the check itself, whose run
 * would only be declared by the workstation.
 *
 * @param path The runner's socket.
 * @param sockets The Unix socket client.
 * @param budget The agent's limit on the hook, which the whole exchange keeps to.
 */
class RunnerSocketChecks(
    private val path: String,
    private val sockets: UnixSockets,
    private val budget: HookBudget = HookBudget.NONE,
) : RunnerChecks {
    override fun run(check: RunnerCheck): ScriptRun {
        // the runner's own limit is the script's; the margin lets it report the run before it answers
        val limit = budget.cap(check.timeoutMillis + REPORT_MARGIN_MILLIS)
            ?: return ScriptRun(null, SandboxedScripts.SPENT, ISOLATED, started = false)
        val request = jsonOf(
            "sessionId" to check.sessionId,
            "digest" to check.digest,
            "nodeId" to check.nodeId,
            "scriptId" to check.scriptId,
            "script" to check.script,
            "directory" to check.directory,
            "head" to check.head,
            "timeoutMs" to check.timeoutMillis,
        )
        val answer = sockets.exchange(path, "$request\n", limit)
        return answer.fold(
            ::read,
            { failure -> ScriptRun(null, "the session's runner did not answer (${failure.message})", ISOLATED, false) },
        )
    }

    private fun read(text: String): ScriptRun {
        val answer = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        val started = (answer?.get("started") as? JsonPrimitive)?.booleanOrNull
        val exit = answer?.get("exit") as? JsonPrimitive
        val output = answer?.string("outputTail").orEmpty()
        return when {
            answer == null || started == null -> ScriptRun(null, UNREADABLE, ISOLATED, started = false)
            !started -> ScriptRun(null, output.ifEmpty { "the session's runner could not run it" }, ISOLATED, false)
            else -> ScriptRun(exit?.intOrNull, output, ISOLATED)
        }
    }

    /** How a check asked of the runner is confined, for the refusal and the hook's own records. */
    companion object {
        /** The sandbox of a check the runner ran. */
        const val ISOLATED = "isolated runner"

        private const val UNREADABLE = "the session's runner gave an answer that could not be read"
        private const val REPORT_MARGIN_MILLIS = 5_000
    }
}
