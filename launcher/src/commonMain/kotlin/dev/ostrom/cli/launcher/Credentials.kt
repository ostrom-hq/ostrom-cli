// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.platform.SecretStore
import kotlinx.serialization.json.JsonObject

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
 * `<config>/credentials.json` (0600), one entry per server. A token found in that file (put there by hand or by a
 * provisioning script) is still read when the secret store has none. `OSTROM_TOKEN` (with `OSTROM_DEV`) wins when set,
 * for CI; the launcher removes it from the agent's environment, and inside an agent's session it is never read
 * (security audit S2-32: a token the agent put there would speak for the developer).
 *
 * @param secrets Opens the secret store; opened once, on the first call that needs it (opening probes the system).
 */
class FileCredentialStore(
    private val files: LaunchFiles,
    private val path: String,
    secrets: () -> SecretStore,
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
        val token = store.read(server) ?: entry?.text("token")
        return if (token.isNullOrEmpty()) null else Credentials(token, entry?.text("login") ?: UNKNOWN)
    }

    override fun save(server: String, credentials: Credentials) {
        store.write(server, credentials.token)
        val entry = objectOf("login" to credentials.login)
        files.write(path, pretty(JsonObject(all() + (server to entry))), FileAccess.PRIVATE)
    }

    override fun forget(server: String) {
        // in both stores, even when the token was read from the file (SecretStore.delete reaches every store)
        store.delete(server)
        files.write(path, pretty(JsonObject(all() - server)), FileAccess.PRIVATE)
    }

    private fun all(): JsonObject = parseObject(files.read(path)) ?: JsonObject(emptyMap())

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
