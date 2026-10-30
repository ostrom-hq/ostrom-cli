// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * On the real Secret Service of the CI runner (GNOME Keyring on a private session bus, with
 * `OSTROM_SECRET_SERVICE_TESTS=1`): a secret is kept, replaced, read back and forgotten. Without that variable, only
 * the choice of store is checked.
 */
class SecretServiceVaultTest {
    private val withService = environmentVariable("OSTROM_SECRET_SERVICE_TESTS") == "1"

    @Test
    fun theSecretServiceKeepsReplacesAndForgetsASecret() {
        if (!withService) return
        val name = "test-${Random.nextLong().toULong()}"
        val store = systemSecretStore(EnvironmentImpl(), FileStoreImpl())
        assertEquals(SecretBackend.SECRET_SERVICE, store.backend)
        assertNull(store.read(name))
        store.write(name, "osd_first")
        store.write(name, "osd_second")
        assertEquals("osd_second", store.read(name))
        store.delete(name)
        store.delete(name)
        assertNull(store.read(name))
    }

    @Test
    fun withoutASecretServiceTheStoreIsTheEncryptedFile() {
        if (withService) return
        // The runner has a machine identifier; a home of its own keeps the test out of the real CLI folder.
        val home = "/tmp/ostrom-secret-test-${Random.nextLong().toULong()}"
        val environment = EnvironmentImpl { name -> if (name == "OSTROM_HOME") home else environmentVariable(name) }
        val store = systemSecretStore(environment, FileStoreImpl())
        val service = SecretServiceVault.isAvailable()
        assertEquals(if (service) SecretBackend.SECRET_SERVICE else SecretBackend.ENCRYPTED_FILE, store.backend)
    }
}
