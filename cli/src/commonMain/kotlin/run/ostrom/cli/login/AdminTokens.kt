// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.login

import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.platform.SecretStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The tokens of `ostrom login --admin`, kept apart from the device's ordinary token (decision of 02/11/2026,
 * « écritures de gouvernance depuis la CLI »): the token in the secret store under `<server>#admin`, its expiry in
 * `admin.json` (no secret there). A token past its expiry is forgotten, never renewed: the person signs in again.
 *
 * @param path The file of the expiries, one per server.
 * @param secrets Opens the secret store, on the first call that needs it.
 * @param clock The time, in milliseconds since the epoch.
 */
class AdminTokens(
    private val files: FileStore,
    private val path: String,
    secrets: () -> SecretStore,
    private val clock: () -> Long,
) {
    private val store by lazy(secrets)

    /**
     * Keeps [token] for [server] until [expiresAtMillis]. The token it replaces goes first, and the expiry is written
     * before the new token: a store that fails midway leaves an expiry without a token (no admin token), never an old
     * token with a new expiry, nor a token [forget] cannot reach.
     */
    fun save(server: String, token: String, expiresAtMillis: Long) {
        store.delete(nameOf(server))
        files.write(path, JsonObject(all() + (server to JsonPrimitive(expiresAtMillis))).toString())
        store.write(nameOf(server), token)
    }

    /** The admin token of [server] while it is valid; one that expired is forgotten, and null is the answer. */
    fun valid(server: String): String? {
        val expiresAt = (all()[server] as? JsonPrimitive)?.longOrNull ?: return null
        val expired = expiresAt <= clock()
        if (expired) forget(server)
        return if (expired) null else store.read(nameOf(server))
    }

    /**
     * Forgets [server]'s admin token, in the secret store and in the file; without an entry there is none, and the
     * secret store is not opened (opening it probes the system).
     */
    fun forget(server: String) {
        val all = all()
        if (server in all) {
            files.write(path, JsonObject(all - server).toString())
            store.delete(nameOf(server))
        }
    }

    private fun all(): JsonObject = parseObject(files.read(path)) ?: objectOf()

    /** The admin scopes and the longest an admin token lives. */
    companion object {
        /** The write scopes of the organisation's governance an admin token holds. */
        val SCOPES = listOf("policy:write", "harness:write", "gates:policy")

        /** 8 hours: the longest an admin token is kept, whatever the server says. */
        const val MAX_MILLIS = 8 * 60 * 60 * 1_000L

        private fun nameOf(server: String) = "$server#admin"
    }
}
