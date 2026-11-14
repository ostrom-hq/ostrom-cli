// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ostrom configure`, as TeamCode's `teamcode configure`. */
class ConfigureCommandTest : CliKit() {
    private val home = "/home/maya/.config/ostrom"
    private val me = "GET /api/v1/auth/me" to (200 to """{"dev":{"login":"ci-bot"}}""")
    private val env = mapOf("HOME" to "/home/maya")

    private fun run(routes: Map<String, Pair<Int, String>>, vararg args: String, env: Map<String, String> = this.env) =
        cli(environment = env, api = serverOf(routes)).run(arrayOf("configure") + args)

    @Test
    fun itKeepsTheServerAndTheTokenOnceTheServerAcceptedIt() {
        typed += "ost_key"
        assertEquals(0, run(mapOf(capabilities(), me), "--server", "https://ostrom.example/"))
        assertEquals("ost_key", secretsKept["https://ostrom.example"])
        assertTrue(fileSystem.read("$home/credentials.json".toPath()) { readUtf8() }.contains("ci-bot"))
        assertTrue(fileSystem.read("$home/config.toml".toPath()) { readUtf8() }.contains("https://ostrom.example"))
        assertEquals(listOf("ost_key", "ost_key"), requests.map { it.second.token })
    }

    @Test
    fun itAsksForTheServerWhenNoneIsGivenAndKeepsAnUnknownLoginWhenTheServerDoesNotSayIt() {
        typed += "https://ostrom.example"
        typed += "ost_key"
        assertEquals(0, run(mapOf(capabilities())))
        assertTrue(console.err.contains("Ostrom server URL: "))
        assertTrue(fileSystem.read("$home/credentials.json".toPath()) { readUtf8() }.contains("unknown"))
    }

    @Test
    fun anAnswerWithoutALoginKeepsAnUnknownOneToo() {
        for (answer in listOf("{}", """{"dev":{}}""")) {
            typed += "ost_key"
            val bare = "GET /api/v1/auth/me" to (200 to answer)
            assertEquals(0, run(mapOf(capabilities(), bare), "--server", "https://ostrom.example"))
            assertTrue(fileSystem.read("$home/credentials.json".toPath()) { readUtf8() }.contains("unknown"))
        }
    }

    @Test
    fun aTokenTheServerRefusesSavesNothing() {
        typed += "bad"
        val refused = "GET /api/v1/capabilities" to (401 to """{"error":{"type":"unauthorized","message":"no"}}""")
        assertEquals(65, run(mapOf(refused), "--server", "https://ostrom.example"))
        assertTrue(console.err.contains("did not accept this access token (HTTP 401)"), console.err.toString())
        assertTrue(secretsKept.isEmpty())
        assertFalse(fileSystem.exists("$home/config.toml".toPath()))
    }

    @Test
    fun aServerOfAnotherApiOrWithoutCapabilitiesOrFailingSavesNothing() {
        typed += "ost_key"
        assertEquals(69, run(mapOf(capabilities(apiVersion = 2)), "--server", "https://ostrom.example"))
        assertTrue(console.err.contains("Nothing was saved."))
        typed += "ost_key"
        assertEquals(69, run(emptyMap(), "--server", "https://ostrom.example"))
        assertTrue(console.err.contains("is too old"), console.err.toString())
        typed += "ost_key"
        val failing = "GET /api/v1/capabilities" to (500 to """{"error":{"type":"internal","message":"boom"}}""")
        assertEquals(69, run(mapOf(failing), "--server", "https://ostrom.example"))
        assertTrue(console.err.contains("did not say which Ostrom API it speaks (boom)"), console.err.toString())
        assertTrue(secretsKept.isEmpty())
    }

    @Test
    fun missingInputAnInsecureServerAndAnAgentSessionAreRefused() {
        assertEquals(64, run(emptyMap(), "--server", "https://ostrom.example"), "no token")
        assertEquals(64, run(emptyMap()), "no server asked for, nothing typed")
        typed += "ost_key"
        assertEquals(64, run(emptyMap(), "--server", " "), "no server")
        typed += "ost_key"
        assertEquals(64, run(emptyMap(), "--server", "http://ostrom.example"), "in clear")
        typed += "ost_key"
        val inSession = env + ("OSTROM_LAUNCH_ID" to "l1")
        assertEquals(64, run(emptyMap(), "--server", "https://ostrom.example", env = inSession))
        assertTrue(secretsKept.isEmpty())
    }

    @Test
    fun aFolderThatCannotBeWrittenIsExit73() {
        typed += "ost_key"
        val noHome = run(mapOf(capabilities()), "--server", "https://ostrom.example", env = emptyMap())
        assertEquals(73, noHome, "no folder")
        // config.toml is a folder with something in it: the token is kept, the server cannot be
        fileSystem.createDirectories("$home/config.toml/x".toPath())
        typed += "ost_key"
        assertEquals(73, run(mapOf(capabilities()), "--server", "https://ostrom.example"))
        assertTrue(console.err.endsWith("ostrom: could not write $home/config.toml\n"), console.err.toString())
        // credentials.json is a folder too: the login cannot be kept
        fileSystem.delete("$home/credentials.json".toPath())
        fileSystem.createDirectories("$home/credentials.json/x".toPath())
        typed += "ost_key"
        assertEquals(73, run(mapOf(capabilities()), "--server", "https://ostrom.example"))
        assertTrue(console.err.contains("ostrom: could not write $home ("), console.err.toString())
    }
}
