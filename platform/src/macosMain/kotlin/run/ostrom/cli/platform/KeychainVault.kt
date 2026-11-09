// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import run.ostrom.cli.platform.vault.ostrom_keychain_delete
import run.ostrom.cli.platform.vault.ostrom_keychain_read
import run.ostrom.cli.platform.vault.ostrom_keychain_write

internal actual fun systemVault(): Vault? = KeychainVault

/**
 * The login keychain, generic passwords. An item is readable without a prompt by the program that created it while
 * its code identity stays the same (releases signed with the same Developer ID); another program (an agent calling
 * `security`), or a build signed differently, gets the system's prompt, which the developer can refuse. Where no
 * prompt can be shown (SSH, a session without a desktop), the call fails with -25308 (errSecInteractionNotAllowed),
 * and the store says how to allow it ([SecretBackend.whenRefused]).
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
