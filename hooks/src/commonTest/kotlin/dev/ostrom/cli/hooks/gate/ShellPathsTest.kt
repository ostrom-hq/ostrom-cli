// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

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
