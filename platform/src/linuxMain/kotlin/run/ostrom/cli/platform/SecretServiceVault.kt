// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import run.ostrom.cli.platform.vault.ostrom_secret_delete
import run.ostrom.cli.platform.vault.ostrom_secret_load
import run.ostrom.cli.platform.vault.ostrom_secret_read
import run.ostrom.cli.platform.vault.ostrom_secret_write

internal actual fun systemVault(): Vault? = SecretServiceVault

/** The Secret Service of the session bus, through libsecret loaded at run time. */
internal object SecretServiceVault : Vault {
    override val backend = SecretBackend.SECRET_SERVICE

    /**
     * libsecret is installed and a Secret Service runs on the session bus. The probe looks up an account that is never
     * written: with nothing to unlock it shows no prompt. Only "no service to ask" (no bus, no service on it) means
     * absent. A service that is slow (a cold start past [PROBE_MILLIS]) or refuses stays the store, so the choice never
     * flips from one command to the next: its secrets are there, and the encrypted file would hide them. A hung
     * service then fails each call loudly, after its deadline, rather than silently storing elsewhere.
     */
    override fun isAvailable(): Boolean {
        if (ostrom_secret_load() != 1) return false
        val probe = vaultRead { buffer, capacity, length, code ->
            ostrom_secret_read(SECRET_SERVICE_NAME, PROBE, PROBE_MILLIS, buffer, capacity, length, code)
        }
        return probe.status != VAULT_UNREACHABLE
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
     * How long a call may wait for the service: with a person at a terminal (its input or its error output, so that
     * `ostrom login 2>log` still counts), long enough to answer an unlock prompt; elsewhere (a hook, whose input is
     * the agent's pipe; a background job), short, as nobody is there to answer it and the agent waits.
     */
    private fun deadline() =
        if (isTerminal(STDIN) || isTerminal(STDERR)) INTERACTIVE_MILLIS else BACKGROUND_MILLIS

    /** An account never written: looking it up only proves the service runs. */
    private const val PROBE = "availability-probe"
    private const val PROBE_MILLIS = 1_000
    private const val BACKGROUND_MILLIS = 3_000
    private const val INTERACTIVE_MILLIS = 120_000
    private const val STDIN = 0
    private const val STDERR = 2
}
