// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.cli.platform.LoopbackResponse
import run.ostrom.cli.platform.OperatingSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** S2-39: `ostrom update` knows when it runs inside an agent's session, even with the session's variables cleared. */
class AgentSessionsImplTest {
    private val fake = FakeFileSystem()
    private val probed = mutableListOf<Pair<Int, LoopbackRequest>>()

    private val relays = object : LoopbackHttp {
        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
            probed += port to request
            val answer = LoopbackResponse(UNAUTHORIZED, "")
            return if (port == LIVE) Result.success(answer) else Result.failure(IllegalStateException("refused"))
        }
    }

    private class Variables(private val values: Map<String, String>) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    private fun sessions(vararg variables: Pair<String, String>) =
        AgentSessionsImpl(Variables(mapOf(*variables)), fake, "/tmp", relays)

    private fun run(name: String, settings: String?) {
        fake.createDirectories("/tmp/$name".toPath())
        if (settings != null) fake.write("/tmp/$name/session-env.json".toPath()) { writeUtf8(settings) }
    }

    @Test
    fun aVariableOfTheLaunchIsASession() {
        for (name in listOf("OSTROM_LAUNCH_ID", "OSTROM_RELAY_URL", "OSTROM_SESSION_FILE", "OSTROM_RELAY_TOKEN")) {
            assertEquals("$name is set", sessions(name to "x").running())
        }
        assertNull(sessions("OSTROM_LAUNCH_ID" to "", "HOME" to "/home/maya").running(), "empty is unset")
        assertEquals(emptyList(), probed, "no temporary folder: nothing to probe")
    }

    @Test
    fun aLaunchFolderWhoseRelayStillAnswersIsASession() {
        fake.createDirectories("/tmp/other".toPath())
        fake.write("/tmp/ostrom-run-0".toPath()) { writeUtf8("a file") }
        run("ostrom-run-1", null)
        run("ostrom-run-2", "[]")
        run("ostrom-run-3", "{}")
        run("ostrom-run-4", """{"OSTROM_RELAY_URL":"http://evil.example:$LIVE"}""")
        run("ostrom-run-5", """{"OSTROM_RELAY_URL":"http://127.0.0.1:4200/"}""")
        assertNull(sessions().running(), "an ended launch leaves a folder whose relay no longer answers")
        assertEquals(listOf(4200 to LoopbackRequest("GET", "/")), probed)
        run("ostrom-run-6", """{"OSTROM_RELAY_URL":"http://127.0.0.1:$LIVE"}""")
        val live = "an Ostrom session runs on this device, its relay answers (/tmp/ostrom-run-6)"
        assertEquals(live, sessions().running())
    }

    private companion object {
        const val LIVE = 4100
        const val UNAUTHORIZED = 401
    }
}
