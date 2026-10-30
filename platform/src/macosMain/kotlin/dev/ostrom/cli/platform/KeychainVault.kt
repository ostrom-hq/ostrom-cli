// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import dev.ostrom.cli.platform.vault.ostrom_keychain_delete
import dev.ostrom.cli.platform.vault.ostrom_keychain_read
import dev.ostrom.cli.platform.vault.ostrom_keychain_write

internal actual fun systemVault(): Vault? = KeychainVault

/**
 * The login keychain, generic passwords. An item is readable without a prompt by the binary that wrote it; another
 * program (an agent calling `security`) gets the system's prompt, which the developer can refuse.
 */
internal object KeychainVault : Vault {
    override val backend = SecretBackend.KEYCHAIN

    override fun isAvailable() = true

    override fun read(service: String, account: String) =
        vaultRead { buffer, capacity, length, code ->
            ostrom_keychain_read(service, account, buffer, capacity, length, code)
        }

    override fun write(service: String, account: String, secret: ByteArray) =
        vaultWrite(secret) { bytes, size, code -> ostrom_keychain_write(service, account, bytes, size, code) }

    override fun delete(service: String, account: String) =
        vaultCall { code -> ostrom_keychain_delete(service, account, code) }
}
