// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.GitRunner
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.LoopbackException
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.LoopbackRequest
import dev.ostrom.cli.platform.LoopbackResponse
import dev.ostrom.cli.platform.ProcessCapture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * [OstromCalls] through the launcher's local relay (`OSTROM_RELAY_URL`, `http://127.0.0.1:<port>`), with the
 * relay's capability for the hooks (read from `OSTROM_RELAY_TOKEN_FILE`, never from the agent's environment, S2-25):
 * the relay holds the device's credential and forwards to Ostrom only the routes a hook may call (audit A12).
 */
class RelayCalls(
    private val relayUrl: String?,
    private val token: String?,
    private val http: LoopbackHttp,
) : OstromCalls {
    override fun call(path: String, body: JsonElement?, timeoutMillis: Int): CallResult {
        val port = relayUrl?.let { PORT.matchEntire(it.trimEnd('/')) }?.let { it.groupValues[1].toIntOrNull() }
        val headers = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
        val request = LoopbackRequest(if (body == null) "GET" else "POST", path, headers, body?.toString())
        return if (port == null) {
            CallResult.Failed("no Ostrom relay in this session (run the agent with `ostrom <agent>`)")
        } else {
            http.exchange(port, request, timeoutMillis).fold(
                onSuccess = ::resultOf,
                onFailure = { CallResult.Failed((it as LoopbackException).failure.message) },
            )
        }
    }

    private fun resultOf(response: LoopbackResponse): CallResult {
        val json = try {
            Json.parseToJsonElement(response.body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        // the contract's refusal is {"error":{"type","message"}}; the prototype's was {"error":"…"}
        val detail = json?.get("error") as? JsonObject
        val message = detail?.string("message") ?: json?.string("error") ?: "HTTP ${response.status}"
        return if (response.status in SUCCESS) {
            CallResult.Ok(json ?: JsonObject(emptyMap()))
        } else {
            CallResult.Failed(message, detail?.string("type"))
        }
    }

    /** The relay's address and answers. */
    companion object {
        private val PORT = Regex("http://127\\.0\\.0\\.1:(\\d{1,5})")
        private val SUCCESS = 200..299
    }
}

/**
 * [GitRunner] over the real `git`, its output trimmed; "" when git fails, is missing or is slow.
 *
 * @param git The `git` the launcher resolved (`OSTROM_GIT`), not a name looked up on a PATH the agent may have
 * changed (S2-30).
 */
class CapturedGit(private val capture: ProcessCapture, private val git: String) : GitRunner {
    override fun run(directory: String, arguments: List<String>): String {
        val command = listOf(git, "-C", directory.ifEmpty { "." }) + arguments
        val result = capture.capture(command, TIMEOUT_MILLIS)
        return if (result is CaptureResult.Exited && result.code == 0) result.output.trim() else ""
    }

    private companion object {
        /** git answers these in milliseconds; a hung git must not hold the agent past its own hook limit. */
        const val TIMEOUT_MILLIS = 3_000
    }
}

/**
 * [HookFiles] on the file system.
 *
 * @param now The current time in milliseconds since the epoch.
 */
class FileSystemHookFiles(
    private val fileSystem: FileSystem,
    private val now: () -> Long,
) : HookFiles {
    override fun read(path: String): String? = try {
        fileSystem.read(path.toPath()) { readUtf8() }
    } catch (_: IOException) {
        null
    }

    override fun write(path: String, text: String): Boolean = try {
        val file = path.toPath()
        val temporary = "$path.${now()}.tmp".toPath()
        file.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(temporary) { writeUtf8(text) }
        fileSystem.atomicMove(temporary, file)
        true
    } catch (_: IOException) {
        false
    }

    /** [path] with its symbolic links resolved, or as given when it cannot be. */
    fun canonical(path: String): String = try {
        fileSystem.canonicalize(path.toPath()).toString()
    } catch (_: IOException) {
        path
    }
}
