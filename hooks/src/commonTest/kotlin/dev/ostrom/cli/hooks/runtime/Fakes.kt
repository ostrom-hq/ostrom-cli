// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Files in memory. */
class FakeFiles(val texts: MutableMap<String, String> = mutableMapOf()) : HookFiles {
    override fun read(path: String): String? = texts[path]

    override fun readStart(path: String, maxBytes: Int): String? = texts[path]?.take(maxBytes)

    override fun write(path: String, text: String): Boolean {
        texts[path] = text
        return true
    }
}

/** One call a hook made. */
data class Call(val path: String, val body: JsonElement?, val timeoutMillis: Int)

/** Ostrom in memory: answers by path prefix, in order of the queue for that path, and records every call. */
class FakeCalls(private val answers: MutableMap<String, ArrayDeque<CallResult>> = mutableMapOf()) : OstromCalls {
    val calls = mutableListOf<Call>()

    /** Queues [results] for calls whose path starts with [path]. */
    fun on(path: String, vararg results: CallResult): FakeCalls {
        answers.getOrPut(path) { ArrayDeque() }.addAll(results)
        return this
    }

    override fun call(path: String, body: JsonElement?, timeoutMillis: Int): CallResult {
        calls += Call(path, body, timeoutMillis)
        val queue = answers.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
        return when {
            queue == null || queue.isEmpty() -> CallResult.Failed("HTTP 404")
            queue.size == 1 -> queue.first()
            else -> queue.removeFirst()
        }
    }

    fun paths(): List<String> = calls.map { it.path }

    fun bodyOf(prefix: String): JsonObject = calls.last { it.path.startsWith(prefix) }.body as JsonObject
}

/** A JSON object answer. */
fun ok(json: String = "{}"): CallResult.Ok =
    CallResult.Ok(kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject)

/** A session context with [overrides] applied over a plain Claude Code session in /w. */
fun contextOf(vararg overrides: Pair<String, String>): HookContext {
    val variables = mapOf(
        "OSTROM_REPO" to "acme/app",
        "OSTROM_DEV" to "lena",
        "OSTROM_HARNESS_VERSION" to "7",
        "OSTROM_GATES" to "/s/gates.json",
        "OSTROM_SESSION_FILE" to "/s/session",
        "OSTROM_LAUNCH_TOP" to "/w",
        "OSTROM_LAUNCH_ID" to "L1",
        "HOME" to "/home/lena",
    ) + overrides
    return HookContext.read { variables[it]?.takeIf { value -> value.isNotEmpty() } }
}
