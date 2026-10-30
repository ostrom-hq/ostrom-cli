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
     * libsecret is installed and a Secret Service runs on the session bus. A service that runs but refuses (a locked
     * collection, no answer within the shim's deadline) is still the store: its secrets are there, and switching to
     * the encrypted file would hide them (or keep a new one where the next command does not look).
     */
    override fun isAvailable(): Boolean =
        ostrom_secret_load() == 1 && read(SECRET_SERVICE_NAME, PROBE).status != VAULT_UNREACHABLE

    override fun read(service: String, account: String) =
        vaultRead { buffer, capacity, length, code ->
            ostrom_secret_read(service, account, buffer, capacity, length, code)
        }

    override fun write(service: String, account: String, secret: ByteArray) =
        // libsecret keeps text: a device token is ASCII.
        vaultCall { code -> ostrom_secret_write(service, account, secret.decodeToString(), code) }

    override fun delete(service: String, account: String) =
        vaultCall { code -> ostrom_secret_delete(service, account, code) }

    /** An account never written: looking it up only proves the service runs. */
    private const val PROBE = "availability-probe"
}
