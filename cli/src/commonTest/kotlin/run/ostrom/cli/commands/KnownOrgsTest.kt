// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.FileWriteException
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The organisation ids `ostrom verify` reads offline, kept by the commands that looked them up online. */
class KnownOrgsTest {
    private val fileSystem = FakeFileSystem().apply { createDirectories("/cfg".toPath()) }
    private val files = FileStoreImpl(fileSystem)
    private val acme = Org("7b1e4c2a-5d3f-4a6b-9c8d-1e2f3a4b5c6d", "acme", "Acme", "admin")
    private val beta = Org("0d9e8f7a-6b5c-4d3e-8f1a-2b3c4d5e6f70", "beta", "Beta", "member")

    private fun read() = fileSystem.read("/cfg/orgs.json".toPath()) { readUtf8() }

    @Test
    fun theIdsAreKeptByServerAndShortName() {
        val known = KnownOrgs(files, "/cfg")
        assertEquals(emptyMap(), known.of("https://ostrom.example"), "never looked up")
        known.keep("https://ostrom.example", listOf(acme, beta))
        known.keep("https://other.example", listOf(beta))
        assertEquals(mapOf("acme" to acme.id, "beta" to beta.id), known.of("https://ostrom.example"))
        assertEquals(mapOf("beta" to beta.id), KnownOrgs(files, "/cfg").of("https://other.example"))
        // a later lookup replaces the server's organisations: one left is no longer known
        known.keep("https://ostrom.example", listOf(beta))
        assertEquals(mapOf("beta" to beta.id), known.of("https://ostrom.example"))
    }

    @Test
    fun theSameOrganisationsAreNotWrittenAgain() {
        var writes = 0
        val counted = object : FileStore by files {
            override fun write(path: String, text: String) {
                writes++
                files.write(path, text)
            }
        }
        val known = KnownOrgs(counted, "/cfg")
        repeat(3) { known.keep("https://ostrom.example", listOf(acme)) }
        assertEquals(1, writes)
    }

    @Test
    fun aFileThatCannotBeWrittenIsLeftAsItIs() {
        val readOnly = object : FileStore by files {
            override fun write(path: String, text: String) {
                throw FileWriteException(path, IOException("read-only"))
            }
        }
        KnownOrgs(readOnly, "/cfg").keep("https://ostrom.example", listOf(acme))
        assertEquals(emptyMap(), KnownOrgs(files, "/cfg").of("https://ostrom.example"))
    }

    @Test
    fun whatIsNotAKeptOrganisationIsIgnored() {
        fileSystem.write("/cfg/orgs.json".toPath()) {
            writeUtf8("""{"https://ostrom.example":{"acme":"${acme.id}","n":3,"o":{}},"https://x.example":[]}""")
        }
        val known = KnownOrgs(files, "/cfg")
        assertEquals(mapOf("acme" to acme.id), known.of("https://ostrom.example"))
        assertEquals(emptyMap(), known.of("https://x.example"))
        fileSystem.write("/cfg/orgs.json".toPath()) { writeUtf8("not json") }
        assertEquals(emptyMap(), known.of("https://ostrom.example"))
        known.keep("https://ostrom.example", listOf(acme))
        assertTrue(read().startsWith("{\"https://ostrom.example\":{\"acme\":"), read())
    }
}
