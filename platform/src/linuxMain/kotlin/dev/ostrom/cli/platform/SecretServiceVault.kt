// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import dev.ostrom.cli.platform.vault.ostrom_secret_delete
import dev.ostrom.cli.platform.vault.ostrom_secret_load
import dev.ostrom.cli.platform.vault.ostrom_secret_read
import dev.ostrom.cli.platform.vault.ostrom_secret_write

internal actual fun systemVault(): Vault? = SecretServiceVault

/** The Secret Service of the session bus, through libsecret loaded at run time. */
internal object SecretServiceVault : Vault {
    override val backend = SecretBackend.SECRET_SERVICE

    /**
     * libsecret is installed and a Secret Service answers on the session bus. The probe looks up an account that is
     * never written: with nothing to unlock it shows no prompt, so any running service answers it at once, and one
     * that does not within [PROBE_MILLIS] counts as absent. A service that answers but later refuses (a locked
     * collection) stays the store: its secrets are there, and switching to the encrypted file would hide them.
     */
    override fun isAvailable(): Boolean {
        if (ostrom_secret_load() != 1) return false
        val probe = vaultRead { buffer, capacity, length, code ->
            ostrom_secret_read(SECRET_SERVICE_NAME, PROBE, PROBE_MILLIS, buffer, capacity, length, code)
        }
        return probe.status == VAULT_OK || probe.status == VAULT_NOT_FOUND
    }

    override fun read(service: String, account: String) =
        vaultRead { buffer, capacity, length, code ->
            ostrom_secret_read(service, account, deadline(), buffer, capacity, length, code)
        }

    override fun write(service: String, account: String, secret: ByteArray) =
        // libsecret keeps text: a device token is ASCII.
        vaultCall { code -> ostrom_secret_write(service, account, secret.decodeToString(), deadline(), code) }

    override fun delete(service: String, account: String) =
        vaultCall { code -> ostrom_secret_delete(service, account, deadline(), code) }

    /**
     * How long a call may wait for the service: in a terminal, long enough to answer an unlock prompt; elsewhere (a
     * hook, a background job), short, as nobody is there to answer it and the agent waits.
     */
    private fun deadline() = if (isTerminal(STDERR)) INTERACTIVE_MILLIS else BACKGROUND_MILLIS

    /** An account never written: looking it up only proves the service runs. */
    private const val PROBE = "availability-probe"
    private const val PROBE_MILLIS = 1_000
    private const val BACKGROUND_MILLIS = 3_000
    private const val INTERACTIVE_MILLIS = 120_000
    private const val STDERR = 2
}
