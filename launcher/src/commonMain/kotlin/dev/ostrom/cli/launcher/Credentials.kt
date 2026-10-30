// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

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
 * [CredentialStore] in `<config>/credentials.json` (0600), one entry per server. `OSTROM_TOKEN` (with `OSTROM_DEV`)
 * wins when set, for CI; the launcher removes it from the agent's environment, and inside an agent's session it is
 * never read (security audit S2-32: a token the agent put there would speak for the developer). The system keychain
 * replaces the file when the socle's vault lands (QUESTIONS.md).
 */
class FileCredentialStore(
    private val files: LaunchFiles,
    private val path: String,
    private val variable: (String) -> String?,
) : CredentialStore {
    override fun load(server: String): Credentials? {
        val inSession = !variable(LaunchVariables.LAUNCH_ID).isNullOrEmpty()
        val token = if (inSession) null else variable(LaunchVariables.DEVICE_TOKEN)
        val login = variable(LaunchVariables.DEV) ?: UNKNOWN
        return if (token.isNullOrEmpty()) saved(server) else Credentials(token, login)
    }

    override fun saved(server: String): Credentials? {
        val entry = all().child(server) ?: return null
        val token = entry.text("token")
        return if (token.isNullOrEmpty()) null else Credentials(token, entry.text("login") ?: UNKNOWN)
    }

    override fun save(server: String, credentials: Credentials) {
        val entry = objectOf("token" to credentials.token, "login" to credentials.login)
        files.write(path, pretty(JsonObject(all() + (server to entry))), FileAccess.PRIVATE)
    }

    override fun forget(server: String) = files.write(path, pretty(JsonObject(all() - server)), FileAccess.PRIVATE)

    private fun all(): JsonObject = parseObject(files.read(path)) ?: JsonObject(emptyMap())

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
