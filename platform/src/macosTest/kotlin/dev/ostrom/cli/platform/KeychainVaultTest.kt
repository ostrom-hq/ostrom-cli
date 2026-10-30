// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** On the real login keychain of the macOS runner: a secret is kept, replaced, read back and forgotten. */
class KeychainVaultTest {
    private val name = "test-${Random.nextLong().toULong()}"
    private val store = VaultSecretStore(KeychainVault)

    @AfterTest
    fun cleanUp() = store.delete(name)

    @Test
    fun theKeychainKeepsReplacesAndForgetsASecret() {
        assertEquals(SecretBackend.KEYCHAIN, store.backend)
        assertNull(store.read(name))
        store.write(name, "osd_first")
        store.write(name, "osd_second")
        assertEquals("osd_second", store.read(name))
        store.delete(name)
        assertNull(store.read(name))
    }

    @Test
    fun theSystemStoreOfMacosIsTheKeychain() {
        val store = systemSecretStore(EnvironmentImpl(), FileStoreImpl())
        assertEquals(SecretBackend.KEYCHAIN, store.backend)
    }
}
