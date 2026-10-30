// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.getenv
import dev.ostrom.cli.platform.interop.ostrom_credential_delete
import dev.ostrom.cli.platform.interop.ostrom_credential_read
import dev.ostrom.cli.platform.interop.ostrom_credential_write
import dev.ostrom.cli.platform.interop.ostrom_is_terminal
import dev.ostrom.cli.platform.interop.ostrom_read_stdin
import dev.ostrom.cli.platform.interop.ostrom_spawn_wait_w
import dev.ostrom.cli.platform.interop.ostrom_write

internal actual fun spawnAndWait(command: List<String>): ProcessResult = memScoped {
    val launch = windowsLaunch(command, environmentVariable("SystemRoot"))
        ?: return ProcessResult.Failed("a script cannot receive a line break, nor run from a path with a quote")
    val exitCode = alloc<IntVar>()
    when (ostrom_spawn_wait_w(launch.application?.wcstr, launch.commandLine.wcstr, exitCode.ptr)) {
        0 -> ProcessResult.Exited(exitCode.value)
        1 -> ProcessResult.NotFound
        else -> ProcessResult.Failed("could not start ${command.first()}")
    }
}

internal actual fun writeBytes(descriptor: Int, bytes: ByteArray) {
    bytes.usePinned { ostrom_write(descriptor, it.addressOf(0).reinterpret(), bytes.size) }
}

internal actual fun isTerminal(descriptor: Int): Boolean = ostrom_is_terminal(descriptor) == 1

internal actual fun readStdin(buffer: ByteArray, offset: Int, timeoutMillis: Int): Int =
    buffer.usePinned { ostrom_read_stdin(it.addressOf(0).reinterpret(), buffer.size, offset, timeoutMillis) }

// getenv returns the ANSI code page on Windows; the values the CLI reads (paths, URLs, flags) are ASCII in practice.
// A UTF-16 read (_wgetenv) is the follow-up if a non-ASCII profile path shows up.
internal actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()

internal actual fun restrictToOwner(path: String) = Unit

internal actual fun systemVault(): Vault? = CredentialManagerVault

/** Windows Credential Manager: one generic credential `dev.ostrom.cli:<name>` per secret, for this user. */
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
