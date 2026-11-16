// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How Windows starts an agent: npm's entry points, the reasons it cannot, its environment block. */
class AgentExecutableTest {
    @Test
    fun claudePrefersNativeBinaryAndKeepsLegacyCliJs() {
        val paths = npmLaunchCandidates("claude", "x64")
        assertEquals(
            "node_modules/@anthropic-ai/claude-code/bin/claude.exe",
            paths.first { it.relativePath.endsWith("/bin/claude.exe") }.relativePath,
        )
        assertTrue(paths.filter { !it.needsNode }.all { it.relativePath.endsWith("claude.exe") })
        assertEquals(NpmLaunchCandidate("node_modules/@anthropic-ai/claude-code/cli.js", true), paths.last())
        assertTrue(paths.any { it.relativePath.contains("claude-code-win32-x64") })
        assertEquals("x64", paths.first().relativePath.substringAfter("claude-code-win32-").substringBefore("/"))
    }

    @Test
    fun claudeOnArm64TriesArm64PackageFirst() {
        assertContains(npmLaunchCandidates("claude", "arm64").first().relativePath, "claude-code-win32-arm64")
    }

    @Test
    fun codexUsesNodeLauncherAndUnknownCommandsHaveNone() {
        assertEquals(
            listOf(NpmLaunchCandidate("node_modules/@openai/codex/bin/codex.js", true)),
            npmLaunchCandidates("codex", "x64"),
        )
        assertTrue(npmLaunchCandidates("other", "x64").isEmpty())
    }

    @Test
    fun messagesAreActionable() {
        fun message(reason: AgentStartException.Reason, command: String) =
            AgentStartException(reason, command).message.orEmpty()
        assertContains(message(AgentStartException.Reason.NOT_FOUND, "claude"), "%USERPROFILE%")
        assertContains(message(AgentStartException.Reason.NOT_FOUND, "claude"), "PATH")
        assertContains(message(AgentStartException.Reason.NOT_FOUND, "codex"), "@openai/codex")
        assertContains(message(AgentStartException.Reason.NOT_FOUND, "gemini"), "\"gemini\"")
        assertContains(message(AgentStartException.Reason.NODE_MISSING, "codex"), "Node.js")
        assertContains(message(AgentStartException.Reason.CANNOT_LAUNCH, "claude"), "Cannot start claude")
        assertContains(message(AgentStartException.Reason.TOO_LONG, "claude"), "32767")
        assertEquals("claude", AgentStartException(AgentStartException.Reason.TOO_LONG, "claude").command)
    }

    @Test
    fun theEnvironmentBlockIsSortedCaseInsensitiveAndDoubleNulTerminated() {
        val block = windowsEnvironmentBlock(
            linkedMapOf("Path" to "C:\\a", "b" to "2", "=C:" to "C:\\work", "PATH" to "C:\\b", "A" to "1"),
        )
        assertEquals("=C:=C:\\work\u0000A=1\u0000b=2\u0000PATH=C:\\b\u0000\u0000", block)
        assertEquals("\u0000\u0000", windowsEnvironmentBlock(emptyMap()))
    }

    @Test
    fun aVariableTheBlockCannotHoldIsRefused() {
        assertFailsWith<IllegalArgumentException> { windowsEnvironmentBlock(mapOf("" to "x")) }
        assertFailsWith<IllegalArgumentException> { windowsEnvironmentBlock(mapOf("A=B" to "x")) }
        assertFailsWith<IllegalArgumentException> { windowsEnvironmentBlock(mapOf("A\u0000" to "x")) }
        assertFailsWith<IllegalArgumentException> { windowsEnvironmentBlock(mapOf("A" to "x\u0000y")) }
    }

    @Test
    fun aVariableIsReadWhateverTheCaseOfItsName() {
        assertEquals("C:\\b", windowsVariable(linkedMapOf("Path" to "C:\\a", "PATH" to "C:\\b"), "path"))
        assertEquals("C:\\a", windowsVariable(mapOf("Path" to "C:\\a"), "PATH"))
        assertNull(windowsVariable(mapOf("HOME" to "C:\\h"), "PATH"))
    }
}
