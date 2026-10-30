package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.FakeGit
import dev.ostrom.cli.hooks.gate.Push
import dev.ostrom.cli.hooks.gate.PushRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffsTest {
    @Test
    fun theOutgoingCommitsOfTheBranchElseTheLastOne() {
        val git = FakeGit(
            mapOf(
                "/r|rev-parse --abbrev-ref @{u}" to "origin/main",
                "/r|log --no-color --format=%h %s (%an) @{u}..HEAD" to "abc fix (lena)",
                "/r|diff --no-color --stat --patch @{u}..HEAD" to "+x",
                "/n|log --no-color --format=%h %s (%an) HEAD~1..HEAD" to "def init (lena)",
                "/n|diff --no-color --stat --patch HEAD~1..HEAD" to "+y",
            ),
        )
        assertEquals("abc fix (lena)\n\n+x", Diffs(git).outgoing("/r"))
        assertEquals("def init (lena)\n\n+y", Diffs(git).outgoing("/n"))
        assertEquals("", Diffs(git).outgoing("/empty"))
    }

    @Test
    fun whatAPushSendsRefByRef() {
        val git = FakeGit(
            mapOf(
                "/r|rev-parse --verify --quiet refs/remotes/origin/main" to "B",
                "/r|log --no-color --format=%h %s (%an) refs/remotes/origin/main..S1" to "s1 a (l)",
                "/r|diff --no-color --stat --patch refs/remotes/origin/main..S1" to "+a",
                "/r|log --no-color --format=%h %s (%an) S2~1..S2" to "s2 b (l)",
                "/r|diff --no-color --stat --patch S2~1..S2" to "+b",
            ),
        )
        val push = Push(
            "origin",
            "acme/app",
            listOf(PushRef("S1", "main"), PushRef("S2", "topic"), PushRef("", "gone"), PushRef("S3", "same")),
            force = false,
        )
        assertEquals("main ← S1\ns1 a (l)\n\n+a\ntopic ← S2\ns2 b (l)\n\n+b\n", Diffs(git).pushed("/r", push))
    }

    @Test
    fun aLongDiffIsCutOnACharacterBoundary() {
        assertEquals("short", Diffs.cut("short"))
        val cut = Diffs.cut("a".repeat(7_999) + "é" + "b".repeat(10))
        assertEquals("a".repeat(7_999) + "\n… (diff cut at 8 000 bytes)", cut)
        assertTrue(Diffs.cut("b".repeat(9_000)).startsWith("b".repeat(8_000) + "\n…"))
    }
}
