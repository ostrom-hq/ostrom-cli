// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What Ostrom answered to one call of a hook. */
sealed interface CallResult {
    /** A 2xx answer and its JSON body (an empty object when the body was not JSON). */
    data class Ok(val body: JsonObject) : CallResult

    /**
     * No usable answer: the server's error message when it sent one, else `HTTP <status>`, else why the call did not
     * reach it.
     *
     * @property type The server's stable error key (`session_closed`), or null.
     */
    data class Failed(val message: String, val type: String? = null) : CallResult {
        /** Ostrom refused the write because its session is closed (the machine slept past Ostrom's wait). */
        val sessionClosed: Boolean get() = type == "session_closed" || message.startsWith("session closed:")
    }
}

/**
 * The calls the hooks make to Ostrom. In a session they go to the launcher's local relay (loopback, the session's
 * own token), which adds the device's credential: the agent's environment never holds a token that reaches the
 * server (audit A12).
 */
interface OstromCalls {
    /**
     * @param path The path and query, starting with `/`.
     * @param body The JSON to POST, or null for a GET.
     * @param timeoutMillis The whole call's limit: a hook must answer before the agent's own limit (Grok: 5 s).
     */
    fun call(path: String, body: JsonElement?, timeoutMillis: Int): CallResult
}

/** The small files of a session the hooks read and write (catalogue copy, session map, marks). */
interface HookFiles {
    /** The text of the file, or null when it does not exist or cannot be read. */
    fun read(path: String): String?

    /** Replaces the file whole (a reader sees the old or the new text, never half); false when it cannot be written. */
    fun write(path: String, text: String): Boolean

    /** Deletes the file; nothing happens when it does not exist. */
    fun delete(path: String)

    /** How long ago the file was last written, in milliseconds, or null when it does not exist. */
    fun ageMillis(path: String): Long?
}

/** Starts `ostrom hook gates-refresh` detached from the hook: no shared stream, its own session, not waited for. */
fun interface BackgroundRefresh {
    /** @return False when it could not be started. */
    fun start(): Boolean
}
