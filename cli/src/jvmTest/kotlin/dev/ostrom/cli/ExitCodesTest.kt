// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The table of docs/MODULES.md is the one of [ExitCode], line for line. */
class ExitCodesTest {
    @Test
    fun theDocumentedTableIsTheExitCodesOfTheBinary() {
        // Gradle runs the tests from the module's folder, cli/.
        val docs = File("../docs/MODULES.md").readLines()
        val rows = docs.mapNotNull { ROW.matchEntire(it)?.destructured }
            .map { (code, name, meaning) -> Triple(code.toInt(), name, meaning) }
        assertEquals(ExitCode.entries.map { Triple(it.code, it.name, it.meaning) }, rows)
    }

    private companion object {
        val ROW = Regex("""^\| (\d+) \| `([A-Z_]+)` \| (.+) \|$""")
    }
}
