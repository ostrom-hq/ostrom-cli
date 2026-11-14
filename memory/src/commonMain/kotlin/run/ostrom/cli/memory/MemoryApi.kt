// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.memory

import kotlinx.serialization.KSerializer
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.ApiError
import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.MemorySearchResult
import run.ostrom.core.api.models.UpdateMemoryRequest

/**
 * Ostrom refused a memory call ([code] is its stable error key, `memory_secret`, `memory_conflict`…), or could not be
 * reached ([code] `unreachable`, [status] null). [message] is Ostrom's reason, never a body it did not mean as one.
 */
class MemoryApiException(val code: String, val status: Int?, message: String) : Exception(message)

/**
 * The memory routes (ostrom-core `api/openapi.yaml`, tag `memory`) the agent's `ostrom-memory` server calls: always
 * through the launch's local relay, which adds the launch's credential and marks the call as an agent's
 * (`x-ostrom-origin: agent`). What a caller may read or write is Ostrom's decision, never this side's. Every call
 * throws [MemoryApiException] for a refusal.
 */
interface MemoryApi {
    /** A page of the index, newest first (`listMemory`): never the content. */
    fun index(scope: String?, tags: List<String>, after: String?, limit: Int): MemoryIndexPage

    /** The entries whose title, summary or tags hold every word of [query] (`searchMemory`). */
    fun search(query: String, tags: List<String>, limit: Int): MemorySearchResult

    /** One entry whole (`getMemory`). */
    fun read(id: String): MemoryEntry

    /** Writes a new entry (`createMemory`). */
    fun create(request: CreateMemoryRequest): MemoryEntry

    /** Replaces an entry whole, at the version read (`updateMemory`, 409 `memory_conflict`). */
    fun update(id: String, request: UpdateMemoryRequest): MemoryEntry

    /** Removes an entry for good (`deleteMemory`). */
    fun forget(id: String)
}

/** The root of the memory routes. */
const val MEMORY_ROOT = "/api/v1/memory"

/** Ostrom's key for an entry that does not exist, or is another member's. */
const val MEMORY_NOT_FOUND = "not_found"

/**
 * [MemoryApi] over the launch's local relay on `127.0.0.1:[port]`, with the agent's capability of the relay ([token]).
 *
 * @param timeoutMillis The bound of one call: a slower answer is an infrastructure problem, never a reason to wait.
 */
class RelayMemoryApi(
    http: LoopbackHttp,
    port: Int,
    token: String,
    timeoutMillis: Int = CALL_MILLIS,
) : MemoryApi {
    private val relay = MemoryRelay(http, port, token, timeoutMillis)

    override fun index(scope: String?, tags: List<String>, after: String?, limit: Int): MemoryIndexPage {
        val query = listOf("scope" to scope, "tags" to joined(tags), "after" to after, "limit" to limit.toString())
        return relay.call("GET", "$MEMORY_ROOT/index" + QueryString.of(query), null, MemoryIndexPage.serializer())
    }

    override fun search(query: String, tags: List<String>, limit: Int): MemorySearchResult {
        val parameters = listOf("q" to query, "tags" to joined(tags), "limit" to limit.toString())
        val path = "$MEMORY_ROOT/search" + QueryString.of(parameters)
        return relay.call("GET", path, null, MemorySearchResult.serializer())
    }

    override fun read(id: String): MemoryEntry = relay.call("GET", entry(id), null, MemoryEntry.serializer())

    override fun create(request: CreateMemoryRequest): MemoryEntry {
        val body = OstromJson.encodeToString(CreateMemoryRequest.serializer(), request)
        return relay.call("POST", MEMORY_ROOT, body, MemoryEntry.serializer())
    }

    override fun update(id: String, request: UpdateMemoryRequest): MemoryEntry {
        val body = OstromJson.encodeToString(UpdateMemoryRequest.serializer(), request)
        return relay.call("PATCH", entry(id), body, MemoryEntry.serializer())
    }

    override fun forget(id: String) {
        relay.send("DELETE", entry(id), null)
    }

    /** An entry's route; an id that is no plain id would reach another route of the relay, so it is refused here. */
    private fun entry(id: String): String {
        if (!ENTRY_ID.matches(id) || id in RESERVED) {
            throw MemoryApiException(MEMORY_NOT_FOUND, null, "No memory entry '$id'.")
        }
        return "$MEMORY_ROOT/$id"
    }

    private fun joined(tags: List<String>): String? = tags.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** The limits and keys of the memory calls. */
    companion object {
        /** One call to Ostrom, as the todo's. */
        const val CALL_MILLIS = 20_000

        /**
         * The index read when the agent initializes the server: a slow Ostrom must not hold the agent's start (an MCP
         * client gives a server about ten seconds), which then goes on without the index.
         */
        const val START_MILLIS = 3_000

        /** The key of a call that did not reach Ostrom. */
        const val UNREACHABLE = "unreachable"

        /** The key of an answer that is not what the contract says. */
        const val UNREADABLE = "unreadable"

        /** An entry's id as the relay lets it through (a UUID is one). */
        private val ENTRY_ID = Regex("[A-Za-z0-9_-]{1,128}")

        /** The names of the routes beside an entry's, which the relay never takes for an id. */
        private val RESERVED = setOf("index", "search", "proposals", "curation", "reviews", "superseded")
    }
}

