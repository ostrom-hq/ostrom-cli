// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialsTest {
    private val files = MemoryFiles()
    private val path = join("/config", "credentials.json")

    private fun store(vararg variables: Pair<String, String>) =
        FileCredentialStore(files, path) { mapOf(*variables)[it] }

    @Test
    fun theTokenIsSavedPerServerOwnerOnly() {
        val store = store()
        assertNull(store.load("https://a.example"))
        store.save("https://a.example", Credentials("ost_a", "alice"))
        store.save("https://b.example", Credentials("ost_b", "bob"))
        assertEquals(Credentials("ost_a", "alice"), store.load("https://a.example"))
        assertEquals(FileAccess.PRIVATE, files.access[path])
        store.forget("https://a.example")
        assertNull(store.load("https://a.example"))
        assertEquals(Credentials("ost_b", "bob"), store.load("https://b.example"))
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
    }
}
