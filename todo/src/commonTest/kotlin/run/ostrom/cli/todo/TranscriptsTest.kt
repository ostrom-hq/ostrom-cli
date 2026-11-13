// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptsTest {
    private val files = FakeFileSystem()

    private fun written(vararg lines: String): String {
        files.write("/t.jsonl".toPath()) { writeUtf8(lines.joinToString("\n")) }
        return "/t.jsonl"
    }

    private fun use(id: String, command: String) =
        """{"message":{"content":[{"type":"tool_use","id":"$id","input":{"command":"$command"}}]}}"""

    private fun result(id: String, content: String, error: Boolean = false) =
        """{"message":{"content":[{"type":"tool_result","tool_use_id":"$id","is_error":$error,"content":$content}]}}"""

    private val url = "https://github.com/acme/web/pull/12"

    @Test
    fun theAddressAGhPrCreatePrintedIsFound() {
        val path = written(
            use("a", "gh pr create --fill"),
            result("a", "\"Creating pull request\\n$url\""),
            use("b", "gh pr create -t x"),
            result("b", """[{"type":"text","text":"https://github.com/acme/api/pull/3"},7,{"type":"image"}]"""),
            // the same address again, another command's output, a failed create, a result of nothing readable
            result("a", "\"$url\""),
            use("c", "gh pr list"),
            result("c", "\"https://github.com/acme/web/pull/99\""),
            use("d", "gh pr create"),
            result("d", "\"https://github.com/acme/web/pull/98\"", error = true),
            result("a", "null"),
            result("a", """{"text":"$url/x"}"""),
        )
        assertEquals(listOf(url, "https://github.com/acme/api/pull/3"), Transcripts(files).pullRequestsOf(path))
    }

    @Test
    fun linesOfAnotherShapeAreSkipped() {
        val path = written(
            "not json gh pr create",
            """["gh pr create"]""",
            """{"type":"gh pr create"}""",
            """{"message":"gh pr create"}""",
            """{"message":{"content":"gh pr create"}}""",
            """{"message":{"content":["gh pr create",{"type":"text","text":"gh pr create"}]}}""",
            """{"message":{"content":[{"type":"tool_use","input":{"command":"gh pr create"}}]}}""",
            """{"message":{"content":[{"type":"tool_use","id":"e","input":"gh pr create"}]}}""",
            """{"message":{"content":[{"type":"tool_use","id":"f"}]}} gh pr create""",
            result("e", "\"$url\""),
            result("f", "\"$url\""),
        )
        assertEquals(emptyList(), Transcripts(files).pullRequestsOf(path))
    }

    @Test
    fun aTranscriptIsReadWithinItsBounds() {
        val long = use("a", "gh pr create " + "x".repeat(200))
        val path = written(long, result("a", "\"$url\""), use("b", "gh pr create"), result("b", "\"$url\""))
        assertEquals(listOf(url), Transcripts(files, maxLine = 150).pullRequestsOf(path), "a long line is skipped")
        val firstOnly = Transcripts(files, maxBytes = (long.length + 1L) + 1L)
        assertEquals(emptyList(), firstOnly.pullRequestsOf(path), "reading stops past the bound")
        assertEquals(listOf(url), Transcripts(files).pullRequestsOf(path))
    }

    @Test
    fun noFileNamesNoPullRequest() {
        files.createDirectories("/folder".toPath())
        assertEquals(emptyList(), Transcripts(files).pullRequestsOf("/missing.jsonl"))
        assertEquals(emptyList(), Transcripts(files).pullRequestsOf("/folder"))
    }
}