/** One memory call through the relay on `127.0.0.1:[port]` with [token], bounded by [timeoutMillis]. */
private class MemoryRelay(
    private val http: LoopbackHttp,
    private val port: Int,
    private val token: String,
    private val timeoutMillis: Int,
) {
    /** The answer of [method] [path] with [body], read as [answer] says. */
    fun <T> call(method: String, path: String, body: String?, answer: KSerializer<T>): T =
        decode(answer, send(method, path, body))

    /** The body of Ostrom's answer to [method] [path] with [body], or the refusal. */
    fun send(method: String, path: String, body: String?): String {
        val request = LoopbackRequest(method, path, mapOf("Authorization" to "Bearer $token"), body)
        val response = http.exchange(port, request, timeoutMillis).getOrElse {
            throw MemoryApiException(RelayMemoryApi.UNREACHABLE, null, "the local relay did not answer (${it.message})")
        }
        if (response.status !in SUCCESS) throw refusal(response.status, response.body)
        return response.body
    }

    private fun <T> decode(answer: KSerializer<T>, body: String): T = try {
        OstromJson.decodeFromString(answer, body)
    } catch (_: IllegalArgumentException) {
        // a SerializationException is one: an answer that is not what the contract says
        throw MemoryApiException(RelayMemoryApi.UNREADABLE, null, "Ostrom's answer could not be read")
    }

    /** Ostrom's refusal is `{"error":{"type","message"}}`; the relay's own refusals are plain text. */
    private fun refusal(status: Int, body: String): MemoryApiException {
        val error = try {
            OstromJson.decodeFromString(ApiError.serializer(), body).error
        } catch (_: IllegalArgumentException) {
            return MemoryApiException("http_$status", status, "Ostrom answered HTTP $status")
        }
        return MemoryApiException(error.type, status, error.message)
    }

    private companion object {
        private val SUCCESS = 200..299
    }
}

/** A URL query written without an HTTP library: the unreserved characters as they are, every other byte escaped. */
internal object QueryString {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"
    private const val NIBBLE = 4
    private const val LOW = 0x0F
    private const val BYTE = 0xFF

    /** `?name=value&…` of the [parameters] that have a value, or nothing when none has. */
    fun of(parameters: List<Pair<String, String?>>): String {
        val present = parameters.mapNotNull { (name, value) -> value?.let { "$name=${escape(it)}" } }
        return if (present.isEmpty()) "" else present.joinToString("&", prefix = "?")
    }

    /** [value] percent-encoded, byte by byte of its UTF-8. */
    fun escape(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and BYTE
            val char = code.toChar()
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%').append(HEX[code shr NIBBLE]).append(HEX[code and LOW])
            }
        }
    }
}
