// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

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
     * @property relayDown Nothing listens on the local relay's port: the launch that served it has ended.
     * @property status The HTTP status of the answer, or null when there was none.
     * @property answered The body was Ostrom's error, with its reason in [message]: Ostrom itself answered, not the
     *   local relay (whose own refusals are plain text) nor a server without the route.
     */
    data class Failed(
        val message: String,
        val type: String? = null,
        val relayDown: Boolean = false,
        val status: Int? = null,
        val answered: Boolean = false,
    ) : CallResult {
        /** Ostrom refused the write because its session is closed (the machine slept past Ostrom's wait). */
        val sessionClosed: Boolean get() = type == "session_closed" || message.startsWith("session closed:")

        /**
         * Ostrom could not be reached at all (the relay's `unreachable` error, or the relay itself down): the only
         * failure an organisation's `open` fail mode lets an action through. A refusal or an error of Ostrom is not.
         */
        val unreachable: Boolean get() = type == UNREACHABLE

        /** Ostrom answered and refused (a 4xx with its error): [message] is its reason, never an outage. */
        val refused: Boolean get() = answered && status != null && status in REFUSED
    }

    /** The error type of a call that never reached Ostrom. */
    companion object {
        /** The relay's `{"error":{"type":"unreachable"}}`, also given to a relay that does not answer. */
        const val UNREACHABLE = "unreachable"
    }
}

/** The statuses of Ostrom's refusals. */
private val REFUSED = 400..499

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

/**
 * The small files of a session the hooks read and write (session map, marks). The agent can write them too: nothing
 * that lets an action through is read from them (S2-20, S2-29).
 */
interface HookFiles {
    /** The text of the file, or null when it does not exist or cannot be read. */
    fun read(path: String): String?

    /** At most the first [maxBytes] bytes of the file as text, or null: a script read never loads a whole binary. */
    fun readStart(path: String, maxBytes: Int): String?

    /** Replaces the file whole (a reader sees the old or the new text, never half); false when it cannot be written. */
    fun write(path: String, text: String): Boolean
}
