// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import run.ostrom.cli.platform.interop.ostrom_credential_delete
import run.ostrom.cli.platform.interop.ostrom_credential_read
import run.ostrom.cli.platform.interop.ostrom_credential_write
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.wcstr

internal actual fun systemVault(): Vault? = CredentialManagerVault

// Credential Manager is always there: Windows never falls back to the encrypted file, whose key alone needs a user id.
internal actual fun currentUserId(): String = ""

/** Windows Credential Manager: one generic credential `run.ostrom.cli:<name>` per secret, for this user. */
internal object CredentialManagerVault : Vault {
    override val backend = SecretBackend.CREDENTIAL_MANAGER

    override fun isAvailable() = true

    override fun read(service: String, account: String) = memScoped {
        val target = "$service:$account".wcstr.ptr
        vaultRead { buffer, capacity, length, code -> ostrom_credential_read(target, buffer, capacity, length, code) }
    }

    override fun write(service: String, account: String, secret: ByteArray) = memScoped {
        val target = "$service:$account".wcstr.ptr
        vaultWrite(secret) { bytes, size, code -> ostrom_credential_write(target, bytes, size, code) }
    }

    override fun delete(service: String, account: String) = memScoped {
        val target = "$service:$account".wcstr.ptr
        vaultCall { code -> ostrom_credential_delete(target, code) }
    }
}
