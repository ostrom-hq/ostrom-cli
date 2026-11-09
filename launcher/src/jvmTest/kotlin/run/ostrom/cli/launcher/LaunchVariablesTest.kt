// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [LaunchVariables.ALL] names every `OSTROM_` variable the sources read, whatever the module. */
class LaunchVariablesTest {
    @Test
    fun everyOstromVariableOfTheSourcesIsInTheList() {
        val root = File("..").canonicalFile
        val named = Regex(""""(OSTROM_[A-Z][A-Z0-9_]*)"""")
        val read = root.listFiles().orEmpty().filter { File(it, "src").isDirectory }.flatMap { module ->
            File(module, "src").listFiles().orEmpty().filter { it.name.endsWith("Main") }
                .flatMap { main -> main.walkTopDown().filter { it.extension == "kt" }.toList() }
        }.flatMap { file -> named.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(LaunchVariables.HOOK_DIALECT in read, "the sources are found from ${File(".").absolutePath}")
        assertEquals(emptySet(), read - LaunchVariables.ALL.toSet())
    }
}
