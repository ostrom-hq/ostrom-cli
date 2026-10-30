// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateCatalogueTest {
    private val catalogue = checkNotNull(
        GateCatalogue.parse(
            """
            {"failMode":"closed","extra":1,"actions":[
              {"class":"push-any","tools":["Bash"],"patterns":["\\bgit\\s+push\\b"]},
              {"class":"push-main-proposed","tools":["Bash"],"patterns":["\\bgit\\s+push\\b.*\\bmain\\b"],"gate":{"label":"Main?"}},
              {"class":"push-main","label":"Push to main","tools":["Bash"],"patterns":["(unclosed","\\bgit\\s+push\\b.*\\bmain\\b"],
               "gate":{"enforce":true,"label":"Main","approvers":["lena","omar"]}},
              {"class":"push-main-again","tools":["Bash"],"patterns":["main"],"gate":{"enforce":true}},
              {"class":"secrets","tools":["Edit"],"patterns":["\\.env$"],"gate":{"enforce":true}},
              {"class":"merge","tools":["mcp"],"patterns":["^mcp__github__merge"],"gate":{"enforce":true}}
            ]}
            """.trimIndent(),
        ),
    )
    private val patterns = PatternCache()

    private fun match(kind: ToolKind, name: String, text: String) =
        catalogue.match(ToolSubject(kind, name, text), patterns)?.actionClass

    @Test
    fun theMostGuardedActionWinsThenTheFirst() {
        assertEquals("push-main", match(ToolKind.BASH, "Bash", "GIT PUSH origin main"))
        assertEquals("push-any", match(ToolKind.BASH, "Bash", "git push origin topic"))
        assertNull(match(ToolKind.BASH, "Bash", "git status"))
        assertNull(match(ToolKind.BASH, "Bash", ""), "nothing to match")
    }

    @Test
    fun aWriteMatchesEditActionsAndAnMcpCallMatchesOnItsToolName() {
        assertEquals("secrets", match(ToolKind.WRITE, "Write", "/w/.env"))
        assertNull(match(ToolKind.OTHER, "Read", "/w/.env"))
        assertEquals("merge", match(ToolKind.MCP, "mcp", "MCP__GitHub__merge_pr {\"main\":1}"))
        val listing = match(ToolKind.MCP, "mcp", "mcp__github__list {\"q\":\"mcp__github__merge\"}")
        assertNull(listing, "arguments are not read")
    }

    @Test
    fun theCatalogueReadsAndWritesItself() {
        assertEquals("closed", catalogue.failMode)
        assertEquals(listOf("lena", "omar"), catalogue.actions[2].gate?.approvers)
        val copy = GateCatalogue.parse(GateCatalogue.write(catalogue))
        assertEquals(catalogue, copy)
        assertNull(GateCatalogue.parse("not json"))
        assertNull(GateCatalogue.parse("""{"actions":3}"""))
        assertEquals(GateCatalogue(), GateCatalogue.parse("{}"))
    }

    @Test
    fun approversReadAsOnePhrase() {
        assertEquals("lena or omar", Gate(approvers = listOf("lena", "omar")).approversPhrase)
        assertEquals("the gate owner", Gate().approversPhrase)
    }

    @Test
    fun aPatternThatDoesNotCompileMatchesNothingEveryTime() {
        val cache = PatternCache()
        repeat(2) {
            assertTrue(cache.matches("\\bgit\\s+push\\b.*\\bmain\\b", "GIT PUSH origin main"))
            assertFalse(cache.matches("\\bgit\\s+push\\b.*\\bmain\\b", "git status"))
            assertFalse(cache.matches("(unclosed", "(unclosed"))
        }
    }
}
