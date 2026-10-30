// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.platform.SecretStore
import kotlinx.serialization.json.JsonObject
import okio.IOException

/**
 * Who the developer is on a server.
 *
 * @property token The device token from `ostrom login`.
 * @property login Their GitHub login.
 */
data class Credentials(val token: String, val login: String)

/** Where `ostrom login` keeps the device token, per server. */
interface CredentialStore {
    /** The credentials for [server], or null when this device is not signed in there. */
    fun load(server: String): Credentials?

    /** The credentials `ostrom login` saved for [server], never the environment's: what `ostrom logout` revokes. */
    fun saved(server: String): Credentials? = load(server)

    /** Keeps [credentials] for [server]. */
    fun save(server: String, credentials: Credentials)

    /** Forgets [server]'s credentials. */
    fun forget(server: String)
}

/**
 * [CredentialStore] with the device token in the device's secret store ([SecretStore]: Keychain, Credential Manager,
 * Secret Service, or the encrypted file), named by the server's URL, and the GitHub login in
 * `<config>/credentials.json` (0600), one entry per server. A token found in clear in that file (an older ostrom, a
 * provisioning script) is moved into the secret store and removed from the file, once, with a notice (S2-32); a store
 * that does not answer leaves the file as it is. `OSTROM_TOKEN` (with `OSTROM_DEV`) wins when set, for CI; the launcher
 * removes it from the agent's environment, and inside an agent's session it is never read (security audit S2-32: a
 * token the agent put there would speak for the developer).
 *
 * @param secrets Opens the secret store; opened once, on the first call that needs it (opening probes the system).
 * @param notice Tells the developer a line (the move of a token).
 */
class FileCredentialStore(
    private val files: LaunchFiles,
    private val path: String,
    secrets: () -> SecretStore,
    private val notice: (String) -> Unit,
    private val variable: (String) -> String?,
) : CredentialStore {
    private val store by lazy(secrets)

    override fun load(server: String): Credentials? {
        val inSession = !variable(LaunchVariables.LAUNCH_ID).isNullOrEmpty()
        val token = if (inSession) null else variable(LaunchVariables.DEVICE_TOKEN)
        val login = variable(LaunchVariables.DEV) ?: UNKNOWN
        return if (token.isNullOrEmpty()) saved(server) else Credentials(token, login)
    }

    override fun saved(server: String): Credentials? {
        val entry = all().child(server)
        val login = entry?.text("login") ?: UNKNOWN
        val written = entry?.text("token")?.takeIf { it.isNotEmpty() }
        if (written != null) moveToStore(server, written, login)
        val token = store.read(server)
        return if (token.isNullOrEmpty()) null else Credentials(token, login)
    }

    /**
     * S2-32: the token left in clear in the file goes to the secret store (unless it holds one), then off the file. A
     * file that cannot be rewritten (a read-only mount) never stops the command: it is said, and tried again next time.
     */
    private fun moveToStore(server: String, token: String, login: String) {
        if (store.read(server) == null) store.write(server, token)
        val where = store.backend.displayName
        try {
            files.write(path, pretty(JsonObject(all() + (server to objectOf("login" to login)))), FileAccess.PRIVATE)
            notice("ostrom: the device token of $server moved from credentials.json to $where")
        } catch (e: IOException) {
            notice("! The device token of $server is in $where now, but $path could not be rewritten (${e.message}): " +
                "remove its token from that file")
        }
    }

    override fun save(server: String, credentials: Credentials) {
        store.write(server, credentials.token)
        val entry = objectOf("login" to credentials.login)
        files.write(path, pretty(JsonObject(all() + (server to entry))), FileAccess.PRIVATE)
    }

    override fun forget(server: String) {
        // in both stores, even when the token was read from the file (SecretStore.delete reaches every store)
        store.delete(server)
        val all = all()
        if (server in all) files.write(path, pretty(JsonObject(all - server)), FileAccess.PRIVATE)
    }

    private fun all(): JsonObject = parseObject(files.read(path)) ?: JsonObject(emptyMap())

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
