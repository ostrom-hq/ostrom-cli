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
    fun theCatalogueIsReadFieldByField() {
        assertEquals("closed", catalogue.failMode)
        val main = catalogue.actions[2]
        assertEquals(Gate(enforce = true, label = "Main", approvers = listOf("lena", "omar")), main.gate)
        assertEquals("Push to main", main.label)
        assertEquals(Gate(enforce = false, label = "Main?", approvers = emptyList()), catalogue.actions[1].gate)
        assertEquals(null, catalogue.actions[0].gate)
        val nulls = """{"failMode":null,"actions":[{"class":3,"label":null,"tools":null,"gate":null},{}]}"""
        val read = GateCatalogue.parse(nulls)
        assertEquals(GateAction("3", null, emptyList(), emptyList(), null), read?.actions?.first())
        assertEquals(GateAction("", null, emptyList(), emptyList(), null), read?.actions?.last())
        assertEquals(null, read?.failMode)
        val loose = GateCatalogue.parse("""{"actions":[{"gate":{"enforce":"yes","approvers":null}}]}""")
        assertEquals(Gate(enforce = false, label = null, approvers = emptyList()), loose?.actions?.single()?.gate)
        assertEquals(GateCatalogue(emptyList(), null), GateCatalogue.parse("{}"))
    }

    @Test
    fun aCatalogueWithAFieldOfTheWrongTypeIsNoCatalogue() {
        for (text in listOf("not json", "[]", """{"actions":3}""", """{"actions":[3]}""", """{"failMode":{}}""",
            """{"actions":[{"tools":"Bash"}]}""", """{"actions":[{"gate":[]}]}""")) {
            assertNull(GateCatalogue.parse(text), text)
        }
    }

    @Test
    fun approversReadAsOnePhrase() {
        assertEquals("lena or omar", Gate(false, null, listOf("lena", "omar")).approversPhrase)
        assertEquals("the gate owner", Gate(false, null, emptyList()).approversPhrase)
    }

    @Test
    fun theShellsWordsAreMatchedAsWellAsTheText() {
        // S2-26: quotes and backslashes split nothing for the shell
        assertEquals("push-main", match(ToolKind.BASH, "Bash", "git pu''sh origin main"))
        assertEquals("push-main", match(ToolKind.BASH, "Bash", "g\\it \"push\" origin ma'i'n"))
        assertEquals("merge", match(ToolKind.MCP, "mcp", "mcp__github__merge {}"))
        assertNull(match(ToolKind.WRITE, "Write", "/w/'.env'"), "a path is matched as written")
    }

    @Test
    fun enforcedGatesAreFoundByToolAndBySubject() {
        assertEquals("push-main", catalogue.enforcedOn("Bash")?.first?.actionClass)
        assertEquals("secrets", catalogue.enforcedOn("Edit")?.first?.actionClass)
        assertNull(catalogue.enforcedOn("Read"))
        val push = ToolSubject(ToolKind.BASH, "Bash", "git push origin main")
        assertEquals("Main", catalogue.enforcedFor(push, patterns)?.second?.label)
        assertNull(catalogue.enforcedFor(ToolSubject(ToolKind.BASH, "Bash", "git push x"), patterns), "observed only")
        assertNull(catalogue.enforcedFor(ToolSubject(ToolKind.BASH, "Bash", "ls"), patterns))
    }

    @Test
    fun anAddedCatalogueAddsAndClosesNeverOpens() {
        val open = GateCatalogue(catalogue.actions.take(1), "open")
        val added = GateCatalogue(catalogue.actions.drop(1).take(1), null)
        assertEquals(catalogue.actions.take(2), open.with(added).actions)
        assertEquals("open", open.with(added).failMode)
        assertEquals("closed", open.with(GateCatalogue(emptyList(), "closed")).failMode)
        assertEquals(null, GateCatalogue(emptyList(), null).with(GateCatalogue(emptyList(), "open")).failMode)
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
