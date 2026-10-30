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

    /** libsecret is installed and a Secret Service answers a lookup (none runs without a session bus). */
    override fun isAvailable(): Boolean =
        ostrom_secret_load() == 1 && read(SECRET_SERVICE_NAME, PROBE).status != VAULT_ERROR_STATUS

    override fun read(service: String, account: String) =
        vaultRead { buffer, capacity, length, code ->
            ostrom_secret_read(service, account, buffer, capacity, length, code)
        }

    override fun write(service: String, account: String, secret: ByteArray) =
        // libsecret keeps text: a device token is ASCII.
        vaultCall { code -> ostrom_secret_write(service, account, secret.decodeToString(), code) }

    override fun delete(service: String, account: String) =
        vaultCall { code -> ostrom_secret_delete(service, account, code) }

    /** An account never written: looking it up only proves the service answers. */
    private const val PROBE = "availability-probe"

    /** The shims' status for a failure. */
    private const val VAULT_ERROR_STATUS = 3
}
