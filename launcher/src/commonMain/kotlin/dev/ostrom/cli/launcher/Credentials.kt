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

    /** Keeps [credentials] for [server]. */
    fun save(server: String, credentials: Credentials)

    /** Forgets [server]'s credentials. */
    fun forget(server: String)
}

/**
 * [CredentialStore] in `<config>/credentials.json` (0600), one entry per server. `OSTROM_TOKEN` (with `OSTROM_DEV`)
 * wins when set, for CI; the launcher removes it from the agent's environment. The system keychain replaces the file
 * when the socle's vault lands (QUESTIONS.md).
 */
class FileCredentialStore(
    private val files: LaunchFiles,
    private val path: String,
    private val variable: (String) -> String?,
) : CredentialStore {
    override fun load(server: String): Credentials? {
        val token = variable(LaunchVariables.DEVICE_TOKEN)
        val login = variable(LaunchVariables.DEV) ?: UNKNOWN
        return if (token.isNullOrEmpty()) saved(server) else Credentials(token, login)
    }

    private fun saved(server: String): Credentials? {
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
