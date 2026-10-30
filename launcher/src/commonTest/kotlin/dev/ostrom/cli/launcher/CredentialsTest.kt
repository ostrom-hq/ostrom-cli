// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.platform.SecretBackend
import dev.ostrom.cli.platform.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialsTest {
    private val files = MemoryFiles()
    private val path = join("/config", "credentials.json")
    private val kept = mutableMapOf<String, String>()
    private var opened = 0

    private val secrets = object : SecretStore {
        override val backend = SecretBackend.KEYCHAIN

        override fun read(name: String) = kept[name]

        override fun write(name: String, secret: String) {
            kept[name] = secret
        }

        override fun delete(name: String) {
            kept -= name
        }
    }

    private fun store(vararg variables: Pair<String, String>) =
        FileCredentialStore(files, path, { secrets.also { opened++ } }) { mapOf(*variables)[it] }

    @Test
    fun theTokenIsKeptInTheSecretStoreAndTheLoginInTheFile() {
        val store = store()
        assertNull(store.load("https://a.example"))
        store.save("https://a.example", Credentials("ost_a", "alice"))
        store.save("https://b.example", Credentials("ost_b", "bob"))
        assertEquals(Credentials("ost_a", "alice"), store.load("https://a.example"))
        assertEquals(mapOf("https://a.example" to "ost_a", "https://b.example" to "ost_b"), kept)
        assertTrue("ost_a" !in files.texts.getValue(path), "never the token in the file")
        assertEquals(FileAccess.PRIVATE, files.access[path])
        assertEquals(1, opened, "the secret store is opened once")
        store.forget("https://a.example")
        assertNull(store.load("https://a.example"))
        assertEquals(mapOf("https://b.example" to "ost_b"), kept)
        assertEquals(Credentials("ost_b", "bob"), store.load("https://b.example"))
    }

    @Test
    fun forgettingATokenReadFromTheFileForgetsItInBothStores() {
        files.texts[path] = """{"https://a.example":{"token":"from-file","login":"alice"}}"""
        val store = store()
        assertEquals(Credentials("from-file", "alice"), store.saved("https://a.example"))
        kept["https://a.example"] = "in-store"
        assertEquals("in-store", store.saved("https://a.example")?.token, "the secret store first")
        store.forget("https://a.example")
        assertEquals(emptyMap(), kept)
        assertNull(store.saved("https://a.example"))
        kept["https://c.example"] = "t"
        assertEquals(Credentials("t", "unknown"), store.saved("https://c.example"), "a token without its file entry")
    }

    @Test
    fun anEntryWithoutTokenOrLoginIsReadAsWellAsItCanBe() {
        files.texts[path] = """{"https://a.example":{"login":"alice"},"https://b.example":{"token":"t"}}"""
        assertNull(store().load("https://a.example"))
        assertEquals(Credentials("t", "unknown"), store().load("https://b.example"))
        files.texts[path] = """{"https://a.example":{"token":""}}"""
        assertNull(store().load("https://a.example"))
    }

    @Test
    fun theEnvironmentsTokenWinsForCiAndSandboxes() {
        files.texts[path] = """{"https://a.example":{"token":"saved","login":"alice"}}"""
        assertEquals(Credentials("env", "ci-bot"), store("OSTROM_TOKEN" to "env", "OSTROM_DEV" to "ci-bot").load("x"))
        assertEquals(Credentials("env", "unknown"), store("OSTROM_TOKEN" to "env").load("x"))
        assertEquals("saved", store("OSTROM_TOKEN" to "").load("https://a.example")?.token)
        // S2-32: inside an agent's session the environment's token is never the developer's
        val inSession = store("OSTROM_TOKEN" to "env", "OSTROM_LAUNCH_ID" to "L1")
        assertEquals("saved", inSession.load("https://a.example")?.token)
        val emptyLaunch = store("OSTROM_TOKEN" to "env", "OSTROM_LAUNCH_ID" to "")
        assertEquals("env", emptyLaunch.load("https://a.example")?.token, "an empty launch id is no session")
    }

    @Test
    fun theSavedEntryIsNeverTheEnvironments() {
        files.texts[path] = """{"https://a.example":{"token":"saved","login":"alice"}}"""
        val ci = store("OSTROM_TOKEN" to "env", "OSTROM_DEV" to "ci-bot")
        assertEquals(Credentials("saved", "alice"), ci.saved("https://a.example"))
        assertNull(ci.saved("https://b.example"))
        val plain = object : CredentialStore {
            override fun load(server: String) = Credentials("t", server)

            override fun save(server: String, credentials: Credentials) = Unit

            override fun forget(server: String) = Unit
        }
        assertEquals(Credentials("t", "s"), plain.saved("s"), "a store without its own saved entries loads")
        plain.save("s", Credentials("t", "s"))
        plain.forget("s")
    }
}
