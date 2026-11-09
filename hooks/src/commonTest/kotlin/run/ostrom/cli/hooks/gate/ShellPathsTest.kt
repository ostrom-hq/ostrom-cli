// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellPathsTest {
    @Test
    fun joinFollowsTheShell() {
        assertEquals("/x", ShellPaths.join("/w", "/x", "/h"))
        assertEquals("/h", ShellPaths.join("/w", "~", "/h"))
        assertEquals("/h/a", ShellPaths.join("/w", "~/a", "/h/"))
        assertEquals("/w/a", ShellPaths.join("/w/", "a", "/h"))
        assertEquals("C:\\w/a", ShellPaths.join("C:\\w\\", "a", "/h"))
        assertEquals("D:/x", ShellPaths.join("/w", "D:/x", "/h"))
    }

    @Test
    fun realResolvesTheDeepestLinkedFolderAndKeepsTheRest() {
        val links = mapOf("/w/a" to "/x", "/w/f.txt" to "/y/g.txt", "C:\\w\\a" to "C:\\x", "C:/w/a" to "C:\\x")
        val canonical = { path: String -> links[path] ?: path }
        assertEquals("/x/b/c.txt", ShellPaths.real("/w/a/b/c.txt", canonical), "a link above the file")
        assertEquals("/y/g.txt", ShellPaths.real("/w/f.txt", canonical), "a link to the file itself")
        assertEquals("/x/c", ShellPaths.real("/w/b/../a/c", canonical), "`..` folded first, as path.resolve does")
        assertEquals("/x", ShellPaths.real("/w/a/", canonical), "a trailing separator")
        assertEquals("/w/new/x.txt", ShellPaths.real("/w/new/x.txt", canonical), "nothing linked, nothing written yet")
        assertEquals("/", ShellPaths.real("/", canonical))
        assertEquals("C:/x/b.txt", ShellPaths.real("C:/w/a/b.txt", canonical), "Windows: forward separators out")
    }

    @Test
    fun absolutePathsOfEverySystem() {
        assertTrue(ShellPaths.isAbsolute("/a"))
        assertTrue(ShellPaths.isAbsolute("C:\\a"))
        assertTrue(ShellPaths.isAbsolute("\\\\server\\share"))
        assertFalse(ShellPaths.isAbsolute("a/b"))
        assertFalse(ShellPaths.isAbsolute("C:"))
    }

    @Test
    fun fileNameIsTheLastPart() {
        assertEquals("b", ShellPaths.fileName("/a/b/"))
        assertEquals("b", ShellPaths.fileName("C:\\a\\b"))
        assertEquals("", ShellPaths.fileName("/"))
    }
}
