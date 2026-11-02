// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.platform.ProcessCaptureImpl
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real `sandbox-exec` (security audit L3): a check's script cannot read the CLI's files where `OSTROM_HOME` put
 * them, outside `~/.config/ostrom`. Without the folder in the profile, the same script reads them: the test proves the
 * profile, not a missing file.
 */
class SandboxConfigFolderTest {
    private val files = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-l3-${Random.nextLong().toULong()}"
    private val config = root / "elsewhere" / "ostrom"
    private val sandbox = root / "sbx"
    private val repository = root / "repo"
    private val home = root / "home"

    private fun canonical(path: String): String = try {
        files.canonicalize(path.toPath()).toString()
    } catch (_: IOException) {
        path
    }

    @AfterTest
    fun removeTheFolders() = files.deleteRecursively(root)

    private fun read(hidden: String?): ScriptRun {
        listOf(config, sandbox, repository, home).forEach(files::createDirectories)
        files.write(config / "credentials.json") { writeUtf8("{\"device\":\"maya\"}\n") }
        val script = sandbox / "s1.sh"
        files.write(script) { writeUtf8("cat '${config / "credentials.json"}'\n") }
        val variables = mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.toString())
        val scripts = SandboxedScripts(
            ProcessCaptureImpl(), variables::get, true, home.toString(), ::canonical, configDirectory = hidden,
        )
        val request = ScriptRequest(
            "s1", script.toString(), repository.toString(), sandbox.toString(), root.toString(), false, 10_000,
        )
        return scripts.run(request)
    }

    @Test
    fun aScriptCannotReadTheCliFolderWhereOstromHomePutsIt() {
        val open = read(hidden = null)
        assertEquals(0, open.exit, "without the folder in the profile the script reads it: ${open.output}")
        assertTrue("maya" in open.output)
        val closed = read(hidden = config.toString())
        assertTrue(closed.exit != 0, "the sandbox refuses the read (exit ${closed.exit}: ${closed.output})")
        assertFalse("maya" in closed.output, "nothing of the file reaches the script's output")
    }
}
